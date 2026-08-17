package net.mhanak.yama.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.home.HomeShelf
import net.mhanak.yama.ui.components.interaction.ContentFocusRegistry
import net.mhanak.yama.ui.components.interaction.LocalContentFocusRegistry
import net.mhanak.yama.ui.components.interaction.RegisterActiveContentFocus
import net.mhanak.yama.ui.components.library.adaptiveCardWidth
import net.mhanak.yama.ui.home.activeHomeBlocks
import net.mhanak.yama.ui.home.homeConfigKey
import net.mhanak.yama.ui.platform.PullToRefreshContainer
import net.mhanak.yama.ui.screens.AlbumDetailRoute
import net.mhanak.yama.ui.screens.GenreDetailRoute
import net.mhanak.yama.ui.screens.HomeBlockRoute
import net.mhanak.yama.ui.theme.glassSource

/**
 * Home landing screen: a vertical stack of horizontal content shelves (recently added, most played,
 * random albums, …) configured per source. The set of shelves comes from [resolveHomeBlocks] (the
 * user's saved layout, or the source's default); album-discovery shelves that need a live connection
 * are dropped while the source is unreachable.
 *
 * The top bar (search / cast) is *not* part of this view — it is shared with the library and hoisted
 * into [net.mhanak.yama.ui.screens.MainScreen], overlaid above this content so it stays stationary
 * while only the body slides when navigating between Home and Library. [topContentPadding] is that
 * shared bar's measured height, applied so the shelves start (and scroll under the glass bar) below it.
 *
 * Loaded shelf data lives in [net.mhanak.yama.ui.home.HomeContentStore] on AppContainer — not in this
 * composable — so it survives navigating to a detail screen and back (no re-fetch, no content jump).
 * The page is bounded, so it renders a plain scrolling [Column] rather than a `LazyColumn` (which would
 * dispose offscreen shelves and re-run their loaders on scroll-back). A first load shows a centered
 * spinner; pull-to-refresh re-pulls the source and reloads every shelf.
 *
 * [onNavigate] routes shelf taps (album/genre detail), "See more" pages, and the layout editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    // Top inset reserved for the shared (overlaid) Home/Library top bar.
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val store = appContainer.homeContent
    val scope = rememberCoroutineScope()
    val reachable by source.isReachable.collectAsState()

    val key = remember(source) { homeConfigKey(source) }
    val blocks = remember(source, reachable) { activeHomeBlocks(source) }

    // TV D-pad focus: one registry for the whole Home screen (shelf cards register per-item via
    // contentFocusItem). savedKey is rememberSaveable so the card the user left on is restored after a
    // navigate-to-detail → back round-trip; a cold entry lands on the first shelf's first card. Mirrors
    // GridView/ListView so Home is no longer the one content screen that falls back to the group. See TvFocus.kt.
    val savedFocusKey = rememberSaveable { mutableStateOf<String?>(null) }
    val focusRegistry = remember { ContentFocusRegistry(savedFocusKey) }
    RegisterActiveContentFocus(focusRegistry)

    // Fast path on the store: a no-op when the data already matches this source + block set (the
    // navigate-back case), so returning to Home is instant. Reloads when the block set changes.
    LaunchedEffect(key, blocks) { store.load(appContainer, key, blocks, force = false) }

    PullToRefreshContainer(
        // Show the pull indicator only for reloads over existing content; a first load (empty data)
        // gets the centered spinner below instead, so the two never stack.
        isRefreshing = store.isLoading && store.data.isNotEmpty(),
        onRefresh = { scope.launch { store.refresh(appContainer, key, blocks) } },
        topPadding = topContentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        if (store.data.isEmpty()) {
            Box(
                modifier = Modifier
                    .glassSource(zIndex = 1f)
                    .fillMaxSize()
                    .padding(top = topContentPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (store.isLoading) CircularProgressIndicator()
            }
        } else {
            CompositionLocalProvider(LocalContentFocusRegistry provides focusRegistry) {
            // Measure the shelf area's width once here and derive the shared card width, rather than
            // letting each HomeShelf run its own BoxWithConstraints. All shelves are full-width, so they
            // resolve to the same width anyway — and a per-shelf BoxWithConstraints re-ran its
            // subcomposition for every shelf on every frame while the rail's width animates (Home is a
            // plain measured Column, so every shelf re-measures each frame), the source of the jank.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cardWidth = adaptiveCardWidth(maxWidth)
                Column(
                    modifier = Modifier
                        .glassSource(zIndex = 1f)
                        .fillMaxSize()
                        // Group the shelves as one D-pad region (parity with GridView); left from a shelf's
                        // first card then propagates out to the content-zone left-exit → sidebar.
                        .focusGroup()
                        .verticalScroll(rememberScrollState())
                        .padding(top = topContentPadding + 8.dp, bottom = bottomContentPadding + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    blocks.forEach { kind ->
                        val data = store.data[kind]
                        if (data != null && !data.isEmpty) {
                            HomeShelf(
                                title = kind.title,
                                data = data,
                                cardWidth = cardWidth,
                                // Namespaced per shelf so ids that recur across shelves (an album in both
                                // "recently added" and "random") don't collide in the shared registry.
                                focusKeyPrefix = kind.name,
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
    }
}
