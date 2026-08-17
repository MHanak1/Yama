package net.mhanak.yama.ui.theme

import android.os.Build

// API 31 (S) matches Haze's own `isBlurEnabledByDefault()`: below it, RenderEffect blur is
// unavailable and Haze falls back to a flat scrim, so we treat blur as unsupported entirely.
actual fun supportsBlurEffects(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
