package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.BlurredBackgroundImage
import net.mhanak.yama.components.DetailViewHeader
import net.mhanak.yama.components.DynamicColorTheme
import net.mhanak.yama.components.GradientDirection
import net.mhanak.yama.components.ListView
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
    val artists by appContainer.activeMusicSource.artists.collectAsState()
    val album = albums.find { it.id == albumId }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    LaunchedEffect(albumId) {
        tracks = appContainer.activeMusicSource.getTracksForAlbum(albumId)
    }

    DynamicColorTheme(
        imageUrl = album?.imageUrl,
        cacheKey = album?.id,
        enabled = appContainer.albumTintMode.tintsDetails,
    ) {
        Surface(
            modifier = modifier.fillMaxSize()
                .glassSource(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            BlurredBackgroundImage(
                imageUrl = album?.imageUrl,
                modifier = Modifier
                    .alpha(0.4f)
            )
        }

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
                    val artist = artists.find { it.name == album.albumArtist }
                    DetailViewHeader(
                        onNavigate = onNavigate,
                        imageUrl = album.imageUrl,
                        name = album.name,
                        artist = artist,
                        //genres = TODO(),
                        year = album.year,
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
}
