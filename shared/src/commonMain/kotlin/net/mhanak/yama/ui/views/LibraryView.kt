package net.mhanak.yama.ui.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import net.mhanak.yama.ui.platform.PullToRefreshContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.state.ErrorCard
import net.mhanak.yama.ui.components.state.LogError
import net.mhanak.yama.ui.components.settings.LibrarySelectionButtons
import net.mhanak.yama.ui.components.state.playableTracks
import net.mhanak.yama.ui.components.settings.LibrarySelectionState
import net.mhanak.yama.ui.platform.LocalHasPullToRefreshIndicator
import net.mhanak.yama.ui.components.settings.LocalLibrarySelection
import net.mhanak.yama.ui.platform.PlatformBackHandler
import net.mhanak.yama.ui.components.settings.SelectableKind
import net.mhanak.yama.ui.theme.glassSource
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.OfflineCapable

private const val TAB_ANIM_DURATION = 300

enum class LibraryTab(val label: String, val icon: ImageVector, val favoritableKind: FavoritableKind) {
    Albums("Albums", Icons.Default.Album, FavoritableKind.Album),
    Artists("Artists", Icons.Default.Person, FavoritableKind.Artist),
    AlbumArtists("Album Artists", Icons.Default.People, FavoritableKind.Artist),
    Genres("Genres", Icons.Default.Category, FavoritableKind.Genre),
    Playlists("Playlists", Icons.AutoMirrored.Filled.QueueMusic, FavoritableKind.Playlist),
    Tracks("Tracks", Icons.Default.MusicNote, FavoritableKind.Track),
}

private fun SelectableKind.toFavoritableKind(): FavoritableKind = when (this) {
    SelectableKind.Album -> FavoritableKind.Album
    SelectableKind.Artist -> FavoritableKind.Artist
    SelectableKind.Genre -> FavoritableKind.Genre
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    // The active tab is owned by MainScreen (so the shared, hoisted top bar can drive/observe it);
    // this view renders that tab's content and, on narrow, syncs it to the swipeable pager.
    selectedTab: LibraryTab,
    // Narrow only: the pager reports swipe-driven tab changes back up so the shared segmented row and
    // search placeholder stay in sync. A no-op on wide, where the rail is the sole tab driver.
    onTabChanged: (LibraryTab) -> Unit,
    // true on the slim layout (swipeable HorizontalPager); false on wide (rail-driven AnimatedContent).
    usePager: Boolean,
    // Inline search filter and favourites filter, both driven by the shared top bar.
    query: String,
    favoritesOnly: Boolean,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumArtistClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    // Slim only: invoked when the user swipes right past the leftmost tab, to pop back to Home.
    onSwipeToHome: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    // Top inset reserved for the shared (overlaid) Home/Library top bar.
    topContentPadding: Dp = 0.dp,
    // Extra space added below the scrollable content so list ends clear the overlaid bottom bar.
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    // Narrow-only swipeable pager; seeded from the hoisted tab and synced both ways below.
    val pagerState = rememberPagerState(initialPage = selectedTab.ordinal, pageCount = { LibraryTab.entries.size })
    val isRefreshing by appContainer.activeMusicSource.isRefreshing.collectAsState()

    // Keep the narrow pager and the hoisted tab in step. An external change (segmented-row tap) scrolls
    // the pager; a swipe reports the new page back up. The equality guards keep the two from fighting.
    LaunchedEffect(selectedTab, usePager) {
        if (usePager && pagerState.currentPage != selectedTab.ordinal) {
            pagerState.animateScrollToPage(selectedTab.ordinal)
        }
    }
    LaunchedEffect(pagerState, usePager) {
        if (usePager) {
            snapshotFlow { pagerState.currentPage }.collect { page -> onTabChanged(LibraryTab.entries[page]) }
        }
    }

    // Slim only: the pager owns horizontal drags, so the top-level swipe detector in MainScreen never
    // sees a right-swipe over the library grid. Instead, observe (via nested scroll, without consuming)
    // the rightward drag the pager can't act on at the leftmost tab (page 0) and pop Home on release
    // once it passes the same 56.dp threshold the MainScreen detector uses for the Home → Library swipe.
    val density = LocalDensity.current
    val currentOnSwipeToHome by rememberUpdatedState(onSwipeToHome)
    val homeSwipeConnection = remember(pagerState, density) {
        object : NestedScrollConnection {
            val threshold = with(density) { 56.dp.toPx() }
            var overscroll = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Peek before the pager consumes; don't consume, so its overscroll stretch still shows.
                if (source == NestedScrollSource.UserInput && pagerState.currentPage == 0 && available.x > 0f) {
                    overscroll += available.x
                } else if (available.x < 0f) {
                    overscroll = 0f
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscroll >= threshold) currentOnSwipeToHome?.invoke()
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }

    // Multi-selection of albums/artists/genres for batch playback (see LibrarySelectionButtons). Cleared
    // when the tab changes so a selection never lingers over a different (or non-selectable) tab.
    val selection = remember { LibrarySelectionState() }
    LaunchedEffect(selectedTab) { selection.clear() }

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
            // Only enqueue what can actually be played right now (offline drops undownloaded tracks).
            val tracks = playableTracks(appContainer, gatherSelectedTracks(shuffled))
            if (tracks.isNotEmpty()) appContainer.playback.viewed.playNow(tracks)
            selection.clear()
        }
    }

    // Favourite state for the current selection: whether *every* selected item is already favourited
    // (drives the heart's filled/outlined look) and whether the source can favourite this kind at all.
    val selectionFavKind = selection.kind?.toFavoritableKind()
    val selectionFavoritesSupported =
        selectionFavKind != null && (appContainer.activeMusicSource as? FavoriteCapable)?.supportsFavorites(selectionFavKind) == true
    var allSelectedFavorite by remember { mutableStateOf(false) }
    LaunchedEffect(selection.selectedIds.toList(), selectionFavKind, appContainer.activeMusicSource) {
        val source = appContainer.activeMusicSource
        // Snapshot the selection before iterating: isFavorite() suspends per item, and the user can
        // add/remove selections on the main thread during those suspension points. Iterating the live
        // SnapshotStateList across a suspend point throws ConcurrentModificationException.
        val ids = selection.selectedIds.toList()
        val fav = source as? FavoriteCapable
        allSelectedFavorite =
            selectionFavKind != null && ids.isNotEmpty() && fav != null &&
                ids.all { fav.isFavorite(selectionFavKind, it) }
    }

    fun toggleSelectionFavorite() {
        val kind = selectionFavKind ?: return
        val target = !allSelectedFavorite
        val ids = selection.selectedIds.toList()
        allSelectedFavorite = target // optimistic; the writes are best-effort and re-read on next change.
        ids.forEach { appContainer.favorites.setFavorite(kind, it, target) }
    }

    // Batch-download the selection: fan each selected container out to the download manager at the
    // default download quality. Only offered when the active source persists downloads (Jellyfin).
    val downloadsSupported = (appContainer.activeMusicSource as? OfflineCapable)?.downloadSourceKey() != null
    fun downloadSelection() {
        val kind = selection.kind ?: return
        val manager = appContainer.downloadManager
        selection.selectedIds.forEach { id ->
            when (kind) {
                SelectableKind.Album -> manager.enqueueAlbum(id)
                SelectableKind.Artist -> manager.enqueueArtist(id)
                SelectableKind.Genre -> manager.enqueueGenre(id)
            }
        }
        selection.clear()
    }

    CompositionLocalProvider(LocalLibrarySelection provides selection) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (usePager) Modifier.nestedScroll(homeSwipeConnection) else Modifier),
    ) {
        // The Home/Library top bar is hoisted into MainScreen and overlaid above this content, so it
        // stays stationary while only the body slides on Home ⇄ Library. [topContentPadding] reserves
        // its height; horizontal system-bar insets (landscape nav bar / cutout) are added here.
        val contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).asPaddingValues()
            .plus(PaddingValues(top = topContentPadding, bottom = bottomContentPadding))
        PullToRefreshContainer(
            isRefreshing = isRefreshing,
            onRefresh = { scope.launch { appContainer.activeMusicSource.refresh() } },
            topPadding = topContentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (usePager) {
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
                    targetState = selectedTab,
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

        // Floating play/shuffle controls for the current multi-selection, above the overlaid player bar.
        LibrarySelectionButtons(
            visible = selection.isActive,
            allFavorite = allSelectedFavorite,
            favoritesSupported = selectionFavoritesSupported,
            downloadsSupported = downloadsSupported,
            onDownload = { downloadSelection() },
            onToggleFavorite = { toggleSelectionFavorite() },
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
        LibraryTab.Tracks -> TracksView(modifier = modifier, contentPadding = contentPadding, favoritesOnly = favoritesOnly, query = query)
    }
}

/**
 * Centered loading state for a library grid. On platforms whose [PullToRefreshContainer] draws its own
 * refresh spinner (Android), this renders nothing so the two don't stack on first load; desktop/TV —
 * which have no pull indicator — keep the spinner.
 */
@Composable
internal fun LibraryLoading(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    if (LocalHasPullToRefreshIndicator.current) return
    Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Error state for a library grid. Made vertically scrollable so the enclosing [PullToRefreshContainer]
 * can still register a downward pull over it — without a scrollable child the gesture never fires, so
 * the user couldn't retry by pulling.
 */
@Composable
internal fun LibraryError(
    error: Throwable,
    fallbackMessage: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LogError(error, context = "Library load failed")
    Box(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ErrorCard(message = error.message ?: fallbackMessage)
    }
}

/**
 * Shown by a library grid when the source is unreachable and we have no cached content to show — an
 * expected offline state, not an error (a connection error is only surfaced while [reachable]).
 */
@Composable
internal fun LibraryOffline(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "You're offline. Downloaded music is available; reconnect to see your full library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
