package net.mhanak.yama.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Track

@Composable
fun PlaylistDetailView(playlistId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    val playlists by appContainer.activeMusicSource.playlists.collectAsState()
    val playlist = playlists.find { it.id == playlistId }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(playlistId) {
        tracks = appContainer.activeMusicSource.getTracksForPlaylist(playlistId)
    }

    Column(
        modifier = modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        if (playlist != null) {
            AsyncImage(
                model = playlist.imageUrl,
                contentDescription = playlist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }
        tracks.forEach { track ->
            Text(
                text = track.name,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
