package net.mhanak.yama.components.player

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.GlassFilledIconButton
import net.mhanak.yama.components.GlassIconButton
import net.mhanak.yama.components.FavoriteButton
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
    val isCasting = appContainer.playback.activeTarget != null
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp * scale, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showSecondaryControls) {
                    IconButton(
                        onClick = { player.setShuffle(!status.shuffle) },
                        modifier = downMod.size(48.dp * scale)
                    ) {
                        Icon(
                            if (status.shuffle) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (status.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp * scale)
                        )
                    }
                }

                IconButton(onClick = { player.previous() }, modifier = downMod.size(48.dp * scale)) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(24.dp * scale)
                    )
                }

                FilledIconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier.size(56.dp * scale)
                        .then(if (playPauseFocusRequester != null) Modifier.focusRequester(playPauseFocusRequester) else Modifier)
                        .then(downMod),
                ) {
                    Icon(
                        if (status.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (status.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(24.dp * scale),
                    )
                }

                IconButton(onClick = { player.next() }, modifier = downMod.size(48.dp * scale)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(24.dp * scale))
                }

                if (showSecondaryControls) {
                    IconButton(
                        onClick = { player.setRepeat(status.repeat.next()) },
                        modifier = downMod.size(48.dp * scale)
                    ) {
                        Icon(
                            if (status.repeat == RepeatMode.One) Icons.Filled.RepeatOneOn else if (status.repeat == RepeatMode.All) Icons.Filled.RepeatOn else Icons.Filled.Repeat,
                            tint = if (status.repeat != RepeatMode.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            contentDescription = "Repeat",
                            modifier = Modifier.size(24.dp * scale),
                        )
                    }
                }
            }

            if (showTertiaryControls) {
                Spacer(Modifier.height(16.dp * scale))
                val loopUp = playPauseFocusRequester?.let { focus ->
                    Modifier.focusProperties { down = focus }
                } ?: Modifier

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
                    IconButton(
                        onClick = onToggleLyrics,
                        modifier = Modifier.focusRequester(bottomCenterFocus).then(loopUp).size(48.dp * scale),
                    ) {
                        Icon(
                            if (showLyrics) Icons.Filled.Lyrics else Icons.Outlined.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp * scale),
                        )
                    }
                    FavoriteButton(
                        kind = FavoritableKind.Track,
                        itemId = status.current?.id,
                        modifier = loopUp.size(48.dp * scale),
                        iconSize = 24.dp * scale,
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
