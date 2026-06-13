package net.mhanak.yama.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player

/**
 * The "Play" / "Shuffle" action pair shown at the top of every detail view (album, artist, genre,
 * playlist). Tapping a button replaces the queue and starts playing all the tracks; long-pressing or
 * right-clicking instead opens a menu to enqueue them right after the current item ("Play next") or at
 * the end ("Add to queue"). [Play] keeps the tracks in their displayed order; [Shuffle] randomises them.
 *
 * [fetchTracks] receives the shuffled flag and returns the tracks to act on, letting each caller decide
 * what "all tracks" means: an album hands over its whole track list, while the collection views cap at
 * 100 and pull a random selection from the backend when shuffled.
 */
@Composable
fun DetailPlayActions(
    player: Player,
    fetchTracks: suspend (shuffled: Boolean) -> List<Track>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Fetch the tracks for the chosen mode, then hand them to the player. Deferred to a coroutine
    // because the collection views fetch their full track set on demand rather than holding it.
    fun act(shuffled: Boolean, enqueue: Player.(List<Track>) -> Unit) {
        scope.launch {
            val tracks = fetchTracks(shuffled)
            if (tracks.isNotEmpty()) player.enqueue(tracks)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlayActionButton(
            label = "Play",
            icon = Icons.Filled.PlayArrow,
            filled = true,
            modifier = Modifier.weight(1f),
            onPlay = { act(false) { playNow(it) } },
            onPlayNext = { act(false) { playNext(it) } },
            onAddToQueue = { act(false) { addToQueue(it) } },
        )
        PlayActionButton(
            label = "Shuffle",
            icon = Icons.Filled.Shuffle,
            filled = false,
            modifier = Modifier.weight(1f),
            onPlay = { act(true) { playNow(it) } },
            onPlayNext = { act(true) { playNext(it) } },
            onAddToQueue = { act(true) { addToQueue(it) } },
        )
    }
}

/**
 * A button styled like a Material filled / filled-tonal button (per [filled]) that plays on tap and
 * opens a Play next / Add to queue menu on long-press or right-click — mirroring [TrackListCard]'s
 * affordances at the whole-collection level.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayActionButton(
    label: String,
    icon: ImageVector,
    filled: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Anchor the menu at the cursor for right-clicks, top-start for long-presses.
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }

    val colors = if (filled) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
    val shape = ButtonDefaults.shape

    Box(modifier = modifier) {
        Surface(
            shape = shape,
            color = colors.containerColor,
            contentColor = colors.contentColor,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = {
                        menuOffset = DpOffset.Zero
                        menuExpanded = true
                    },
                )
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
            Row(
                modifier = Modifier
                    .heightIn(min = ButtonDefaults.MinHeight)
                    .padding(ButtonDefaults.ContentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = menuOffset,
        ) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.Filled.Queue, contentDescription = null) },
                onClick = { menuExpanded = false; onPlayNext() },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                onClick = { menuExpanded = false; onAddToQueue() },
            )
        }
    }
}
