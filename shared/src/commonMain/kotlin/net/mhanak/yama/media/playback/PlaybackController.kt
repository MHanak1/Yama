package net.mhanak.yama.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.RemoteCommand

/**
 * Owns the currently active [Player] and is the single entry point the UI uses to control playback.
 *
 * [active] is Compose-observable so the app can later switch from [LocalPlayer] to a remote player
 * (Jellyfin "Play On", Music Assistant) without the UI being rewired — mirroring how `AppContainer`
 * swaps `activeMusicSource`. For now it is always the local player.
 */
class PlaybackController(source: () -> MusicSource) {
    // Playback on this device. Exposed so the reporter can observe local-only playback and so remote
    // "Play On" requests can always be routed here regardless of which player is currently active.
    val local = LocalPlayer(MediaPlayerEngine(), source)

    var active: Player by mutableStateOf(local)
        private set

    val status get() = active.status

    // Set when something outside the UI (e.g. tapping the Android media notification) asks to open
    // the full-screen player. MainScreen observes this and consumes it (resets it to false).
    var openPlayerRequest: Boolean by mutableStateOf(false)

    /**
     * Apply a command pushed by a remote controller ("Play On" from another client). Play requests
     * always target the local player (and make it active, since "Play On <this device>" means here);
     * transport commands act on whatever player is currently active.
     */
    fun handleRemoteCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Play -> { active = local; local.playNow(command.tracks, command.startIndex) }
            is RemoteCommand.PlayNext -> local.playNext(command.tracks)
            is RemoteCommand.AddToQueue -> local.addToQueue(command.tracks)
            RemoteCommand.Resume -> active.play()
            RemoteCommand.Pause -> active.pause()
            RemoteCommand.PlayPause -> active.togglePlayPause()
            RemoteCommand.Stop -> active.stop()
            RemoteCommand.Next -> active.next()
            RemoteCommand.Previous -> active.previous()
            is RemoteCommand.Seek -> active.seekTo(command.positionMs)
        }
    }
}
