package net.mhanak.yama.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbarIfNeeded(
    listState: LazyListState,
    modifier: Modifier
) { /* no-op */ }