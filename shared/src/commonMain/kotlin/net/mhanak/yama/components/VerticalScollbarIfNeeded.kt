package net.mhanak.yama.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VerticalScrollbarIfNeeded(
    listState: LazyListState,
    modifier: Modifier
)