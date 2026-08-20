package net.mhanak.yama.util

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.Player

/** How far ←/→ jump within the current track. */
private const val SEEK_STEP_MS = 5_000L

/**
 * Maps a keyboard event to a transport command on the *viewed* player, for devices with a hardware
 * keyboard (desktop today). Returns true when the key was consumed.
 *
 * Wire this into the **bubble phase** — Compose Desktop's `Window(onKeyEvent = …)`, which sits at the
 * scene root so it catches keys regardless of what's focused, yet still fires only *after* a focused
 * text field or list has had its chance. That's what keeps Space from being stolen out of the search
 * box: a focused field consumes the space itself and this handler never sees it.
 *
 * Deliberately binds **no** dedicated media keys (`Key.MediaPlayPause`/`Next`/`Previous`). Those belong
 * to the OS media layer — MPRIS on Linux, SMTC on Windows (planned) — which owns them system-wide and
 * would otherwise double-fire against this handler whenever the window happens to be focused.
 *
 * @param tvMode when true, the arrow keys are left to the D-pad focus system (TvZoneFocus); only Space
 *   is bound, since it isn't a D-pad key.
 */
fun handlePlaybackShortcut(event: KeyEvent, playback: PlaybackController, tvMode: Boolean): Boolean {
    // Act on the physical press only; ignore the matching KeyUp so one tap = one command.
    if (event.type != KeyEventType.KeyDown) return false
    val player = playback.viewed

    // Space always toggles play/pause — it isn't claimed by the D-pad model, and in the bubble phase a
    // focused button/text field has already consumed it if it wanted it.
    if (event.key == Key.Spacebar) {
        player.togglePlayPause()
        return true
    }

    // The arrows double as D-pad navigation in TV mode; hand them back so we don't fight TvZoneFocus.
    if (tvMode) return false

    return when (event.key) {
        Key.DirectionRight -> { player.seekBy(SEEK_STEP_MS); true }
        Key.DirectionLeft -> { player.seekBy(-SEEK_STEP_MS); true }
        // Nudge the viewed player's volume and surface the in-app indicator, mirroring the hardware
        // volume-key path (see PlaybackController.notifyVolumeChanged).
        Key.DirectionUp -> { player.volumeUp(); playback.notifyVolumeChanged(); true }
        Key.DirectionDown -> { player.volumeDown(); playback.notifyVolumeChanged(); true }
        else -> false
    }
}

/** Seek relative to the current position, clamped to the track bounds. */
private fun Player.seekBy(deltaMs: Long) {
    val s = status.value
    val max = if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE
    seekTo((s.positionMs + deltaMs).coerceIn(0L, max))
}
