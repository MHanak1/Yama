package net.mhanak.yama.ui.components.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.components.input.SearchBar
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.theme.glassEffect
import net.mhanak.yama.ui.views.LibraryTab

/**
 * The single top bar shared by the Home, Library and Search destinations. It is rendered *once*,
 * hoisted out of the [NavHost] into [net.mhanak.yama.ui.screens.MainScreen] and overlaid above the
 * sliding content, so switching between those destinations animates only the body underneath while
 * this bar — and, crucially, its one search field — stays put. That is what makes tapping the Home
 * search box feel like it *becomes* the search screen: it is literally the same field, never replaced.
 *
 * The bar morphs by route rather than being replaced:
 * - The favourites filter button ([canFavoriteFilter]) expands/collapses via a [RowScope]
 *   `AnimatedVisibility`, so it slides away when leaving Library rather than popping.
 * - The narrow segmented tab row ([showTabs]) expands/collapses below the stationary search row.
 * - The search field has three modes: a read-only shortcut to the search screen on Home ([onSearchTap]
 *   set), an inline live filter on Library ([onSearchTap] null, [searchActive] false), and the live,
 *   auto-focused global-search input on Search ([searchActive] true → [query]/[onQueryChange] drive it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryTopBar(
    onLibrary: Boolean,
    // True on the Search destination: the field becomes the editable, auto-focused global-search input.
    searchActive: Boolean,
    // Non-null only on the slim layout (opens the modal nav rail).
    onMenuClick: (() -> Unit)?,
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    // Home only: tapping the (read-only) search field opens the global search screen. Null on Library
    // and Search, where the field is a live input instead.
    onSearchTap: (() -> Unit)?,
    // TV only: D-pad down from the search field redirects into the content grid. Null off TV / on Home.
    onSearchFocusDown: (() -> Boolean)?,
    // When [searchActive] flips true, focus is requested here so the keyboard opens on entry. Null when
    // the host doesn't drive Search focus (e.g. Home/Library-only usages).
    searchFocusRequester: FocusRequester? = null,
    favoritesOnly: Boolean,
    onToggleFavorites: () -> Unit,
    canFavoriteFilter: Boolean,
    canCast: Boolean,
    isCasting: Boolean,
    onCastClick: () -> Unit,
    // Narrow-Library only: the Albums/Artists/… segmented switcher below the search row.
    showTabs: Boolean,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Open the keyboard the moment we land on Search. Keyed on [searchActive] (not Unit) because the
    // bar composable persists across Home/Library → Search — it is never recomposed from scratch, so a
    // one-shot LaunchedEffect(Unit) would only ever fire for the route we happened to start on.
    if (searchFocusRequester != null) {
        LaunchedEffect(searchActive) {
            if (searchActive) runCatching { searchFocusRequester.requestFocus() }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassEffect(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.statusBarsPadding()) {
            TopAppBar(
                // The search field lives in the title slot so it fills the space between the menu
                // button (if any) and the trailing actions.
                title = {
                    when {
                        onSearchTap != null -> {
                            // Home: a read-only shortcut that opens the dedicated search screen. Rendered
                            // as a clickable (non-text-field) pill so it works with TV D-pad select and
                            // never opens a keyboard here — typing happens on the search screen itself.
                            SearchBar(
                                query = "",
                                onQueryChange = {},
                                placeholder = searchPlaceholder,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onSearchTap,
                            )
                        }
                        searchActive -> {
                            // Search: the same field, now live and auto-focused. Because it is the very
                            // same SearchBar element that was the Home shortcut, the hand-off is seamless.
                            SearchBar(
                                query = query,
                                onQueryChange = onQueryChange,
                                placeholder = searchPlaceholder,
                                modifier = Modifier.fillMaxWidth().then(
                                    if (searchFocusRequester != null) Modifier.focusRequester(searchFocusRequester)
                                    else Modifier,
                                ),
                                onFocusDown = onSearchFocusDown,
                            )
                        }
                        else -> {
                            // Library: a live inline filter for the active tab.
                            SearchBar(
                                query = query,
                                onQueryChange = onQueryChange,
                                placeholder = searchPlaceholder,
                                modifier = Modifier.fillMaxWidth(),
                                onFocusDown = onSearchFocusDown,
                            )
                        }
                    }
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
                    // Slides in when entering Library (and the source supports favouriting this tab),
                    // slides away when returning to Home — this is the "animate away the favourites
                    // button" behaviour. Defaults expand/shrink horizontally inside the actions Row.
                    AnimatedVisibility(visible = onLibrary && canFavoriteFilter) {
                        IconButton(onClick = onToggleFavorites) {
                            Icon(
                                if (favoritesOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (favoritesOnly) "Showing favourites only" else "Show favourites only",
                                tint = if (favoritesOnly) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (canCast) {
                        IconButton(onClick = onCastClick) {
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
            // Narrow Library only: expands in below the (stationary) search row when entering Library.
            AnimatedVisibility(
                visible = showTabs,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                SegmentedButtonRow(
                    options = LibraryTab.entries,
                    selectedOption = selectedTab,
                    onOptionSelected = onTabSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) { tab -> Text(tab.label) }
            }
        }
    }
}
