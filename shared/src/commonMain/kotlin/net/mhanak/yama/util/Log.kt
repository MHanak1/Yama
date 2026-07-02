package net.mhanak.yama.util

/**
 * Minimal multiplatform logging facade. Each platform provides an [actual] that routes to the
 * appropriate backend:
 * - **Android** → `android.util.Log` (native logcat with tag-based filtering).
 * - **Desktop/JVM** → SLF4J / logback (configured via `desktopApp/src/main/resources/logback.xml`).
 *
 * Usage:
 * ```kotlin
 * private val log = logger("MyTag")
 * log.error("stream resolve failed", t)
 * ```
 *
 * Tags should be short and consistent so platform log-filtering works cleanly:
 * - `adb logcat -s Playback:* Downloads:*`
 * - `logback.xml` `<logger name="net.mhanak.yama.Playback" level="DEBUG"/>`
 */
interface Logger {
    fun debug(message: String, throwable: Throwable? = null)
    fun info(message: String, throwable: Throwable? = null)
    fun warn(message: String, throwable: Throwable? = null)
    fun error(message: String, throwable: Throwable? = null)
}

/** Returns a [Logger] identified by [name]. On Android [name] becomes the logcat tag; on desktop it
 *  becomes the SLF4J logger name (prefixed with `net.mhanak.yama.`). */
expect fun logger(name: String): Logger
