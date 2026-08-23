package net.mhanak.yama.ui.platform

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.math.exp

// Pixels a single scroll "tick" adds to the target. A horizontal touchpad swipe emits one button
// press per tick, so this is the per-notch step (tuned to feel like a shift+scroll notch).
private val TickStep = 64.dp

// Exponential-smoothing time constant (ms): how quickly the view chases the target. Smaller = snappier
// / tracks the fingers more tightly; larger = floatier. This replaces a fixed tween duration/easing.
private const val SmoothingTimeConstantMs = 40f

// Remaining distance still to be scrolled (px) plus whether a smoothing loop is live. Both the event
// handler and the animator run on the UI thread, so plain fields need no locking.
private class ScrollTarget {
    var pending = 0f
    var animating = false
}

@Composable
actual fun Modifier.horizontalTouchpadScroll(listState: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    val target = remember { ScrollTarget() }

    return this.pointerInput(listState) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue

                // Horizontal touchpad scrolling arrives as raw mouse buttons 4/5: AWT reserves buttons
                // 1-3 for clicks and the vertical wheel for MouseWheelEvents, then renumbers the X11
                // horizontal-scroll buttons (6/7) down to 4/5. The JDK never recognises them as scroll,
                // so we translate each tick into a horizontal step ourselves. Read the button straight
                // off the AWT event — Compose's PointerButton mapping for these extended buttons is
                // unreliable.
                val awtButton = (event.awtEventOrNull as? MouseEvent)?.button
                val direction = when (awtButton) {
                    4 -> -1f
                    5 -> 1f
                    else -> continue
                }
                event.changes.forEach { it.consume() }

                // Extend the target and, if no loop is running, start one continuous scroll session.
                // A live loop just picks up the enlarged target on its next frame — it never restarts,
                // so a burst of ticks reads as one smooth, decelerating glide rather than N segments.
                target.pending += direction * TickStep.toPx()
                if (!target.animating) {
                    target.animating = true
                    scope.launch {
                        try {
                            listState.scroll {
                                var last = 0L
                                while (true) {
                                    val now = withFrameNanos { it }
                                    if (last == 0L) { last = now; continue }
                                    // Clamp long frame gaps (e.g. after a stall) so we never lurch.
                                    val dtMs = ((now - last).coerceAtMost(64_000_000L)) / 1_000_000f
                                    last = now

                                    val remaining = target.pending
                                    if (abs(remaining) < 0.5f) { target.pending = 0f; break }

                                    // Move a frame-rate-independent fraction of the remaining distance.
                                    val step = remaining * (1f - exp(-dtMs / SmoothingTimeConstantMs))
                                    val consumed = scrollBy(step)
                                    target.pending -= step
                                    // Hit a content edge and can't move: drop the rest, don't spin.
                                    if (abs(consumed) < 0.01f) { target.pending = 0f; break }
                                }
                            }
                        } finally {
                            target.animating = false
                        }
                    }
                }
            }
        }
    }
}
