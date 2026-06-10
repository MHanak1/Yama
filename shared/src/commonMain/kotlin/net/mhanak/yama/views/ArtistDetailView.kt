package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.AsyncImageListCard
import net.mhanak.yama.components.CardImage
import net.mhanak.yama.components.DetailViewHeader
import net.mhanak.yama.components.DynamicColorTheme
import net.mhanak.yama.components.ListView
import net.mhanak.yama.components.TrackListCard
import net.mhanak.yama.components.glassSource
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.screens.AlbumDetailRoute

@Composable
fun ArtistDetailView(artistId: String, onBack: () -> Unit, onNavigate: (Any) -> Unit = {}, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val appContainer = LocalAppContainer.current
    val artists by appContainer.activeMusicSource.artists.collectAsState()
    val artist = artists.find { it.id == artistId }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }

    LaunchedEffect(artistId) {
        tracks = appContainer.activeMusicSource.getTracksForArtist(artistId, limit = 10, sortBy = TrackSortOrder.PlayCount)
        albums = appContainer.activeMusicSource.getAlbumsForArtist(artistId)
    }

    DynamicColorTheme(
        imageUrl = artist?.imageUrl,
        cacheKey = artist?.id,
        enabled = appContainer.albumTintMode.tintsDetails,
    ) {
        ListView(
            modifier = modifier
                .glassSource(zIndex = 1f)
                .statusBarsPadding(),
            contentPadding = contentPadding,
        ) {
            item {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }

            if (artist != null) {
                item {
                    DetailViewHeader(
                        onNavigate = onNavigate,
                        imageUrl = artist.imageUrl,
                        name = artist.name,
                        //genres = TODO(),
                    )
                }
            }

            item{
                Text("Top Tracks", style = MaterialTheme.typography.headlineMedium)
            }

            itemsIndexed(tracks) { index, track ->
                val album = albums.find { it.id == track.albumId }
                TrackListCard(
                    track = track,
                    tracks = tracks,
                    index = index,
                    player = appContainer.playback.active,
                    subtitle = track.album,
                    image = { CardImage(imageUrl = album?.imageUrl, imageHash = album?.imageHash) },
                )
            }

            item{
                Text("Albums", style = MaterialTheme.typography.headlineMedium)
            }

            items(albums) { album ->
                AsyncImageListCard(
                    title = album.name,
                    subtitle = album.year.toString(),
                    imageUrl =  album.imageUrl,
                    imageHash = album.imageHash,
                    onClick = { onNavigate(AlbumDetailRoute(album.id)) },
                )
            }
        }
    }
}
