package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * While [enabled], hides the system bars — the status bar (clock, notifications, battery) and the
 * navigation/gesture bar — for a fullscreen "zen" player, restoring them when [enabled] goes false
 * or this leaves composition. The bars still reveal transiently on a swipe in from the edge.
 *
 * Desktop has no system bars to hide, so its actual is a no-op.
 */
@Composable
expect fun ImmersiveMode(enabled: Boolean)
