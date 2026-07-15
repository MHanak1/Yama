package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.PlaybackReporting
import net.mhanak.yama.media.sources.RemoteCommand
import net.mhanak.yama.util.logger
import kotlin.time.TimeSource

/**
 * Mirrors *local* playback to the active [MusicSource]'s reporting hooks so the backend tracks
 * now-playing / play counts / resume positions, and so remote controllers can see what this device
 * is doing (essential for Jellyfin "Play On").
 *
 * Observes [localStatus] only. Reporting itself is a no-op on sources that don't support it.
 *
 * [isLocalActive] is a caller-supplied gate for suppressing reports in scenarios where [localStatus]
 * could transiently mirror a remote player. Since Phase 2 the Android engine goes directly to the
 * ExoPlayer (no `MediaController` / session bridge), so [localStatus] always reflects true local
 * state — the gate is passed as `{ true }` and the reporter's own `track != null && state in
 * ACTIVE_STATES` check handles idle periods.
 */
class PlaybackReporter(
    private val localStatus: StateFlow<PlayerStatus>,
    private val isLocalActive: () -> Boolean,
    private val source: () -> MusicSource,
    // Invoked once per track when it has been played past the scrobble threshold, with the track and the
    // position reached. Backs the offline scrobble outbox; default no-op keeps the reporter standalone.
    private val onCompletedPlay: (Track, Long) -> Unit = { _, _ -> },
    // Invoked once per track when it starts playing (the track→track transition), independent of source.
    // Backs cross-source "now playing" push (ListenBrainz); default no-op keeps the reporter standalone.
    private val onNowPlaying: (Track) -> Unit = { },
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val log = logger("Playback")

    fun start() {
        scope.launch {
            var currentTrack: Track? = null
            // Whether the current track has already crossed the "counts as a play" threshold (so the
            // completed-play callback fires at most once per track).
            var playedRecorded = false
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
                // Only report while this device is the active player; when casting, the remote device
                // reports its own playback (and localStatus may briefly mirror it — see the class doc).
                val isActive = isLocalActive() && track != null && status.state in ACTIVE_STATES
                if (!isActive) {
                    currentTrack?.let { t ->
                        try {
                            (source() as? PlaybackReporting)?.reportPlaybackStopped(t, lastPositionMs)
                        } catch (e: Throwable) {
                            log.warn("reportPlaybackStopped failed for '${t.name}'", e)
                        }
                    }
                    currentTrack = null
                    lastPaused = null
                    lastVolume = null
                    lastRepeat = null
                    lastShuffle = null
                    lastQueueIds = null
                    lastSeenPlaying = false
                    playedRecorded = false
                    return@collect
                }

                // Snapshot the running baselines before this emission overwrites them: the previous
                // track's last observed position (used to close its tracker on a track advance — Part 1)
                // and the wall-clock elapsed since the previous emission (used to spot a frozen flow
                // after device sleep / doze — Part 2). Both must be read before lines below reassign
                // `lastPositionMs` / `lastSeenMark`.
                val previousPositionMs = lastPositionMs
                val elapsedSinceLastEmission = lastSeenMark.elapsedNow()

                // Repeat-one / manual replay of the SAME track: ExoPlayer loops the item in place (same
                // id, same queueIndex, no Idle transition), so the only observable evidence of a restart
                // is positionMs wrapping back to the start after the track had already counted as a play.
                // Clear the guard so the next threshold crossing scrobbles the new listen — ListenBrainz
                // counts each repeat as its own listen. Gated on playedRecorded, so a normal backward
                // seek *before* completion can't trip it, and on same-id so a genuine A→B advance is
                // handled by the track-change branch below instead.
                val replayed = playedRecorded && track.id == currentTrack?.id &&
                    status.positionMs < TRACK_REPLAY_START_MS &&
                    previousPositionMs - status.positionMs > SEEK_REPORT_THRESHOLD_MS
                if (replayed) {
                    playedRecorded = false
                    onNowPlaying(track)
                }

                // Offline-scrobble seam: once the track has been played past the threshold, record a
                // completed play (the outbox persists it only when offline; online it's a no-op). Fires
                // at most once per track.
                if (!playedRecorded && track.id == currentTrack?.id && playedPastThreshold(status)) {
                    playedRecorded = true
                    onCompletedPlay(track, status.positionMs)
                }

                // A seek shows up only as a position that has moved further (in either direction) than
                // the elapsed wall-clock time since we last saw it would explain. Compare against that
                // expectation before overwriting our running baseline.
                val expectedPositionMs = if (lastSeenPlaying)
                    lastSeenPositionMs + elapsedSinceLastEmission.inWholeMilliseconds
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
                val lv = lastVolume; val sv = status.volume
                val volumeChanged = lv != null && sv != null &&
                    kotlin.math.abs(sv - lv) >= VOLUME_REPORT_DELTA
                // Same rationale for repeat/shuffle/queue edits: a controller mirrors these from our
                // reports, so push one immediately rather than waiting up to PROGRESS_INTERVAL_MS.
                val stateChanged = (lastRepeat != null && repeat != lastRepeat) ||
                    (lastShuffle != null && status.shuffle != lastShuffle) ||
                    (lastQueueIds != null && queueIds != lastQueueIds)

                // A gap far beyond our 5s cadence means the reporter's flow was frozen (Android doze /
                // desktop suspend) — real playback keeps emitting sub-second, so a 30s+ gap can only be
                // a suspended process, not legitimate background music.
                val largeGap = elapsedSinceLastEmission.inWholeMilliseconds > FROZEN_GAP_MS
                // The specific over-count case: the *same* track was playing when the process froze, so
                // on wake the backend would otherwise bill the whole frozen wall-clock as playback.
                val frozenGap = track.id == currentTrack?.id && lastPaused == false && largeGap

                if (track.id != currentTrack?.id) {
                    // Part 1: close the previous track's tracker with an explicit stop *before* opening
                    // the new one. Neither engine leaves ACTIVE_STATES on a track→track advance, so
                    // without this the backend's per-track tracker stays open across the boundary —
                    // which never scrobbles the finished track to server-side ListenBrainz and lets the
                    // Playback Reporting plugin emit duplicate / runaway-duration rows.
                    //
                    // BUT only when the flow wasn't frozen since that track's last report: a stop bills
                    // wall-clock up to now, so stopping a track whose last report predates a sleep would
                    // charge the entire sleep to it. On a frozen advance we skip the stop and let the
                    // server close that tracker at its last progress (correct duration) instead.
                    if (!largeGap) {
                        currentTrack?.let { prev ->
                            try {
                                (source() as? PlaybackReporting)?.reportPlaybackStopped(prev, previousPositionMs)
                            } catch (e: Throwable) {
                                log.warn("reportPlaybackStopped failed for '${prev.name}'", e)
                            }
                        }
                    }
                    try {
                        (source() as? PlaybackReporting)?.reportPlaybackStarted(track, status.positionMs, status.queue, status.volume, repeat, status.shuffle)
                    } catch (e: Throwable) {
                        log.warn("reportPlaybackStarted failed for '${track.name}'", e)
                    }
                    currentTrack = track
                    playedRecorded = false
                    lastProgress = TimeSource.Monotonic.markNow()
                    // Cross-source now-playing seam: fires only on a genuine track change (not the
                    // frozen-gap restart below, which is the *same* track already "now playing").
                    onNowPlaying(track)
                } else if (frozenGap) {
                    // Part 2: re-emit *only* a start for the same track — no stop. A fresh start for a
                    // still-open key makes the Playback Reporting plugin commit the pre-gap segment at
                    // its last real progress (NOT billed the frozen wall-clock) and open a clean new
                    // interval at the current position. A stop here would instead bill the whole gap, so
                    // it is deliberately omitted. `playedRecorded` is left untouched (unlike a real track
                    // change) so the restart can't re-fire the completed-play callback for a track that
                    // already counted. Net effect: a listen fragmented by a sleep becomes two correct
                    // rows rather than one multi-hour phantom row.
                    try {
                        (source() as? PlaybackReporting)?.reportPlaybackStarted(track, status.positionMs, status.queue, status.volume, repeat, status.shuffle)
                    } catch (e: Throwable) {
                        log.warn("frozen-gap restart failed for '${track.name}'", e)
                    }
                    lastProgress = TimeSource.Monotonic.markNow()
                } else if (paused != lastPaused || volumeChanged || stateChanged || seeked ||
                    lastProgress.elapsedNow().inWholeMilliseconds >= PROGRESS_INTERVAL_MS
                ) {
                    try {
                        (source() as? PlaybackReporting)?.reportPlaybackProgress(track, status.positionMs, paused, status.queue, status.volume, repeat, status.shuffle)
                    } catch (e: Throwable) {
                        log.warn("reportPlaybackProgress failed for '${track.name}'", e)
                    }
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
        // How close to the start a same-track position wrap must land to count as a repeat/replay (vs.
        // an ordinary backward seek that stays mid-track). Together with a backward jump larger than
        // SEEK_REPORT_THRESHOLD_MS, this distinguishes a repeat-one loop from a normal scrub.
        const val TRACK_REPLAY_START_MS = 3_000L
        // A gap between emissions larger than this means the reporter's flow was frozen (device sleep /
        // doze / suspend) rather than genuinely playing. Sits well above the 5s progress cadence + any
        // scheduling jitter, so live playback never trips it but a suspended process always does.
        const val FROZEN_GAP_MS = 30_000L
    }
}

// A track counts as "played" (scrobble-worthy) once it reaches half its length or this many ms,
// whichever is smaller — the usual scrobble rule, with a fallback for unknown durations.
private const val COMPLETED_PLAY_MS = 240_000L

private fun playedPastThreshold(status: PlayerStatus): Boolean {
    val duration = status.durationMs
    val threshold = if (duration > 0) minOf(duration / 2, COMPLETED_PLAY_MS) else COMPLETED_PLAY_MS
    return status.positionMs >= threshold
}

// Map the playback-layer repeat mode onto the source-layer command enum the reporting hooks take
// (keeping MusicSource free of media.playback types).
private fun RepeatMode.toRemote(): RemoteCommand.Repeat = when (this) {
    RepeatMode.Off -> RemoteCommand.Repeat.Off
    RepeatMode.All -> RemoteCommand.Repeat.All
    RepeatMode.One -> RemoteCommand.Repeat.One
}
