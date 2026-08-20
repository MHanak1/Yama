package net.mhanak.yama.platform

import androidx.compose.runtime.snapshotFlow
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.PlaybackState
import net.mhanak.yama.media.playback.PlayerStatus
import net.mhanak.yama.util.logger
import java.awt.Window
import java.io.File

/**
 * Windows System Media Transport Controls integration — the Windows counterpart to [MprisService].
 * Publishes Yama to the OS media surface (the volume-flyout media widget / lockscreen) and routes the
 * hardware media keys (⏯ ⏭ ⏮) to Yama system-wide.
 *
 * Mirrors [MprisService]: bound to the [PlaybackController] and follows [PlaybackController.viewed]
 * across "Play On" swaps, so the OS controls whatever the UI is showing. SMTC itself is a WinRT API we
 * can't reach from JNA directly, so the calls go through a bundled native shim ([SmtcLib], built from
 * desktopApp/native/smtc/yama_smtc.cpp). See SMTC_PLAN.md.
 *
 * No-ops silently on non-Windows platforms and when the shim DLL isn't present (a plain `./gradlew
 * run`, where nothing is bundled) — exactly like [ensureBundledVlc]'s discovery.
 */
class SmtcService(
    private val playback: PlaybackController,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val log = logger("SmtcService")

    private var lib: SmtcLib? = null

    // Strong reference to the JNA callback: JNA only keeps a weak ref to the trampoline it hands the
    // native side, so without this field the ButtonPressed callback would be GC'd and later crash.
    private val buttonCallback = SmtcLib.ButtonCallback { button -> onButton(button) }

    // Last pushed status, for diffing so we only touch the OS on real changes (mirrors
    // MprisService.onStatusChanged). Reset to null on a viewed-player swap to force a full re-push.
    @Volatile private var last: PlayerStatus? = null

    /**
     * Bind SMTC to [window]'s native handle and start mirroring playback onto it. Must be called once
     * the window is displayable (from the Compose Window content). No-ops off Windows before touching
     * any native code, so calling it unconditionally on other platforms is safe.
     */
    fun start(window: Window) {
        if (!isWindows()) return
        val l = loadShim() ?: run {
            log.info("SMTC shim not bundled — skipping (expected under ./gradlew run)")
            return
        }
        runCatching {
            // JNA resolves the HWND from the AWT peer; only reached on Windows, so no Wayland/X11 risk.
            val hwnd: Pointer = Native.getWindowPointer(window)
            if (l.smtc_init(hwnd) != 0) {
                log.warn("smtc_init failed — SMTC disabled")
                return
            }
            l.smtc_set_button_callback(buttonCallback)
            lib = l
            // Follow the viewed player across swaps; re-push full state each swap, then diff (same shape
            // as the MprisService observer).
            scope.launch {
                snapshotFlow { playback.viewed }.collectLatest { player ->
                    last = null
                    player.status.collect { push(l, it) }
                }
            }
        }.onFailure { log.warn("SMTC setup failed", it) }
    }

    fun stop() {
        scope.cancel()
        runCatching { lib?.smtc_shutdown() }
        lib = null
    }

    // Button presses arrive on a WinRT threadpool thread (via JNA); marshal to the main thread before
    // touching the viewed player. SMTC sends Play vs Pause based on the PlaybackStatus we report, so we
    // honour that split rather than toggling (which could double-fire against a stale status).
    private fun onButton(button: Int) {
        scope.launch(Dispatchers.Main) {
            val p = playback.viewed
            when (button) {
                0 -> p.play()
                1 -> p.pause()
                2 -> p.next()
                3 -> p.previous()
                4 -> p.stop()
            }
        }
    }

    private fun push(l: SmtcLib, s: PlayerStatus) {
        val prev = last
        last = s

        if (prev == null || statusCode(s) != statusCode(prev)) {
            l.smtc_set_playback_status(statusCode(s))
        }

        // Metadata carries the thumbnail, which flickers if re-pushed constantly — gate on track change.
        val trackChanged = s.current?.id != prev?.current?.id
        if (prev == null || trackChanged) {
            val t = s.current
            l.smtc_set_metadata(
                WString(t?.name ?: ""),
                WString(t?.artists?.joinToString(", ") ?: ""),
                WString(t?.album ?: ""),
                t?.imageUrl?.let { WString(it) },
            )
        }

        // Timeline is cheap and non-flickery; push whenever position or duration moves. SMTC then
        // extrapolates the position between pushes from the reported playback status.
        if (prev == null || trackChanged || s.positionMs != prev.positionMs || s.durationMs != prev.durationMs) {
            l.smtc_set_timeline(s.positionMs, s.durationMs)
        }

        val canNext = canGoNext(s)
        val canPrev = canGoPrevious(s)
        if (prev == null ||
            canNext != canGoNext(prev) ||
            canPrev != canGoPrevious(prev) ||
            (s.current != null) != (prev.current != null)
        ) {
            l.smtc_set_buttons(
                play = 1,
                pause = 1,
                next = if (canNext) 1 else 0,
                prev = if (canPrev) 1 else 0,
                stop = if (s.current != null) 1 else 0,
            )
        }
    }

    // Kotlin ABI status ints, matching yama_smtc.cpp::toStatus (0 Stopped, 1 Paused, 2 Playing).
    private fun statusCode(s: PlayerStatus): Int = when {
        s.state == PlaybackState.Idle || s.state == PlaybackState.Ended -> 0
        s.isPlaying -> 2
        else -> 1
    }

    private fun canGoNext(s: PlayerStatus) =
        s.queue.isNotEmpty() && s.queueIndex >= 0 && s.queueIndex < s.queue.size - 1

    private fun canGoPrevious(s: PlayerStatus) = s.queueIndex > 0

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("windows")

    // Load the bundled shim the same way ensureBundledVlc finds libvlc: it's only present in a
    // packaged app image, exposed via the compose.application.resources.dir system property.
    private fun loadShim(): SmtcLib? {
        val resourcesDir = System.getProperty("compose.application.resources.dir") ?: return null
        val smtcDir = File(resourcesDir, "smtc")
        if (!smtcDir.isDirectory) return null
        val prev = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (prev.isNullOrBlank()) smtcDir.absolutePath else smtcDir.absolutePath + File.pathSeparator + prev,
        )
        return runCatching { Native.load("yama_smtc", SmtcLib::class.java) }
            .onFailure { log.warn("failed to load yama_smtc.dll", it) }
            .getOrNull()
    }
}

/**
 * JNA mapping of the flat C ABI exported by yama_smtc.dll. Method names must match the DLL exports
 * exactly. Strings are [WString] (UTF-16), matching the shim's `const wchar_t*`.
 */
private interface SmtcLib : Library {
    fun smtc_init(hwnd: Pointer): Int
    fun smtc_set_button_callback(cb: ButtonCallback)
    fun smtc_shutdown()
    fun smtc_set_enabled(enabled: Int)
    fun smtc_set_playback_status(status: Int)
    fun smtc_set_buttons(play: Int, pause: Int, next: Int, prev: Int, stop: Int)
    fun smtc_set_metadata(title: WString?, artist: WString?, album: WString?, artUrl: WString?)
    fun smtc_set_timeline(positionMs: Long, durationMs: Long)

    // Matches `typedef void (*smtc_button_cb)(int)`. JNA invokes the single method on a native thread
    // it attaches to the JVM for the duration of the call.
    fun interface ButtonCallback : Callback {
        fun invoke(button: Int)
    }
}
