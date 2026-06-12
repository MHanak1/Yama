package net.mhanak.yama.components.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.isTelevisionDevice
import net.mhanak.yama.components.BlurredBackgroundImage
import net.mhanak.yama.components.glassSource
import net.mhanak.yama.components.DynamicColorTheme
import net.mhanak.yama.components.ImmersiveMode
import net.mhanak.yama.components.LocalUiOpacity
import net.mhanak.yama.components.PlatformUserInteractionEffect
import net.mhanak.yama.components.PlayerIdleTimeoutMs
import net.mhanak.yama.components.isIdle
import net.mhanak.yama.components.rememberIdleMonitor
import net.mhanak.yama.components.resetIdleOn
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus

/**
 * Full-screen player: artwork, track info, the shared [PlayerControls], plus shuffle/repeat toggles.
 * Shown as an overlay at the `MainScreen` level so it covers the rail and bottom bar.
 *
 * The artwork is sized relative to the available space so the whole thing fits without clipping on
 * any screen (notably TV). Dragging down continuously drives [playerExpansion] so the sheet follows
 * the finger and snaps back or collapses on release (asymmetric: >80% stays open, else collapses).
 * On TV, D-pad focus is moved into the controls when it opens.
 *
 * [peekHeight] is the height of the now-playing bar (rail) or bar + bottom bar (slim): the collapsed
 * sheet rests with its top at that line rather than fully off the bottom, so a swipe up from the bar
 * tracks the finger 1:1 instead of trailing it by a bar's height.
 *
 * The lyrics button in the top bar swaps the artwork area for a [LyricsView]. Lyrics are fetched
 * once per track via the active music source and cached for the lifetime of this composable.
 */
@Composable
fun FullPlayer(
    status: PlayerStatus,
    player: Player,
    playerExpansion: Animatable<Float, AnimationVector1D>,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    peekHeight: Dp = 0.dp,
) {
    val track = status.current
    val scope = rememberCoroutineScope()
    val playPauseFocus = remember { FocusRequester() }
    val containerSize = LocalWindowInfo.current.containerSize
    val screenHeightPx = containerSize.height.toFloat()
    // Continuously scale every non-artwork element up on large windows so the controls/info/lyrics
    // fill the space instead of sitting phone-sized in the middle of a desktop window. Driven by the
    // window's smaller dimension (portrait phones stay at the 1f baseline). TV keeps the baseline.
    val density = LocalDensity.current
    val peekHeightPx = with(density) { peekHeight.toPx() }
    // Travel distance of the sheet: from its resting top (at the bar line) up to the screen top.
    val travelPx = (screenHeightPx - peekHeightPx).coerceAtLeast(1f)
    // Minimum upward swipe (in px) that opens the queue when the player is fully expanded.
    val swipeUpThresholdPx = with(density) { 80.dp.toPx() }
    val playerScale = if (isTelevisionDevice()) 1f else with(density) {
        playerScaleFor(minOf(containerSize.width.toDp(), containerSize.height.toDp()))
    }
    val expandSpec = tween<Float>(400, easing = FastOutSlowInEasing)
    val collapseSpec = tween<Float>(450, easing = FastOutSlowInEasing)
    val snapBack = spring<Float>(stiffness = Spring.StiffnessMediumLow)

    val appContainer = LocalAppContainer.current
    val isCasting = appContainer.playback.activeTarget != null
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lyrics: Lyrics? by remember { mutableStateOf(null) }
    val smoothPosition = rememberSmoothPosition(status)

    // After a minute with no interaction, fade the chrome (top bar + transport) away so only the
    // artwork/title/artist remain — a "zen" view. Any pointer/key event resets the timer and brings
    // the controls back. Only armed while the player is actually expanded, so it doesn't tick (and
    // hide the system bars) while the collapsed sheet sits off-screen behind the rest of the UI.
    val idleMonitor = rememberIdleMonitor()
    PlatformUserInteractionEffect { idleMonitor.reset() }
    val expanded by remember { derivedStateOf { playerExpansion.value > 0.99f } }
    val controlsHidden = idleMonitor.isIdle(PlayerIdleTimeoutMs, enabled = expanded)

    // In the zen view, also hide the device's system bars (status bar, gesture/navigation bar) so
    // the artwork owns the whole screen; they return with the controls.
    ImmersiveMode(enabled = controlsHidden)

    LaunchedEffect(track?.id) {
        lyrics = null
        val id = track?.id ?: return@LaunchedEffect
        lyrics = appContainer.activeMusicSource.getLyrics(id)
    }

    // Move D-pad focus into the controls when the player opens (so TV remotes can drive it).
    LaunchedEffect(Unit) { runCatching { playPauseFocus.requestFocus() } }

    // Recolour the whole player to the current artwork (album uuid as the shared cache key), animating
    // to the new scheme when the track changes. Honours the user's "Tint UI with album colours" setting.
    DynamicColorTheme(
        imageUrl = track?.imageUrl,
        cacheKey = track?.albumId ?: track?.id,
        enabled = appContainer.albumTintMode.tintsPlayer,
    ) {
    Surface(
        modifier = modifier.fillMaxSize().glassSource(zIndex = 2f).resetIdleOn(idleMonitor).graphicsLayer {
            val f = playerExpansion.value
            // The sheet rests with its top at the bar line (peekHeight up from the bottom) and slides
            // up to fully cover the screen as f goes 0 → 1. It also fades in over just the first
            // [FadeInUntil] of the drag so it's solid almost immediately, then only moves.
            translationY = (1f - f) * (size.height - peekHeightPx)
            alpha = (f / FadeInUntil).coerceIn(0f, 1f)
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
        BlurredBackgroundImage(
            imageUrl = track?.imageUrl,
            modifier = Modifier.alpha(0.4f),
        )

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .pointerInput(playerExpansion) {
                    // totalDy accumulates raw vertical delta for the current gesture (positive = down).
                    // Declared inside the block so it's reset between pointerInput restarts.
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDy = 0f },
                        onDragEnd = {
                            // Upward swipe (totalDy < 0) past the threshold while fully expanded → queue.
                            if (totalDy < -swipeUpThresholdPx && playerExpansion.value > 0.99f) {
                                showQueue = true
                            }
                            val f = playerExpansion.value
                            scope.launch {
                                playerExpansion.animateTo(
                                    if (f > 0.95f) 1f else 0f,
                                    if (f > 0.95f) expandSpec else collapseSpec,
                                )
                            }
                        },
                        onDragCancel = { scope.launch { playerExpansion.animateTo(1f, snapBack) } },
                    ) { _, dy ->
                        totalDy += dy
                        // 1:1 with the finger: a drag of dy px moves the sheet dy px. The sheet only
                        // travels (screen - peek) px, so df = -dy / travelPx.
                        val newF = (playerExpansion.value - dy / travelPx).coerceIn(0f, 1f)
                        scope.launch { playerExpansion.snapTo(newF) }
                    }
                }
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = !controlsHidden,
                enter = expandVertically(animationSpec = tween(durationMillis = 1000)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 1000))
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Now playing", style = MaterialTheme.typography.titleSmall.scaled(playerScale))
                        if (isCasting) {
                            Text(
                                player.displayName,
                                style = MaterialTheme.typography.labelSmall.scaled(playerScale),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // When the live link drops, the device name above is the last-known target;
                            // flag that its reported state is no longer updating.
                            RemoteConnectionIndicator(scale = playerScale)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.size(48.dp))
                }
            }

            // Center area takes all space between the top bar and the transport controls.
            Column(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    modifier = Modifier.fillMaxSize(),
                ) { isShowingLyrics ->
                    if (isShowingLyrics) {
                        LyricsView(
                            lyrics = lyrics,
                            positionMs = smoothPosition,
                            scale = playerScale,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ArtworkAndInfo(track = track, scale = playerScale, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsHidden,
                enter = expandVertically(animationSpec = tween(durationMillis = 1000)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 1000))
            ) {
                val duration = status.durationMs.coerceAtLeast(1)
                val position = rememberSmoothPosition(status)

                LinearProgressIndicator(
                    progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .padding(vertical = 32.dp)
                        .widthIn(max = 480.dp * playerScale)
                        .fillMaxWidth(),
                    trackColor = ProgressIndicatorDefaults.linearTrackColor.copy(alpha = LocalUiOpacity.current),
                )
            }

            AnimatedVisibility(
                visible = !controlsHidden,
                enter = expandVertically(animationSpec = tween(durationMillis = 1000)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 1000))
            ) {
                Spacer(Modifier.size(16.dp * playerScale))
                PlayerControls(
                    status = status,
                    player = player,
                    modifier = Modifier.widthIn(max = 480.dp * playerScale).fillMaxWidth(),
                    showSecondaryControls = true,
                    showTertiaryControls = true,
                    showVolumeBar = !appContainer.useDeviceVolume,
                    scale = playerScale,
                    playPauseFocusRequester = playPauseFocus,
                    showLyrics = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onOpenQueue = { showQueue = true },
                )
            }
        }

    }
    }

    if (showQueue) {
        QueueSheet(status = status, player = player, onDismiss = { showQueue = false })
    }
}

/** Artwork image + track name + artist, vertically centered within the available space. */
@Composable
private fun ArtworkAndInfo(
    track: Track?,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // Square artwork bounded by both width and height so everything fits on any screen.
        val artSize = minOf(maxWidth, maxHeight * 0.6f)
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = track?.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(artSize).clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.size(24.dp * scale))
            Text(
                track?.name ?: "",
                style = MaterialTheme.typography.headlineSmall.scaled(scale),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            track?.artists?.joinToString(", ")?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium.scaled(scale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// The continuous size curve for the full player. Below [PlayerScaleBaseline] (≈ a phone's narrow
// dimension) everything stays at the phone-tuned 1f; it ramps linearly to [MaxPlayerScale] once the
// window's smaller side reaches [PlayerScaleFull]. Tune these three to reshape the growth.
private val PlayerScaleBaseline = 420.dp
private val PlayerScaleFull = 1180.dp
private const val MaxPlayerScale = 1.7f

// Fraction of the open drag (0 = collapsed, 1 = open) over which the sheet fades from transparent to
// opaque; past this it's fully solid and only translates. Small so it solidifies near the start.
private const val FadeInUntil = 0.15f

private fun playerScaleFor(minDimension: Dp): Float {
    val t = ((minDimension - PlayerScaleBaseline) / (PlayerScaleFull - PlayerScaleBaseline)).coerceIn(0f, 1f)
    return 1f + t * (MaxPlayerScale - 1f)
}

