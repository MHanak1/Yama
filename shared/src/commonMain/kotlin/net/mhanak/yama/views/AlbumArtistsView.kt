package net.mhanak.yama.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.AsyncImageGridCard
import net.mhanak.yama.components.ErrorCard
import net.mhanak.yama.components.GridView
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.artist

@Composable
fun AlbumArtistsView(
    onAlbumArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    query: String = "",
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val albumArtists by source.albumArtists.collectAsState()
    val isRefreshing by source.isRefreshing.collectAsState()
    val refreshError by source.refreshError.collectAsState()

    val filtered = remember(albumArtists, query) {
        if (query.isBlank()) albumArtists
        else albumArtists.filter { it.name.contains(query, ignoreCase = true) }
    }

    when {
        albumArtists.isEmpty() && isRefreshing -> Box(
            modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        albumArtists.isEmpty() && refreshError != null -> Box(
            modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            ErrorCard(message = refreshError!!.message ?: "Failed to load album artists")
        }
        filtered.isEmpty() && query.isNotBlank() ->
            NoSearchResults(query = query, contentPadding = contentPadding, modifier = modifier)
        else -> GridView(
            modifier = modifier,
            contentPadding = contentPadding,
            prefetchUrls = remember(filtered) { filtered.map { it.imageUrl } },
        ) {
            items(filtered) { artist ->
                AsyncImageGridCard(
                    title = artist.name,
                    imageUrl = artist.imageUrl,
                    imageHash = artist.imageHash,
                    imageFallback = painterResource(Res.drawable.artist),
                    onClick = { onAlbumArtistClick(artist.id) },
                )
            }
        }
    }
}
