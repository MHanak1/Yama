package net.mhanak.yama.util

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

// The desktop JVM has no OS-provided dynamic palette, so the app always generates its scheme from the
// user's seed colour.
actual fun supportsDynamicColor(): Boolean = false

@Composable
actual fun systemDynamicColorScheme(darkTheme: Boolean): ColorScheme? = null
