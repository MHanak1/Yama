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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
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
 * Swipe left/right → next/previous. Swipe up → expand ([playerExpansion] animates to 1).
 * Swipe down → stop playback.
 */
@Composable
fun NowPlayingBar(
    status: PlayerStatus,
    player: Player,
    playerExpansion: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val track = status.current ?: return
    val baseHeight = if (wide) MiniPlayerWideHeight else MiniPlayerHeight
    val density = LocalDensity.current
    val baseHeightPx = with(density) { baseHeight.toPx() }
    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
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
            .fillMaxWidth()
            .height((baseHeight + with(density) { heightDeltaPx.value.toDp() }).coerceAtLeast(0.dp))
            .glassEffect(MaterialTheme.colorScheme.surfaceContainer)
            .graphicsLayer {
                translationX = offsetX.value
                val horizFade = (1f - abs(offsetX.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val heightFade = ((baseHeightPx + heightDeltaPx.value) / baseHeightPx).coerceIn(0f, 1f)
                // fade to 0 when the user has dragged far enough to hit the 40dp threshold
                val expandFade = (1f - playerExpansion.value / (swipeThresholdPx / screenHeightPx * 2.5f)).coerceIn(0f, 1f)
                alpha = minOf(horizFade, heightFade, expandFade)
            }
            .pointerInput(player, playerExpansion) {
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
                        scope.launch { playerExpansion.snapTo(0f) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accX += dragAmount.x; accY += dragAmount.y
                        if (horizontal == null && (abs(accX) > axisLockThreshold || abs(accY) > axisLockThreshold))
                            horizontal = abs(accX) >= abs(accY)
                        when (horizontal) {
                            true -> scope.launch { offsetX.snapTo(offsetX.value + dragAmount.x * 0.85f) }
                            false -> {
                                val rawUpDrag = -accY  // positive = swiped up
                                scope.launch { heightDeltaPx.snapTo(rawUpDrag * 0.85f) }
                                if (rawUpDrag > 0)
                                    scope.launch { playerExpansion.snapTo((rawUpDrag / screenHeightPx * 2.5f).coerceIn(0f, 1f)) }
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
                                val delta = heightDeltaPx.value
                                if (delta > 0) {
                                    // Asymmetric: 20% expansion threshold to snap open (from collapsed).
                                    val snapOpen = playerExpansion.value > 0.2f
                                    scope.launch { heightDeltaPx.animateTo(if (snapOpen) baseHeightPx else 0f, if (snapOpen) expandSpec else snapBack); heightDeltaPx.snapTo(0f) }
                                    scope.launch { playerExpansion.animateTo(if (snapOpen) 1f else 0f, if (snapOpen) expandSpec else snapBack) }
                                } else if (abs(delta) >= swipeThreshold) {
                                    scope.launch { playerExpansion.snapTo(0f) }
                                    scope.launch { heightDeltaPx.animateTo(-baseHeightPx, collapseSpec); heightDeltaPx.snapTo(0f); player.stop() }
                                } else {
                                    scope.launch { heightDeltaPx.animateTo(0f, snapBack) }
                                    scope.launch { playerExpansion.animateTo(0f, snapBack) }
                                }
                            }
                            null -> Unit
                        }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, snapBack) }
                        scope.launch { heightDeltaPx.animateTo(0f, snapBack) }
                        scope.launch { playerExpansion.animateTo(0f, snapBack) }
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
        if (wide) {
            InteractiveSeekBar(status, player, Modifier.fillMaxWidth())
        } else {
            val duration = status.durationMs.coerceAtLeast(1)
            val position = rememberSmoothPosition(status)
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                LinearProgressIndicator(
                    progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
    }
}

/**
 * A draggable seek slider. Holds the dragged value locally so the thumb doesn't snap back.
 * seekFraction persists after drag ends to cover the gap while paused (engine won't emit a
 * new positionMs until playback resumes, so we keep the seek target visible until it does).
 */
@Composable
private fun InteractiveSeekBar(status: PlayerStatus, player: Player, modifier: Modifier = Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val duration = status.durationMs.coerceAtLeast(1)
    val position = rememberSmoothPosition(status)

    // Engine confirmed a new position — clear the seek override
    LaunchedEffect(status.positionMs) { seekFraction = null }

    val fraction = dragFraction ?: seekFraction ?: (position.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = {
            dragFraction = it
            seekFraction = null
        },
        onValueChangeFinished = {
            dragFraction?.let {
                player.seekTo((it * duration).toLong())
                seekFraction = it
            }
            dragFraction = null
        },
        track = { sliderState ->
            SliderDefaults.Track(sliderState, modifier = Modifier.height(8.dp))
        },
        thumb = {
            SliderDefaults.Thumb(interactionSource = remember { MutableInteractionSource() }, modifier = Modifier.height(16.dp))
        },
        modifier = modifier.padding(horizontal = 12.dp),
    )
}
