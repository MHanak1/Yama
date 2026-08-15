package net.mhanak.yama.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.mhanak.yama.LocalIsTvMode
import net.mhanak.yama.ui.components.interaction.LocalTvZoneFocus

// How long a fatal-error banner stays up before auto-hiding. The underlying [error] stays in the
// player status until the next command overwrites it, so this is purely how long the user sees it.
private const val ERROR_VISIBLE_MS = 6_000L

/**
 * A transient banner shown when local playback hits a *fatal* fault ([error] non-null in the player
 * status) — a missing track, a rejected stream, an undecodable file, or a reconnect that gave up.
 * Transient network stalls never reach here: they render as the play-button spinner (Reconnecting)
 * and self-recover. The message is latched and auto-hides after [ERROR_VISIBLE_MS]; [onRetry] re-runs
 * the failed track (the engine re-prepares/reopens from the held position).
 *
 * Place inside the root [Box] of the screen; it aligns itself just above the mini-player bar (via
 * [peekHeight]) so it doesn't cover the transport controls.
 */
@Composable
fun BoxScope.PlaybackErrorBanner(error: String?, peekHeight: Dp, onRetry: () -> Unit) {
    // Latch the latest non-null error into a transient visible message. Keyed on the error string, so a
    // new fault re-shows the banner (and resets the auto-hide timer) while recomposition doesn't.
    var shown by remember { mutableStateOf<String?>(null) }
    // TV: if the user D-pad-navigated onto the Retry button and the banner then auto-hides, focus would
    // drop into limbo. Track whether Retry holds focus and, on auto-hide, hand focus back to the content.
    val isTV = LocalIsTvMode.current
    val zone = LocalTvZoneFocus.current
    var retryFocused by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error != null) {
            shown = error
            delay(ERROR_VISIBLE_MS)
            val wasFocused = retryFocused
            shown = null
            if (isTV && wasFocused) zone?.restoreContent()
        }
    }

    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = peekHeight + 8.dp, start = 8.dp, end = 8.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                Text(
                    shown.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { shown = null; onRetry() },
                    modifier = Modifier.onFocusChanged { retryFocused = it.isFocused },
                ) { Text("Retry") }
            }
        }
    }
}
