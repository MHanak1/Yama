package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.RemoteCommand
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
            var lastRepeat: RemoteCommand.Repeat? = null
            var lastShuffle: Boolean? = null
            var lastQueueIds: List<String>? = null
            var lastProgress = TimeSource.Monotonic.markNow()
            // Track the last observed position and when we saw it, so a seek (a position jump that
            // real-time playback can't account for) can be told apart from normal advance and reported
            // at once — a controller mirrors our position from these reports, so otherwise it wouldn't
            // see the jump until the next PROGRESS_INTERVAL_MS tick.
            var lastSeenPositionMs = 0L
            var lastSeenMark = TimeSource.Monotonic.markNow()
            var lastSeenPlaying = false

            localStatus.collect { status ->
                val track = status.current
                val isActive = track != null && status.state in ACTIVE_STATES
                if (!isActive) {
                    currentTrack?.let { source().reportPlaybackStopped(it, lastPositionMs) }
                    currentTrack = null
                    lastPaused = null
                    lastVolume = null
                    lastRepeat = null
                    lastShuffle = null
                    lastQueueIds = null
                    lastSeenPlaying = false
                    return@collect
                }
                track!!

                // A seek shows up only as a position that has moved further (in either direction) than
                // the elapsed wall-clock time since we last saw it would explain. Compare against that
                // expectation before overwriting our running baseline.
                val expectedPositionMs = if (lastSeenPlaying)
                    lastSeenPositionMs + lastSeenMark.elapsedNow().inWholeMilliseconds
                else lastSeenPositionMs
                val seeked = track.id == currentTrack?.id &&
                    kotlin.math.abs(status.positionMs - expectedPositionMs) > SEEK_REPORT_THRESHOLD_MS
                lastSeenPositionMs = status.positionMs
                lastSeenMark = TimeSource.Monotonic.markNow()
                lastSeenPlaying = status.isPlaying

                lastPositionMs = status.positionMs
                val paused = !status.isPlaying
                val repeat = status.repeat.toRemote()
                val queueIds = status.queue.map { it.id }
                // Report a volume change promptly (not just on the 5s tick) so a controller driving this
                // device sees the new level quickly.
                val volumeChanged = lastVolume != null && status.volume != null &&
                    kotlin.math.abs(status.volume!! - lastVolume!!) >= VOLUME_REPORT_DELTA
                // Same rationale for repeat/shuffle/queue edits: a controller mirrors these from our
                // reports, so push one immediately rather than waiting up to PROGRESS_INTERVAL_MS.
                val stateChanged = (lastRepeat != null && repeat != lastRepeat) ||
                    (lastShuffle != null && status.shuffle != lastShuffle) ||
                    (lastQueueIds != null && queueIds != lastQueueIds)

                if (track.id != currentTrack?.id) {
                    source().reportPlaybackStarted(track, status.positionMs, status.queue, status.volume, repeat, status.shuffle)
                    currentTrack = track
                    lastProgress = TimeSource.Monotonic.markNow()
                } else if (paused != lastPaused || volumeChanged || stateChanged || seeked ||
                    lastProgress.elapsedNow().inWholeMilliseconds >= PROGRESS_INTERVAL_MS
                ) {
                    source().reportPlaybackProgress(track, status.positionMs, paused, status.queue, status.volume, repeat, status.shuffle)
                    lastProgress = TimeSource.Monotonic.markNow()
                } else {
                    return@collect
                }
                lastPaused = paused
                lastVolume = status.volume
                lastRepeat = repeat
                lastShuffle = status.shuffle
                lastQueueIds = queueIds
            }
        }
    }

    private companion object {
        val ACTIVE_STATES = setOf(PlaybackState.Playing, PlaybackState.Paused, PlaybackState.Buffering)
        const val PROGRESS_INTERVAL_MS = 5_000L
        // Minimum volume change (fraction) that triggers an out-of-band progress report.
        const val VOLUME_REPORT_DELTA = 0.01f
        // How far the position may diverge from where steady playback would put it before we treat it
        // as a seek and report immediately. Above the engine's status cadence + jitter, below a small
        // deliberate scrub, so normal advance never trips it but a real seek does.
        const val SEEK_REPORT_THRESHOLD_MS = 1_500L
    }
}

// Map the playback-layer repeat mode onto the source-layer command enum the reporting hooks take
// (keeping MusicSource free of media.playback types).
private fun RepeatMode.toRemote(): RemoteCommand.Repeat = when (this) {
    RepeatMode.Off -> RemoteCommand.Repeat.Off
    RepeatMode.All -> RemoteCommand.Repeat.All
    RepeatMode.One -> RemoteCommand.Repeat.One
}
