package net.mhanak.yama.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.ColorSpec2025
import com.materialkolor.rememberDynamicColorScheme
import net.mhanak.yama.LocalAppContainer

/**
 * Root theme. The base colour scheme is chosen by [AppContainer.colorSource]:
 *  - [ColorSourceKind.SystemDynamic] uses the OS dynamic palette (Android 12+ "Material You") when the
 *    platform supports it ([systemDynamicColorScheme] returns non-null);
 *  - external sources ([isExternal] — shell / wallpaper) resolve reactively to a seed via
 *    [rememberExternalSeed], falling back to the user's [AppContainer.seedColor] when they can't
 *    currently produce a colour;
 *  - [ColorSourceKind.Manual] uses that seed colour directly — and on desktop mirrors it to a theme
 *    file ([SyncCustomSeedFile]) that ricing tools can drive.
 *
 * Every non-SystemDynamic path funnels a single seed through [rememberDynamicColorScheme] (materialkolor
 * — the same algorithm Material You uses), so the desktop gets true Material-You-style expansion.
 * Album-art tinting ([net.mhanak.yama.ui.theme.DynamicColorTheme]) layers on top of whatever base this
 * produces.
 */
@Composable
fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val appContainer = LocalAppContainer.current
    val kind = appContainer.colorSource
    // Non-null only on Android 12+ — the finished OS tonal palette rather than a re-expanded seed.
    val systemScheme = systemDynamicColorScheme(darkTheme)

    val colorScheme = if (kind == ColorSourceKind.SystemDynamic && systemScheme != null) {
        systemScheme
    } else {
        val seed = if (kind.isExternal) {
            rememberExternalSeed(kind).seed ?: appContainer.seedColor
        } else {
            appContainer.seedColor
        }
        // Custom source: on desktop, transparently mirror the seed to a theme file and follow external
        // edits to it (matugen &c.); no-op on Android. Runs app-wide so tool-driven recolours take effect
        // even when settings is closed.
        if (kind == ColorSourceKind.Manual) {
            SyncCustomSeedFile(seed) { appContainer.seedColor = it }
        }
        rememberDynamicColorScheme(
            seedColor = seed,
            isDark = darkTheme,
            style = PaletteStyle.Fidelity,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
