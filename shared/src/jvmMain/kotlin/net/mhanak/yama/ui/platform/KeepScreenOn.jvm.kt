package net.mhanak.yama.ui.platform

import androidx.compose.runtime.Composable

// Desktop has no portable screen-wake / screensaver-inhibit API; nothing to do for now.
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
}
