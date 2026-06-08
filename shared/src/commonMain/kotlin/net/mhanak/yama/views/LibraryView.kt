package net.mhanak.yama.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
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
import net.mhanak.yama.components.PullToRefreshContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.components.glassSource

private const val TAB_ANIM_DURATION = 300

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
    // Extra space added below the scrollable content so list ends clear the overlaid bottom bar.
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { LibraryTab.entries.size })
    val internalTab = LibraryTab.entries[pagerState.currentPage]
    val isRefreshing by appContainer.activeMusicSource.isRefreshing.collectAsState()

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
        val contentPadding = innerPadding.plus(PaddingValues(bottom = bottomContentPadding))
        PullToRefreshContainer(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { appContainer.activeMusicSource.refresh() } },
            topPadding = innerPadding.calculateTopPadding(),
            modifier = Modifier.fillMaxSize(),
        ) {
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
                        contentPadding = contentPadding,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onGenreClick = onGenreClick,
                        onPlaylistClick = onPlaylistClick,
                    )
                }
            } else {
                // Wide: rail-driven content, with a vertical slide mirroring the narrow pager's
                // horizontal one — later tabs slide up from below, earlier tabs down from above.
                AnimatedContent(
                    targetState = externalTab,
                    modifier = Modifier
                        .glassSource(zIndex = 1f)
                        .fillMaxSize()
                        // Clip so the vertically sliding tab content can't bleed up into the top bar.
                        .clipToBounds(),
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInVertically(tween(TAB_ANIM_DURATION)) { h -> dir * h } + fadeIn(tween(TAB_ANIM_DURATION)))
                            .togetherWith(
                                slideOutVertically(tween(TAB_ANIM_DURATION)) { h -> -dir * h } + fadeOut(tween(TAB_ANIM_DURATION)),
                            )
                    },
                    label = "libraryTab",
                ) { tab ->
                    LibraryTabContent(
                        tab = tab,
                        contentPadding = contentPadding,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onGenreClick = onGenreClick,
                        onPlaylistClick = onPlaylistClick,
                    )
                }
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
