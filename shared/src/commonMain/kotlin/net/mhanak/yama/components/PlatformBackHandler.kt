package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * Handles the platform "back" affordance (Android system back / gesture). When [enabled], [onBack]
 * is invoked instead of the default back behaviour. Composed innermost-wins, so placing it inside an
 * overlay lets the overlay consume back before the navigation back stack.
 *
 * Desktop has no system back, so its actual is a no-op.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
