package net.mhanak.yama.ui.screens

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import net.mhanak.yama.ui.components.navigation.AdaptiveNavigationLayout
import net.mhanak.yama.ui.components.navigation.AppBottomBar
import net.mhanak.yama.ui.components.navigation.AppNavRail
import net.mhanak.yama.ui.components.navigation.BottomBarDestination
import net.mhanak.yama.ui.components.navigation.HomeLibraryTopBar
import net.mhanak.yama.ui.platform.KeepScreenOn
import net.mhanak.yama.ui.components.interaction.ActiveContentFocus
import net.mhanak.yama.ui.components.interaction.LocalActiveContentFocus
import net.mhanak.yama.ui.platform.PlatformBackHandler
import net.mhanak.yama.ui.platform.PlatformDeviceWakeEffect
import net.mhanak.yama.ui.platform.PlatformUserInteractionEffect
import net.mhanak.yama.ui.components.interaction.PlayerIdleTimeoutMs
import net.mhanak.yama.ui.components.settings.SourceSwitcher
import net.mhanak.yama.ui.components.interaction.isIdle
import net.mhanak.yama.ui.components.input.isInFlight
import net.mhanak.yama.ui.components.interaction.rememberIdleMonitor
import net.mhanak.yama.ui.components.interaction.resetIdleOn
import net.mhanak.yama.ui.player.FullPlayer
import net.mhanak.yama.ui.player.NowPlayingBar
import net.mhanak.yama.ui.player.PlaybackErrorBanner
import net.mhanak.yama.ui.player.PlaybackTargetSheet
import net.mhanak.yama.ui.player.VolumeIndicator
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.LocalIsTvMode
import androidx.compose.foundation.layout.statusBarsPadding
import net.mhanak.yama.ui.components.library.PaginatedTrackList
import net.mhanak.yama.ui.theme.glassSource
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.ui.views.detail.AlbumDetailView
import net.mhanak.yama.ui.views.settings.AppearanceSettingsView
import net.mhanak.yama.ui.views.downloaded.DownloadedAlbumView
import net.mhanak.yama.ui.views.downloaded.DownloadedMusicView
import net.mhanak.yama.ui.views.downloaded.DownloadedTracksView
import net.mhanak.yama.ui.views.settings.DownloadsSettingsView
import net.mhanak.yama.ui.views.detail.ArtistDetailView
import net.mhanak.yama.ui.views.detail.GenreDetailView
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.ui.views.HomeView
import net.mhanak.yama.ui.views.HomeBlockView
import net.mhanak.yama.ui.views.settings.HomeLayoutView
import net.mhanak.yama.ui.views.LibraryTab
import net.mhanak.yama.ui.views.LibraryView
import net.mhanak.yama.ui.views.SearchView
import net.mhanak.yama.ui.views.settings.LocalLibrarySettingsView
import net.mhanak.yama.ui.views.settings.PlaybackSettingsView
import net.mhanak.yama.ui.views.detail.PlaylistDetailView
import net.mhanak.yama.ui.views.settings.ScrobblingSettingsView
import net.mhanak.yama.ui.views.settings.SettingsView
import net.mhanak.yama.ui.views.settings.SystemSettingsView
import net.mhanak.yama.ui.views.settings.AboutView

private const val DETAIL_DURATION = 320

private inline fun <reified T : Any> NavGraphBuilder.detailComposable(
    crossinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(
        enterTransition = { slideInHorizontally(tween(DETAIL_DURATION)) { it } + fadeIn(tween(DETAIL_DURATION)) },
        popExitTransition = { slideOutHorizontally(tween(DETAIL_DURATION)) { it } + fadeOut(tween(DETAIL_DURATION)) },
    ) { backStackEntry -> content(backStackEntry) }
}

/** Ordered index of the top-level destinations, or null for detail screens. */
private fun NavDestination?.topLevelIndex(): Int? = when {
    this == null -> null
    hasRoute<HomeRoute>() -> 0
    hasRoute<SearchRoute>() -> 1
    hasRoute<LibraryRoute>() -> 2
    hasRoute<SettingsRoute>() -> 3
    else -> null
}

// Slide between top-level destinations (Home/Library/Settings) — later destinations enter from
// the trailing edge. [vertical] (true on rail layouts) slides up/down to mirror the wide library
// tab switch; otherwise it slides left/right. Returns null when either side is a detail screen,
// letting the NavHost fall back to a fade so detail screens keep their slide-over-and-fade parallax.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.topLevelEnter(vertical: Boolean): EnterTransition? {
    val from = initialState.destination.topLevelIndex() ?: return null
    val to = targetState.destination.topLevelIndex() ?: return null
    if (from == to) return null
    val dir = if (to > from) 1 else -1
    val fade = fadeIn(tween(DETAIL_DURATION))
    return if (vertical) slideInVertically(tween(DETAIL_DURATION)) { d -> dir * d } + fade
    else slideInHorizontally(tween(DETAIL_DURATION)) { d -> dir * d } + fade
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.topLevelExit(vertical: Boolean): ExitTransition? {
    val from = initialState.destination.topLevelIndex() ?: return null
    val to = targetState.destination.topLevelIndex() ?: return null
    if (from == to) return null
    val dir = if (to > from) 1 else -1
    val fade = fadeOut(tween(DETAIL_DURATION))
    return if (vertical) slideOutVertically(tween(DETAIL_DURATION)) { d -> -dir * d } + fade
    else slideOutHorizontally(tween(DETAIL_DURATION)) { d -> -dir * d } + fade
}

/** Switch top-level destinations (Home/Library/Settings) keeping a shallow, single-top back stack. */
private fun NavController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun MainScreen() {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf(LibraryTab.Albums) }
    // Hoisted state for the shared Home/Library top bar (rendered as an overlay in the content lambda
    // below): the library's inline search query, its favourites filter, and the cast target-picker
    // trigger. They live here so the bar can persist — stationary — across the Home ⇄ Library switch.
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var showTargets by remember { mutableStateOf(false) }
    val isTV = LocalIsTvMode.current
    val contentFocusRequester = remember { FocusRequester() }
    // The active screen's grid registers its ContentFocusRegistry here. The focus effect below
    // calls registry.requestRestore() to land on the saved leaf item directly (no group redirect,
    // no focusRestorer, no global flag). Falls back to contentFocusRequester for non-content
    // screens (Home, Settings) that have no registry. See TvFocus.kt.
    val activeContentFocus = remember { ActiveContentFocus() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    val onHome = destination?.hasRoute<HomeRoute>() == true
    val onSearch = destination?.hasRoute<SearchRoute>() == true
    val onLibrary = destination?.hasRoute<LibraryRoute>() == true
    val onDownloadedMusic = destination?.hasRoute<DownloadedMusicRoute>() == true ||
        destination?.hasRoute<DownloadedAlbumRoute>() == true ||
        destination?.hasRoute<DownloadedTracksRoute>() == true
    val onSettings = destination?.hasRoute<SettingsRoute>() == true ||
        destination?.hasRoute<AppearanceSettingsRoute>() == true ||
        destination?.hasRoute<PlaybackSettingsRoute>() == true ||
        destination?.hasRoute<ScrobblingSettingsRoute>() == true ||
        destination?.hasRoute<LocalLibrarySettingsRoute>() == true ||
        destination?.hasRoute<SystemSettingsRoute>() == true ||
        destination?.hasRoute<DownloadsSettingsRoute>() == true ||
        destination?.hasRoute<AboutRoute>() == true

    // The active player is Compose-observable so a future switch to a remote player rebinds the UI.
    val player = appContainer.playback.viewed
    val playerStatus by player.status.collectAsState()
    val activeVolume by player.volume.collectAsState()

    // Surface the in-app volume indicator whenever the active player's level changes on its own —
    // chiefly so a controller ("Play On") flashes the bar when it moves the remote device's volume
    // (slider drag, or a change made on the device itself and reported back). Gated on the player not
    // driving *this* device's system stream, where the OS shows its own panel instead. The target's
    // remote-command case is handled separately by PlaybackController.volumeChanged below, which fires
    // even in system-volume mode (a networked change pops no OS panel). Skips the value present at
    // (re)bind so swapping the active player or first composition doesn't flash.
    LaunchedEffect(player) {
        var first = true
        player.volume.collect {
            if (first) first = false
            else if (!player.controlsSystemVolume.value) appContainer.playback.notifyVolumeChanged()
        }
    }
    // Whether the full player was open, persisted across Android config changes (rotation recreates the
    // Activity and would otherwise wipe the plain `remember` below, snapping the player shut) and process
    // death. Animatable itself isn't Saveable, so we save this intent and reseed the Animatable from it.
    var playerWasOpen by rememberSaveable { mutableStateOf(false) }
    val playerExpansion = remember { Animatable(if (playerWasOpen) 1f else 0f) }
    // Mirror the animation's target (0=collapsed, 1=open) back into the saveable so every animateTo call
    // stays the single source of truth; targetValue updates the instant animateTo is invoked.
    LaunchedEffect(playerExpansion) {
        snapshotFlow { playerExpansion.targetValue }.collect { playerWasOpen = it >= 0.5f }
    }
    // Distance from the screen bottom to the mini-player bar's top (bar height on rail, bar + bottom
    // bar on slim). Captured from the layout's content bottom inset so the full player can rest with
    // its top at that line — see NowPlayingBar/FullPlayer peekHeight. Updated inside the content lambda.
    var playerPeek by remember { mutableStateOf(0.dp) }

    // On returning to the foreground, ask the active player to resync. A remote ("Play On") player's
    // live state can go stale while backgrounded (the device keeps playing but our socket push lapses);
    // this re-pulls it so the bar/full player don't show the track that was playing when we left. The
    // local player's refresh is a no-op (its engine state is always current).
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        appContainer.playback.viewed.refresh()
    }

    // On Android, when the device wakes and the app returns to the foreground, a socket left
    // backgrounded may be silently half-open. Rebuild the connection at once rather than waiting
    // ~30s for OkHttp to notice. No-op on desktop (we don't want window refocus to reconnect).
    PlatformDeviceWakeEffect { appContainer.onDeviceWake() }

    // Tapping the Android media notification (or a remote "Play On" handoff to this device) asks —
    // via the controller — to open the full player.
    LaunchedEffect(appContainer.playback.openPlayerRequest) {
        if (appContainer.playback.openPlayerRequest) {
            playerExpansion.animateTo(1f)
            appContainer.playback.openPlayerRequest = false
        }
    }

    // On TV only: after a minute with no interaction while something is playing, surface the full
    // player as a now-playing/screensaver-style display. On phone/desktop this auto-expand is more
    // intrusive than useful, so it's gated to TV — the full player still hides its own controls when
    // idle on every platform. Only armed while collapsed; any pointer/key event resets the timer.
    val idleMonitor = rememberIdleMonitor()
    // Catch interaction the resetIdleOn modifier can't see (Android TV D-pad while content has focus).
    PlatformUserInteractionEffect { idleMonitor.reset() }
    val collapsed = playerExpansion.value == 0f
    val idle = idleMonitor.isIdle(PlayerIdleTimeoutMs, enabled = collapsed && playerStatus.isPlaying && isTV)
    LaunchedEffect(idle) {
        if (idle) playerExpansion.animateTo(1f)
    }

    // Hold the screen awake while the full player is open and something is playing, unless the user
    // opted out. No-op on platforms without a screen-wake API (desktop).
    KeepScreenOn(enabled = appContainer.keepScreenOn && playerStatus.isPlaying && playerExpansion.value > 0f)

    // Wait for the entering destination's lifecycle to reach RESUMED (animation done) before
    // requesting focus, so the exiting composable is already gone and focus lands cleanly.
    LaunchedEffect(navBackStackEntry) {
        if (!isTV) return@LaunchedEffect
        val lifecycle = navBackStackEntry?.lifecycle ?: return@LaunchedEffect
        callbackFlow {
            val observer = LifecycleEventObserver { _, _ -> trySend(lifecycle.currentState) }
            lifecycle.addObserver(observer)
            trySend(lifecycle.currentState)
            awaitClose { lifecycle.removeObserver(observer) }
        }.first { it >= Lifecycle.State.RESUMED }
        // Request focus on the exact previously-focused item via ContentFocusRegistry.requestRestore()
        // (direct leaf requestFocus — no group redirect, no focusRestorer, no global flag). Falls
        // back to the NavHost group for screens with no content grid (Home/Settings).
        val registry = activeContentFocus.registry
        if (registry != null) {
            registry.requestRestore()
        } else {
            runCatching { contentFocusRequester.requestFocus() }
        }
    }

    val onTabClick: (LibraryTab) -> Unit = { tab ->
        val onLibrary = navController.currentBackStackEntry?.destination?.hasRoute<LibraryRoute>() == true
        if (onLibrary && tab == selectedTab) {
            scope.launch { runCatching { appContainer.activeMusicSource.refresh() } }
        } else {
            selectedTab = tab
            if (!onLibrary) {
                // Pop to LibraryRoute if it's already in the back stack (e.g., on a detail screen).
                // Avoid navigateTopLevel here: its saveState+restoreState would immediately
                // restore the detail screen that was just popped.
                if (!navController.popBackStack(LibraryRoute, inclusive = false)) {
                    navController.navigateTopLevel(LibraryRoute)
                }
            }
        }
    }

    // Go to Home. Pop to HomeRoute if it's already in the back stack (e.g., after opening an album from
    // a Home shelf) rather than navigateTopLevel — whose saveState+restoreState would restore that saved
    // detail screen instead of the Home landing page. Mirrors onTabClick's handling for the library.
    val goHome: () -> Unit = {
        if (!navController.popBackStack(HomeRoute, inclusive = false)) {
            navController.navigateTopLevel(HomeRoute)
        }
    }

    Box(Modifier.fillMaxSize().resetIdleOn(idleMonitor)) {
    // Provided to the content (NavHost) subtree only — overlays drawn below (full player, sheets)
    // sit outside it, so their lists never register as the screen's entry-focus target.
    CompositionLocalProvider(LocalActiveContentFocus provides activeContentFocus) {
    AdaptiveNavigationLayout(
        playerActive = playerStatus.current != null,
        // On TV, pressing up from the now-playing bar returns focus to the content grid (or the
        // content area as a fallback), not into limbo above the bar.
        miniPlayer = { tall ->
            NowPlayingBar(
                playerStatus, player,
                playerExpansion = playerExpansion,
                tall = tall,
                peekHeight = playerPeek,
                // On TV, D-pad up from the bar returns focus to the content grid (or the NavHost
                // group fallback for non-content screens), not into limbo above the bar.
                onExitUp = if (isTV) ({
                    val r = activeContentFocus.registry
                    if (r != null) r.requestRestore()
                    else runCatching { contentFocusRequester.requestFocus() }
                }) else null,
            )
        },
        rail = { forceExpanded ->
            AppNavRail(
                forceExpanded = forceExpanded,
                homeSelected = onHome,
                // Highlight the active library tab while browsing the library or a detail screen.
                selectedTab = if (onHome || onSearch || onSettings || onDownloadedMusic) null else selectedTab,
                settingsSelected = onSettings,
                searchSelected = onSearch,
                downloadsSelected = onDownloadedMusic,
                onHomeClick = {
                    // Re-tapping Home while already there refreshes it (mirrors the Library re-tap).
                    if (navController.currentBackStackEntry?.destination?.hasRoute<HomeRoute>() == true) {
                        scope.launch { appContainer.homeContent.refreshActive(appContainer) }
                    } else {
                        goHome()
                    }
                },
                onTabClick = onTabClick,
                onSettingsClick = { navController.navigateTopLevel(SettingsRoute) },
                onSearchClick = { navController.navigateTopLevel(SearchRoute) },
                onDownloadsClick = { navController.navigateTopLevel(DownloadedMusicRoute) },
                nowPlayingVisible = playerStatus.current != null,
                onNowPlayingClick = { scope.launch { playerExpansion.animateTo(1f) } },
            )
        },
        bottomBar = {
            AppBottomBar(
                selected = when {
                    onHome -> BottomBarDestination.Home
                    onSearch -> BottomBarDestination.Search
                    onSettings -> null
                    else -> BottomBarDestination.Library
                },
                onSelect = { dest ->
                    when (dest) {
                        BottomBarDestination.Home -> {
                            if (navController.currentBackStackEntry?.destination?.hasRoute<HomeRoute>() == true) {
                                scope.launch { appContainer.homeContent.refreshActive(appContainer) }
                            } else {
                                goHome()
                            }
                        }
                        BottomBarDestination.Library -> {
                            val onLibrary = navController.currentBackStackEntry?.destination?.hasRoute<LibraryRoute>() == true
                            if (onLibrary) {
                                scope.launch { runCatching { appContainer.activeMusicSource.refresh() } }
                            } else if (!navController.popBackStack(LibraryRoute, inclusive = false)) {
                                navController.navigateTopLevel(LibraryRoute)
                            }
                        }
                        BottomBarDestination.Search -> {
                            if (navController.currentBackStackEntry?.destination?.hasRoute<SearchRoute>() != true) {
                                navController.navigateTopLevel(SearchRoute)
                            }
                        }
                    }
                },
            )
        },
        // Slim modal rail: source switcher at the top, settings pinned to the bottom.
        modalContent = { onClose ->
            SourceSwitcher(onRequestClose = onClose)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
            val activeDownloads by appContainer.downloadManager.downloads.collectAsState()
            val activeDownloadCount = activeDownloads.count { it.state.isInFlight }
            NavigationDrawerItem(
                label = { Text("Downloads") },
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                badge = if (activeDownloadCount > 0) {
                    { Text("$activeDownloadCount") }
                } else null,
                selected = onDownloadedMusic,
                onClick = {
                    onClose()
                    navController.navigateTopLevel(DownloadedMusicRoute)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = onSettings,
                onClick = {
                    onClose()
                    navController.navigateTopLevel(SettingsRoute)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            )
        },
    ) { hasRail, onMenuClick, bottomInset ->
        // Mirror the bar-top line out to the full player (which lives outside this lambda).
        if (playerPeek != bottomInset) playerPeek = bottomInset
        // Which top-level screen the app opens on, per the user's preference (defaults to Home).
        val startDestination = remember {
            if (AppPreferences.launchDestination == LaunchDestination.Library) LibraryRoute else HomeRoute
        }
        // Height reserved for the shared, stationary Home/Library top bar (built below). Measured from
        // the bar itself — it grows on narrow Library as the segmented tab row expands in — and seeded
        // with a status-bar + app-bar estimate so the first Home/Library frame doesn't jump.
        val density = LocalDensity.current
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        var sharedBarHeight by remember { mutableStateOf(statusTop + 64.dp) }
        Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize()
                // Group the content so it's a single D-pad focus region distinct from the rail. The
                // contentFocusRequester is the fallback target for screens without a content grid;
                // entry focus normally goes straight to the grid (see the LaunchedEffect above).
                .then(if (isTV) Modifier.focusRequester(contentFocusRequester).focusGroup() else Modifier)
                // Slim (bottom-bar) layout only: swipe horizontally to move between Home and Library —
                // the same two destinations the bottom bar exposes, sliding in the same horizontal
                // direction as their nav transition. Gated to !hasRail because the wide layout navigates
                // via the rail (and there its top-level transition is vertical), and to !isTV where the
                // D-pad drives navigation. A horizontally-scrolling child (e.g. a Home shelf) consumes
                // the drag first, so this only fires on non-scrolling areas / the vertical library grid.
                .then(
                    if (!hasRail && !isTV) {
                        Modifier.pointerInput(onHome, onLibrary) {
                            val threshold = 56.dp.toPx()
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    // Swipe left (negative) advances Home → Library; swipe right returns.
                                    if (total <= -threshold && onHome) {
                                        if (!navController.popBackStack(LibraryRoute, inclusive = false)) {
                                            navController.navigateTopLevel(LibraryRoute)
                                        }
                                    } else if (total >= threshold && onLibrary) {
                                        goHome()
                                    }
                                },
                            ) { _, dragAmount -> total += dragAmount }
                        }
                    } else Modifier,
                ),
            enterTransition = { topLevelEnter(vertical = hasRail) ?: fadeIn(tween(DETAIL_DURATION)) },
            exitTransition = { topLevelExit(vertical = hasRail) ?: fadeOut(tween(DETAIL_DURATION)) },
            popEnterTransition = { topLevelEnter(vertical = hasRail) ?: fadeIn(tween(DETAIL_DURATION)) },
            popExitTransition = { topLevelExit(vertical = hasRail) ?: fadeOut(tween(DETAIL_DURATION)) },
        ) {
            composable<HomeRoute> {
                HomeView(
                    onNavigate = { navController.navigate(it) { launchSingleTop = true } },
                    topContentPadding = sharedBarHeight,
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<HomeBlockRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<HomeBlockRoute>()
                HomeBlockView(
                    blockKind = route.blockKind,
                    onBack = { navController.popBackStack() },
                    onNavigate = { navController.navigate(it) { launchSingleTop = true } },
                    contentPadding = PaddingValues(bottom = bottomInset),
                )
            }
            detailComposable<HomeLayoutRoute> {
                HomeLayoutView(
                    onBack = { navController.popBackStack() },
                    contentPadding = PaddingValues(bottom = bottomInset),
                )
            }
            composable<LibraryRoute> {
                LibraryView(
                    selectedTab = selectedTab,
                    // Narrow reports pager swipes back up; wide is rail-driven so this is unused there.
                    onTabChanged = { selectedTab = it },
                    // Swipeable pager on slim; rail-driven vertical AnimatedContent on medium/wide.
                    usePager = !hasRail,
                    query = query,
                    favoritesOnly = favoritesOnly,
                    topContentPadding = sharedBarHeight,
                    bottomContentPadding = bottomInset,
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailRoute(albumId)) { launchSingleTop = true } },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailRoute(artistId)) { launchSingleTop = true } },
                    onAlbumArtistClick = { artistId -> navController.navigate(ArtistDetailRoute(artistId)) { launchSingleTop = true } },
                    onGenreClick = { genreId -> navController.navigate(GenreDetailRoute(genreId)) { launchSingleTop = true } },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailRoute(playlistId)) { launchSingleTop = true } },
                    // Slim: swiping right past the leftmost tab (which the pager can't page) pops Home,
                    // mirroring the Home → Library swipe handled by the top-level detector above.
                    onSwipeToHome = goHome,
                )
            }
            composable<SearchRoute> {
                SearchView(
                    onMenuClick = onMenuClick,
                    onNavigate = { navController.navigate(it) { launchSingleTop = true } },
                    bottomContentPadding = bottomInset,
                )
            }
            composable<SettingsRoute> {
                SettingsView(
                    onMenuClick = onMenuClick,
                    bottomContentPadding = bottomInset,
                    onNavigate = { navController.navigate(it) { launchSingleTop = true } },
                )
            }
            composable<DownloadedMusicRoute> {
                DownloadedMusicView(
                    onAlbumClick = { albumId -> navController.navigate(DownloadedAlbumRoute(albumId)) { launchSingleTop = true } },
                    onTracksClick = { navController.navigate(DownloadedTracksRoute) { launchSingleTop = true } },
                    onSettingsClick = { navController.navigate(DownloadsSettingsRoute) { launchSingleTop = true } },
                    onMenuClick = onMenuClick,
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<DownloadedAlbumRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DownloadedAlbumRoute>()
                DownloadedAlbumView(
                    albumId = route.albumId,
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<DownloadedTracksRoute> {
                DownloadedTracksView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<AppearanceSettingsRoute> {
                AppearanceSettingsView(
                    onBack = { navController.popBackStack() },
                    onOpenHomeLayout = { navController.navigate(HomeLayoutRoute) { launchSingleTop = true } },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<PlaybackSettingsRoute> {
                PlaybackSettingsView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<ScrobblingSettingsRoute> {
                ScrobblingSettingsView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<SystemSettingsRoute> {
                SystemSettingsView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<LocalLibrarySettingsRoute> {
                LocalLibrarySettingsView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<DownloadsSettingsRoute> {
                DownloadsSettingsView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<AboutRoute> {
                AboutView(
                    onBack = { navController.popBackStack() },
                    bottomContentPadding = bottomInset,
                )
            }
            detailComposable<AlbumDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AlbumDetailRoute>()
                AlbumDetailView(albumId = route.albumId, onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }, contentPadding = PaddingValues(bottom = bottomInset) + WindowInsets.navigationBars.asPaddingValues())
            }
            detailComposable<ArtistDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ArtistDetailRoute>()
                ArtistDetailView(artistId = route.artistId, onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }, contentPadding = PaddingValues(bottom = bottomInset) + WindowInsets.navigationBars.asPaddingValues())
            }
            detailComposable<GenreDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GenreDetailRoute>()
                GenreDetailView(genreId = route.genreId, onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }, contentPadding = PaddingValues(bottom = bottomInset) + WindowInsets.navigationBars.asPaddingValues())
            }
            detailComposable<PlaylistDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<PlaylistDetailRoute>()
                PlaylistDetailView(playlistId = route.playlistId, onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }, contentPadding = PaddingValues(bottom = bottomInset) + WindowInsets.navigationBars.asPaddingValues())
            }
            detailComposable<ArtistTracksRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ArtistTracksRoute>()
                PaginatedTrackList(
                    loadPage = { offset, limit, sortBy -> appContainer.catalog.getTracksForArtist(route.artistId, limit, offset, sortBy) },
                    defaultSort = TrackSortOrder.PlayCount,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize().glassSource(zIndex = 1f).statusBarsPadding(),
                    contentPadding = PaddingValues(bottom = bottomInset) + WindowInsets.navigationBars.asPaddingValues(),
                )
            }
            detailComposable<GenreTracksRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GenreTracksRoute>()
                PaginatedTrackList(
                    loadPage = { offset, limit, sortBy -> appContainer.catalog.getTracksForGenre(route.genreId, limit, offset, sortBy) },
                    defaultSort = TrackSortOrder.PlayCount,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize().glassSource(zIndex = 1f).statusBarsPadding(),
                    contentPadding = PaddingValues(bottom = bottomInset) + WindowInsets.navigationBars.asPaddingValues(),
                )
            }
        }

        // The single Home/Library top bar, hoisted out of the NavHost and overlaid above the content so
        // it stays stationary while only the body slides between Home and Library. It morphs by route:
        // the favourites button and (narrow) segmented tab row animate in/out via AnimatedVisibility
        // rather than being replaced. Shown only on these two destinations; every other screen keeps its
        // own top bar inside the NavHost.
        if (onHome || onLibrary) {
            HomeLibraryTopBar(
                onLibrary = onLibrary,
                onMenuClick = onMenuClick,
                query = query,
                onQueryChange = { query = it },
                searchPlaceholder = if (onLibrary) "Search ${selectedTab.label.lowercase()}" else "Search",
                // Home's field is a read-only shortcut to the search screen; Library's is an inline filter.
                onSearchTap = if (onHome) ({ navController.navigateTopLevel(SearchRoute) }) else null,
                // TV: D-pad down from the search field drops into the active library grid.
                onSearchFocusDown = if (isTV && onLibrary) ({
                    val r = activeContentFocus.registry
                    if (r != null) { r.requestRestore(); true } else false
                }) else null,
                favoritesOnly = favoritesOnly,
                onToggleFavorites = { favoritesOnly = !favoritesOnly },
                canFavoriteFilter = (appContainer.activeMusicSource as? FavoriteCapable)
                    ?.supportsFavorites(selectedTab.favoritableKind) == true,
                canCast = appContainer.activeMusicSource is RemotePlaybackProvider,
                isCasting = appContainer.playback.viewedTarget != null,
                onCastClick = { showTargets = true },
                showTabs = onLibrary && !hasRail,
                selectedTab = selectedTab,
                onTabSelected = onTabClick,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { sharedBarHeight = with(density) { it.height.toDp() } },
            )
        }
        }
    }
    }

        // Full-screen player lives outside the NavHost so it persists across navigation and covers
        // the rail/bottom bar. Driven by playerExpansion (0=collapsed, 1=open) so the mini-bar
        // swipe-up gesture animates it in sync rather than snapping open after the gesture.
        if (playerExpansion.value > 0f) {
            // Scrim darkens the content behind the expanding player.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = (playerExpansion.value * 0.5f).coerceIn(0f, 0.5f)))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        scope.launch {
                            playerExpansion.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
                        }
                    }
            )
            // Registered here (inside the overlay) so it consumes system back before the NavHost's
            // own back handling — back collapses the player rather than popping the screen behind it.
            PlatformBackHandler(enabled = true) { scope.launch { playerExpansion.animateTo(0f) } }
            FullPlayer(
                playerStatus, player,
                playerExpansion = playerExpansion,
                onCollapse = { scope.launch { playerExpansion.animateTo(0f) } },
                peekHeight = playerPeek,
                onNavigate = { route ->
                    scope.launch { playerExpansion.animateTo(0f, tween(450, easing = FastOutSlowInEasing)) }
                    navController.navigate(route) { launchSingleTop = true }
                },
            )
        }

        // Transient in-app volume bar (right edge). Shown whenever the playback controller signals a
        // volume change that the OS won't surface itself — a remote command (incl. one changing this
        // device's volume over the network) or an active-player level change (see the LaunchedEffect
        // above). Local hardware keys driving the system stream stay on the OS panel and never emit.
        VolumeIndicator(
            volume = activeVolume,
            volumeChanged = appContainer.playback.volumeChanged,
        )

        // Surface a fatal playback fault (missing/rejected/undecodable track, or a reconnect that gave
        // up) just above the mini-player. Transient network stalls don't come through here — they show
        // as the play-button spinner and self-recover; only non-recoverable errors set status.error.
        PlaybackErrorBanner(
            error = playerStatus.error,
            peekHeight = playerPeek,
            onRetry = { player.play() },
        )

        // Cast / "Play on" target picker, opened from the shared Home/Library top bar's cast button.
        // Hoisted here (with the bar's state) so it no longer lives inside either view.
        if (showTargets) {
            PlaybackTargetSheet(onDismiss = { showTargets = false })
        }
    }
}
