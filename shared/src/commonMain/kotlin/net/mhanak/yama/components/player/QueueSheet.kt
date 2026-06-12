package net.mhanak.yama.components.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.components.glassSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Bottom sheet showing the live playback queue as a flat reorderable list. The currently-playing
 * track is visually distinguished (primary colour, bold, animated equaliser) but participates in
 * reordering like any other row.
 *
 * [entries] is a single working copy mirroring the whole queue (index == real queue index). Each
 * entry carries a stable [QueueEntry.uid] so the LazyColumn can key/animate rows even when the
 * same track appears twice. [entries] re-syncs from [status] whenever the queue changes externally,
 * but never mid-drag, so a background update can't clobber an in-progress reorder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    status: PlayerStatus,
    player: Player,
    onDismiss: () -> Unit,
) {
    val entries = remember { mutableStateListOf<QueueEntry>() }
    var nextUid by remember { mutableStateOf(0L) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var didInitialScroll by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = entries.indexOfFirst { it.uid == from.key }
        val toIndex = entries.indexOfFirst { it.uid == to.key }
        if (fromIndex >= 0 && toIndex >= 0) {
            entries.add(toIndex, entries.removeAt(fromIndex))
        }
    }

    LaunchedEffect(status.queue, status.queueIndex) {
        if (dragStartIndex >= 0) return@LaunchedEffect
        val desired = status.queue.mapIndexed { index, track -> track to (index == status.queueIndex) }
        if (entries.map { it.track to it.isCurrent } == desired) return@LaunchedEffect
        entries.clear()
        desired.forEach { (track, isCurrent) -> entries.add(QueueEntry(nextUid++, track, isCurrent)) }
    }

    val currentIndex = entries.indexOfFirst { it.isCurrent }
    LaunchedEffect(currentIndex) {
        if (!didInitialScroll && currentIndex >= 0) {
            listState.scrollToItem(currentIndex)
            didInitialScroll = true
        }
    }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassEffect(sheetContainerColor, sheetShape)
                .glassSource(zIndex = 3f),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
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
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(entries, key = { it.uid }) { entry ->
                    ReorderableItem(reorderableLazyListState, key = entry.uid) { isDragging ->
                        QueueRow(
                            track = entry.track,
                            isCurrent = entry.isCurrent,
                            isDragging = isDragging,
                            onClick = { player.skipTo(entries.indexOfFirst { it.uid == entry.uid }) },
                            onRemove = {
                                val at = entries.indexOfFirst { it.uid == entry.uid }
                                if (at >= 0) { entries.removeAt(at); player.removeAt(at) }
                            },
                            dragHandleModifier = Modifier.draggableHandle(
                                onDragStarted = {
                                    dragStartIndex = entries.indexOfFirst { it.uid == entry.uid }
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragStopped = {
                                    val finalIndex = entries.indexOfFirst { it.uid == entry.uid }
                                    if (dragStartIndex >= 0 && finalIndex >= 0 && finalIndex != dragStartIndex) {
                                        player.move(dragStartIndex, finalIndex)
                                    }
                                    dragStartIndex = -1
                                },
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp).navigationBarsPadding())
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    isCurrent: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val container = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = track.album ?: track.artists?.joinToString(", ")
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                NowPlayingIndicator()
            } else {
                Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove from queue",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Box(Modifier.size(40.dp).then(dragHandleModifier), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = "Reorder",
                tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowPlayingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "nowplaying")
    val b1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "b1",
    )
    val b2 by transition.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse),
        label = "b2",
    )
    val b3 by transition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
        label = "b3",
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(b1, b2, b3).forEach { frac ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp * frac)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private data class QueueEntry(val uid: Long, val track: Track, val isCurrent: Boolean)
