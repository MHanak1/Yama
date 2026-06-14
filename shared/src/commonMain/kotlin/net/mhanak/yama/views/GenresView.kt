package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.AsyncImageGridCard
import net.mhanak.yama.components.GridView
import net.mhanak.yama.components.SelectableKind
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.folder

@Composable
fun GenresView(
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    query: String = "",
    favoritesOnly: Boolean = false,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val genres by source.genres.collectAsState()
    val isRefreshing by source.isRefreshing.collectAsState()
    val refreshError by source.refreshError.collectAsState()

    val filtered = remember(genres, query, favoritesOnly) {
        genres.filter {
            (!favoritesOnly || it.favorite) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    when {
        genres.isEmpty() && isRefreshing -> LibraryLoading(contentPadding, modifier)
        genres.isEmpty() && refreshError != null ->
            LibraryError(refreshError!!.message ?: "Failed to load genres", contentPadding, modifier)
        filtered.isEmpty() && (query.isNotBlank() || favoritesOnly) ->
            NoSearchResults(query = query, contentPadding = contentPadding, modifier = modifier, favoritesOnly = favoritesOnly)
        else -> GridView(
            modifier = modifier,
            contentPadding = contentPadding,
            prefetchUrls = remember(filtered) { filtered.map { it.imageUrl } },
        ) {
            items(filtered) { genre ->
                AsyncImageGridCard(
                    title = genre.name,
                    imageUrl = genre.imageUrl,
                    imageHash = genre.imageHash,
                    onClick = { onGenreClick(genre.id) },
                    imageFallback = painterResource(Res.drawable.folder),
                    selectableKind = SelectableKind.Genre,
                    selectionId = genre.id,
                )
            }
        }
    }
}
