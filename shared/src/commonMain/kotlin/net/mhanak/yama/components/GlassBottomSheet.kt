package net.mhanak.yama.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A glass-styled modal bottom sheet, hand-rolled rather than M3's [androidx.compose.material3.ModalBottomSheet]
 * because that renders its content in a Popup, which doesn't compose correctly when raised from inside
 * the full-player overlay (it ends up drawn beneath the player chrome). This is a plain in-composition
 * overlay instead: a full-size scrim plus a bottom-anchored glass panel, so it draws in the normal
 * layer order of wherever it's placed — render it last in its parent so it sits on top.
 *
 * Like [GlassModalDrawerSheet] the panel is a hazeSource at zIndex=1, so glass children inside it only
 * blur the background (zIndex<1) rather than each other.
 *
 * Dismisses on a scrim tap or by dragging the handle down past a threshold. The entrance slides up;
 * there is no exit animation because callers gate it with `if (show) GlassModalBottomSheet { … }`, so
 * it simply leaves the composition on dismiss.
 */
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    // Drives the slide-up entrance (and the scrim fade) on first composition.
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }

    Box(modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismissRequest,
                    )
            )
        }

        AnimatedVisibility(
            visibleState,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                    .glassEffect(containerColor, shape)
                    .glassSource(zIndex = 1f),
            ) {
                // Drag handle — the only drag-to-dismiss target, so the sheet's own scrollable content
                // (e.g. the queue list) keeps its vertical drags.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragOffset.value >= dismissThresholdPx) onDismissRequest()
                                    else scope.launch { dragOffset.animateTo(0f) }
                                },
                                onDragCancel = { scope.launch { dragOffset.animateTo(0f) } },
                            ) { _, dy ->
                                scope.launch { dragOffset.snapTo((dragOffset.value + dy).coerceAtLeast(0f)) }
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }

                content()
            }
        }
    }
}
