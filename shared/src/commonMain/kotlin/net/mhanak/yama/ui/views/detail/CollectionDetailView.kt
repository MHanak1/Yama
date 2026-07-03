package net.mhanak.yama.ui.views.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import net.mhanak.yama.ui.components.state.ErrorBox
import net.mhanak.yama.ui.components.state.LoadState
import net.mhanak.yama.ui.components.library.AsyncImageListCard
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.detail.DetailPlayActions
import net.mhanak.yama.ui.components.detail.DetailViewHeader
import net.mhanak.yama.ui.components.input.DownloadButton
import net.mhanak.yama.ui.components.input.DownloadableKind
import net.mhanak.yama.ui.components.input.FavoriteButton
import net.mhanak.yama.ui.components.library.ListView
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.theme.RegisterDetailTint
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.components.library.TrackListCard
import net.mhanak.yama.ui.theme.glassSource
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.ui.screens.AlbumDetailRoute

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
    kind: FavoritableKind? = null,
    itemId: String? = null,
    initialFavorite: Boolean? = null,
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
    var tracksState by remember { mutableStateOf<LoadState<List<Track>>>(LoadState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }

    LaunchedEffect(cacheKey, sortOrder, retryKey) {
        tracksState = LoadState.Loading
        tracksState = runCatching { fetchTopTracks(5, sortOrder) }
            .fold({ LoadState.Success(it) }, { LoadState.Failure(it) })
    }

    val tracks = (tracksState as? LoadState.Success)?.value ?: emptyList()

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val downloadKind = when (kind) {
                        FavoritableKind.Artist -> DownloadableKind.Artist
                        FavoritableKind.Genre -> DownloadableKind.Genre
                        FavoritableKind.Playlist -> DownloadableKind.Playlist
                        else -> null
                    }
                    if (downloadKind != null && itemId != null) {
                        DownloadButton(kind = downloadKind, id = itemId)
                    }
                    if (kind != null) {
                        FavoriteButton(kind = kind, itemId = itemId, initial = initialFavorite)
                    }
                }
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
                        // Playable if downloaded (per kind) or the source is reachable.
                        val playable = when (kind) {
                            FavoritableKind.Artist -> LocalAvailability.current.artist(itemId ?: "")
                            FavoritableKind.Genre -> LocalAvailability.current.genre(itemId ?: "")
                            else -> LocalAvailability.current.reachable
                        }
                        DetailPlayActions(
                            player = appContainer.playback.viewed,
                            // Collections can be huge, so cap at 100 tracks; shuffling pulls a random
                            // set from the backend rather than reshuffling only the displayed sort order.
                            fetchTracks = { shuffled ->
                                fetchTopTracks(100, if (shuffled) TrackSortOrder.Random else sortOrder)
                            },
                            enabled = playable,
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

        when (val state = tracksState) {
            LoadState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is LoadState.Failure -> item {
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
                val album = albums.find { it.id == track.albumId }
                TrackListCard(
                    track = track,
                    tracks = state.value,
                    index = index,
                    player = appContainer.playback.viewed,
                    subtitle = track.album,
                    image = { CardImage(imageUrl = album?.imageUrl ?: track.imageUrl, imageHash = album?.imageHash) },
                )
            }
        }

        if (fetchAlbums != null) {
            item { CollectionSectionHeader(title = "Albums", onViewAll = onViewAllAlbums) }

            items(albums, key = { it.id }) { album ->
                AsyncImageListCard(
                    title = album.name,
                    subtitle = album.year?.toString(),
                    imageUrl = album.imageUrl,
                    imageHash = album.imageHash,
                    onClick = { onNavigate(AlbumDetailRoute(album.id)) },
                    focusKey = album.id,
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
