package net.mhanak.yama.ui.platform

import androidx.compose.runtime.Composable

// Desktop has no system back affordance; nothing to handle.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
