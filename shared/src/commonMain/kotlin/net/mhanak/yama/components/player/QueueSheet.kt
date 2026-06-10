package net.mhanak.yama.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import net.mhanak.yama.components.GlassModalBottomSheet
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus

/**
 * Bottom sheet showing the live playback queue. Each row shows the track's album cover, title and
 * album/artist; tapping a row jumps playback to it. Tracks can be removed (trailing ✕) or rearranged
 * by dragging the leading handle.
 *
 * Reordering is done locally on a working copy ([entries]) for snappy, finger-tracking feedback, then
 * committed to the [player] as a single [Player.move] on drop. The working copy re-syncs from
 * [status] whenever the queue changes from the outside (a track ending, an enqueue) — but only while
 * no drag is in flight, so a background update can't clobber an in-progress reorder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    status: PlayerStatus,
    player: Player,
    onDismiss: () -> Unit,
) {
    // Working copy of the queue. Each entry carries a stable [uid] so the LazyColumn can key/animate
    // rows even when the same track appears twice — track ids aren't unique within a queue.
    val entries = remember { mutableStateListOf<QueueEntry>() }
    var nextUid by remember { mutableStateOf(0L) }

    // The uid of the row being dragged (null when idle), plus the bookkeeping the gesture needs.
    var draggingUid by remember { mutableStateOf<Long?>(null) }
    var dragStartIndex by remember { mutableStateOf(-1) }
    var dragInitialOffset by remember { mutableStateOf(0) }
    var dragDistance by remember { mutableStateOf(0f) }

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    // Re-sync the working copy from the player whenever the queue changes externally — but never mid
    // drag (draggingUid != null), so an incoming update can't fight the reorder under the finger.
    // Keyed on status.queue only (not draggingUid): a drag's commit (player.move) reaches status
    // asynchronously, so retriggering on drag-end would briefly rebuild to the stale order and flicker.
    // When status finally catches up it already matches entries, so the comparison no-ops.
    LaunchedEffect(status.queue) {
        if (draggingUid != null) return@LaunchedEffect
        if (entries.map { it.track } == status.queue) return@LaunchedEffect
        entries.clear()
        status.queue.forEach { entries.add(QueueEntry(nextUid++, it)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Queue",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (entries.isEmpty()) {
            Text(
                "The queue is empty",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        LazyColumn(
            state = listState,
            // Cap the height so a long queue doesn't push the sheet to full screen; it scrolls within.
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.uid }) { index, entry ->
                val isDragging = entry.uid == draggingUid
                // The dragged row floats under the finger: translate it by (where the finger has taken
                // it) minus (its current laid-out position), recomputed each frame from layoutInfo so it
                // stays put as the list reorders/scrolls beneath it. Others animate to their new slots.
                val rowModifier = if (isDragging) {
                    Modifier.zIndex(1f).graphicsLayer {
                        val current = listState.layoutInfo.itemOffsetByKey(entry.uid) ?: dragInitialOffset
                        translationY = (dragInitialOffset + dragDistance) - current
                    }
                } else {
                    Modifier.animateItem()
                }

                QueueRow(
                    track = entry.track,
                    isCurrent = index == status.queueIndex,
                    isDragging = isDragging,
                    onClick = { player.skipTo(index) },
                    onRemove = {
                        val at = entries.indexOfFirst { it.uid == entry.uid }
                        if (at >= 0) {
                            entries.removeAt(at)
                            player.removeAt(at)
                        }
                    },
                    dragHandleModifier = Modifier.pointerInput(entry.uid) {
                        detectDragGestures(
                            onDragStart = {
                                val start = entries.indexOfFirst { it.uid == entry.uid }
                                if (start >= 0) {
                                    draggingUid = entry.uid
                                    dragStartIndex = start
                                    dragInitialOffset = listState.layoutInfo.itemOffsetByKey(entry.uid) ?: 0
                                    dragDistance = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDistance += amount.y
                                reorderToHover(entries, listState.layoutInfo, draggingUid, dragInitialOffset, dragDistance)
                            },
                            onDragEnd = {
                                val finalIndex = entries.indexOfFirst { it.uid == draggingUid }
                                if (dragStartIndex >= 0 && finalIndex >= 0 && finalIndex != dragStartIndex) {
                                    player.move(dragStartIndex, finalIndex)
                                }
                                draggingUid = null
                                dragStartIndex = -1
                            },
                            onDragCancel = {
                                draggingUid = null
                                dragStartIndex = -1
                            },
                        )
                    },
                    modifier = rowModifier,
                )
            }
        }

        Spacer(Modifier.height(8.dp).navigationBarsPadding())
    }

    // Autoscroll while a drag is parked near the top/bottom edge, so a track can be moved past the
    // visible window. Reads drag state via snapshot each frame; ends when the drag does.
    LaunchedEffect(draggingUid) {
        if (draggingUid == null) return@LaunchedEffect
        while (draggingUid != null) {
            val info = listState.layoutInfo
            val top = (dragInitialOffset + dragDistance)
            val rowSize = info.itemSizeByKey(draggingUid) ?: 0
            val edge = rowSize.toFloat()
            val delta = when {
                top + rowSize > info.viewportEndOffset - edge -> (top + rowSize) - (info.viewportEndOffset - edge)
                top < info.viewportStartOffset + edge -> top - (info.viewportStartOffset + edge)
                else -> 0f
            }.coerceIn(-edge, edge)
            if (delta != 0f) {
                listState.scrollBy(delta)
                reorderToHover(entries, listState.layoutInfo, draggingUid, dragInitialOffset, dragDistance)
            }
            // Pace to roughly one step per frame.
            delay(16)
        }
    }
}

/** A queue row: album art, title + album/artist, an equaliser glyph on the current track, ✕, handle. */
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
    // Rows are transparent so the sheet's glass shows through; the dragged row gets a solid lifted
    // surface so it reads as picked up off the list.
    val container =
        if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
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
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
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

        if (isCurrent) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "Now playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }

        Box(Modifier.size(40.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove from queue",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        Box(Modifier.size(40.dp).then(dragHandleModifier), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = "Reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A queue entry paired with a process-stable id so duplicate tracks stay individually keyable. */
private data class QueueEntry(val uid: Long, val track: Track)

private fun LazyListLayoutInfo.itemOffsetByKey(key: Any?): Int? =
    visibleItemsInfo.firstOrNull { it.key == key }?.offset

private fun LazyListLayoutInfo.itemSizeByKey(key: Any?): Int? =
    visibleItemsInfo.firstOrNull { it.key == key }?.size

/**
 * If the dragged row's centre now sits over another row, swap it into that slot in [entries]. Called
 * on every drag delta and every autoscroll step; idempotent when nothing has changed.
 */
private fun reorderToHover(
    entries: MutableList<QueueEntry>,
    info: LazyListLayoutInfo,
    draggingUid: Long?,
    initialOffset: Int,
    distance: Float,
) {
    val key = draggingUid ?: return
    val dragged = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
    val centre = (initialOffset + distance + dragged.size / 2f).toInt()
    val target = info.visibleItemsInfo.firstOrNull { item ->
        item.key != key && centre in item.offset..(item.offset + item.size)
    } ?: return
    val from = entries.indexOfFirst { it.uid == key }
    val to = target.index
    if (from in entries.indices && to in entries.indices && from != to) {
        entries.add(to, entries.removeAt(from))
    }
}
