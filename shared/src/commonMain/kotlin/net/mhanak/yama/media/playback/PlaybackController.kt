package net.mhanak.yama.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.RemoteCommand
import net.mhanak.yama.util.AppPreferences

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
     *
     * When [remember] is true (the default — a user picking a device in the cast sheet) the choice is
     * persisted per source so switching away and back to this source restores it. Pass false for
     * choices that aren't the user's own (a remote "Play On" pushed here, or [restoreTargetForActiveSource]
     * replaying a saved choice) so they don't overwrite what the user last asked for.
     */
    fun selectTarget(target: RemoteTarget?, remember: Boolean = true) {
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
        if (remember) AppPreferences.setLastRemoteTarget(source().type.name, activeTarget?.encode())
    }

    /**
     * Apply the [RemoteTarget] last chosen for the currently active source, or local playback when the
     * source can't cast ([RemotePlaybackProvider]) or has no remembered target. Call this right after
     * the active source changes (see `AppContainer.selectSource`) so each source resumes its own cast
     * device. Restoring isn't a fresh user choice, so it doesn't re-persist.
     */
    fun restoreTargetForActiveSource() {
        val src = source()
        // Only attempt a remote restore once the source is actually usable — a provider whose backend
        // client isn't connected yet would fail to build a player.
        val remembered = (src as? RemotePlaybackProvider)
            ?.takeIf { src.isAuthenticated }
            ?.let { AppPreferences.lastRemoteTarget(src.type.name)?.let(::decodeTarget) }
        selectTarget(remembered, remember = false)
    }

    /**
     * Recreate the active remote player against the source's freshly rebuilt backend client. The
     * Jellyfin remote player captures its client at construction, so after the source rebuilds it on
     * device wake (see [net.mhanak.yama.media.sources.JellyfinSource.reconnect]) the old player's
     * transport commands would keep hitting the dead client. No-op when playing locally.
     *
     * This is needed only because the current remote player holds the rebuilt client; a source whose
     * remote player survives a connection refresh (or has none) wouldn't need this. Since [createPlayer]
     * just rebuilds a player for the same target, calling it for such a source is harmless if wasteful.
     */
    fun rebuildActiveRemotePlayer() {
        val target = activeTarget ?: return
        val previous = active
        val next = (source() as? RemotePlaybackProvider)?.createPlayer(target) ?: return
        active = next
        if (previous !== local) previous.release()
    }

    /**
     * Apply a command pushed by a remote controller ("Play On" from another client). Play requests
     * always target the local player (and make it active, since "Play On <this device>" means here);
     * transport commands act on whatever player is currently active.
     */
    fun handleRemoteCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Play -> {
                // Another device pushed playback here; that's not the user choosing local for this
                // source, so don't overwrite their remembered cast target.
                selectTarget(null, remember = false)
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

    // A RemoteTarget round-trips through prefs joined on the ASCII unit separator (U+001F, which
    // can't occur in any field): "id\u001Fname\u001Fclient". client is optional — an empty tail.
    private fun RemoteTarget.encode(): String = listOf(id, name, client ?: "").joinToString("\u001F")

    private fun decodeTarget(raw: String): RemoteTarget? {
        val parts = raw.split('\u001F')
        val id = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
        return RemoteTarget(
            id = id,
            name = parts.getOrNull(1) ?: id,
            client = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }
}
