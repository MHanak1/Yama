package net.mhanak.yama.components

import androidx.compose.runtime.Composable

// Desktop has no portable screen-wake / screensaver-inhibit API; nothing to do for now.
@Composable
actual fun KeepScreenOn(enabled: Boolean) {
}
