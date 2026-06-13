package net.mhanak.yama.components

import androidx.compose.runtime.Composable

// Desktop processes aren't frozen on screen-off the way a backgrounded Android app is, and we don't
// want window refocus to force a reconnect, so there's nothing to do here.
@Composable
actual fun PlatformDeviceWakeEffect(onWake: () -> Unit) {
}
