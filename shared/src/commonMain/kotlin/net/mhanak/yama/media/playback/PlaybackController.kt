package net.mhanak.yama.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                local.playNow(command.tracks, command.startIndex)
                // A remote "Play On <this device>" handoff: surface the full player like a notification tap.
                openPlayerRequest = true
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
            is RemoteCommand.SetVolume -> active.setVolume(command.level)
            RemoteCommand.VolumeUp -> active.volumeUp()
            RemoteCommand.VolumeDown -> active.volumeDown()
        }
    }
}
