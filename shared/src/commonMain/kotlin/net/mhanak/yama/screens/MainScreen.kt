package net.mhanak.yama.screens

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
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
import net.mhanak.yama.components.AppearanceSettings
import net.mhanak.yama.components.SidebarMode
import net.mhanak.yama.components.SourceSwitcher
import net.mhanak.yama.isTelevisionDevice
import net.mhanak.yama.views.AlbumDetailView
import net.mhanak.yama.views.ArtistDetailView
import net.mhanak.yama.views.GenreDetailView
import net.mhanak.yama.views.LibraryTab
import net.mhanak.yama.views.LibraryView
import net.mhanak.yama.views.PlaylistDetailView

private val DETAIL_DURATION = 320

@Composable
private fun SidebarNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.padding(horizontal = 8.dp, vertical = 2.dp) else Modifier.padding(vertical = 2.dp))
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(onClick = onClick)
            .then(if (expanded) Modifier.padding(horizontal = 16.dp) else Modifier),
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            icon()
            if (expanded) {
                Spacer(Modifier.width(12.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private inline fun <reified T : Any> NavGraphBuilder.detailComposable(
    crossinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(
        enterTransition = { slideInHorizontally(tween(DETAIL_DURATION)) { it } + fadeIn(tween(DETAIL_DURATION)) },
        popExitTransition = { slideOutHorizontally(tween(DETAIL_DURATION)) { it } + fadeOut(tween(DETAIL_DURATION)) },
    ) { backStackEntry -> content(backStackEntry) }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    var selectedTab by remember { mutableStateOf(LibraryTab.Albums) }
    val isTV = isTelevisionDevice()
    val contentFocusRequester = remember { FocusRequester() }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
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
        val isOnLibrary = navController.currentBackStackEntry
            ?.destination?.hasRoute<LibraryRoute>() == true
        selectedTab = tab
        if (!isOnLibrary) {
            navController.navigate(LibraryRoute) {
                popUpTo<LibraryRoute> { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    AdaptiveNavigationLayout(
        narrowGesturesEnabled = false,
        wideDrawerContent = { mode ->
            val expanded = mode == SidebarMode.Expanded
            SourceSwitcher(collapsed = !expanded)
            LibraryTab.entries.forEach { tab ->
                SidebarNavItem(
                    selected = selectedTab == tab,
                    onClick = { onTabClick(tab) },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = tab.label,
                    expanded = expanded,
                )
            }
            if (mode == SidebarMode.Expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                AppearanceSettings()
            }
        },
        narrowDrawerContent = { onClose ->
            SourceSwitcher(onRequestClose = onClose)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            AppearanceSettings()
        },
    ) { isWide, onMenuClick ->
        NavHost(
            navController = navController,
            startDestination = LibraryRoute,
            modifier = Modifier.fillMaxSize()
                .then(if (isTV) Modifier.focusRequester(contentFocusRequester).focusGroup() else Modifier),
            // Library slides behind detail screens (parallax); no enter animation on cold start.
            enterTransition = { fadeIn(tween(DETAIL_DURATION)) },
            exitTransition = {
                fadeOut(tween(DETAIL_DURATION))
            },
            popEnterTransition = {
                fadeIn(tween(DETAIL_DURATION))
            },
            popExitTransition = { fadeOut(tween(DETAIL_DURATION)) },
        ) {
            composable<LibraryRoute> {
                LibraryView(
                    externalTab = if (isWide) selectedTab else null,
                    onMenuClick = onMenuClick,
                    onAlbumClick = { albumId -> navController.navigate(AlbumDetailRoute(albumId)) { launchSingleTop = true } },
                    onArtistClick = { artistId -> navController.navigate(ArtistDetailRoute(artistId)) { launchSingleTop = true } },
                    onGenreClick = { genreId -> navController.navigate(GenreDetailRoute(genreId)) { launchSingleTop = true } },
                    onPlaylistClick = { playlistId -> navController.navigate(PlaylistDetailRoute(playlistId)) { launchSingleTop = true } },
                )
            }
            detailComposable<AlbumDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AlbumDetailRoute>()
                AlbumDetailView(albumId = route.albumId, onBack = { navController.popBackStack() })
            }
            detailComposable<ArtistDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ArtistDetailRoute>()
                ArtistDetailView(artistId = route.artistId, onBack = { navController.popBackStack() })
            }
            detailComposable<GenreDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GenreDetailRoute>()
                GenreDetailView(genreId = route.genreId, onBack = { navController.popBackStack() })
            }
            detailComposable<PlaylistDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<PlaylistDetailRoute>()
                PlaylistDetailView(playlistId = route.playlistId, onBack = { navController.popBackStack() })
            }
        }
    }
}
