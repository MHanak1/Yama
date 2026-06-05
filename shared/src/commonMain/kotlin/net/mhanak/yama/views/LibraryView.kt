package net.mhanak.yama.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.components.glassSource

enum class LibraryTab(val label: String, val icon: ImageVector) {
    Albums("Albums", Icons.Default.Album),
    Artists("Artists", Icons.Default.Person),
    Genres("Genres", Icons.Default.Category),
    Playlists("Playlists", Icons.Default.QueueMusic),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    externalTab: LibraryTab?,
    onMenuClick: (() -> Unit)?,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { LibraryTab.entries.size })
    val internalTab = LibraryTab.entries[pagerState.currentPage]

    fun navigateTo(tab: LibraryTab) {
        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassEffect(MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            title = { Text(externalTab?.label ?: "Yama") },
                            modifier = Modifier.height(48.dp),
                            navigationIcon = {
                                if (onMenuClick != null) {
                                    IconButton(onClick = onMenuClick) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        )
                    }
                }
                if (externalTab == null) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            LibraryTab.entries.forEachIndexed { index, tab ->
                                val selected = internalTab == tab
                                val shape = SegmentedButtonDefaults.itemShape(index, LibraryTab.entries.size)
                                SegmentedButton(
                                    selected = selected,
                                    onClick = { navigateTo(tab) },
                                    shape = shape,
                                    modifier = Modifier.glassEffect(
                                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                        shape,
                                    ),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = Color.Transparent,
                                        inactiveContainerColor = Color.Transparent,
                                    ),
                                    icon = {},
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        if (externalTab == null) {
            // Narrow: swipeable pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .glassSource(zIndex = 1f)
                    .fillMaxSize(),
            ) { page ->
                LibraryTabContent(
                    tab = LibraryTab.entries[page],
                    contentPadding = innerPadding,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onGenreClick = onGenreClick,
                    onPlaylistClick = onPlaylistClick,
                )
            }
        } else {
            // Wide: direct content driven by the sidebar
            Box(
                modifier = Modifier
                    .glassSource(zIndex = 1f)
                    .fillMaxSize(),
            ) {
                LibraryTabContent(
                    tab = externalTab,
                    contentPadding = innerPadding,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onGenreClick = onGenreClick,
                    onPlaylistClick = onPlaylistClick,
                )
            }
        }
    }
}

@Composable
private fun LibraryTabContent(
    tab: LibraryTab,
    contentPadding: PaddingValues,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
) {
    val modifier = Modifier.fillMaxSize()
    when (tab) {
        LibraryTab.Albums -> AlbumsView(onAlbumClick = onAlbumClick, modifier = modifier, contentPadding = contentPadding)
        LibraryTab.Artists -> ArtistsView(onArtistClick = onArtistClick, modifier = modifier, contentPadding = contentPadding)
        LibraryTab.Genres -> GenresView(onGenreClick = onGenreClick, modifier = modifier, contentPadding = contentPadding)
        LibraryTab.Playlists -> PlaylistsView(onPlaylistClick = onPlaylistClick, modifier = modifier, contentPadding = contentPadding)
    }
}
