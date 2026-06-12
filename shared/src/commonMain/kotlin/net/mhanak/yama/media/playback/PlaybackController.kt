package net.mhanak.yama.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.RemoteCommand

/**
 * Owns the currently active [Player] and is the single entry point the UI uses to control playback.
 *
 * [active] is Compose-observable so switching from [LocalPlayer] to a remote player (Jellyfin
 * "Play On" via [selectTarget]) rebinds the UI without rewiring — mirroring how `AppContainer`
 * swaps `activeMusicSource`.
 */
class PlaybackController(private val source: () -> MusicSource) {
    // Playback on this device. Exposed so the reporter can observe local-only playback and so remote
    // "Play On" requests can always be routed here regardless of which player is currently active.
    val local = LocalPlayer(MediaPlayerEngine(), source)

    var active: Player by mutableStateOf(local)
        private set

    // The remote device playback is currently directed to, or null when playing on this device.
    var activeTarget: RemoteTarget? by mutableStateOf(null)
        private set

    val status get() = active.status

    // Set when something outside the UI (e.g. tapping the Android media notification) asks to open
    // the full-screen player. MainScreen observes this and consumes it (resets it to false).
    var openPlayerRequest: Boolean by mutableStateOf(false)

    // Emits when a volume change was triggered by a remote command (not local UI interaction).
    private val _remoteVolumeChange = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val remoteVolumeChange: SharedFlow<Unit> = _remoteVolumeChange

    /**
     * Direct playback to [target] (a "cast" device), or back to this device when null. Builds a
     * remote [Player] via the active source's [RemotePlaybackProvider] and swaps it in as [active];
     * the previously active remote player is released. No-op if the source can't cast.
     */
    fun selectTarget(target: RemoteTarget?) {
        if (target?.id == activeTarget?.id) return
        val previous = active
        val next = if (target == null) {
            local
        } else {
            (source() as? RemotePlaybackProvider)?.createPlayer(target) ?: return
        }
        active = next
        activeTarget = if (next === local) null else target
        if (previous !== local && previous !== next) previous.release()
    }

    /**
     * Apply a command pushed by a remote controller ("Play On" from another client). Play requests
     * always target the local player (and make it active, since "Play On <this device>" means here);
     * transport commands act on whatever player is currently active.
     */
    fun handleRemoteCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Play -> {
                selectTarget(null)
                // A controller's reorder/removal arrives as a fresh Play with the same now-playing track;
                // applyRemotePlay rearranges the live queue in place when it can. Only a genuine new
                // playback (which restarts) should surface the full player, like a notification tap.
                val restarted = !local.applyRemotePlay(command.tracks, command.startIndex, command.startPositionMs)
                if (restarted) openPlayerRequest = true
            }
            is RemoteCommand.PlayNext -> local.playNext(command.tracks)
            is RemoteCommand.AddToQueue -> local.addToQueue(command.tracks)
            RemoteCommand.Resume -> active.play()
            RemoteCommand.Pause -> active.pause()
            RemoteCommand.PlayPause -> active.togglePlayPause()
            RemoteCommand.Stop -> active.stop()
            RemoteCommand.Next -> active.next()
            RemoteCommand.Previous -> active.previous()
            is RemoteCommand.Seek -> active.seekTo(command.positionMs)
            is RemoteCommand.SetVolume -> { active.setVolume(command.level); _remoteVolumeChange.tryEmit(Unit) }
            RemoteCommand.VolumeUp -> { active.volumeUp(); _remoteVolumeChange.tryEmit(Unit) }
            RemoteCommand.VolumeDown -> { active.volumeDown(); _remoteVolumeChange.tryEmit(Unit) }
            is RemoteCommand.SetRepeat -> active.setRepeat(
                when (command.mode) {
                    RemoteCommand.Repeat.Off -> RepeatMode.Off
                    RemoteCommand.Repeat.All -> RepeatMode.All
                    RemoteCommand.Repeat.One -> RepeatMode.One
                },
            )
            is RemoteCommand.SetShuffle -> active.setShuffle(command.enabled)
        }
    }
}
