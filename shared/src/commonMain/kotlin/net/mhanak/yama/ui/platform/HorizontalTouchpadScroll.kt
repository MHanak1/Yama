package net.mhanak.yama.ui.platform

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop-only: routes a touchpad's horizontal (two-finger sideways) scroll deltas into [listState].
 *
 * Compose Desktop maps only the *vertical* mouse-wheel axis onto a horizontal `LazyRow`; the
 * horizontal axis a touchpad emits is dropped, so trackpad users can't swipe shelves sideways. This
 * modifier rescues that axis and dispatches it to the row.
 *
 * No-op on Android/touch, where a horizontal drag already scrolls the row natively (intercepting the
 * scroll there would just double-count it).
 */
@Composable
expect fun Modifier.horizontalTouchpadScroll(listState: LazyListState): Modifier
