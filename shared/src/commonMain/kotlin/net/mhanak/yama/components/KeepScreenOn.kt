package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * Keeps the device screen awake while [enabled] is true, releasing it when [enabled] goes false or
 * the composable leaves composition. Used to hold the screen on during full-screen playback so a
 * now-playing display doesn't dim or lock.
 *
 * Desktop currently has no portable way to inhibit the screensaver, so its actual is a no-op.
 */
@Composable
expect fun KeepScreenOn(enabled: Boolean)
