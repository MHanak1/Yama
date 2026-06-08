package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.ListCard
import net.mhanak.yama.components.ListView
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

    ListView(
        modifier = modifier
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
                AsyncImage(
                    model = playlist.imageUrl,
                    contentDescription = playlist.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
        }

        items(tracks) { track ->
            ListCard(
                title = track.name,
                subtitle = track.artists?.joinToString(", "),
            )
        }
    }
}
