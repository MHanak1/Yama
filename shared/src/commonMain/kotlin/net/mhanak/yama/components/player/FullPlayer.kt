package net.mhanak.yama.components.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import net.mhanak.yama.components.BlurredBackgroundImage
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus
import net.mhanak.yama.media.playback.RepeatMode

/**
 * Full-screen player: artwork, track info, the shared [PlayerControls], plus shuffle/repeat toggles.
 * Shown as an overlay at the `MainScreen` level so it covers the rail and bottom bar.
 *
 * The artwork is sized relative to the available space so the whole thing fits without clipping on
 * any screen (notably TV). Dragging down continuously drives [playerExpansion] so the sheet follows
 * the finger and snaps back or collapses on release (asymmetric: >80% stays open, else collapses).
 * On TV, D-pad focus is moved into the controls when it opens.
 */
@Composable
fun FullPlayer(
    status: PlayerStatus,
    player: Player,
    playerExpansion: Animatable<Float, AnimationVector1D>,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = status.current
    val scope = rememberCoroutineScope()
    val playPauseFocus = remember { FocusRequester() }
    val screenHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val expandSpec = tween<Float>(400, easing = FastOutSlowInEasing)
    val collapseSpec = tween<Float>(450, easing = FastOutSlowInEasing)
    val snapBack = spring<Float>(stiffness = Spring.StiffnessMediumLow)

    // Move D-pad focus into the controls when the player opens (so TV remotes can drive it).
    LaunchedEffect(Unit) { runCatching { playPauseFocus.requestFocus() } }

    Surface(
        modifier = modifier.fillMaxSize().graphicsLayer {
            val f = playerExpansion.value
            translationY = (1f - f) * size.height
            alpha = f.coerceIn(0f, 1f)
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints {
            // Square artwork bounded by both width and height so everything fits on any screen.
            val artSize = minOf(maxWidth - 48.dp, maxHeight * 0.42f)

            BlurredBackgroundImage(
                imageUrl = track?.imageUrl,
                modifier = Modifier
                    .alpha(0.4f)
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .pointerInput(playerExpansion) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                val f = playerExpansion.value
                                scope.launch {
                                    playerExpansion.animateTo(
                                        if (f > 0.8f) 1f else 0f,
                                        if (f > 0.8f) expandSpec else collapseSpec,
                                    )
                                }
                            },
                            onDragCancel = { scope.launch { playerExpansion.animateTo(1f, snapBack) } },
                        ) { _, dy ->
                            val newF = (playerExpansion.value - dy / screenHeightPx * 2.5f).coerceIn(0f, 1f)
                            scope.launch { playerExpansion.snapTo(newF) }
                        }
                    }
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Now playing", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(48.dp))
                }

                Spacer(Modifier.weight(1f))

                AsyncImage(
                    model = track?.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(artSize).clip(RoundedCornerShape(20.dp)),
                )

                Spacer(Modifier.size(24.dp))

                Text(
                    track?.name ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                track?.artists?.joinToString(", ")?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.size(16.dp))

                PlayerControls(
                    status = status,
                    player = player,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    playPauseFocusRequester = playPauseFocus,
                )

                Row(
                    Modifier.fillMaxWidth().widthIn(max = 480.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val shuffleTint = if (status.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = { player.setShuffle(!status.shuffle) }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle", tint = shuffleTint)
                    }
                    val repeatTint = if (status.repeat != RepeatMode.Off) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    IconButton(onClick = { player.setRepeat(status.repeat.next()) }) {
                        Icon(
                            if (status.repeat == RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Repeat",
                            tint = repeatTint,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Cycle Off → All → One → Off. */
private fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}
