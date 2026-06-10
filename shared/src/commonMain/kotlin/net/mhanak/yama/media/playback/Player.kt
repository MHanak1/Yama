package net.mhanak.yama.media.playback

import kotlinx.coroutines.flow.StateFlow
import net.mhanak.yama.media.model.Track

/**
 * The playback abstraction the UI binds to. Today the only implementation is [LocalPlayer] (playback
 * on this device), but the interface is deliberately backend-agnostic so a remote player — Jellyfin
 * "Play On", Music Assistant — can be swapped in via [PlaybackController.active] without the UI knowing.
 *
 * Queue manipulation lives here because every modern player needs it and remote backends manage their
 * own queues too; what differs is only how a command is carried out, not the surface.
 */
interface Player {
    /** Human-readable target, e.g. "This device" or "Living Room TV". Shown in the player UI. */
    val displayName: String

    val status: StateFlow<PlayerStatus>

    /** Replace the queue with [tracks] and start playing at [startIndex]. */
    fun playNow(tracks: List<Track>, startIndex: Int = 0)

    /** Insert [tracks] immediately after the current item. */
    fun playNext(tracks: List<Track>)

    /** Append [tracks] to the end of the queue. */
    fun addToQueue(tracks: List<Track>)

    fun play()
    fun pause()
    /** Pause and clear the queue, hiding the player bar. */
    fun stop()
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipTo(index: Int)

    fun removeAt(index: Int)
    fun move(from: Int, to: Int)
    fun clearQueue()

    fun setRepeat(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)

    /**
     * Output volume in 0f..1f, or null when the current level is unknown. Note this can be null even
     * while volume *is* controllable (a remote device may accept volume commands without reporting a
     * level) — use [volumeControllable] to decide whether to act on volume, and this only to render a
     * slider that needs a concrete value.
     */
    val volume: StateFlow<Float?>

    /**
     * Whether volume can be controlled at all, independent of whether a current level is known. True
     * for local playback; for a remote device, true when it advertises volume commands (or reports a
     * level). Gates things like hardware-key capture.
     */
    val volumeControllable: StateFlow<Boolean>

    /** Set absolute output volume (0f..1f). No-op on players without volume control. */
    fun setVolume(level: Float)

    /**
     * Nudge volume by one [VOLUME_STEP]. Defaults to stepping [setVolume] from the last known level;
     * a remote player overrides these to send the device's native volume-up/down commands so it steps
     * on its own scale (and reports the new level back).
     */
    fun volumeUp() { volume.value?.let { setVolume((it + VOLUME_STEP).coerceAtMost(1f)) } }
    fun volumeDown() { volume.value?.let { setVolume((it - VOLUME_STEP).coerceAtLeast(0f)) } }

    /**
     * Re-pull authoritative state from the backend, e.g. after the app returns to the foreground.
     * Local playback is always current so the default no-ops; a remote player overrides this to
     * resync against the device it controls (whose live push may have gone stale while backgrounded).
     */
    fun refresh() {}

    fun release()
}

/** Half of one hardware/step volume increment, as a fraction of the full 0f..1f range. */
const val VOLUME_STEP = 0.025f
