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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import net.mhanak.yama.components.PullToRefreshContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.LibrarySelectionButtons
import net.mhanak.yama.components.LibrarySelectionState
import net.mhanak.yama.components.LocalLibrarySelection
import net.mhanak.yama.components.PlatformBackHandler
import net.mhanak.yama.components.SearchBar
import net.mhanak.yama.components.SegmentedButtonRow
import net.mhanak.yama.components.SelectableKind
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.components.glassSource
import net.mhanak.yama.components.player.PlaybackTargetSheet
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.sources.FavoritableKind

private const val TAB_ANIM_DURATION = 300

enum class LibraryTab(val label: String, val icon: ImageVector, val favoritableKind: FavoritableKind) {
    Albums("Albums", Icons.Default.Album, FavoritableKind.Album),
    Artists("Artists", Icons.Default.Person, FavoritableKind.Artist),
    AlbumArtists("Album Artists", Icons.Default.People, FavoritableKind.Artist),
    Genres("Genres", Icons.Default.Category, FavoritableKind.Genre),
    Playlists("Playlists", Icons.Default.QueueMusic, FavoritableKind.Playlist),
    Tracks("Tracks", Icons.Default.MusicNote, FavoritableKind.Track),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    externalTab: LibraryTab?,
    onMenuClick: (() -> Unit)?,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumArtistClick: (String) -> Unit,
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
    val activeTab = externalTab ?: internalTab
    val isRefreshing by appContainer.activeMusicSource.isRefreshing.collectAsState()

    // Search filters the currently visible tab; threaded down into the tab content.
    var query by remember { mutableStateOf("") }

    // Cast / "Play on" target picker, mirroring the button in FullPlayer.
    val canCast = appContainer.activeMusicSource is RemotePlaybackProvider
    var showTargets by remember { mutableStateOf(false) }

    // Favourites filter — restricts the active tab to favourited items. Only shown when the source
    // can favourite this kind of item.
    var favoritesOnly by remember { mutableStateOf(false) }
    val canFavoriteFilter = appContainer.activeMusicSource.supportsFavorites(activeTab.favoritableKind)

    // Multi-selection of albums/artists/genres for batch playback (see LibrarySelectionButtons). Cleared
    // when the tab changes so a selection never lingers over a different (or non-selectable) tab.
    val selection = remember { LibrarySelectionState() }
    LaunchedEffect(activeTab) { selection.clear() }

    // Concatenate the selected items' tracks (in selection order), shuffling the whole pool when asked.
    suspend fun gatherSelectedTracks(shuffled: Boolean): List<Track> {
        val kind = selection.kind ?: return emptyList()
        val source = appContainer.activeMusicSource
        val tracks = selection.selectedIds.flatMap { id ->
            when (kind) {
                SelectableKind.Album -> source.getTracksForAlbum(id)
                SelectableKind.Artist -> source.getTracksForArtist(id)
                SelectableKind.Genre -> source.getTracksForGenre(id)
            }
        }
        return if (shuffled) tracks.shuffled() else tracks
    }

    fun playSelection(shuffled: Boolean) {
        scope.launch {
            val tracks = gatherSelectedTracks(shuffled)
            if (tracks.isNotEmpty()) appContainer.playback.active.playNow(tracks)
            selection.clear()
        }
    }

    fun navigateTo(tab: LibraryTab) {
        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
    }

    CompositionLocalProvider(LocalLibrarySelection provides selection) {
    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassEffect(MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            // The search field lives in the title slot, so it fills the space
                            // between the menu button (if any) and the cast action.
                            title = {
                                SearchBar(
                                    query = query,
                                    onQueryChange = { query = it },
                                    placeholder = "Search ${activeTab.label.lowercase()}",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            },
                            modifier = Modifier.height(64.dp),
                            navigationIcon = {
                                if (onMenuClick != null) {
                                    IconButton(onClick = onMenuClick) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                                    }
                                }
                            },
                            actions = {
                                if (canFavoriteFilter) {
                                    IconButton(onClick = { favoritesOnly = !favoritesOnly }) {
                                        Icon(
                                            if (favoritesOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                            contentDescription = if (favoritesOnly) "Showing favourites only" else "Show favourites only",
                                            tint = if (favoritesOnly) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (canCast) {
                                    val isCasting = appContainer.playback.activeTarget != null
                                    IconButton(onClick = { showTargets = true }) {
                                        Icon(
                                            if (isCasting) Icons.Filled.Speaker else Icons.Outlined.Speaker,
                                            contentDescription = "Play on another device",
                                            tint = if (isCasting) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        )
                    }
                }
                if (externalTab == null) {
                    SegmentedButtonRow(
                        options = LibraryTab.entries,
                        selectedOption = internalTab,
                        onOptionSelected = { navigateTo(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { tab -> Text(tab.label) }
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
                        query = query,
                        favoritesOnly = favoritesOnly,
                        contentPadding = contentPadding,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onAlbumArtistClick = onAlbumArtistClick,
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
                        query = query,
                        favoritesOnly = favoritesOnly,
                        contentPadding = contentPadding,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onAlbumArtistClick = onAlbumArtistClick,
                        onGenreClick = onGenreClick,
                        onPlaylistClick = onPlaylistClick,
                    )
                }
            }
        }
    }

        // Floating play/shuffle controls for the current multi-selection, above the overlaid player bar.
        LibrarySelectionButtons(
            visible = selection.isActive,
            onPlay = { playSelection(shuffled = false) },
            onShuffle = { playSelection(shuffled = true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // bottomContentPadding covers the bar/mini-player; the bars add the system
                // navigation-bar inset below themselves, so clear that too (as the detail views do).
                .padding(WindowInsets.navigationBars.asPaddingValues())
                .padding(16.dp)
                .padding(bottom = bottomContentPadding),
        )

        // Back clears the selection instead of leaving the library.
        PlatformBackHandler(enabled = selection.isActive) { selection.clear() }
    }
    }

    if (showTargets) {
        PlaybackTargetSheet(onDismiss = { showTargets = false })
    }
}

@Composable
private fun LibraryTabContent(
    tab: LibraryTab,
    query: String,
    favoritesOnly: Boolean,
    contentPadding: PaddingValues,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumArtistClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
) {
    val modifier = Modifier.fillMaxSize()
    when (tab) {
        LibraryTab.Albums -> AlbumsView(onAlbumClick = onAlbumClick, modifier = modifier, contentPadding = contentPadding, query = query, favoritesOnly = favoritesOnly)
        LibraryTab.Artists -> ArtistsView(onArtistClick = onArtistClick, modifier = modifier, contentPadding = contentPadding, query = query, favoritesOnly = favoritesOnly)
        LibraryTab.AlbumArtists -> AlbumArtistsView(onAlbumArtistClick = onAlbumArtistClick, modifier = modifier, contentPadding = contentPadding, query = query, favoritesOnly = favoritesOnly)
        LibraryTab.Genres -> GenresView(onGenreClick = onGenreClick, modifier = modifier, contentPadding = contentPadding, query = query, favoritesOnly = favoritesOnly)
        LibraryTab.Playlists -> PlaylistsView(onPlaylistClick = onPlaylistClick, modifier = modifier, contentPadding = contentPadding, query = query, favoritesOnly = favoritesOnly)
        LibraryTab.Tracks -> TracksView(modifier = modifier, contentPadding = contentPadding)
    }
}

/**
 * Shown by the tab views when the active filters leave nothing to show — either a search [query]
 * matched nothing or [favoritesOnly] is on and no item is favourited.
 */
@Composable
internal fun NoSearchResults(
    query: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    favoritesOnly: Boolean = false,
) {
    Box(
        modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                query.isNotBlank() -> "No results for \"$query\""
                favoritesOnly -> "No favourites here yet"
                else -> "Nothing here"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
