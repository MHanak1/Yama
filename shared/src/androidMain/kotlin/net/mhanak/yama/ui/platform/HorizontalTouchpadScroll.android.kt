package net.mhanak.yama.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Touch already drags the row horizontally; nothing to rescue.
@Composable
actual fun Modifier.horizontalTouchpadScroll(listState: LazyListState): Modifier = this
