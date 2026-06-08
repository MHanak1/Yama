package net.mhanak.yama.screens

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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import net.mhanak.yama.components.AdaptiveNavigationLayout
import net.mhanak.yama.components.AppBottomBar
import net.mhanak.yama.components.AppNavRail
import net.mhanak.yama.components.BottomBarDestination
import net.mhanak.yama.components.PlatformBackHandler
import net.mhanak.yama.components.SourceSwitcher
import net.mhanak.yama.components.player.FullPlayer
import net.mhanak.yama.components.player.NowPlayingBar
import net.mhanak.yama.isTelevisionDevice
import net.mhanak.yama.views.AlbumDetailView
import net.mhanak.yama.views.ArtistDetailView
import net.mhanak.yama.views.GenreDetailView
import net.mhanak.yama.views.HomeView
import net.mhanak.yama.views.LibraryTab
import net.mhanak.yama.views.LibraryView
import net.mhanak.yama.views.PlaylistDetailView
import net.mhanak.yama.views.SettingsView

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
    hasRoute<LibraryRoute>() -> 1
    hasRoute<SettingsRoute>() -> 2
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
    val isTV = isTelevisionDevice()
    val contentFocusRequester = remember { FocusRequester() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = navBackStackEntry?.destination

    val onHome = destination?.hasRoute<HomeRoute>() == true
    val onSettings = destination?.hasRoute<SettingsRoute>() == true

    // The active player is Compose-observable so a future switch to a remote player rebinds the UI.
    val player = appContainer.playback.active
    val playerStatus by player.status.collectAsState()
    val playerExpansion = remember { Animatable(0f) }

    // Tapping the Android media notification asks (via the controller) to open the full player.
    LaunchedEffect(appContainer.playback.openPlayerRequest) {
        if (appContainer.playback.openPlayerRequest) {
            playerExpansion.animateTo(1f)
            appContainer.playback.openPlayerRequest = false
        }
    }

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
        runCatching { contentFocusRequester.requestFocus() }
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

    Box(Modifier.fillMaxSize()) {
    AdaptiveNavigationLayout(
        playerActive = playerStatus.current != null,
        miniPlayer = { wide -> NowPlayingBar(playerStatus, player, playerExpansion = playerExpansion, wide = wide) },
        rail = { forceExpanded ->
            AppNavRail(
                forceExpanded = forceExpanded,
                homeSelected = onHome,
                // Highlight the active library tab while browsing the library or a detail screen.
                selectedTab = if (onHome || onSettings) null else selectedTab,
                settingsSelected = onSettings,
                onHomeClick = { navController.navigateTopLevel(HomeRoute) },
                onTabClick = onTabClick,
                onSettingsClick = { navController.navigateTopLevel(SettingsRoute) },
                nowPlayingVisible = playerStatus.current != null,
                onNowPlayingClick = { scope.launch { playerExpansion.animateTo(1f) } },
            )
        },
        bottomBar = {
            AppBottomBar(
                selected = when {
                    onHome -> BottomBarDestination.Home
                    onSettings -> null
                    else -> BottomBarDestination.Library
                },
                onSelect = { dest ->
                    when (dest) {
                        BottomBarDestination.Home -> navController.navigateTopLevel(HomeRoute)
                        BottomBarDestination.Library -> {
                            val onLibrary = navController.currentBackStackEntry?.destination?.hasRoute<LibraryRoute>() == true
                            if (onLibrary) {
                                scope.launch { runCatching { appContainer.activeMusicSource.refresh() } }
                            } else if (!navController.popBackStack(LibraryRoute, inclusive = false)) {
                                navController.navigateTopLevel(LibraryRoute)
                            }
                        }
                        BottomBarDestination.More -> Unit // mock slot
                    }
                },
            )
        },
        // Slim modal rail: source switcher at the top, settings pinned to the bottom.
        modalContent = { onClose ->
            SourceSwitcher(onRequestClose = onClose)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f))
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
        NavHost(
            navController = navController,
            startDestination = LibraryRoute,
            modifier = Modifier.fillMaxSize()
                .then(if (isTV) Modifier.focusRequester(contentFocusRequester).focusGroup() else Modifier),
            enterTransition = { topLevelEnter(vertical = hasRail) ?: fadeIn(tween(DETAIL_DURATION)) },
            exitTransition = { topLevelExit(vertical = hasRail) ?: fadeOut(tween(DETAIL_DURATION)) },
            popEnterTransition = { topLevelEnter(vertical = hasRail) ?: fadeIn(tween(DETAIL_DURATION)) },
            popExitTransition = { topLevelExit(vertical = hasRail) ?: fadeOut(tween(DETAIL_DURATION)) },
        ) {
            composable<HomeRoute> {
                HomeView(onMenuClick = onMenuClick, bottomContentPadding = bottomInset)
            }
            composable<LibraryRoute> {
                LibraryView(
                    // Rail-driven tab on medium/wide; self-managed segmented switcher on slim.
                    externalTab = if (hasRail) selectedTab else null,
                    onMenuClick = onMenuClick,
                    bottomContentPadding = bottomInset,
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailRoute(albumId)) { launchSingleTop = true } },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailRoute(artistId)) { launchSingleTop = true } },
                    onGenreClick = { genreId -> navController.navigate(GenreDetailRoute(genreId)) { launchSingleTop = true } },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailRoute(playlistId)) { launchSingleTop = true } },
                )
            }
            composable<SettingsRoute> {
                SettingsView(onMenuClick = onMenuClick, bottomContentPadding = bottomInset)
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
            )
        }
    }
}
