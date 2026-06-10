package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent

/**
 * Desktop engine over libvlc (via vlcj). Unlike Media3, libvlc plays one media at a time, so the
 * queue is managed here by hand: on the native "finished" event we advance and load the next track.
 *
 * libvlc is discovered at runtime; if it isn't installed the component is left null and playback is a
 * no-op (rather than crashing the app at startup) — desktop requires libvlc as a system package on
 * Linux / bundled on Windows.
 */
actual class MediaPlayerEngine actual constructor() {
    private val _status = MutableStateFlow(EngineStatus())
    actual val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    actual val volume: StateFlow<Float> = _volume.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val queue = mutableListOf<PlayableMedia>()
    private var index = -1
    private var repeat = RepeatMode.Off
    private var shuffle = false

    // Built lazily so a missing libvlc only disables playback instead of crashing app launch.
    private val component: AudioPlayerComponent? by lazy {
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
                override fun playing(mediaPlayer: MediaPlayer) = pushState(PlaybackState.Playing, true)
                override fun paused(mediaPlayer: MediaPlayer) = pushState(PlaybackState.Paused, false)
                override fun stopped(mediaPlayer: MediaPlayer) = pushState(PlaybackState.Paused, false)
                override fun error(mediaPlayer: MediaPlayer) = pushState(PlaybackState.Idle, false)
            }
        }.getOrElse {
            System.err.println("libvlc unavailable — desktop playback disabled: ${it.message}")
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

    private fun playIndex(i: Int) {
        val p = player ?: return
        if (i !in queue.indices) return
        index = i
        p.media().play(queue[i].uri)
        // libvlc resets volume to default on a fresh media; reassert the chosen level.
        p.audio().setVolume((_volume.value * 100).toInt())
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
            player?.controls()?.stop()
            pushState(PlaybackState.Idle, false)
        } else {
            playIndex(startIndex.coerceIn(0, items.size - 1))
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
        player?.controls()?.stop()
        pushState(PlaybackState.Idle, false)
    }

    actual fun play() {
        val p = player ?: return
        if (index == -1 && queue.isNotEmpty()) {
            playIndex(0)
        } else {
            p.controls().setPause(false)
            // Reflect intent immediately rather than waiting for libvlc's playing() event (see pause()).
            pushState(PlaybackState.Playing, true)
        }
    }

    actual fun pause() {
        val p = player ?: return
        p.controls().setPause(true)
        // libvlc delivers its paused() event with a noticeable lag, so the play/pause button and
        // progress bar would otherwise keep showing "playing" until it arrives. Push the paused state
        // now; the eventual paused() callback just confirms it.
        pushState(PlaybackState.Paused, false)
    }

    actual fun seekTo(positionMs: Long) {
        player?.controls()?.setTime(positionMs)
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
        player?.audio()?.setVolume((clamped * 100).toInt())
    }

    // libvlc only exposes an in-app gain; there's no OS device-volume control here, so this is a no-op.
    actual fun setVolumeMode(useDeviceVolume: Boolean) {}

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
