package net.mhanak.yama.media.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Queue-aware, low-level playback engine — the only part of playback that is platform-specific.
 *
 * - **androidMain:** drives the [androidx.media3.exoplayer.ExoPlayer] hosted by `PlaybackService`
 *   directly (in-process, no IPC hop). The OS notification / lockscreen / media keys come from the
 *   [androidx.media3.session.MediaSession] in `PlaybackService`, which is kept separate from the
 *   engine's direct ExoPlayer access (Phase 2 two-axis model — see PLAYBACK_PLAN.md). [status]
 *   always reflects true local decode state, never the remote bridge the session may be showing.
 * - **jvmMain:** vlcj (libvlc), with the queue managed by hand.
 *
 * It is intentionally queue-aware (rather than a one-URL-at-a-time player) precisely so the Android
 * actual can hand its whole playlist to the ExoPlayer. [LocalPlayer] sits on top and translates the
 * domain-level [net.mhanak.yama.media.model.Track] queue into [PlayableMedia] for the engine.
 *
 * Constructed with a no-arg constructor; platform actuals obtain whatever they need internally (the
 * Android actual reaches `MyApplication.appContext`, mirroring `SecureStorage`).
 */
expect class MediaPlayerEngine() {
    val status: StateFlow<EngineStatus>

    /** Output volume in 0f..1f. */
    val volume: StateFlow<Float>
    fun setVolume(level: Float)

    /**
     * Whether [setVolume]/[volume] are currently acting on the OS/device media-stream volume (true)
     * rather than an in-app gain (false). Reflects the *actual* mode, not just the requested one: it's
     * false when device-volume control was asked for but isn't available, and always false on platforms
     * with no device-volume concept. The UI uses it to decide whether the OS will show its own volume
     * panel (so it can suppress the in-app indicator/slider).
     */
    val controlsSystemVolume: StateFlow<Boolean>

    /**
     * Choose what [setVolume]/[volume] act on: the device (media stream) volume when true, or an
     * in-app gain when false. Platforms that can't control device volume ignore this and always use
     * in-app gain.
     */
    fun setVolumeMode(useDeviceVolume: Boolean)

    fun setQueue(items: List<PlayableMedia>, startIndex: Int)
    /** Like [setQueue] but stays paused — loads the queue in a ready state without starting playback. */
    fun loadQueue(items: List<PlayableMedia>, startIndex: Int)
    fun addToQueue(items: List<PlayableMedia>)
    fun addNext(items: List<PlayableMedia>)
    fun removeAt(index: Int)
    fun move(from: Int, to: Int)
    fun clear()

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun seekToIndex(index: Int)

    fun setRepeat(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)

    fun release()
}
