package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
fun PlaylistDetailView(playlistId: String, onBack: () -> Unit, onNavigate: (Any) -> Unit = {}, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val appContainer = LocalAppContainer.current
    val playlists by appContainer.activeMusicSource.playlists.collectAsState()
    val playlist = playlists.find { it.id == playlistId }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(playlistId) {
        tracks = appContainer.activeMusicSource.getTracksForPlaylist(playlistId)
    }

    // Recolour the whole app to this playlist and paint its artwork as the app background (see AppColorTheme).
    RegisterDetailTint(imageUrl = playlist?.imageUrl, cacheKey = playlist?.id)

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

        if (playlist != null) {
            item {
                DetailViewHeader(
                    onNavigate = onNavigate,
                    imageUrl = playlist.imageUrl,
                    name = playlist.name,
                    genres = playlist.genres.ifEmpty { null },
                    playActions = {
                        DetailPlayActions(
                            player = appContainer.playback.active,
                            // Cap at 100 tracks, in playlist order; shuffling randomises the picked set.
                            fetchTracks = { shuffled -> (if (shuffled) tracks.shuffled() else tracks).take(100) },
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
            )
        }
    }
}
