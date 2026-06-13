package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * Invokes [onWake] when the device wakes and the app returns to the foreground after the screen was
 * off — the point at which a backgrounded WebSocket may be silently half-open and worth rebuilding.
 *
 * Android-only: the actual fires on the activity's ON_START (skipping the initial start, whose
 * connection is already fresh). Desktop has no comparable freeze/wake cycle and we explicitly don't
 * want window refocus to trigger a reconnect, so its actual is a no-op.
 */
@Composable
expect fun PlatformDeviceWakeEffect(onWake: () -> Unit)
