package net.mhanak.yama.ui.theme

/**
 * Whether this platform can render real frosted-glass blur (the [glassEffect] haze surfaces).
 *
 * Android needs API 31+ (Android 12): Haze's blur is built on `RenderEffect.createBlurEffect`, and on
 * older releases it silently degrades to a flat, opaque scrim that looks broken. This is the same
 * threshold [supportsDynamicColor] uses. Desktop always supports it (Skia render effects).
 *
 * Gated in two places: the "Blur effects" toggle is hidden where this is false, and the global
 * [LocalHazeState] provider forces blur off regardless of the stored preference.
 */
expect fun supportsBlurEffects(): Boolean
