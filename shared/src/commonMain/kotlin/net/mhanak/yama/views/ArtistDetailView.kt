package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.views.CollectionDetailView
import net.mhanak.yama.screens.ArtistTracksRoute

@Composable
fun ArtistDetailView(
    artistId: String,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val artists by appContainer.activeMusicSource.artists.collectAsState()
    val artist = artists.find { it.id == artistId }

    CollectionDetailView(
        name = artist?.name,
        imageUrl = artist?.imageUrl,
        cacheKey = artistId,
        genres = artist?.genres?.ifEmpty { null },
        onBack = onBack,
        onNavigate = onNavigate,
        onViewAllTracks = { onNavigate(ArtistTracksRoute(artistId)) },
        fetchTopTracks = { limit, sortBy ->
            appContainer.activeMusicSource.getTracksForArtist(artistId, limit, 0, sortBy)
        },
        fetchAlbums = { appContainer.activeMusicSource.getAlbumsForArtist(artistId) },
        modifier = modifier,
        contentPadding = contentPadding,
    )
}
