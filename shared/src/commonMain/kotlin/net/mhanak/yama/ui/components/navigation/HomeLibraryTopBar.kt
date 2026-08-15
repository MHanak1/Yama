package net.mhanak.yama.ui.components.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.components.input.SearchBar
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.theme.glassEffect
import net.mhanak.yama.ui.views.LibraryTab

/**
 * The single top bar shared by the Home and Library destinations. It is rendered *once*, hoisted out
 * of the [NavHost] into [net.mhanak.yama.ui.screens.MainScreen] and overlaid above the sliding content,
 * so switching Home ⇄ Library slides only the body underneath while this bar stays put — the same way
 * the wide library's rail-driven tab switch leaves its bar stationary.
 *
 * The bar morphs by route rather than being replaced:
 * - The favourites filter button ([canFavoriteFilter]) expands/collapses via a [RowScope]
 *   `AnimatedVisibility`, so it slides away when leaving Library rather than popping.
 * - The narrow segmented tab row ([showTabs]) expands/collapses below the stationary search row.
 * - The search field is a read-only shortcut to the global search screen on Home ([onSearchTap] set),
 *   and an inline live filter on Library ([onSearchTap] null → [query]/[onQueryChange] drive it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryTopBar(
    onLibrary: Boolean,
    // Non-null only on the slim layout (opens the modal nav rail).
    onMenuClick: (() -> Unit)?,
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    // Home only: tapping the (read-only) search field opens the global search screen. Null on Library,
    // where the field is an inline filter instead.
    onSearchTap: (() -> Unit)?,
    // TV only: D-pad down from the search field redirects into the content grid. Null off TV / on Home.
    onSearchFocusDown: (() -> Boolean)?,
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
                    if (onSearchTap != null) {
                        // Home: a read-only affordance; a transparent overlay swallows taps before the
                        // text field and opens the dedicated search screen.
                        Box(Modifier.fillMaxWidth()) {
                            SearchBar(
                                query = "",
                                onQueryChange = {},
                                placeholder = searchPlaceholder,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Box(Modifier.matchParentSize().clickable { onSearchTap() })
                        }
                    } else {
                        // Library: a live inline filter for the active tab.
                        SearchBar(
                            query = query,
                            onQueryChange = onQueryChange,
                            placeholder = searchPlaceholder,
                            modifier = Modifier.fillMaxWidth(),
                            onFocusDown = onSearchFocusDown,
                        )
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
