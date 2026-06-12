package net.mhanak.yama.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.ColorSpec2025
import com.materialkolor.rememberDynamicColorScheme
import net.mhanak.yama.LocalAppContainer

/**
 * Root theme. The base colour scheme comes from one of two sources:
 *  - the system dynamic palette (Android 12+ "Material You") when [AppContainer.useMaterialYou] is on
 *    and the platform supports it ([systemDynamicColorScheme] returns non-null);
 *  - otherwise a scheme generated from the user's seed colour ([AppContainer.seedColor]).
 *
 * Album-art tinting ([net.mhanak.yama.components.DynamicColorTheme]) layers on top of whatever base
 * this produces.
 */
@Composable
fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val appContainer = LocalAppContainer.current
    val systemScheme = systemDynamicColorScheme(darkTheme)
    val colorScheme = if (appContainer.useMaterialYou && systemScheme != null) {
        systemScheme
    } else {
        rememberDynamicColorScheme(
            seedColor = appContainer.seedColor,
            isDark = darkTheme,
            style = PaletteStyle.Fidelity,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
