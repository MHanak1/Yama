package net.mhanak.yama.platform

import net.mhanak.yama.util.logger
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.TimeUnit

/**
 * `org.freedesktop.portal.Settings` — the XDG Desktop Portal's cross-desktop settings interface. We only
 * touch the `org.freedesktop.appearance` `accent-color` key: a `(ddd)` struct of R,G,B doubles in `[0,1]`,
 * or `-1,-1,-1` when no accent is set. Dark/light (`color-scheme`) is deliberately *not* read here — that
 * stays on the app's own theme axis, mirroring how the portal keeps the two independent.
 */
@DBusInterfaceName("org.freedesktop.portal.Settings")
interface PortalSettings : DBusInterface {
    /** Portal v2+: read one key, returning its value variant directly. */
    fun ReadOne(namespace: String, key: String): Variant<*>

    /** Legacy fallback: returns the value double-wrapped (a variant holding a variant). */
    fun Read(namespace: String, key: String): Variant<*>

    /** Emitted when any setting changes; we filter to the appearance accent-colour key. */
    class SettingChanged(
        path: String,
        val namespace: String,
        val key: String,
        val value: Variant<*>,
    ) : DBusSignal(path, namespace, key, value)
}

/**
 * The desktop shell's accent colour, as R,G,B floats in `[0,1]`.
 *
 * Sourced from the XDG appearance portal where it's served (GNOME 47+, and KDE versions that implement
 * the key) and falling back to KDE's `kdeglobals` otherwise — because current `xdg-desktop-portal-kde`
 * (6.7) still returns `NotFound` for `accent-color` even though Plasma has an accent, storing it only in
 * `[General] AccentColor` (RGB 0–255). One shared session-bus connection for the process (null where
 * there's no bus, e.g. Windows).
 */
internal object DesktopAccent {
    private val log = logger("DesktopAccent")

    private const val NS = "org.freedesktop.appearance"
    private const val KEY = "accent-color"
    private const val BUS = "org.freedesktop.portal.Desktop"
    private const val PATH = "/org/freedesktop/portal/desktop"

    private val connection: DBusConnection? by lazy {
        runCatching { DBusConnectionBuilder.forSessionBus().build() }
            .onFailure { log.debug("No session bus for the appearance portal", it) }
            .getOrNull()
    }

    private fun settings(): PortalSettings? = connection?.let { conn ->
        runCatching { conn.getRemoteObject(BUS, PATH, PortalSettings::class.java) }.getOrNull()
    }

    private val kdeglobals: File by lazy {
        val cfg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.config")
        File(cfg, "kdeglobals")
    }

    /**
     * Whether *any* accent source can currently produce a colour (portal key present, or kdeglobals has an
     * accent). Probed once — this is what gates whether the shell-accent source is offered at all, so on a
     * bare compositor with neither (e.g. Caelestia) it stays hidden and the theme file drives colour.
     */
    val accentSupported: Boolean by lazy { readAccentRgb() != null }

    /** The current accent, portal first then kdeglobals; null when neither provides one. */
    fun readAccentRgb(): Triple<Float, Float, Float>? = portalAccent() ?: kdeglobalsAccent()

    /**
     * Invoke [onChange] whenever the accent changes, watching *both* the portal (`SettingChanged`) and
     * kdeglobals (a file watch). Returns a handle that stops both, or null if nothing could be watched.
     */
    fun watchAccent(onChange: () -> Unit): AutoCloseable? {
        val portalSub = watchPortal(onChange)
        val kdeSub = watchKdeglobals(onChange)
        if (portalSub == null && kdeSub == null) return null
        return AutoCloseable {
            runCatching { portalSub?.close() }
            runCatching { kdeSub?.close() }
        }
    }

    // ── Portal ──────────────────────────────────────────────────────────────────────

    private fun portalAccent(): Triple<Float, Float, Float>? {
        val (r, g, b) = readRawPortalAccent() ?: return null
        if (r < 0.0 || g < 0.0 || b < 0.0) return null // -1,-1,-1 → no accent set
        return Triple(r.toFloat(), g.toFloat(), b.toFloat())
    }

    private fun watchPortal(onChange: () -> Unit): AutoCloseable? {
        val conn = connection ?: return null
        val handler = DBusSigHandler<PortalSettings.SettingChanged> { sig ->
            if (sig.namespace == NS && sig.key == KEY) onChange()
        }
        return runCatching { conn.addSigHandler(PortalSettings.SettingChanged::class.java, handler) }
            .onFailure { log.warn("Failed to watch portal accent colour", it) }
            .getOrNull()
    }

    /** Raw read including the `-1,-1,-1` "unset" sentinel; null only when the key/portal is absent. */
    private fun readRawPortalAccent(): Triple<Double, Double, Double>? {
        val s = settings() ?: return null
        val value = try {
            s.ReadOne(NS, KEY)
        } catch (_: Exception) {
            // Older portals only have Read, which double-wraps the value in a variant.
            runCatching { unwrap(s.Read(NS, KEY)) }.getOrNull() ?: return null
        }
        return parseDdd(value)
    }

    private fun unwrap(v: Variant<*>?): Variant<*>? = (v?.value as? Variant<*>) ?: v

    /** Pull three doubles out of a variant-wrapped `(ddd)` struct across dbus-java's possible shapes. */
    private fun parseDdd(v: Variant<*>?): Triple<Double, Double, Double>? {
        val inner = v?.value ?: return null
        val nums: List<Any?> = when (inner) {
            is Array<*> -> inner.toList()
            is List<*> -> inner
            // An anonymous struct is surfaced as a Struct subtype; read its members reflectively so we
            // don't depend on that API being public.
            else -> runCatching {
                (inner.javaClass.getMethod("getParameters").invoke(inner) as? Array<*>)?.toList()
            }.getOrNull() ?: return null
        }
        if (nums.size < 3) return null
        val r = (nums[0] as? Number)?.toDouble() ?: return null
        val g = (nums[1] as? Number)?.toDouble() ?: return null
        val b = (nums[2] as? Number)?.toDouble() ?: return null
        return Triple(r, g, b)
    }

    // ── KDE kdeglobals fallback ───────────────────────────────────────────────────────

    // Line-anchored so it matches `[General]`'s `AccentColor=r,g,b` and not `LastUsedCustomAccentColor`.
    private val ACCENT_REGEX = Regex("^AccentColor=(\\d{1,3}),(\\d{1,3}),(\\d{1,3})", RegexOption.MULTILINE)

    private fun kdeglobalsAccent(): Triple<Float, Float, Float>? {
        if (!kdeglobals.exists()) return null
        return try {
            val m = ACCENT_REGEX.find(kdeglobals.readText()) ?: return null
            val (r, g, b) = m.destructured
            Triple(r.toInt() / 255f, g.toInt() / 255f, b.toInt() / 255f)
        } catch (_: Exception) {
            null
        }
    }

    private fun watchKdeglobals(onChange: () -> Unit): AutoCloseable? {
        val dir = kdeglobals.parentFile?.takeIf { it.isDirectory }?.toPath() ?: return null
        return try {
            val watch = FileSystems.getDefault().newWatchService()
            dir.register(watch, ENTRY_CREATE, ENTRY_MODIFY)
            // KDE rewrites kdeglobals atomically (temp + rename), so a plain dedicated watcher thread is
            // simplest; interrupting it unblocks the poll and closes the service.
            val thread = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val key = watch.poll(1, TimeUnit.SECONDS) ?: continue
                        val touched = key.pollEvents().any {
                            (it.context() as? Path)?.fileName?.toString() == kdeglobals.name
                        }
                        key.reset()
                        if (touched) onChange()
                    }
                } catch (_: InterruptedException) {
                    // normal shutdown
                } catch (e: Exception) {
                    log.warn("kdeglobals accent watch stopped", e)
                } finally {
                    runCatching { watch.close() }
                }
            }.apply { isDaemon = true; name = "kdeglobals-accent-watch"; start() }
            AutoCloseable { thread.interrupt() }
        } catch (_: Exception) {
            null
        }
    }
}
