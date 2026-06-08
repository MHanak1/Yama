package net.mhanak.yama.components

import androidx.compose.runtime.Composable

// Desktop has no system back affordance; nothing to handle.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
