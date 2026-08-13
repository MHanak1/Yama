package net.mhanak.yama.media.playback

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import net.mhanak.yama.util.logger
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent

// Pending-seek retry loop: up to ATTEMPTS × VERIFY_DELAY (~3s) of nudging a just-opened stream until
// its reported position lands within TOLERANCE of the target.
private const val SEEK_APPLY_ATTEMPTS = 30
private const val SEEK_VERIFY_DELAY_MS = 100L
private const val SEEK_LANDED_TOLERANCE_MS = 1_500L

/**
 * Desktop engine over libvlc (via vlcj). Unlike Media3, libvlc plays one media at a time, so the
 * queue is managed here by hand: on the native "finished" event we advance and load the next track.
 *
 * libvlc is discovered at runtime; if it isn't installed the component is left null and playback is a
 * no-op (rather than crashing the app at startup) — desktop requires libvlc as a system package on
 * Linux / bundled on Windows.
 */
actual class MediaPlayerEngine actual constructor() {
    private val log = logger("Playback")

    private val _status = MutableStateFlow(EngineStatus())
    actual val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    actual val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _controlsSystemVolume = MutableStateFlow(false)
    actual val controlsSystemVolume: StateFlow<Boolean> = _controlsSystemVolume.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val queue = mutableListOf<PlayableMedia>()
    private var index = -1
    private var mediaLoaded = false
    private var repeat = RepeatMode.Off
    private var shuffle = false
    private var useDeviceVolume = false

    // A seek target that libvlc can't act on yet because the current track isn't actively playing
    // and seekable (queue restored-but-not-started, or paused). Applied once the stream reports
    // seekable, via playing()/seekableChanged(). 0 = none. Touched from the native event thread and
    // engine coroutines, hence @Volatile.
    @Volatile private var pendingSeekMs = 0L
    private var seekJob: Job? = null

    // Built lazily so a missing libvlc only disables playback instead of crashing app launch.
    private val component: AudioPlayerComponent? by lazy {
        // Point JNA at the bundled libvlc (packaged Windows app) before vlcj loads the native library;
        // a no-op under `./gradlew run` and on Linux, where the system VLC is discovered normally.
        ensureBundledVlc()
        runCatching {
            object : AudioPlayerComponent() {
                override fun finished(mediaPlayer: MediaPlayer) {
                    // Must not re-enter the player from its own event thread — hop threads first.
                    scope.launch { advanceAfterFinish() }
                }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    _status.value = _status.value.copy(positionMs = newTime)
                }
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    _status.value = _status.value.copy(durationMs = newLength)
                }
                override fun playing(mediaPlayer: MediaPlayer) {
                    applyPendingSeek()
                    pushState(PlaybackState.Playing, true)
                }
                // A freshly-started network stream isn't seekable the instant playing() fires, so a
                // restored-queue seek has to wait for this; a resume-from-pause is already seekable and
                // gets applied by playing() above. newSeekable != 0 means "you can seek now".
                override fun seekableChanged(mediaPlayer: MediaPlayer, newSeekable: Int) {
                    if (newSeekable != 0) applyPendingSeek()
                }
                override fun paused(mediaPlayer: MediaPlayer) = pushState(PlaybackState.Paused, false)
                override fun stopped(mediaPlayer: MediaPlayer) = pushState(PlaybackState.Paused, false)
                override fun error(mediaPlayer: MediaPlayer) {
                    val uri = if (index in queue.indices) queue[index].uri else "<none>"
                    log.error("vlcj playback error at index $index uri=$uri")
                    pushState(PlaybackState.Idle, false)
                }
            }
        }.getOrElse {
            log.warn("libvlc unavailable — desktop playback disabled", it)
            null
        }
    }

    private val player: MediaPlayer? get() = component?.mediaPlayer()

    private fun pushState(state: PlaybackState, playing: Boolean) {
        _status.value = _status.value.copy(
            state = state,
            isPlaying = playing,
            queueIndex = index,
            repeat = repeat,
            shuffle = shuffle,
        )
    }

    /**
     * Applies a queued seek to a freshly-started/paused track. libvlc reports isSeekable == true before
     * a just-opened network stream can actually honour a seek (the setTime gets clamped to ~0), so this
     * nudges and verifies: setTime, read back the position, and retry until it lands near the target or
     * we give up. Runs on an engine coroutine — never the native event thread, where re-entering the
     * player synchronously from a callback deadlocks it (see finished()). Idempotent: extra calls from
     * playing()/seekableChanged() are coalesced into the single in-flight [seekJob].
     */
    private fun applyPendingSeek() {
        if (pendingSeekMs <= 0 || seekJob?.isActive == true) return
        seekJob = scope.launch {
            val target = pendingSeekMs
            repeat(SEEK_APPLY_ATTEMPTS) {
                val p = player ?: return@launch
                if (pendingSeekMs != target) return@launch // superseded by a newer seek or track change
                if (p.status().isSeekable) {
                    p.controls().setTime(target)
                    delay(SEEK_VERIFY_DELAY_MS)
                    if (abs(p.status().time() - target) < SEEK_LANDED_TOLERANCE_MS) {
                        pendingSeekMs = 0
                        log.info("Restored playback position to ${target}ms")
                        return@launch
                    }
                } else {
                    delay(SEEK_VERIFY_DELAY_MS)
                }
            }
            log.warn("Gave up restoring playback position to ${target}ms — stream never accepted the seek")
        }
    }

    private fun playIndex(i: Int) {
        val p = player ?: run { log.warn("playIndex($i) dropped — libvlc engine unavailable"); return }
        if (i !in queue.indices) { log.warn("playIndex($i) out of bounds (queue size=${queue.size})"); return }
        // A pending seek only belongs to the track it was made against; switching tracks discards it.
        // When we're (re)loading the same track it's kept, and playing() applies it once the stream is
        // running. (:start-time was tried here but libvlc ignores it for network streams.)
        if (i != index) pendingSeekMs = 0L
        index = i
        mediaLoaded = true
        p.media().play(queue[i].uri)
        // libvlc resets volume to default on a fresh media; reassert the chosen level.
        // When device volume is active the in-app gain must stay at 100 so only the OS level matters.
        val gainLevel = if (_controlsSystemVolume.value) 1f else _volume.value
        p.audio().setVolume((gainLevel * 100).toInt())
        pushState(PlaybackState.Buffering, true)
    }

    private fun nextIndex(): Int? = when {
        repeat == RepeatMode.One -> index
        shuffle && queue.size > 1 -> (queue.indices - index).randomOrNull()
        index + 1 < queue.size -> index + 1
        repeat == RepeatMode.All && queue.isNotEmpty() -> 0
        else -> null
    }

    private fun advanceAfterFinish() {
        val next = nextIndex()
        if (next != null) playIndex(next)
        else pushState(PlaybackState.Ended, false)
    }

    actual fun setQueue(items: List<PlayableMedia>, startIndex: Int) {
        queue.clear()
        queue.addAll(items)
        if (items.isEmpty()) {
            index = -1
            mediaLoaded = false
            player?.controls()?.stop()
            pushState(PlaybackState.Idle, false)
        } else {
            playIndex(startIndex.coerceIn(0, items.size - 1))
        }
    }

    actual fun loadQueue(items: List<PlayableMedia>, startIndex: Int) {
        queue.clear()
        queue.addAll(items)
        mediaLoaded = false
        if (items.isEmpty()) {
            index = -1
            player?.controls()?.stop()
            pushState(PlaybackState.Idle, false)
        } else {
            index = startIndex.coerceIn(0, items.size - 1)
            pushState(PlaybackState.Paused, false)
        }
    }

    actual fun addToQueue(items: List<PlayableMedia>) {
        queue.addAll(items)
    }

    actual fun addNext(items: List<PlayableMedia>) {
        queue.addAll((index + 1).coerceIn(0, queue.size), items)
    }

    actual fun removeAt(index: Int) {
        if (index !in queue.indices) return
        queue.removeAt(index)
        when {
            index < this.index -> this.index--
            index == this.index -> playIndex(this.index.coerceAtMost(queue.size - 1))
        }
    }

    actual fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        queue.add(to, queue.removeAt(from))
        index = when (index) {
            from -> to
            in (from + 1)..to -> index - 1
            in to until from -> index + 1
            else -> index
        }
    }

    actual fun clear() {
        queue.clear()
        index = -1
        mediaLoaded = false
        player?.controls()?.stop()
        pushState(PlaybackState.Idle, false)
    }

    actual fun play() {
        val p = player ?: run { log.warn("play() dropped — libvlc engine unavailable"); return }
        when {
            // Queue was restored via loadQueue but media never started — begin playing now.
            !mediaLoaded && queue.isNotEmpty() -> playIndex(if (index >= 0) index else 0)
            index == -1 && queue.isNotEmpty() -> playIndex(0)
            else -> {
                p.controls().setPause(false)
                // Reflect intent immediately rather than waiting for libvlc's playing() event (see pause()).
                pushState(PlaybackState.Playing, true)
            }
        }
    }

    actual fun pause() {
        val p = player ?: run { log.warn("pause() dropped — libvlc engine unavailable"); return }
        p.controls().setPause(true)
        // libvlc delivers its paused() event with a noticeable lag, so the play/pause button and
        // progress bar would otherwise keep showing "playing" until it arrives. Push the paused state
        // now; the eventual paused() callback just confirms it.
        pushState(PlaybackState.Paused, false)
    }

    actual fun seekTo(positionMs: Long) {
        // libvlc only honours setTime on a track that's actively playing. When it isn't (restored queue
        // not yet started, or paused), stash the target and let playIndex/playing() apply it later.
        if (mediaLoaded && _status.value.isPlaying) {
            pendingSeekMs = 0L
            player?.controls()?.setTime(positionMs)
        } else {
            pendingSeekMs = positionMs
        }
        // Reflect the seek immediately so the progress bar tracks even while paused, where libvlc emits
        // no timeChanged events.
        _status.value = _status.value.copy(positionMs = positionMs)
    }

    actual fun next() {
        val saved = repeat
        repeat = RepeatMode.Off // a manual "next" never repeats the current track
        advanceAfterFinish()
        repeat = saved
    }

    actual fun previous() {
        val p = player ?: return
        if (p.status().time() > 3_000) {
            p.controls().setTime(0)
        } else {
            val prev = if (index - 1 >= 0) index - 1 else if (repeat == RepeatMode.All) queue.size - 1 else 0
            playIndex(prev)
        }
    }

    actual fun seekToIndex(index: Int) = playIndex(index)

    actual fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _volume.value = clamped
        if (_controlsSystemVolume.value) {
            SystemVolume.set(clamped)
        } else {
            player?.audio()?.setVolume((clamped * 100).toInt())
        }
    }

    actual fun setVolumeMode(useDeviceVolume: Boolean) {
        this.useDeviceVolume = useDeviceVolume
        if (!useDeviceVolume) {
            _controlsSystemVolume.value = false
            // Reassert in-app gain so libvlc immediately reflects the slider value.
            player?.audio()?.setVolume((_volume.value * 100).toInt())
            return
        }
        // Check availability off the main thread; brief startup race is harmless — slider just
        // stays in in-app mode until the check resolves.
        scope.launch(Dispatchers.IO) {
            val canControl = SystemVolume.isAvailable
            _controlsSystemVolume.value = canControl
            if (canControl) {
                // Pin in-app gain so the system volume isn't additionally attenuated.
                player?.audio()?.setVolume(100)
                SystemVolume.get()?.let { _volume.value = it }
            }
        }
    }

    actual fun setRepeat(mode: RepeatMode) {
        repeat = mode
        _status.value = _status.value.copy(repeat = mode)
    }

    actual fun setShuffle(enabled: Boolean) {
        shuffle = enabled
        _status.value = _status.value.copy(shuffle = enabled)
    }

    actual fun release() {
        scope.cancel()
        component?.release()
    }
}

/**
 * OS-level volume control for desktop.
 *
 * - **Linux**: pactl (PulseAudio / PipeWire-pulse).
 * - **macOS**: osascript.
 * - **Windows**: direct JNA COM dispatch into WASAPI's IAudioEndpointVolume — no process spawn,
 *   no Add-Type compilation delay.
 */
private object SystemVolume {
    private val os = System.getProperty("os.name").lowercase()
    private val isLinux = os.contains("linux")
    private val isMac = os.contains("mac")
    private val isWindows = os.contains("windows")

    val isAvailable: Boolean by lazy {
        when {
            isLinux -> runCatching {
                ProcessBuilder("pactl", "get-sink-volume", "@DEFAULT_SINK@")
                    .redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
            isMac -> true
            isWindows -> true
            else -> false
        }
    }

    fun get(): Float? = when {
        isLinux -> runCatching {
            val out = ProcessBuilder("pactl", "get-sink-volume", "@DEFAULT_SINK@")
                .start().inputStream.bufferedReader().readText()
            // "Volume: front-left: 65536 / 75% / 0.00 dB, ..."
            Regex("""/ (\d+)%""").find(out)?.groupValues?.get(1)?.toIntOrNull()?.div(100f)
        }.getOrNull()
        isMac -> runCatching {
            ProcessBuilder("osascript", "-e", "output volume of (get volume settings)")
                .start().inputStream.bufferedReader().readText().trim().toIntOrNull()?.div(100f)
        }.getOrNull()
        isWindows -> WindowsVolume.get()
        else -> null
    }

    fun set(level: Float) {
        val pct = (level * 100).toInt().coerceIn(0, 100)
        when {
            isLinux -> runCatching { ProcessBuilder("pactl", "set-sink-volume", "@DEFAULT_SINK@", "$pct%").start() }
            isMac -> runCatching { ProcessBuilder("osascript", "-e", "set volume output volume $pct").start() }
            isWindows -> WindowsVolume.set(level)
        }
    }
}

/**
 * Windows WASAPI volume via JNA COM vtable dispatch. No process spawn, no compilation step.
 *
 * COM vtable indices used (0–2 are always QueryInterface/AddRef/Release from IUnknown):
 * - IMMDeviceEnumerator index 4: GetDefaultAudioEndpoint
 * - IMMDevice index 3:           Activate
 * - IAudioEndpointVolume index 7: SetMasterVolumeLevelScalar
 * - IAudioEndpointVolume index 9: GetMasterVolumeLevelScalar
 */
private object WindowsVolume {
    private val CLSID_MMDE = GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    private val IID_MMDE   = GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    private val IID_AEV    = GUID.fromString("{5CDF2C82-841E-4546-9722-0CF74078229A}")

    fun get(): Float? = withVolume { vol ->
        val out = FloatByReference()
        vtbl(vol, 9, vol, out)
        out.value
    }

    fun set(level: Float) {
        withVolume<Unit> { vol -> vtbl(vol, 7, vol, level, null) }
    }

    private fun <T> withVolume(block: (Pointer) -> T): T? = runCatching {
        Ole32.INSTANCE.CoInitializeEx(null, 0)
        try {
            val enumRef = PointerByReference()
            Ole32.INSTANCE.CoCreateInstance(CLSID_MMDE, null, 1 /* CLSCTX_INPROC_SERVER */, IID_MMDE, enumRef)
            val enumerator = enumRef.value

            val deviceRef = PointerByReference()
            vtbl(enumerator, 4, enumerator, 0 /* eRender */, 0 /* eConsole */, deviceRef)
            release(enumerator)

            val device = deviceRef.value
            val volRef = PointerByReference()
            vtbl(device, 3, device, IID_AEV, 1 /* CLSCTX_INPROC_SERVER */, null, volRef)
            release(device)

            val vol = volRef.value
            block(vol).also { release(vol) }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }.getOrNull()

    // Dispatch a COM vtable method by index. The COM object itself is always args[0].
    private fun vtbl(obj: Pointer, idx: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(
            obj.getPointer(0).getPointer(idx.toLong() * Native.POINTER_SIZE.toLong()),
            Function.ALT_CONVENTION,
        )
        return fn.invokeInt(args)
    }

    private fun release(obj: Pointer) { vtbl(obj, 2, obj) }
}
