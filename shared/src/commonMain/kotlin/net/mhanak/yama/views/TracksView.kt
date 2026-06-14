package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.PaginatedTrackList
import net.mhanak.yama.media.sources.TrackSortOrder

@Composable
fun TracksView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    favoritesOnly: Boolean = false,
    query: String = "",
) {
    val appContainer = LocalAppContainer.current
    PaginatedTrackList(
        loadPage = { offset, limit, sortBy ->
            // The list is paginated/lazy-loaded, so the search term goes to the backend rather than
            // filtering an already-loaded set the way the grid tabs do.
            appContainer.activeMusicSource.getAllTracks(limit, offset, sortBy, favoritesOnly, searchTerm = query)
        },
        modifier = modifier,
        contentPadding = contentPadding,
        // Most-played first is the most useful default for the whole-library track list.
        defaultSort = TrackSortOrder.PlayCount,
        addSingleToQueue = true,
        // The library pager owns horizontal swipes; let it have them.
        disableGestures = true,
        // Re-run the page loader when the favourites filter or the search query changes.
        reloadKey = favoritesOnly to query,
    )
}
