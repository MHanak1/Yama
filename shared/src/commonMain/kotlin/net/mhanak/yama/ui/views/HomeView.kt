package net.mhanak.yama.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.ui.components.home.HomeShelf
import net.mhanak.yama.ui.player.PlaybackTargetSheet
import net.mhanak.yama.ui.home.activeHomeBlocks
import net.mhanak.yama.ui.home.homeConfigKey
import net.mhanak.yama.ui.platform.PullToRefreshContainer
import net.mhanak.yama.ui.screens.AlbumDetailRoute
import net.mhanak.yama.ui.screens.GenreDetailRoute
import net.mhanak.yama.ui.screens.HomeBlockRoute
import net.mhanak.yama.ui.theme.glassEffect
import net.mhanak.yama.ui.theme.glassSource

/**
 * Home landing screen: a vertical stack of horizontal content shelves (recently added, most played,
 * random albums, …) configured per source. The set of shelves comes from [resolveHomeBlocks] (the
 * user's saved layout, or the source's default); album-discovery shelves that need a live connection
 * are dropped while the source is unreachable.
 *
 * Loaded shelf data lives in [net.mhanak.yama.ui.home.HomeContentStore] on AppContainer — not in this
 * composable — so it survives navigating to a detail screen and back (no re-fetch, no content jump).
 * The page is bounded, so it renders a plain scrolling [Column] rather than a `LazyColumn` (which would
 * dispose offscreen shelves and re-run their loaders on scroll-back). A first load shows a centered
 * spinner; pull-to-refresh re-pulls the source and reloads every shelf.
 *
 * [onMenuClick] is non-null only on the slim layout (it opens the modal nav rail). [onNavigate] routes
 * shelf taps (album/genre detail), "See more" pages, and the layout editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
    onMenuClick: (() -> Unit)?,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val store = appContainer.homeContent
    val scope = rememberCoroutineScope()
    val reachable by source.isReachable.collectAsState()

    val key = remember(source) { homeConfigKey(source) }
    val blocks = remember(source, reachable) { activeHomeBlocks(source) }

    // Cast / "Play on" target picker, mirroring the button in the library and full player. Sits in the
    // app-bar action slot the home-layout editor button used to occupy.
    val canCast = source is RemotePlaybackProvider
    var showTargets by remember { mutableStateOf(false) }

    // Fast path on the store: a no-op when the data already matches this source + block set (the
    // navigate-back case), so returning to Home is instant. Reloads when the block set changes.
    LaunchedEffect(key, blocks) { store.load(appContainer, key, blocks, force = false) }

    Scaffold(
        modifier = modifier,
        // Only vertical insets: horizontal system-bar insets (e.g. a landscape nav bar / cutout) are
        // already covered by the rail on the left, so applying them here would double-pad the content
        // and push the shelves too far right. Portrait and desktop have no horizontal inset, so they
        // are unaffected.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Vertical),
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Home") },
                        modifier = Modifier.height(48.dp),
                        navigationIcon = {
                            if (onMenuClick != null) {
                                IconButton(onClick = onMenuClick) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        },
                        // The home-layout editor now lives under Settings → Appearance → Layout;
                        // this slot holds the "Play on another device" target picker instead.
                        actions = {
                            if (canCast) {
                                val isCasting = appContainer.playback.viewedTarget != null
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
        },
    ) { innerPadding ->
        PullToRefreshContainer(
            // Show the pull indicator only for reloads over existing content; a first load (empty data)
            // gets the centered spinner below instead, so the two never stack.
            isRefreshing = store.isLoading && store.data.isNotEmpty(),
            onRefresh = { scope.launch { store.refresh(appContainer, key, blocks) } },
            topPadding = innerPadding.calculateTopPadding(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (store.data.isEmpty()) {
                Box(
                    modifier = Modifier
                        .glassSource(zIndex = 1f)
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    if (store.isLoading) CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .glassSource(zIndex = 1f)
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = bottomContentPadding + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    blocks.forEach { kind ->
                        val data = store.data[kind]
                        if (data != null && !data.isEmpty) {
                            HomeShelf(
                                title = kind.title,
                                data = data,
                                onSeeMore = { onNavigate(HomeBlockRoute(kind.name)) },
                                onAlbumClick = { onNavigate(AlbumDetailRoute(it)) },
                                onGenreClick = { onNavigate(GenreDetailRoute(it)) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTargets) {
        PlaybackTargetSheet(onDismiss = { showTargets = false })
    }
}
