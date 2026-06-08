package net.mhanak.yama.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import net.mhanak.yama.isTelevisionDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun PullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    topPadding: Dp,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (isTelevisionDevice()) {
        Box(modifier) { content() }
        return
    }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topPadding),
                isRefreshing = isRefreshing,
                state = state,
            )
        },
    ) {
        content()
    }
}
