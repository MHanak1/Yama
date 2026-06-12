package net.mhanak.yama.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LowPriority
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player
import kotlin.math.abs
import kotlin.math.roundToInt

/** Drag distance past which releasing fires the swipe action. */
private val SwipeTriggerDistance = 64.dp

/**
 * A track row that knows how to enqueue itself. Reusable across every track list (album, artist,
 * genre, playlist detail) so the play/queue affordances stay identical everywhere.
 *
 * - **Tap** plays from this track onward (`playNow(tracks, index)`) — the default list behaviour.
 * - **Long-press** (or **right-click**) opens a menu: Play / Play from here / Play next / Add to queue.
 * - **Swipe right** adds the track to the end of the queue; **swipe left** plays it next. The row
 *   follows the finger and snaps back on release. Crossing [SwipeTriggerDistance] emphasises the
 *   action icon and fires a haptic pulse to signal "let go now".
 *
 * @param tracks the full list this row belongs to, so "play from here" can hand the player the tail.
 * @param index this track's position within [tracks].
 */
@Composable
fun TrackListCard(
    track: Track,
    tracks: List<Track>,
    index: Int,
    player: Player,
    modifier: Modifier = Modifier,
    subtitle: String? = track.artists?.joinToString(", "),
    image: (@Composable BoxScope.() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Where to anchor the menu: the cursor for right-clicks, top-start for long-presses.
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }

    val playThis = { player.playNow(listOf(track)) }
    val playFromHere = { player.playNow(tracks, index) }
    val playNext = { player.playNext(listOf(track)) }
    val addToQueue = { player.addToQueue(listOf(track)) }

    val density = LocalDensity.current
    val triggerPx = with(density) { SwipeTriggerDistance.toPx() }
    val scope = rememberCoroutineScope()
    // Plain state updated synchronously while dragging, so onDragEnd reads the true offset (a launched
    // Animatable.snapTo would lag behind the release). An Animatable only drives the snap-back.
    var offsetX by remember { mutableStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // The swipe has dragged past the point where releasing would fire the action.
    val triggerReached by remember { derivedStateOf { abs(offsetX) >= triggerPx } }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(triggerReached) {
        if (triggerReached) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(modifier = modifier) {
        // Coloured action backing, revealed as the card slides off it.
        TrackSwipeBackground(
            offset = offsetX,
            triggerReached = triggerReached,
            modifier = Modifier.matchParentSize(),
        )

        GlassElevatedCard(
            onClick = playFromHere,
            onLongClick = {
                menuOffset = DpOffset.Zero
                menuExpanded = true
            },
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { settleJob?.cancel() },
                        onDragEnd = {
                            when {
                                offsetX >= triggerPx ->  playNext()
                                offsetX <= -triggerPx -> addToQueue()
                            }
                            settleJob = scope.launch {
                                Animatable(offsetX).animateTo(0f) { offsetX = value }
                            }
                        },
                        onDragCancel = {
                            settleJob = scope.launch {
                                Animatable(offsetX).animateTo(0f) { offsetX = value }
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            offsetX += delta
                        },
                    )
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val position = event.changes.first().position
                            menuOffset = DpOffset(position.x.toDp(), position.y.toDp())
                            menuExpanded = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            ListCardRow(image = image, title = track.name, subtitle = subtitle)
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = menuOffset,
        ) {
            TrackMenuItem("Play", Icons.Filled.PlayArrow) { menuExpanded = false; playThis() }
            TrackMenuItem("Play from here", Icons.Filled.PlayCircle) { menuExpanded = false; playFromHere() }
            TrackMenuItem("Play next", Icons.Filled.Queue) { menuExpanded = false; playNext() }
            TrackMenuItem("Add to queue", Icons.Filled.QueueMusic) { menuExpanded = false; addToQueue() }
        }
    }
}

@Composable
private fun TrackMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/**
 * Coloured backing revealed while swiping, with the action's icon pinned to the leading/trailing edge.
 * When [triggerReached] the icon scales up and the backing reaches full opacity — the visual half of
 * the "you can let go now" cue that the haptic pulse completes. Renders nothing at rest ([offset] == 0).
 */
@Composable
private fun TrackSwipeBackground(offset: Float, triggerReached: Boolean, modifier: Modifier = Modifier) {
    if (offset == 0f) return

    val startToEnd = offset > 0f
    val alignment = if (startToEnd) Alignment.CenterStart else Alignment.CenterEnd
    val icon = if (startToEnd) Icons.AutoMirrored.Filled.QueueMusic else Icons.Filled.LowPriority
    val color =
        if (startToEnd) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.secondaryContainer

    val iconScale by animateFloatAsState(if (triggerReached) 1.25f else 0.85f, label = "swipeIconScale")
    val backgroundAlpha by animateFloatAsState(if (triggerReached) 1f else 0.5f, label = "swipeBgAlpha")

    Box(
        modifier = modifier
            .clip(CardDefaults.shape)
            .background(color.copy(alpha = color.alpha * backgroundAlpha))
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Row (
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ){
            if (!startToEnd) {
                Text("Add to Queue")
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.scale(iconScale),
            )
            if (startToEnd) {
                Text("Play Next")
            }
        }
    }
}
