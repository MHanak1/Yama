package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.DetailPlayActions
import net.mhanak.yama.components.DetailViewHeader
import net.mhanak.yama.components.ListView
import net.mhanak.yama.components.RegisterDetailTint
import net.mhanak.yama.components.TrackListCard
import net.mhanak.yama.components.glassSource
import net.mhanak.yama.media.model.Track

@Composable
fun AlbumDetailView(
    albumId: String,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val appContainer = LocalAppContainer.current
    val albums by appContainer.activeMusicSource.albums.collectAsState()
    val album = albums.find { it.id == albumId }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(albumId) {
        tracks = appContainer.activeMusicSource.getTracksForAlbum(albumId)
    }

    // Recolour the whole app to this album and paint its artwork as the app background (see AppColorTheme).
    RegisterDetailTint(imageUrl = album?.imageUrl, cacheKey = album?.id)

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

        if (album != null) {
            item {
                DetailViewHeader(
                    onNavigate = onNavigate,
                    imageUrl = album.imageUrl,
                    name = album.name,
                    artist = album.albumArtist,
                    genres = album.genres.ifEmpty { null },
                    year = album.year,
                    playActions = {
                        DetailPlayActions(
                            player = appContainer.playback.active,
                            fetchTracks = { shuffled -> if (shuffled) tracks.shuffled() else tracks },
                        )
                    },
                )
            }
        }

        itemsIndexed(tracks) { index, track ->
            TrackListCard(
                track = track,
                tracks = tracks,
                index = index,
                player = appContainer.playback.active,
                image = { track.trackNumber?.toString()?.let { Text(text = it) } },
            )
        }
    }
}
