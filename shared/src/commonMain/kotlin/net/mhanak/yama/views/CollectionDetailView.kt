package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.AsyncImageListCard
import net.mhanak.yama.components.CardImage
import net.mhanak.yama.components.DetailPlayActions
import net.mhanak.yama.components.DetailViewHeader
import net.mhanak.yama.components.ListView
import net.mhanak.yama.components.RegisterDetailTint
import net.mhanak.yama.components.SegmentedButtonRow
import net.mhanak.yama.components.TrackListCard
import net.mhanak.yama.components.glassSource
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.screens.AlbumDetailRoute

private val detailSortOptions = listOf(
    TrackSortOrder.PlayCount,
    TrackSortOrder.RecentlyPlayed,
    TrackSortOrder.RecentlyAdded,
    TrackSortOrder.ReleaseDate,
    TrackSortOrder.Alphabetical,
    TrackSortOrder.Random,
)

/**
 * Unified detail view for collections that have both tracks and albums (artists, genres).
 * Shows a top-N track list with sort control and all albums below.
 */
@Composable
fun CollectionDetailView(
    name: String?,
    imageUrl: String?,
    cacheKey: String?,
    genres: List<String>? = null,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit = {},
    onViewAllTracks: (() -> Unit)? = null,
    onViewAllAlbums: (() -> Unit)? = null,
    fetchTopTracks: suspend (limit: Int, sortBy: TrackSortOrder) -> List<Track>,
    fetchAlbums: (suspend () -> List<Album>)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    var sortOrder by remember { mutableStateOf(TrackSortOrder.PlayCount) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }

    LaunchedEffect(cacheKey, sortOrder) {
        tracks = fetchTopTracks(5, sortOrder)
    }

    if (fetchAlbums != null) {
        LaunchedEffect(cacheKey) {
            albums = fetchAlbums()
        }
    }

    // Recolour the whole app to this collection and paint its artwork as the app background (see AppColorTheme).
    RegisterDetailTint(imageUrl = imageUrl, cacheKey = cacheKey)

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

        if (name != null) {
            item {
                DetailViewHeader(
                    onNavigate = onNavigate,
                    imageUrl = imageUrl,
                    name = name,
                    genres = genres,
                    playActions = {
                        DetailPlayActions(
                            player = appContainer.playback.active,
                            // Collections can be huge, so cap at 100 tracks; shuffling pulls a random
                            // set from the backend rather than reshuffling only the displayed sort order.
                            fetchTracks = { shuffled ->
                                fetchTopTracks(100, if (shuffled) TrackSortOrder.Random else sortOrder)
                            },
                        )
                    },
                )
            }
        }

        item { CollectionSectionHeader(title = "Tracks", onViewAll = onViewAllTracks) }

        item {
            SegmentedButtonRow(
                options = detailSortOptions,
                selectedOption = sortOrder,
                onOptionSelected = { sortOrder = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(it.label) }
        }

        itemsIndexed(tracks) { index, track ->
            val album = albums.find { it.id == track.albumId }
            TrackListCard(
                track = track,
                tracks = tracks,
                index = index,
                player = appContainer.playback.active,
                subtitle = track.album,
                image = { CardImage(imageUrl = album?.imageUrl ?: track.imageUrl, imageHash = album?.imageHash) },
            )
        }

        if (fetchAlbums != null) {
            item { CollectionSectionHeader(title = "Albums", onViewAll = onViewAllAlbums) }

            items(albums) { album ->
                AsyncImageListCard(
                    title = album.name,
                    subtitle = album.year?.toString(),
                    imageUrl = album.imageUrl,
                    imageHash = album.imageHash,
                    onClick = { onNavigate(AlbumDetailRoute(album.id)) },
                )
            }
        }
    }
}

@Composable
private fun CollectionSectionHeader(
    title: String,
    onViewAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (onViewAll != null) {
            IconButton(onClick = onViewAll) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "View all $title")
            }
        }
    }
}
