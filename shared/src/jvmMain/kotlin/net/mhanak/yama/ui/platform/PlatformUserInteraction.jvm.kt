package net.mhanak.yama.ui.platform

import androidx.compose.runtime.Composable

// Desktop has no global user-interaction callback; resetIdleOn's pointer/key handling covers it.
@Composable
actual fun PlatformUserInteractionEffect(onInteraction: () -> Unit) {
}
