package net.mhanak.yama.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.mhanak.yama.platform.DesktopAccent
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.Locale
import java.util.concurrent.TimeUnit

// The shell accent (XDG appearance portal, falling back to KDE's kdeglobals) is offered only where a real
// accent can be read — so it appears on KDE/GNOME but not on a bare compositor (Caelestia), which drives
// colour through the theme file (Manual) instead. Wallpaper extraction is a possible future source.
actual fun availableColorSources(): List<ColorSourceKind> =
    if (DesktopAccent.accentSupported) listOf(ColorSourceKind.ShellAccent, ColorSourceKind.Manual)
    else listOf(ColorSourceKind.Manual)

@Composable
actual fun rememberExternalSeed(kind: ColorSourceKind): ExternalSeed = when (kind) {
    ColorSourceKind.ShellAccent -> rememberShellAccent()
    else -> ExternalSeed(null, SeedStatus.Unavailable) // Wallpaper: possible future source.
}

@Composable
private fun rememberShellAccent(): ExternalSeed {
    var state by remember { mutableStateOf(ExternalSeed(null, SeedStatus.Unavailable)) }
    DisposableEffect(Unit) {
        // Read (and re-read) the accent off the caller thread — a portal round-trip can block — and push
        // the result into snapshot state. Compose state writes are safe from any thread.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        fun refresh() {
            val rgb = DesktopAccent.readAccentRgb()
            state = if (rgb != null) ExternalSeed(Color(rgb.first, rgb.second, rgb.third), SeedStatus.Active)
            else ExternalSeed(null, SeedStatus.Unavailable)
        }
        scope.launch { refresh() }
        val watch = DesktopAccent.watchAccent { scope.launch { refresh() } }
        onDispose { watch?.close(); scope.cancel() }
    }
    return state
}

/**
 * `$XDG_CONFIG_HOME/yama/scheme.json` (Linux/macOS) or `%APPDATA%\Yama\scheme.json` (Windows). A
 * constant, XDG-respecting path — a user on a non-standard layout can symlink it into place.
 */
private val schemeFile: File by lazy {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val dir = when {
        os.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "Yama") }
        else -> {
            val cfg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.config")
            File(cfg, "yama")
        }
    } ?: File(System.getProperty("user.home"), ".config/yama")
    File(dir, "scheme.json")
}

actual fun colorSchemeFilePath(): String? = schemeFile.absolutePath

private const val WriteDebounceMs = 300L

@Composable
actual fun SyncCustomSeedFile(seed: Color, onExternalChange: (Color) -> Unit) {
    // Always read the latest seed/callback inside the long-lived watch coroutine without restarting it.
    val currentSeed by rememberUpdatedState(seed)
    val onExternal by rememberUpdatedState(onExternalChange)

    // App → file: mirror the seed out, debounced (LaunchedEffect(seed) cancels the pending write on each
    // change, so a slider drag produces one write when it settles). Skip when the file already holds this
    // colour — that's either a no-op or an external edit we just applied, and must not echo back.
    LaunchedEffect(seed) {
        delay(WriteDebounceMs)
        if (readSchemeColor()?.rgb24() != seed.rgb24()) writeSchemeColor(seed)
    }

    // File → app: watch for *external* edits and push them back. Ensure the file exists first (so tools
    // have a template and the parent dir exists to watch); guard on rgb24 so our own writes don't bounce.
    DisposableEffect(Unit) {
        ensureSchemeFile(currentSeed)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val dir = schemeFile.parentFile?.toPath() ?: return@launch
            val watch = FileSystems.getDefault().newWatchService()
            try {
                dir.register(watch, ENTRY_CREATE, ENTRY_MODIFY)
                while (isActive) {
                    // Poll with a timeout so cancellation is observed promptly rather than blocking forever.
                    val key = watch.poll(1, TimeUnit.SECONDS) ?: continue
                    val touched = key.pollEvents().any {
                        (it.context() as? Path)?.fileName?.toString() == schemeFile.name
                    }
                    key.reset()
                    if (touched) {
                        val fileColor = readSchemeColor()
                        if (fileColor != null && fileColor.rgb24() != currentSeed.rgb24()) onExternal(fileColor)
                    }
                }
            } finally {
                watch.close()
            }
        }
        onDispose { scope.cancel() }
    }
}

// A hand-rolled match rather than a JSON dependency: the schema is a single `"seed": "#rrggbb"`
// (6- or 8-digit hex, leading `#` optional). Yama defines this shape; matugen (or any tool) fills it.
private val SEED_REGEX = Regex("\"seed\"\\s*:\\s*\"(#?[0-9a-fA-F]{6,8})\"")

private fun readSchemeColor(): Color? {
    if (!schemeFile.exists()) return null
    return try {
        SEED_REGEX.find(schemeFile.readText())?.groupValues?.get(1)?.let { parseHexColor(it) }
    } catch (_: Exception) {
        null
    }
}

private fun writeSchemeColor(color: Color) {
    try {
        schemeFile.parentFile?.mkdirs()
        schemeFile.writeText("{\n  \"seed\": \"${color.toHexRgb()}\"\n}\n")
    } catch (_: Exception) {
        // Best-effort mirror: a failed write just means tools don't see this colour; the app is unaffected.
    }
}

private fun ensureSchemeFile(seed: Color) {
    if (!schemeFile.exists()) writeSchemeColor(seed)
}

/** Compare/round-trip on the 24-bit RGB int: colours survive `#rrggbb` losslessly there, whereas the
 *  float [Color] channels don't, so this is the stable key for the echo-loop guards. */
private fun Color.rgb24(): Int = toArgb() and 0xFFFFFF

/** Parse `#rrggbb` / `#rrggbbaa` (or without `#`) into an opaque [Color]; alpha is dropped (a seed has
 *  no meaningful transparency). Returns null on anything malformed. */
private fun parseHexColor(raw: String): Color? {
    val hex = raw.removePrefix("#")
    // #RRGGBBAA (CSS order) — keep the RGB, drop the meaningless alpha.
    val rgb = if (hex.length == 8) hex.substring(0, 6) else hex
    if (rgb.length != 6) return null
    return try {
        Color(0xFF000000.toInt() or rgb.toInt(16))
    } catch (_: NumberFormatException) {
        null
    }
}

private fun Color.toHexRgb(): String = "#%06X".format(rgb24())
