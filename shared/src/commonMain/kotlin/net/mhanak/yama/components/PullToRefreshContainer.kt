package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether the surrounding [PullToRefreshContainer] draws its own refresh spinner (true on touch
 * platforms, false on desktop/TV where there's no pull gesture). Content uses it to avoid stacking a
 * second loading indicator on top of the pull-to-refresh one during a refresh. Defaults to false so a
 * view outside any container still shows its own spinner.
 */
val LocalHasPullToRefreshIndicator = staticCompositionLocalOf { false }

@Composable
expect fun PullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    // Top inset so the indicator clears the glass top bar.
    topPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
