package net.mhanak.yama.ui.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.ui.components.library.AsyncImageGridCard
import net.mhanak.yama.ui.components.library.GridView
import net.mhanak.yama.ui.components.playlist.PlaylistActionSheet
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.library_music

@Composable
fun PlaylistsView(
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    query: String = "",
    favoritesOnly: Boolean = false,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val playlists by source.playlists.collectAsState()
    val isRefreshing by source.isRefreshing.collectAsState()
    val refreshError by source.refreshError.collectAsState()
    val reachable by source.isReachable.collectAsState()
    val scope = rememberCoroutineScope()

    // The playlist a long-press / right-click has opened the action sheet for (null = no sheet). The
    // grid outlives the transient sheet, so [scope] drives its playback/edits — a dismiss-on-action
    // disposing the sheet mid-fetch would otherwise cancel it. See PlaylistActionSheet.
    var actionTarget by remember { mutableStateOf<Playlist?>(null) }

    val filtered = remember(playlists, query, favoritesOnly) {
        playlists.filter {
            (!favoritesOnly || it.favorite) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true))
        }
    }

    // The "New playlist" FAB lives in [LibraryView] (a sibling of the tab pager), not here: a glass
    // surface can only frost content it isn't nested within, and this view sits inside the pager's own
    // haze source, so a FAB rendered here would blur nothing and read as opaque.
    Box(modifier = modifier.fillMaxSize()) {
        when {
            playlists.isEmpty() && isRefreshing -> LibraryLoading(contentPadding)
            playlists.isEmpty() && !reachable -> LibraryOffline(contentPadding)
            playlists.isEmpty() && refreshError != null ->
                LibraryError(refreshError!!, "Failed to load playlists", contentPadding)
            filtered.isEmpty() && (query.isNotBlank() || favoritesOnly) ->
                NoSearchResults(query = query, contentPadding = contentPadding, favoritesOnly = favoritesOnly)
            else -> GridView(
                contentPadding = contentPadding,
                prefetchUrls = remember(filtered) { filtered.map { it.imageUrl } },
            ) {
                items(filtered, key = { it.id }) { playlist ->
                    AsyncImageGridCard(
                        title = playlist.name,
                        subtitle = playlist.itemCount?.let { "$it tracks" },
                        imageUrl = playlist.imageUrl,
                        imageHash = playlist.imageHash,
                        imageFallback = painterResource(Res.drawable.library_music),
                        onClick = { onPlaylistClick(playlist.id) },
                        onLongClick = { actionTarget = playlist },
                        focusKey = playlist.id,
                    )
                }
            }
        }
    }

    actionTarget?.let { target ->
        PlaylistActionSheet(
            playlist = target,
            onDismiss = { actionTarget = null },
            playbackScope = scope,
        )
    }
}
