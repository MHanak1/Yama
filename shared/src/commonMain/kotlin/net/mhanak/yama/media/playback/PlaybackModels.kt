package net.mhanak.yama.media.playback

import net.mhanak.yama.media.model.Track

/** How the queue advances when a track ends. */
enum class RepeatMode { Off, All, One }

/**
 * High-level transport state, shared by every [Player] regardless of backend.
 *
 * [Reconnecting] is a *retryable* interruption: playback stalled on a transient network fault (buffer
 * exhausted with no connection) and the engine is waiting to resume from the same position once the
 * network returns. The UI treats it like [Buffering] (spinner), but it's distinct so the engine can
 * hold the queue/position and self-recover rather than collapsing into [Idle] (an indistinguishable
 * "stopped"). A *fatal* fault (missing file, bad status, undecodable) instead lands in [Idle]/[Ended]
 * with [EngineStatus.error] set.
 */
enum class PlaybackState { Idle, Buffering, Reconnecting, Playing, Paused, Ended }

/**
 * A single playable item handed to a [MediaPlayerEngine]. Carries the metadata the engine needs to
 * present playback to the OS (e.g. the Android media notification / lockscreen), so the engine never
 * has to reach back into a [net.mhanak.yama.media.sources.MusicSource].
 */
data class PlayableMedia(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val artworkUri: String?,
    val durationMs: Long?,
)

/**
 * Low-level engine state ([MediaPlayerEngine] knows nothing about [Track]s, only its own queue
 * indices). [LocalPlayer] zips this together with its authoritative track list to build a
 * [PlayerStatus].
 */
data class EngineStatus(
    val state: PlaybackState = PlaybackState.Idle,
    val queueIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val shuffle: Boolean = false,
    /**
     * A user-facing message for a *fatal* playback fault (one that can't be recovered by waiting for
     * the network — a missing/deleted file, a bad HTTP status, an undecodable stream). Null while
     * healthy or merely [PlaybackState.Reconnecting]. Surfaced once by the UI, then left to be cleared
     * by the next successful command.
     */
    val error: String? = null,
)

/** Everything the playback UI binds to. Produced by whatever [Player] is currently active. */
data class PlayerStatus(
    val current: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val state: PlaybackState = PlaybackState.Idle,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeat: RepeatMode = RepeatMode.Off,
    val shuffle: Boolean = false,
    /** Output volume in 0f..1f, or null when the active player exposes no volume control. */
    val volume: Float? = null,
    /** A user-facing message for a fatal playback fault; see [EngineStatus.error]. */
    val error: String? = null,
)
