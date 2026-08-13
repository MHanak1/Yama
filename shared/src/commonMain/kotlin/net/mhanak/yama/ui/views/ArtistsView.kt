package net.mhanak.yama.ui.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.library.AsyncImageGridCard
import net.mhanak.yama.ui.components.library.GridView
import net.mhanak.yama.ui.components.settings.SelectableKind
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.artist

@Composable
fun ArtistsView(
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    query: String = "",
    favoritesOnly: Boolean = false,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val artists by source.artists.collectAsState()
    val isRefreshing by source.isRefreshing.collectAsState()
    val refreshError by source.refreshError.collectAsState()
    val reachable by source.isReachable.collectAsState()

    val filtered = remember(artists, query, favoritesOnly) {
        artists.filter {
            (!favoritesOnly || it.favorite) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    when {
        artists.isEmpty() && isRefreshing -> LibraryLoading(contentPadding, modifier)
        artists.isEmpty() && !reachable -> LibraryOffline(contentPadding, modifier)
        artists.isEmpty() && refreshError != null ->
            LibraryError(refreshError!!, "Failed to load artists", contentPadding, modifier)
        filtered.isEmpty() && (query.isNotBlank() || favoritesOnly) ->
            NoSearchResults(query = query, contentPadding = contentPadding, modifier = modifier, favoritesOnly = favoritesOnly)
        else -> GridView(
            modifier = modifier,
            contentPadding = contentPadding,
            prefetchUrls = remember(filtered) { filtered.map { it.imageUrl } },
        ) {
            items(filtered, key = { it.id }) { artist ->
                AsyncImageGridCard(
                    title = artist.name,
                    imageUrl = artist.imageUrl,
                    imageHash = artist.imageHash,
                    imageFallback = painterResource(Res.drawable.artist),
                    onClick = { onArtistClick(artist.id) },
                    selectableKind = SelectableKind.Artist,
                    selectionId = artist.id,
                )
            }
        }
    }
}
