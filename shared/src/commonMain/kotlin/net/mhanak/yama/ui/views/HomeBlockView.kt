package net.mhanak.yama.ui.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.HomeBlockKind
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.ui.components.library.AsyncImageGridCard
import net.mhanak.yama.ui.components.library.GridView
import net.mhanak.yama.ui.components.library.PaginatedTrackList
import net.mhanak.yama.ui.components.settings.SelectableKind
import net.mhanak.yama.ui.home.HomeBlockData
import net.mhanak.yama.ui.home.load
import net.mhanak.yama.ui.screens.AlbumDetailRoute
import net.mhanak.yama.ui.screens.GenreDetailRoute
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import yama.shared.generated.resources.folder

// A generous single page for grid-style "See more" pages (album/genre blocks). Track blocks paginate
// properly via PaginatedTrackList and ignore this.
private const val SEE_MORE_LIMIT = 200

/**
 * Full-page "See more" for a home block. Track blocks reuse [PaginatedTrackList] (real pagination +
 * play/shuffle/sort), pointed at the same [net.mhanak.yama.coordinators.CatalogReader] path the shelf
 * uses so it degrades to downloads offline. Album/genre blocks render a one-shot [GridView] loaded at
 * [SEE_MORE_LIMIT] via the shared block loader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeBlockView(
    blockKind: String,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val kind = rememberHomeBlockKind(blockKind)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(kind?.title ?: "Home") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (kind == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("Unknown block")
            }
            return@Scaffold
        }

        val trackSort = kind.trackSort
        if (trackSort != null) {
            // Track-discovery block → the full paginated, sortable list.
            PaginatedTrackList(
                loadPage = { offset, limit, sortBy ->
                    appContainer.catalog.getAllTracks(limit, offset, sortBy)
                },
                defaultSort = trackSort,
                addSingleToQueue = false,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
            )
        } else {
            // Album / genre / downloaded block → a one-shot grid.
            val source = appContainer.activeMusicSource
            val isRefreshing by source.isRefreshing.collectAsState()
            val data by produceState<HomeBlockData?>(initialValue = null, kind, source, isRefreshing) {
                value = runCatching { kind.load(appContainer, limit = SEE_MORE_LIMIT) }.getOrNull()
            }

            val gridPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
            )
            when (val loaded = data) {
                is HomeBlockData.Albums -> GridView(
                    contentPadding = gridPadding,
                    prefetchUrls = loaded.albums.map { it.imageUrl },
                ) {
                    items(loaded.albums, key = { it.id }) { album ->
                        AsyncImageGridCard(
                            title = album.name,
                            subtitle = album.albumArtist ?: "",
                            imageUrl = album.imageUrl,
                            imageHash = album.imageHash,
                            imageFallback = painterResource(Res.drawable.album),
                            onClick = { onNavigate(AlbumDetailRoute(album.id)) },
                            selectableKind = SelectableKind.Album,
                            selectionId = album.id,
                        )
                    }
                }
                is HomeBlockData.Genres -> GridView(contentPadding = gridPadding) {
                    items(loaded.genres, key = { it.id }) { genre ->
                        AsyncImageGridCard(
                            title = genre.name,
                            imageUrl = genre.imageUrl,
                            imageHash = genre.imageHash,
                            imageFallback = painterResource(Res.drawable.folder),
                            onClick = { onNavigate(GenreDetailRoute(genre.id)) },
                        )
                    }
                }
                else -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    // null while loading, or Tracks (unreachable here) — render nothing/quiet.
                }
            }
        }
    }
}

@Composable
private fun rememberHomeBlockKind(name: String): HomeBlockKind? =
    remember(name) { runCatching { HomeBlockKind.valueOf(name) }.getOrNull() }
