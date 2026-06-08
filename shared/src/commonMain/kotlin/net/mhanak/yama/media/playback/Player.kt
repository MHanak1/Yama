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

    fun release()
}
