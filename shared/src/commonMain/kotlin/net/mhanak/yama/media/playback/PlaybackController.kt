package net.mhanak.yama.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.mhanak.yama.media.sources.MusicSource

/**
 * Owns the currently active [Player] and is the single entry point the UI uses to control playback.
 *
 * [active] is Compose-observable so the app can later switch from [LocalPlayer] to a remote player
 * (Jellyfin "Play On", Music Assistant) without the UI being rewired — mirroring how `AppContainer`
 * swaps `activeMusicSource`. For now it is always the local player.
 */
class PlaybackController(source: () -> MusicSource) {
    private val local = LocalPlayer(MediaPlayerEngine(), source)

    var active: Player by mutableStateOf(local)
        private set

    val status get() = active.status

    // Set when something outside the UI (e.g. tapping the Android media notification) asks to open
    // the full-screen player. MainScreen observes this and consumes it (resets it to false).
    var openPlayerRequest: Boolean by mutableStateOf(false)
}
