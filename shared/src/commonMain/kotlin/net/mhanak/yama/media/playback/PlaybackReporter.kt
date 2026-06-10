package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.MusicSource
import kotlin.time.TimeSource

/**
 * Mirrors *local* playback to the active [MusicSource]'s reporting hooks so the backend tracks
 * now-playing / play counts / resume positions, and so remote controllers can see what this device
 * is doing (essential for Jellyfin "Play On").
 *
 * Observes [localStatus] only — when we control a remote device the local player is idle, so nothing
 * is reported here (the remote device reports its own playback). Reporting itself is a no-op on
 * sources that don't support it.
 */
class PlaybackReporter(
    private val localStatus: StateFlow<PlayerStatus>,
    private val source: () -> MusicSource,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            var currentTrack: Track? = null
            var lastPositionMs = 0L
            var lastPaused: Boolean? = null
            var lastVolume: Float? = null
            var lastProgress = TimeSource.Monotonic.markNow()

            localStatus.collect { status ->
                val track = status.current
                val isActive = track != null && status.state in ACTIVE_STATES
                if (!isActive) {
                    currentTrack?.let { source().reportPlaybackStopped(it, lastPositionMs) }
                    currentTrack = null
                    lastPaused = null
                    lastVolume = null
                    return@collect
                }
                track!!
                lastPositionMs = status.positionMs
                val paused = !status.isPlaying
                // Report a volume change promptly (not just on the 5s tick) so a controller driving this
                // device sees the new level quickly.
                val volumeChanged = lastVolume != null && status.volume != null &&
                    kotlin.math.abs(status.volume!! - lastVolume!!) >= VOLUME_REPORT_DELTA

                if (track.id != currentTrack?.id) {
                    source().reportPlaybackStarted(track, status.positionMs, status.queue, status.volume)
                    currentTrack = track
                    lastPaused = paused
                    lastVolume = status.volume
                    lastProgress = TimeSource.Monotonic.markNow()
                } else if (paused != lastPaused || volumeChanged ||
                    lastProgress.elapsedNow().inWholeMilliseconds >= PROGRESS_INTERVAL_MS
                ) {
                    source().reportPlaybackProgress(track, status.positionMs, paused, status.queue, status.volume)
                    lastPaused = paused
                    lastVolume = status.volume
                    lastProgress = TimeSource.Monotonic.markNow()
                }
            }
        }
    }

    private companion object {
        val ACTIVE_STATES = setOf(PlaybackState.Playing, PlaybackState.Paused, PlaybackState.Buffering)
        const val PROGRESS_INTERVAL_MS = 5_000L
        // Minimum volume change (fraction) that triggers an out-of-band progress report.
        const val VOLUME_REPORT_DELTA = 0.01f
    }
}
