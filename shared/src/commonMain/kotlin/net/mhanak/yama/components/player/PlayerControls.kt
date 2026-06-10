package net.mhanak.yama.components.player

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import net.mhanak.yama.components.GlassFilledIconButton
import net.mhanak.yama.components.GlassIconButton
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus
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
// smaller while playing is assumed to be a stale report and ignored in favour of extrapolation.
private const val RESYNC_THRESHOLD_MS = 3_000L

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
 * The transport row (prev / play-pause / next) plus an optional seek bar. Shared by every player
 * surface so the controls stay consistent.
 *
 * [belowFocusRequester]: when set, D-pad DOWN from every button in the transport row is explicitly
 * directed to that requester. Use this to bridge to a secondary row below when the spatial
 * algorithm can't cross composable boundaries (common on TV).
 *
 * [leadingContent] / [trailingContent]: composables injected at the left/right ends of the
 * transport row (e.g. shuffle, repeat). They receive [belowFocusRequester] via the same downMod
 * so D-pad DOWN from those buttons bridges to the secondary row too.
 */
@Composable
fun PlayerControls(
    status: PlayerStatus,
    player: Player,
    modifier: Modifier = Modifier,
    showSeek: Boolean = true,
    // Multiplies every transport size (buttons, icons, spacing, time text) so the controls can grow
    // to fill a large window. 1f = the phone-tuned baseline; FullPlayer drives this continuously.
    scale: Float = 1f,
    // When set, attached to the play/pause button so a TV can move D-pad focus into the controls.
    playPauseFocusRequester: FocusRequester? = null,
    belowFocusRequester: FocusRequester? = null,
    leadingContent: (@Composable (downModifier: Modifier) -> Unit)? = null,
    trailingContent: (@Composable (downModifier: Modifier) -> Unit)? = null,
) {
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
        val downMod = belowFocusRequester?.let { below -> Modifier.focusProperties { down = below } } ?: Modifier
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp * scale, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingContent?.invoke(downMod)
            IconButton(onClick = { player.previous() }, modifier = downMod.size(48.dp * scale)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(24.dp * scale))
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
            trailingContent?.invoke(downMod)
        }
    }
}

/** Scale a text style's font + line height by [scale] (identity at 1f). Used to grow player text. */
internal fun TextStyle.scaled(scale: Float): TextStyle =
    if (scale == 1f) this
    else copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )
