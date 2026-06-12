package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/**
 * Tracks user interaction within a subtree and reports when it has gone idle. Attach [resetIdleOn]
 * to the region whose pointer/key events count as "interaction", then read [isIdle] to drive
 * idle-triggered UI (auto-opening the full player, hiding its controls, …).
 *
 * Interaction is observed in the [PointerEventPass.Initial] pass so it's counted without consuming
 * the event — the underlying buttons/gestures still receive it.
 */
@Stable
class IdleMonitor {
    // Bumped on every interaction; [isIdle]'s timer keys off this so each event restarts the clock.
    var pulse by mutableStateOf(0)
        private set

    fun reset() { pulse++ }
}

@Composable
fun rememberIdleMonitor(): IdleMonitor = remember { IdleMonitor() }

/** Idle threshold (1 minute) for auto-opening the full player and dimming its controls. */
const val PlayerIdleTimeoutMs = 60_000L

/**
 * Reset [monitor] on genuine user interaction within this subtree.
 *
 * Pointer interaction is observed in the [PointerEventPass.Initial] pass (counted without consuming
 * the event). For Move events we track the last seen cursor position ourselves and only count a move
 * as interaction when the position actually changed: layout-shift re-dispatches (chrome fading in/out
 * causes Compose to re-dispatch the pointer over the stationary cursor) arrive at the same coordinates
 * as the previous event and are therefore ignored. Press/Release/Scroll always count.
 * Note: positionChanged() is unreliable for this purpose on Linux/JVM in the Initial pass.
 *
 * Key events (desktop keyboard, and TV D-pad while a focusable in this subtree holds focus) reset
 * via [onPreviewKeyEvent]. Once the zen view hides its controls there is no focusable left to route
 * D-pad keys here, so call sites pair this with [PlatformUserInteractionEffect] to catch the rest.
 */
fun Modifier.resetIdleOn(monitor: IdleMonitor): Modifier = this
    .pointerInput(monitor) {
        var lastMovePos: Offset? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val interacted = when (event.type) {
                    PointerEventType.Press, PointerEventType.Release, PointerEventType.Scroll -> {
                        lastMovePos = null
                        true
                    }
                    PointerEventType.Move -> {
                        val pos = event.changes.firstOrNull()?.position
                        (pos != null && pos != lastMovePos).also { moved -> if (moved) lastMovePos = pos }
                    }
                    else -> false
                }
                if (interacted) monitor.reset()
            }
        }
    }
    .onPreviewKeyEvent { monitor.reset(); false }

/**
 * True once no interaction has been registered on this monitor for [timeoutMs] while [enabled].
 * Returns false (and the timer is held) whenever [enabled] is false.
 *
 * The countdown is also paused whenever the app isn't foreground-interactive (lifecycle below
 * RESUMED — e.g. the screen is off or locked). Otherwise the delay keeps elapsing in the dark and
 * the hide animation fires the instant the screen comes back; gating on RESUMED instead restarts
 * the full wait when the screen returns.
 */
@Composable
fun IdleMonitor.isIdle(timeoutMs: Long, enabled: Boolean = true): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(pulse, active, timeoutMs) {
        idle = false
        if (!active) return@LaunchedEffect
        delay(timeoutMs)
        idle = true
    }
    return idle && enabled
}
