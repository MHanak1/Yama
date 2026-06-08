package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
expect fun PullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    // Top inset so the indicator clears the glass top bar.
    topPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
