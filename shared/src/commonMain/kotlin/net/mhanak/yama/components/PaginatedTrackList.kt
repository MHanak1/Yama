package net.mhanak.yama.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.TrackSortOrder

private const val PAGE_SIZE = 100

private val defaultSortOptions = listOf(
    TrackSortOrder.PlayCount,
    TrackSortOrder.RecentlyPlayed,
    TrackSortOrder.RecentlyAdded,
    TrackSortOrder.ReleaseDate,
    TrackSortOrder.Alphabetical,
    TrackSortOrder.Random,
)

@Composable
fun PaginatedTrackList(
    loadPage: suspend (offset: Int, limit: Int, sortBy: TrackSortOrder) -> List<Track>,
    modifier: Modifier = Modifier,
    sortOptions: List<TrackSortOrder> = defaultSortOptions,
    defaultSort: TrackSortOrder = TrackSortOrder.Alphabetical,
    addSingleToQueue: Boolean = false,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    var sortOrder by remember { mutableStateOf(defaultSort) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // One coroutine per sort order: loads pages sequentially, suspending between each until the
    // user scrolls within 20 rows of the bottom. Keying on sortOrder means a sort change cancels
    // the in-progress load cleanly and restarts from the beginning — no stuck isLoading flag.
    LaunchedEffect(sortOrder) {
        tracks = emptyList()
        hasMore = true

        while (hasMore) {
            isLoading = true
            val page = loadPage(tracks.size, PAGE_SIZE, sortOrder)
            tracks = tracks + page
            hasMore = page.size == PAGE_SIZE
            isLoading = false

            if (!hasMore) break

            // Suspend until the user scrolls close enough to the end to warrant the next page.
            snapshotFlow {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                last >= tracks.size - 20
            }.first { it }
        }
    }

    ListView(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        if (onBack != null) {
            item {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }

        item {
            DetailPlayActions(
                player = appContainer.playback.active,
                // The list is paginated, so play/shuffle act on a single page pulled fresh from the
                // backend (capped at PAGE_SIZE): "Play" keeps the current sort, "Shuffle" asks the
                // backend for a random page rather than reshuffling only what's loaded.
                fetchTracks = { shuffled ->
                    loadPage(0, PAGE_SIZE, if (shuffled) TrackSortOrder.Random else sortOrder)
                },
            )
        }

        item {
            SegmentedButtonRow(
                options = sortOptions,
                selectedOption = sortOrder,
                onOptionSelected = { sortOrder = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(it.label) }
        }

        itemsIndexed(tracks) { index, track ->
            TrackListCard(
                track = track,
                tracks = if (addSingleToQueue) listOf(track) else tracks,
                index = index,
                player = appContainer.playback.active,
                subtitle = track.artists?.joinToString(", ") ?: track.album,
                image = { CardImage(imageUrl = track.imageUrl) },
            )
        }

        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}
