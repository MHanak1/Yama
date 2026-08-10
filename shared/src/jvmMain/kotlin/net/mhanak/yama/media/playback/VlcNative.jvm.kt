package net.mhanak.yama.media.playback

import com.sun.jna.Library
import com.sun.jna.Native
import net.mhanak.yama.util.logger
import java.io.File

/**
 * Points vlcj / JNA at a libvlc runtime that we ship *inside* the packaged desktop app, rather than
 * relying on a system-installed VLC.
 *
 * This only does something in a jpackaged distribution: Compose Desktop exposes the app's bundled
 * resources directory via the `compose.application.resources.dir` system property, and we place a
 * `vlc/` folder there (see desktopApp `appResourcesRootDir` + the release workflow that fills it with
 * `libvlc.dll`, `libvlccore.dll` and `plugins/`). In a plain `./gradlew run` that property is unset,
 * so this is a no-op and vlcj falls back to its normal system discovery — which is what we want on
 * Linux, where libvlc is a package dependency rather than a bundled file.
 *
 * The function is version-agnostic on purpose: it never mentions a VLC version. It just points at
 * whatever DLLs happen to sit in `vlc/`, so bumping the bundled VLC is purely a CI concern.
 */
private val log = logger("Playback")

@Volatile private var configured = false

/**
 * Idempotent; safe to call from multiple threads. Must run *before* the first `AudioPlayerComponent`
 * is constructed (JNA resolves and loads libvlc at that point and won't re-scan afterwards).
 */
internal fun ensureBundledVlc() {
    if (configured) return
    synchronized(::configured) {
        if (configured) return
        configured = true
        runCatching { configureBundledVlc() }
            .onFailure { log.warn("bundled libvlc setup failed — falling back to system discovery", it) }
    }
}

private fun configureBundledVlc() {
    // Only present in a packaged app image; null under `./gradlew run` and in tests.
    val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return
    val vlcDir = File(resourcesDir, "vlc")
    if (!vlcDir.isDirectory) return

    // 1. Make JNA load *our* libvlc/libvlccore. Prepending wins over any system install on PATH.
    val absVlc = vlcDir.absolutePath
    val prev = System.getProperty("jna.library.path")
    System.setProperty(
        "jna.library.path",
        if (prev.isNullOrBlank()) absVlc else absVlc + File.pathSeparator + prev,
    )

    // 2. Tell libvlc where its codec plugins live. libvlc reads this from the *process environment*
    //    (a JVM system property is not enough), so we push it into the native C runtime via JNA.
    val plugins = File(vlcDir, "plugins")
    if (plugins.isDirectory) {
        setNativeEnv("VLC_PLUGIN_PATH", plugins.absolutePath)
    }
    log.info("using bundled libvlc at $absVlc")
}

// --- native env-var plumbing (Windows-only in practice; harmless elsewhere) ---

private interface WinKernel32 : Library {
    fun SetEnvironmentVariableW(name: String, value: String): Boolean
}

private interface WinMsvcrt : Library {
    fun _putenv_s(name: String, value: String): Int
}

private interface PosixC : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
}

/**
 * Sets an environment variable *in the native process*, so a natively-loaded libvlc can `getenv` it.
 *
 * On Windows we set it both via the Win32 API (process block) and the CRT (`_putenv_s`), because
 * libvlc reads it through the C runtime's `getenv`, which keeps its own snapshot of the environment.
 * On POSIX a single `setenv` suffices — but on desktop Linux we never reach here (no bundled `vlc/`).
 */
private fun setNativeEnv(name: String, value: String) {
    val os = System.getProperty("os.name").lowercase()
    if (os.contains("win")) {
        runCatching { Native.load("kernel32", WinKernel32::class.java).SetEnvironmentVariableW(name, value) }
            .onFailure { log.warn("SetEnvironmentVariableW($name) failed", it) }
        runCatching { Native.load("msvcrt", WinMsvcrt::class.java)._putenv_s(name, value) }
            .onFailure { log.warn("_putenv_s($name) failed", it) }
    } else {
        runCatching { Native.load("c", PosixC::class.java).setenv(name, value, 1) }
            .onFailure { log.warn("setenv($name) failed", it) }
    }
}
