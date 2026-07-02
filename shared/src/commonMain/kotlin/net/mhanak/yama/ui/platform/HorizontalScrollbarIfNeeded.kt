package net.mhanak.yama.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HorizontalScrollbarIfNeeded(
    listState: LazyListState,
    modifier: Modifier
)