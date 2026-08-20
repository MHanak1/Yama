package net.mhanak.yama.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Android's only "system" source is Material You (a whole OS scheme, handled by systemDynamicColorScheme),
// so the choice here is just that vs a hand-picked seed. The shell/wallpaper/file sources are desktop-only.
actual fun availableColorSources(): List<ColorSourceKind> =
    if (supportsDynamicColor()) listOf(ColorSourceKind.SystemDynamic, ColorSourceKind.Manual)
    else listOf(ColorSourceKind.Manual)

// Android has no *external* (shell/wallpaper) seed sources, so nothing resolves reactively here.
@Composable
actual fun rememberExternalSeed(kind: ColorSourceKind): ExternalSeed =
    ExternalSeed(null, SeedStatus.Unavailable)

// No theme file on Android — the Manual seed lives only in preferences.
@Composable
actual fun SyncCustomSeedFile(seed: Color, onExternalChange: (Color) -> Unit) = Unit

actual fun colorSchemeFilePath(): String? = null
