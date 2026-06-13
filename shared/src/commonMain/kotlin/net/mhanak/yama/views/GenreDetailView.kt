package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.views.CollectionDetailView
import net.mhanak.yama.screens.GenreTracksRoute

@Composable
fun GenreDetailView(
    genreId: String,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val genres by appContainer.activeMusicSource.genres.collectAsState()
    val genre = genres.find { it.id == genreId }

    CollectionDetailView(
        name = genre?.name,
        imageUrl = genre?.imageUrl,
        cacheKey = genreId,
        onBack = onBack,
        onNavigate = onNavigate,
        onViewAllTracks = { onNavigate(GenreTracksRoute(genreId)) },
        fetchTopTracks = { limit, sortBy ->
            appContainer.activeMusicSource.getTracksForGenre(genreId, limit, 0, sortBy)
        },
        fetchAlbums = { appContainer.activeMusicSource.getAlbumsForGenre(genreId) },
        modifier = modifier,
        contentPadding = contentPadding,
    )
}
