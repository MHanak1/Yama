package net.mhanak.yama.ui.views.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.state.ErrorBox
import net.mhanak.yama.ui.components.state.LogError
import net.mhanak.yama.ui.components.state.LoadState
import net.mhanak.yama.ui.components.detail.DetailPlayActions
import net.mhanak.yama.ui.components.detail.DetailViewHeader
import net.mhanak.yama.ui.components.input.DownloadButton
import net.mhanak.yama.ui.components.input.DownloadableKind
import net.mhanak.yama.ui.components.input.FavoriteButton
import net.mhanak.yama.ui.components.library.ListView
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.theme.RegisterDetailTint
import net.mhanak.yama.ui.components.library.TrackListCard
import net.mhanak.yama.ui.theme.glassSource
import net.mhanak.yama.media.download.TrackListKind
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.FavoritableKind

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
    var retryKey by remember { mutableStateOf(0) }
    var tracksState by remember { mutableStateOf<LoadState<List<Track>>>(LoadState.Loading) }

    LaunchedEffect(albumId, retryKey) {
        tracksState = LoadState.Loading
        tracksState = runCatching {
            appContainer.catalog.tracksFor(TrackListKind.Album, albumId) {
                appContainer.activeMusicSource.getTracksForAlbum(albumId)
            }
        }.fold({ LoadState.Success(it) }, { LoadState.Failure(it) })
    }

    val tracks = (tracksState as? LoadState.Success)?.value ?: emptyList()
    // The album is playable if it's downloaded or the source is reachable; gate Play/Shuffle on it.
    val albumPlayable = LocalAvailability.current.album(albumId)

    // Recolour the whole app to this album and paint its artwork as the app background (see AppColorTheme).
    RegisterDetailTint(imageUrl = album?.imageUrl, cacheKey = album?.id)

    ListView(
        modifier = modifier
            .glassSource(zIndex = 1f)
            .statusBarsPadding(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadButton(kind = DownloadableKind.Album, id = albumId)
                    FavoriteButton(kind = FavoritableKind.Album, itemId = albumId, initial = album?.favorite)
                }
            }
        }

        if (album != null) {
            item {
                DetailViewHeader(
                    onNavigate = onNavigate,
                    imageUrl = album.imageUrl,
                    name = album.name,
                    albumArtist = album.albumArtist,
                    genres = album.genres.ifEmpty { null },
                    year = album.year,
                    playActions = {
                        DetailPlayActions(
                            player = appContainer.playback.viewed,
                            fetchTracks = { shuffled -> if (shuffled) tracks.shuffled() else tracks },
                            enabled = albumPlayable,
                        )
                    },
                )
            }
        }

        when (val state = tracksState) {
            LoadState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            is LoadState.Failure -> item {
                LogError(state.throwable, context = "Album tracks load failed")
                Column (
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ){
                    Text(
                        state.throwable.message ?: "Couldn't load tracks",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.secondary),
                    )
                    TextButton(onClick = { retryKey++ }) {
                        Text("Retry")
                    }
                }
            }

            is LoadState.Success -> itemsIndexed(state.value, key = { _, track -> track.id }) { index, track ->
                TrackListCard(
                    track = track,
                    tracks = state.value,
                    index = index,
                    player = appContainer.playback.viewed,
                    image = { track.trackNumber?.toString()?.let { Text(text = it) } },
                )
            }
        }
    }
}
