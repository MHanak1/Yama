package net.mhanak.yama.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun HorizontalScrollbarIfNeeded(listState: LazyListState, modifier: Modifier) {
    HorizontalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = modifier
    )
}