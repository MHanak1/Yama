package net.mhanak.yama.ui.components.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.theme.glassEffect

/** Content height of the bar (excludes the system navigation-bar inset it adds below itself). */
val BottomBarHeight = 80.dp

/** Top-level destinations reachable from the slim-layout bottom bar. */
enum class BottomBarDestination(val label: String) {
    Home("Home"),
    Library("Library"),
    /** Mock slot, to be populated later. */
    More("More"),
}

/**
 * Bottom navigation bar shown only on the slim layout. Switches between the home and library
 * top-level destinations (plus a mock third slot). On medium/wide the [AppNavRail] takes over.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppBottomBar(
    selected: BottomBarDestination?,
    onSelect: (BottomBarDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Expressive short navigation bar: same container height and bottom-inset consumption as the
    // classic NavigationBar (so BottomBarHeight stays valid). Note the active indicator is a
    // per-item pill that fades/expands under the selected destination — it does NOT slide between
    // destinations. A shared sliding pill would need a custom indicator layer.
    ShortNavigationBar(
        modifier = modifier.glassEffect(MaterialTheme.colorScheme.surfaceContainer),
        containerColor = Color.Transparent,
    ) {
        BottomBarDestination.entries.forEach { dest ->
            ShortNavigationBarItem(
                selected = selected == dest,
                onClick = { onSelect(dest) },
                icon = {
                    val icon = when (dest) {
                        BottomBarDestination.Home -> Icons.Default.Home
                        BottomBarDestination.Library -> Icons.AutoMirrored.Filled.LibraryBooks
                        BottomBarDestination.More -> Icons.Default.MoreHoriz
                    }
                    Icon(icon, contentDescription = dest.label)
                },
                label = { Text(dest.label) },
            )
        }
    }
}
