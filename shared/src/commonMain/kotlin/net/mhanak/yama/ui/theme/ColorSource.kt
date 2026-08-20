package net.mhanak.yama.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Where the app's *base* colour scheme takes its seed from. Replaces the old "Material You on/off"
 * boolean: on Android the meaningful choice is [SystemDynamic] vs [Manual], while on desktop the
 * external origins ([ShellAccent], [Wallpaper]) join in (a follow-up) alongside [Manual].
 *
 * This only chooses the *base*. Album-art tinting ([DynamicColorTheme]) still layers on top of whatever
 * base this produces, exactly as it did under the Material You switch.
 *
 * Note there is no separate "theme file" source: on desktop the [Manual] seed is transparently mirrored
 * to a file ([SyncCustomSeedFile]) that ricing tools can read *and* overwrite, so a hand-picked colour
 * and a matugen-driven one are the same source — [Manual] is just backed by a file there.
 */
enum class ColorSourceKind(val label: String, val description: String) {
    /** The OS-provided tonal palette (Android 12+ "Material You"). A finished scheme, not a seed. */
    SystemDynamic("Material You", "Follow your system's dynamic colour palette"),

    /** The desktop shell's accent colour (Linux XDG portal / Windows). Added in a follow-up. */
    ShellAccent("Match system theme", "Match your desktop shell's accent colour"),

    /** A seed quantized from the desktop wallpaper, à la Material You. Added in a follow-up. */
    Wallpaper("Wallpaper", "Generate colours from your desktop wallpaper"),

    /** A hand-picked seed colour (the accent-colour picker); on desktop, file-backed. The universal fallback. */
    Manual("Custom", "Pick your own accent colour"),
}

/**
 * True for sources whose seed comes from *outside* the app (shell, wallpaper) and is resolved reactively
 * via [rememberExternalSeed]; false for [ColorSourceKind.SystemDynamic] (a whole OS scheme, read by
 * [systemDynamicColorScheme]) and [ColorSourceKind.Manual] (the stored seed, read directly).
 */
val ColorSourceKind.isExternal: Boolean
    get() = this == ColorSourceKind.ShellAccent || this == ColorSourceKind.Wallpaper

/**
 * Live state of an external seed source. [seed] is null whenever the source can't currently produce a
 * colour ([status] says why), in which case the theme falls back to the user's [ColorSourceKind.Manual]
 * seed — surfaced rather than hidden, so the user knows why their chosen source isn't taking effect.
 */
data class ExternalSeed(val seed: Color?, val status: SeedStatus)

enum class SeedStatus {
    /** Producing a colour. */
    Active,

    /** Supported here, but nothing to read right now (e.g. the shell reports no accent). */
    Unavailable,
}

/**
 * The colour sources this platform can offer, in the order they should appear in settings. Always
 * includes [ColorSourceKind.Manual] as the universal fallback. `AppContainer` clamps a stored/migrated
 * selection that isn't in this list down to [ColorSourceKind.Manual].
 */
expect fun availableColorSources(): List<ColorSourceKind>

/**
 * Reactively resolve the seed for an *external* [kind] (see [isExternal]), subscribing to the relevant
 * OS signal (the XDG portal's `SettingChanged` / a wallpaper watch) so the theme follows live changes.
 * Non-external kinds return a null seed — callers handle [ColorSourceKind.SystemDynamic]/
 * [ColorSourceKind.Manual] directly. Currently a stub returning [SeedStatus.Unavailable] on every
 * platform; filled in when [ColorSourceKind.ShellAccent]/[ColorSourceKind.Wallpaper] land.
 */
@Composable
expect fun rememberExternalSeed(kind: ColorSourceKind): ExternalSeed

/**
 * While the [ColorSourceKind.Manual] source is active on desktop, keeps [seed] mirrored to a theme file
 * that ricing tools can read *and* overwrite: writes [seed] out (debounced) when it changes, ensures the
 * file exists so tools have a template, and calls [onExternalChange] when the file is edited externally.
 * No-op on Android (no file). `@Composable` so it can own the file watch's lifetime.
 */
@Composable
expect fun SyncCustomSeedFile(seed: Color, onExternalChange: (Color) -> Unit)

/**
 * Absolute path of the desktop theme file mirrored by [SyncCustomSeedFile], for display in settings so
 * the user can point their theming tools at it; null where there is no such file (Android).
 */
expect fun colorSchemeFilePath(): String?
