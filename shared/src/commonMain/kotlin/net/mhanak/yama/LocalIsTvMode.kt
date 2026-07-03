package net.mhanak.yama

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the current composition is running in TV/controller mode — either because the device is
 * physically a television ([isTelevisionDevice]) or because the user has forced it on via
 * [AppContainer.forceTvMode]. Provided at the [App] level; any composable below can read it
 * instead of calling [isTelevisionDevice] directly.
 *
 * Affects: nav rail vs bottom bar layout, D-pad focus infrastructure, full-player scale,
 * now-playing bar directional focus exit, search bar D-pad-down override.
 *
 * Use [staticCompositionLocalOf] so the entire subtree recomposes when the mode changes rather
 * than tracking per-read — this value changes at most once per session (user toggle) so the
 * coarser invalidation is cheaper than per-read tracking.
 */
val LocalIsTvMode = staticCompositionLocalOf<Boolean> { false }
