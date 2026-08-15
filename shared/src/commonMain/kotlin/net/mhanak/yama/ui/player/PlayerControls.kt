package net.mhanak.yama.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.lerp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.theme.GlassFilledIconButton
import net.mhanak.yama.ui.theme.GlassIconButton
import net.mhanak.yama.ui.components.input.FavoriteButton
import net.mhanak.yama.media.playback.PlaybackState
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.playback.RepeatMode
import net.mhanak.yama.media.sources.FavoritableKind
import kotlin.time.TimeSource

/**
 * A position (ms) that advances smoothly every frame while playing, re-anchoring to the reported
 * [PlayerStatus.positionMs] when it updates. The position source only reports a few times a second
 * (and for a remote/cast device it arrives over the network, several seconds behind real time), so
 * reading [PlayerStatus.positionMs] directly makes progress bars jump or lag; this interpolates
 * between reports.
 *
 * While playing it won't let a *slightly stale* report drag the indicator backwards: re-anchoring to
 * a report that's a few seconds behind real time on every update would pin the bar that far behind
 * (very visible when controlling a remote device). It trusts forward extrapolation and only snaps for
 * a forward jump or a large backward jump — i.e. a genuine seek or track change. When paused it trusts
 * the reported position exactly.
 */
@Composable
fun rememberSmoothPosition(status: PlayerStatus): Long {
    var smooth by remember { mutableStateOf(status.positionMs) }
    LaunchedEffect(status.positionMs, status.isPlaying, status.durationMs) {
        val reported = status.positionMs
        smooth = when {
            !status.isPlaying -> reported
            reported > smooth -> reported
            smooth - reported > RESYNC_THRESHOLD_MS -> reported
            else -> smooth // small backward delta = stale report; keep the extrapolated value
        }
        if (status.isPlaying) {
            val base = smooth
            val max = if (status.durationMs > 0) status.durationMs else Long.MAX_VALUE
            val mark = TimeSource.Monotonic.markNow()
            while (true) {
                withFrameMillis {
                    smooth = (base + mark.elapsedNow().inWholeMilliseconds).coerceAtMost(max)
                }
            }
        }
    }
    return smooth
}

// A backward jump larger than this is treated as a real seek/track change (snap to it); anything
// smaller while playing is assumed to be a stale report and ignored in favour of extrapolation. Kept
// just above the report-travel latency of a remote ("Play On") session — a controlled device now
// reports seeks promptly (see PlaybackReporter), so this only needs to absorb that jitter, not a
// whole progress interval; lower means a controller snaps to a backward seek sooner.
private const val RESYNC_THRESHOLD_MS = 1_500L

/** Format a millisecond duration as `m:ss` (or `h:mm:ss`). */
fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    else "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * The full player control suite: optional seek bar, transport row (prev / play-pause / next),
 * optional shuffle/repeat flanks ([showSecondaryControls]), optional action row of
 * queue/lyrics/rating ([showTertiaryControls]), and optional volume slider ([showVolumeBar]).
 *
 * All sections default off so this also works as a slim transport strip. [FullPlayer] opts all in.
 *
 * [playPauseFocusRequester]: when set, attached to the play/pause button so TV D-pad focus can
 * enter the controls on open. D-pad DOWN from the transport row bridges to the tertiary row when
 * it is shown, and loops back up from there.
 *
 * [scale]: multiplies every size (buttons, icons, spacing, time text) so the controls grow to fill
 * a large window. 1f = phone baseline; [FullPlayer] drives this continuously.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerControls(
    status: PlayerStatus,
    player: Player,
    modifier: Modifier = Modifier,
    showSeek: Boolean = true,
    showSecondaryControls: Boolean = false,
    showTertiaryControls: Boolean = false,
    showVolumeBar: Boolean = false,
    scale: Float = 1f,
    playPauseFocusRequester: FocusRequester? = null,
    showLyrics: Boolean = false,
    onToggleLyrics: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
) {
    val appContainer = LocalAppContainer.current
    val canCast = appContainer.activeMusicSource is RemotePlaybackProvider
    val isCasting = appContainer.playback.viewedTarget != null
    var showTargets by remember { mutableStateOf(false) }

    val bottomCenterFocus = remember { FocusRequester() }
    val downMod = if (showTertiaryControls)
        Modifier.focusProperties { down = bottomCenterFocus }
    else Modifier

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (showSeek) {
            // Hold the dragged value locally so the thumb doesn't snap back to the (lagging) reported
            // position mid-drag; commit the seek on release.
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            val duration = status.durationMs.coerceAtLeast(1)
            val position = rememberSmoothPosition(status)
            val fraction = dragFraction ?: (position.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = fraction,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { player.seekTo((it * duration).toLong()) }
                    dragFraction = null
                },
                track = { sliderState ->
                    SliderDefaults.Track(sliderState, modifier = Modifier.height(12.dp))
                },
                thumb = {
                    SliderDefaults.Thumb(interactionSource = remember { MutableInteractionSource() }, modifier = Modifier.height(32.dp))
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatPlaybackTime(position), style = MaterialTheme.typography.labelSmall.scaled(scale))
                Text(formatPlaybackTime(status.durationMs), style = MaterialTheme.typography.labelSmall.scaled(scale))
            }
        }

        Column (
            modifier = Modifier
                .width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
        ){
            // prev/play/next each get an interaction source so ButtonGroup's animateWidth can expand the
            // pressed button and compress its neighbours — the expressive "squish" physics. Shuffle and
            // repeat sit outside the group, so they need no such source and never shove the transport.
            val prevInteraction = remember { MutableInteractionSource() }
            val playInteraction = remember { MutableInteractionSource() }
            val nextInteraction = remember { MutableInteractionSource() }
            // Inactive toggles are transparent so they read like the plain prev/next icon buttons; only
            // the active (checked) state fills, keeping the row visually even instead of boxing off the
            // toggles behind grey containers.
            val toggleColors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // A single 0→1 fraction drives all three transport shapes together: 0 when paused (three
            // full circles), 1 when playing (play squishes to a squircle and the prev/next edges
            // facing it tighten, so the trio reads as one connected segmented button). Spring so the
            // morph feels physical rather than mechanically timed.
            val morph by animateFloatAsState(
                targetValue = if (status.isPlaying) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "transportMorph",
            )
            // All three transport buttons share one height, so corners as a fraction of that height
            // (50% = full round, 25% = segmented interface — the same ratio segmentedItemShape() uses)
            // keep the facing edges visually matched at every frame of the morph.
            val cornerFrac = lerp(0.50f, 0.25f, morph)
            val buttonSize = 56.dp * scale
            val playShape = RoundedCornerShape(buttonSize * cornerFrac)
            // Prev: outer (start) corners stay fully round; the inner (end) corners facing play morph.
            val prevShape = RoundedCornerShape(
                topStart = buttonSize * 0.5f, bottomStart = buttonSize * 0.5f,
                topEnd = buttonSize * cornerFrac, bottomEnd = buttonSize * cornerFrac,
            )
            // Next: mirror — inner (start) corners morph, outer (end) corners stay round.
            val nextShape = RoundedCornerShape(
                topStart = buttonSize * cornerFrac, bottomStart = buttonSize * cornerFrac,
                topEnd = buttonSize * 0.5f, bottomEnd = buttonSize * 0.5f,
            )
            // Prev/next share the seek-bar's inactive-track colour so the transport reads as tied to the
            // scrubber above it. Bound to SliderDefaults.colors().inactiveTrackColor directly (rather than
            // hardcoding a role) so it stays in lockstep with whatever the Slider actually paints —
            // currently secondaryContainer, hence onSecondaryContainer for the glyphs.
            val sideColors = IconButtonDefaults.filledIconButtonColors(
                containerColor = SliderDefaults.colors().inactiveTrackColor,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            // Shuffle and repeat flank the transport group but sit *outside* it, vertically centred
            // against its taller (56dp) buttons, so toggling one animates only itself (the checked-shape
            // morph) and never nudges prev/play/next.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp * scale, Alignment.CenterHorizontally),
            ) {
                if (showSecondaryControls) {
                    // Shape still morphs round → squarish when active; the transparent container means
                    // that morph (not a grey box) is what signals shuffle-on.
                    ToggleButton(
                        checked = status.shuffle,
                        onCheckedChange = { player.setShuffle(it) },
                        modifier = Modifier.then(downMod).size(48.dp * scale),
                        shapes = ToggleButtonDefaults.shapes(),
                        colors = toggleColors,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            if (status.shuffle) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            modifier = Modifier.size(24.dp * scale)
                        )
                    }
                }

                ButtonGroup(
                    horizontalArrangement = Arrangement.spacedBy(4.dp * scale, Alignment.CenterHorizontally),
                ) {
                    FilledIconButton(
                        onClick = { player.previous() },
                        modifier = Modifier.animateWidth(prevInteraction).then(downMod).size(56.dp * scale),
                        shape = prevShape,
                        colors = sideColors,
                        interactionSource = prevInteraction,
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(24.dp * scale)
                        )
                    }

                    FilledIconButton(
                        onClick = { player.togglePlayPause() },
                        modifier = Modifier.animateWidth(playInteraction).size(56.dp * scale)
                            .then(if (playPauseFocusRequester != null) Modifier.focusRequester(playPauseFocusRequester) else Modifier)
                            .then(downMod),
                        shape = playShape,
                        interactionSource = playInteraction,
                    ) {
                        // While the track is buffering — or stalled on a transient network drop and
                        // waiting to reconnect — swap the glyph for the app's standard spinner. It must be
                        // tinted onPrimary (LocalContentColor here) — the indicator's default primary
                        // colour would be invisible on the primary-filled container.
                        val loadingState = status.state == PlaybackState.Buffering ||
                            status.state == PlaybackState.Reconnecting
                        Crossfade(loadingState, label = "playLoading") { loading ->
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp * scale),
                                    color = LocalContentColor.current,
                                )
                            } else {
                                Icon(
                                    if (status.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (status.isPlaying) "Pause" else "Play",
                                    modifier = Modifier.size(24.dp * scale),
                                )
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = { player.next() },
                        modifier = Modifier.animateWidth(nextInteraction).then(downMod).size(56.dp * scale),
                        shape = nextShape,
                        colors = sideColors,
                        interactionSource = nextInteraction,
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(24.dp * scale))
                    }
                }

                if (showSecondaryControls) {
                    // Tri-state (Off → All → One → Off): the tap always cycles via next(), while
                    // `checked` (repeat != Off) drives the same fill + shape morph. The icon still
                    // distinguishes All vs One.
                    ToggleButton(
                        checked = status.repeat != RepeatMode.Off,
                        onCheckedChange = { player.setRepeat(status.repeat.next()) },
                        modifier = Modifier.then(downMod).size(48.dp * scale),
                        shapes = ToggleButtonDefaults.shapes(),
                        colors = toggleColors,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            if (status.repeat == RepeatMode.One) Icons.Filled.RepeatOneOn else if (status.repeat == RepeatMode.All) Icons.Filled.RepeatOn else Icons.Filled.Repeat,
                            contentDescription = "Repeat",
                            modifier = Modifier.size(24.dp * scale),
                        )
                    }
                }
            }

            if (showTertiaryControls) {
                Spacer(Modifier.height(16.dp * scale))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = { showTargets = true },
                        modifier = Modifier.size(48.dp * scale),
                        enabled = canCast,
                    ) {
                        Icon(
                            if (isCasting) Icons.Filled.Speaker else Icons.Outlined.Speaker,
                            contentDescription = "Play on another device",
                            tint = when {
                                isCasting -> MaterialTheme.colorScheme.primary
                                canCast -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            },
                            modifier = Modifier.size(24.dp * scale),
                        )
                    }
                    IconButton(onClick = onOpenQueue, modifier = Modifier.size(48.dp * scale)) {
                        Icon(
                            Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                            modifier = Modifier.size(24.dp * scale)
                        )
                    }
                    // Lyrics toggle: same round→squarish fill-on-active morph as shuffle/repeat, so an
                    // open lyrics pane reads from the button's silhouette. Shares `toggleColors`
                    // (transparent until checked, then fills primary).
                    ToggleButton(
                        checked = showLyrics,
                        onCheckedChange = { onToggleLyrics() },
                        // No down override: like cast/queue, D-pad down stays on this row rather than
                        // jumping back up to play/pause (the tertiary row is the bottom of the controls).
                        modifier = Modifier.focusRequester(bottomCenterFocus).size(48.dp * scale),
                        shapes = ToggleButtonDefaults.shapes(),
                        colors = toggleColors,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            if (showLyrics) Icons.Filled.Lyrics else Icons.Outlined.Lyrics,
                            contentDescription = "Lyrics",
                            modifier = Modifier.size(24.dp * scale),
                        )
                    }
                    FavoriteButton(
                        kind = FavoritableKind.Track,
                        itemId = status.current?.id,
                        initial = status.current?.favorite,
                        modifier = Modifier.size(48.dp * scale),
                        iconSize = 24.dp * scale,
                        emphasized = true,
                    )
                }
            }
        }

        if (showVolumeBar) {
            VolumeSlider(
                player = player,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        Spacer(Modifier.size(24.dp * scale))
    }

    if (showTargets) {
        PlaybackTargetSheet(onDismiss = { showTargets = false })
    }
}

private fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}

/** Scale a text style's font + line height by [scale] (identity at 1f). Used to grow player text. */
internal fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this
    else copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )
