package net.mhanak.yama.util

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Whether this platform can provide a system dynamic colour palette (Android 12+ "Material You"). */
expect fun supportsDynamicColor(): Boolean

/**
 * The system dynamic colour scheme for the current wallpaper, or null when the platform/OS doesn't
 * provide one. `@Composable` because the Android implementation reads it from the platform context.
 */
@Composable
expect fun systemDynamicColorScheme(darkTheme: Boolean): ColorScheme?
