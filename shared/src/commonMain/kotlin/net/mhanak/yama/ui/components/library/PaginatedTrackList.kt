package net.mhanak.yama.ui.components.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import net.mhanak.yama.ui.components.state.ErrorBox
import net.mhanak.yama.ui.components.state.LogError
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.ui.components.detail.DetailPlayActions
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.components.image.CardImage

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
    // Disables the per-row swipe gestures (play-next / add-to-queue). Set where the row sits inside a
    // horizontally swipeable container (the library pager) so the two don't fight over the drag.
    disableGestures: Boolean = false,
    // Extra dependency that, when changed, restarts loading from the first page — e.g. a favourites
    // filter toggle whose effect is captured inside [loadPage] but isn't visible to the sort key.
    reloadKey: Any? = null,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    var sortOrder by remember { mutableStateOf(defaultSort) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var fetchError by remember { mutableStateOf<Throwable?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // Reload from the top when the source is refreshed. A pull-to-refresh only re-runs
    // source.refresh(), which repopulates the grid StateFlows — this list reads the backend directly,
    // so without this it would ignore the pull (and any live library-change push). Bumped on each
    // refresh *start*: the reload re-runs getAllTracks from page 0, which re-fetches from the server
    // (Subsonic invalidates its cached all-tracks list at refresh start; Jellyfin always hits the
    // server), so the rising edge is enough and avoids the double reload that keying on the boolean
    // itself would cause.
    var refreshGen by remember { mutableStateOf(0) }
    LaunchedEffect(source) {
        var first = true
        source.isRefreshing.collect { refreshing ->
            if (refreshing && !first) refreshGen++
            first = false
        }
    }

    // One coroutine per sort order: loads pages sequentially, suspending between each until the
    // user scrolls within 20 rows of the bottom. Keying on sortOrder means a sort change cancels
    // the in-progress load cleanly and restarts from the beginning — no stuck isLoading flag.
    // [source] restarts the load when the active source is swapped; [refreshGen] on a refresh.
    LaunchedEffect(sortOrder, reloadKey, source, refreshGen, retryKey) {
        tracks = emptyList()
        hasMore = true
        fetchError = null

        while (hasMore) {
            isLoading = true
            val result = runCatching { loadPage(tracks.size, PAGE_SIZE, sortOrder) }
            val page = result.getOrNull()
            if (page == null) {
                fetchError = result.exceptionOrNull()
                isLoading = false
                break
            }
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
                player = appContainer.playback.viewed,
                // The list is paginated, so play/shuffle act on a single page pulled fresh from the
                // backend (capped at PAGE_SIZE): "Play" keeps the current sort, "Shuffle" asks the
                // backend for a random page rather than reshuffling only what's loaded.
                // When the current sort is already Random, "Play" reuses the loaded list so the played
                // queue matches the displayed order instead of fetching a new (different) random page.
                fetchTracks = { shuffled ->
                    when {
                        shuffled -> loadPage(0, PAGE_SIZE, TrackSortOrder.Random)
                        sortOrder == TrackSortOrder.Random && tracks.isNotEmpty() -> tracks
                        else -> loadPage(0, PAGE_SIZE, sortOrder)
                    }
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

        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackListCard(
                track = track,
                tracks = if (addSingleToQueue) listOf(track) else tracks,
                index = index,
                player = appContainer.playback.viewed,
                subtitle = track.artists?.joinToString(", ") ?: track.album,
                disableGestures = disableGestures,
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

        val error = fetchError
        if (error != null) {
            item {
                LogError(error, context = "Track list load failed")
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        error.message ?: "Couldn't load tracks",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.secondary),
                    )
                    TextButton(onClick = { retryKey++ }) { Text("Retry") }
                }
            }
        }
    }
}
