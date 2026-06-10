package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * Invokes [onInteraction] on platform-level user input that Compose's pointer/key modifiers can't
 * reliably see — most importantly Android TV D-pad presses, which stop reaching a [resetIdleOn]
 * subtree once its focusable controls are hidden (no focus target left to route keys to).
 *
 * Android wires this to `Activity.onUserInteraction()` (every touch/key/D-pad event). Desktop has
 * no equivalent firehose and relies on [resetIdleOn]'s pointer/key handling, so its actual is a no-op.
 */
@Composable
expect fun PlatformUserInteractionEffect(onInteraction: () -> Unit)
