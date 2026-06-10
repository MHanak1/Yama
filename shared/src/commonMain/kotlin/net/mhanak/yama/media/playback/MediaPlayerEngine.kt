package net.mhanak.yama.media.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Queue-aware, low-level playback engine — the only part of playback that is platform-specific.
 *
 * - **androidMain:** a Media3 [androidx.media3.common.Player] (a `MediaController` bound to a
 *   `MediaSessionService`), so the OS gets a notification, lockscreen controls and media-key handling
 *   for free, driven by the engine's own playlist.
 * - **jvmMain:** vlcj (libvlc), with the queue managed by hand.
 *
 * It is intentionally queue-aware (rather than a one-URL-at-a-time player) precisely so the Android
 * actual can hand its whole playlist to Media3. [LocalPlayer] sits on top and translates the
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
     * Choose what [setVolume]/[volume] act on: the device (media stream) volume when true, or an
     * in-app gain when false. Platforms that can't control device volume ignore this and always use
     * in-app gain.
     */
    fun setVolumeMode(useDeviceVolume: Boolean)

    fun setQueue(items: List<PlayableMedia>, startIndex: Int)
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
