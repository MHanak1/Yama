package net.mhanak.yama.components.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import net.mhanak.yama.components.LocalUiOpacity
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus
import kotlin.math.abs

/** Height of the compact player bar (slim / TV): track row + a thin progress line. */
val MiniPlayerHeight = 80.dp

/** Height of the wider player bar (medium / wide): larger row + an interactive seek slider. */
val MiniPlayerWideHeight = 120.dp

/**
 * The persistent player bar shown at the bottom of every layout (glass, so it blurs the content
 * scrolling underneath). Tapping the track info expands the full player.
 *
 * On the wider two layouts ([wide] = true) the artwork and text are larger and the progress bar is
 * an interactive seek slider; on slim/TV it is a thin, non-interactive progress line.
 *
 * Gestures: swipe left/right → next/previous, swipe down → stop playback, swipe up → expand. The
 * swipe-up raises the full-player sheet 1:1 with the finger (driving [playerExpansion] directly, the
 * mirror of dragging the full player back down) and commits open/closed on release. Tapping the track
 * info also expands.
 */
@Composable
fun NowPlayingBar(
    status: PlayerStatus,
    player: Player,
    playerExpansion: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    peekHeight: Dp = 0.dp,
    // TV only: where D-pad focus goes when leaving the bar upward (the content area). Null off TV.
    focusUp: FocusRequester? = null,
) {
    val track = status.current ?: return
    val baseHeight = if (wide) MiniPlayerWideHeight else MiniPlayerHeight
    val density = LocalDensity.current
    val baseHeightPx = with(density) { baseHeight.toPx() }
    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    // The full-player sheet rests with its top at this bar's top, so it only travels (screen - peek)
    // px; dividing the up-drag by that keeps the sheet 1:1 under the finger.
    val travelPx = (screenHeightPx - with(density) { peekHeight.toPx() }).coerceAtLeast(1f)
    val swipeThresholdPx = with(density) { 40.dp.toPx() }
    val scope = rememberCoroutineScope()

    val offsetX = remember { Animatable(0f) }
    val heightDeltaPx = remember { Animatable(0f) }
    val snapBack = spring<Float>(stiffness = Spring.StiffnessMediumLow)
    val slideOff = tween<Float>(durationMillis = 220)
    val expandSpec = tween<Float>(400, easing = FastOutSlowInEasing)
    val collapseSpec = tween<Float>(450, easing = FastOutSlowInEasing)

    Column(
        modifier
            // Make the bar a focus group so D-pad up from any control exits into the content grid
            // rather than getting stuck on the bar. Other directions keep their defaults (left → rail).
            // focusProperties must precede focusGroup so onExit applies to the bar's own focus target.
            .then(
                if (focusUp != null) Modifier
                    .focusProperties {
                        onExit = { if (requestedFocusDirection == FocusDirection.Up) focusUp.requestFocus() }
                    }
                    .focusGroup()
                else Modifier,
            )
            .fillMaxWidth()
            .height((baseHeight + with(density) { heightDeltaPx.value.toDp() }).coerceAtLeast(0.dp))
            .glassEffect(MaterialTheme.colorScheme.surfaceContainer)
            .graphicsLayer {
                translationX = offsetX.value
                val horizFade = (1f - abs(offsetX.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val heightFade = ((baseHeightPx + heightDeltaPx.value) / baseHeightPx).coerceIn(0f, 1f)
                alpha = minOf(horizFade, heightFade)
            }
            .pointerInput(player) {
                val swipeThreshold = swipeThresholdPx
                val axisLockThreshold = 10.dp.toPx()
                var horizontal: Boolean? = null
                var accX = 0f
                var accY = 0f
                detectDragGestures(
                    onDragStart = { _ ->
                        horizontal = null; accX = 0f; accY = 0f
                        scope.launch { offsetX.snapTo(0f) }
                        scope.launch { heightDeltaPx.snapTo(0f) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accX += dragAmount.x; accY += dragAmount.y
                        if (horizontal == null && (abs(accX) > axisLockThreshold || abs(accY) > axisLockThreshold))
                            horizontal = abs(accX) >= abs(accY)
                        when (horizontal) {
                            true -> scope.launch { offsetX.snapTo(offsetX.value + dragAmount.x * 0.85f) }
                            false -> {
                                if (accY <= 0f) {
                                    // Dragging up → raise the full-player sheet 1:1 with the finger.
                                    scope.launch { heightDeltaPx.snapTo(0f) }
                                    scope.launch { playerExpansion.snapTo((-accY / travelPx).coerceIn(0f, 1f)) }
                                } else {
                                    // Dragging down → shrink the bar toward stop.
                                    scope.launch { playerExpansion.snapTo(0f) }
                                    scope.launch { heightDeltaPx.snapTo(-accY * 0.85f) }
                                }
                            }
                            null -> Unit
                        }
                    },
                    onDragEnd = {
                        when (horizontal) {
                            true -> {
                                val x = offsetX.value
                                if (abs(x) >= swipeThreshold) {
                                    scope.launch {
                                        offsetX.animateTo(if (x < 0) -size.width.toFloat() else size.width.toFloat(), slideOff)
                                        offsetX.snapTo(0f)
                                        if (x < 0) player.next() else player.previous()
                                    }
                                } else scope.launch { offsetX.animateTo(0f, snapBack) }
                            }
                            false -> {
                                if (playerExpansion.value > 0f) {
                                    // Swipe up: commit open past 10% of the way, else fall back collapsed.
                                    val open = playerExpansion.value > 0.1f
                                    scope.launch { playerExpansion.animateTo(if (open) 1f else 0f, if (open) expandSpec else snapBack) }
                                } else if (-heightDeltaPx.value >= swipeThreshold) {
                                    // Swipe down past the threshold stops playback.
                                    scope.launch { heightDeltaPx.animateTo(-baseHeightPx, collapseSpec); heightDeltaPx.snapTo(0f); player.stop(); player.clearQueue()}
                                } else {
                                    scope.launch { heightDeltaPx.animateTo(0f, snapBack) }
                                }
                            }
                            null -> Unit
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, snapBack) }
                        scope.launch { heightDeltaPx.animateTo(0f, snapBack) }
                        if (playerExpansion.value < 1f) scope.launch { playerExpansion.animateTo(0f, snapBack) }
                    },
                )
            },
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable { scope.launch { playerExpansion.animateTo(1f) } }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = track.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(if (wide) 64.dp else 48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    track.name,
                    style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                track.artists?.joinToString(", ")?.let {
                    Text(
                        it,
                        style = if (wide) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Zero-footprint unless we're casting and the live link has dropped, in which case the
                // bar above shows the last-known remote state — flag that it's no longer updating.
                RemoteConnectionIndicator()
            }
            IconButton(onClick = { player.togglePlayPause() }, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (status.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (status.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { player.next() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }

        val duration = status.durationMs.coerceAtLeast(1)
        val position = rememberSmoothPosition(status)

        LinearProgressIndicator(
            progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).padding(horizontal = 4.dp),
            trackColor = ProgressIndicatorDefaults.linearTrackColor.copy(alpha = LocalUiOpacity.current),
        )
    }
}