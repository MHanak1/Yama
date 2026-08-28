package net.mhanak.yama.ui.views.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import net.mhanak.yama.coordinators.PlaylistEditResult
import net.mhanak.yama.media.sources.PlaylistWritable
import net.mhanak.yama.ui.components.image.CardImage
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
fun PlaylistDetailView(playlistId: String, onBack: () -> Unit, onNavigate: (Any) -> Unit = {}, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val playlists by source.playlists.collectAsState()
    val playlist = playlists.find { it.id == playlistId }
    var retryKey by remember { mutableStateOf(0) }
    var tracksState by remember { mutableStateOf<LoadState<List<Track>>>(LoadState.Loading) }

    // Removing a track is online-only and only where the source supports it (rename/delete of the whole
    // playlist moved to the grid's PlaylistActionSheet).
    val reachable by source.isReachable.collectAsState()
    val editable = source is PlaylistWritable && reachable
    val scope = rememberCoroutineScope()

    LaunchedEffect(playlistId, retryKey) {
        tracksState = LoadState.Loading
        tracksState = runCatching {
            appContainer.catalog.tracksFor(TrackListKind.Playlist, playlistId) {
                appContainer.activeMusicSource.getTracksForPlaylist(playlistId)
            }
        }.fold({ LoadState.Success(it) }, { LoadState.Failure(it) })
    }

    val tracks = (tracksState as? LoadState.Success)?.value ?: emptyList()
    val playlistPlayable = LocalAvailability.current.playlist(playlistId)

    // Recolour the whole app to this playlist and paint its artwork as the app background (see AppColorTheme).
    RegisterDetailTint(imageUrl = playlist?.imageUrl, cacheKey = playlist?.id)

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
                    DownloadButton(kind = DownloadableKind.Playlist, id = playlistId)
                    FavoriteButton(kind = FavoritableKind.Playlist, itemId = playlistId, initial = playlist?.favorite)
                    // Rename/Delete live on the grid card's long-press action sheet (PlaylistActionSheet),
                    // not here — matching how home-shelf items expose their per-item actions.
                }
            }
        }

        if (playlist != null) {
            item {
                DetailViewHeader(
                    onNavigate = onNavigate,
                    imageUrl = playlist.imageUrl,
                    name = playlist.name,
                    genres = playlist.genres.ifEmpty { null },
                    playActions = {
                        DetailPlayActions(
                            player = appContainer.playback.viewed,
                            // Cap at 100 tracks, in playlist order; shuffling randomises the picked set.
                            fetchTracks = { shuffled -> (if (shuffled) tracks.shuffled() else tracks).take(100) },
                            enabled = playlistPlayable,
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
                LogError(state.throwable, context = "Playlist tracks load failed")
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        state.throwable.message ?: "Couldn't load tracks",
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.secondary),
                    )
                    TextButton(onClick = { retryKey++ }) { Text("Retry") }
                }
            }
            is LoadState.Success -> itemsIndexed(state.value, key = { _, track -> track.id }) { index, track ->
                TrackListCard(
                    track = track,
                    tracks = state.value,
                    index = index,
                    player = appContainer.playback.viewed,
                    // A playlist mixes tracks from many albums, so each row shows its own cover (unlike
                    // AlbumDetailView, which shows track numbers against the one shared album art).
                    image = { CardImage(imageUrl = track.imageUrl) },
                    // Remove by position (server order == the loaded order); on success drop it locally so
                    // the list updates without a full reload. A failure leaves the row in place.
                    onRemoveFromPlaylist = if (editable) {
                        {
                            scope.launch {
                                val result = appContainer.playlists.removeTracksFromPlaylist(playlistId, listOf(index))
                                if (result is PlaylistEditResult.Ok) {
                                    tracksState = LoadState.Success(state.value.filterIndexed { i, _ -> i != index })
                                }
                            }
                        }
                    } else null,
                )
            }
        }
    }
}
