package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.toTrack
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.SessionInfoDto
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * A [Player] that controls *another* Jellyfin session ("cast" / Play On a different device). It owns
 * no queue of its own: transport calls become Jellyfin session commands, and [status] is derived
 * purely from the live [sessions] push (the same `SessionsMessage` stream that powers the cast list),
 * so the UI reflects whatever the remote device reports.
 *
 * Created by [net.mhanak.yama.media.sources.JellyfinSource.createPlayer] and swapped into `PlaybackController.active`; the controller
 * calls [release] when switching away.
 */
class JellyfinRemotePlayer(
    private val api: ApiClient,
    sessions: StateFlow<List<SessionInfoDto>>,
    private val target: RemoteTarget,
    private val controllingUserId: UUID?,
    // Pulls a fresh session snapshot over REST, used by [refresh] to recover from a stale live push.
    private val resync: suspend () -> Unit,
) : Player {
    override val displayName: String = target.name

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // The remote device's actual state, as reported via the live session push.
    private val reportedStatus: StateFlow<PlayerStatus> =
        sessions
            .map { list -> list.find { it.id == target.id }?.toPlayerStatus() ?: PlayerStatus() }
            .stateIn(scope, SharingStarted.Eagerly, PlayerStatus())

    // Optimistic play/pause override. A transport command round-trips through the server and back as a
    // session push before [reportedStatus] reflects it, which would leave the play/pause button stale
    // for that whole window. We apply the intended value immediately and drop it once the device's
    // report confirms it (or [OPTIMISTIC_TIMEOUT_MS] elapses, in case the command was dropped).
    private val optimisticPlaying = MutableStateFlow<Boolean?>(null)
    private var optimisticClearJob: Job? = null

    // Optimistic volume override, same rationale as [optimisticPlaying] plus one more: hardware-key
    // spam must accumulate, so each step reads the *intended* level rather than the lagging report
    // (otherwise every press from the same ~1s window converges on base+one-step). Cleared once the
    // device reports a matching level (or after the timeout).
    private val optimisticVolume = MutableStateFlow<Float?>(null)
    private var optimisticVolumeClearJob: Job? = null

    // Optimistic repeat/shuffle overrides, same rationale as [optimisticPlaying]: the toggle round-trips
    // through the server and back as a session push before [reportedStatus] reflects it, so we show the
    // intended value immediately and drop it once the device's report confirms it (or the timeout).
    private val optimisticRepeat = MutableStateFlow<RepeatMode?>(null)
    private var optimisticRepeatClearJob: Job? = null
    private val optimisticShuffle = MutableStateFlow<Boolean?>(null)
    private var optimisticShuffleClearJob: Job? = null

    // Optimistic now-playing override, same rationale as [optimisticPlaying] but for the queue/track a
    // PLAY_NOW selects: a `skipTo`/`playNow`/queue-edit round-trips through the server and back as a
    // session push before [reportedStatus] reflects it, so without this the controller keeps showing
    // the *old* track for that whole window (seconds, sometimes far longer if the server's push lags).
    // We apply the intended queue immediately and drop it once the device reports that track playing
    // (or the timeout). [resyncBurst] runs alongside to pull the real state in quickly.
    private val optimisticNowPlaying = MutableStateFlow<OptimisticNowPlaying?>(null)
    private var optimisticNowPlayingClearJob: Job? = null

    // Optimistic seek override. A SEEK round-trips through the server and back as a session push before
    // [reportedStatus] reflects it; until then the seek bar snaps back to the *old* reported position
    // (the UI's smoothing re-anchors to it), so a seek looks unresponsive for that window. We apply the
    // seeked position immediately — the UI's per-frame extrapolation advances from it — and drop it once
    // the device reports a position consistent with the seek (or after the timeout). Unlike the other
    // overrides we can't match a fixed value: position keeps advancing, so [seekMark] tracks when the
    // seek was issued and the clear compares against target+elapsed (see the collector below).
    private val optimisticPosition = MutableStateFlow<Long?>(null)
    private var optimisticPositionClearJob: Job? = null
    private var seekMark: TimeSource.Monotonic.ValueTimeMark? = null

    // The "what's playing / where" group: the reported state with the now-playing and seek overrides
    // applied. Split out from the toggle overrides below only because `combine` has no typed overload
    // past five flows; position is applied last so a seek wins over the (usually 0) now-playing anchor.
    private val playbackStatus =
        combine(reportedStatus, optimisticNowPlaying, optimisticPosition) { reported, nowPlaying, position ->
            var result = reported
            if (nowPlaying != null) {
                result = result.copy(
                    current = nowPlaying.current,
                    queue = nowPlaying.queue,
                    queueIndex = nowPlaying.queueIndex,
                    positionMs = nowPlaying.positionMs,
                    durationMs = nowPlaying.current?.durationTicks?.let { it / 10_000 } ?: 0L,
                    state = PlaybackState.Playing,
                    isPlaying = true,
                )
            }
            if (position != null) result = result.copy(positionMs = position)
            result
        }

    override val status: StateFlow<PlayerStatus> =
        combine(
            playbackStatus,
            optimisticPlaying,
            optimisticRepeat,
            optimisticShuffle,
        ) { base, playing, repeat, shuffle ->
            var result = base
            if (playing != null && playing != result.isPlaying) {
                result = result.copy(
                    isPlaying = playing,
                    state = if (playing) PlaybackState.Playing else PlaybackState.Paused,
                )
            }
            if (repeat != null) result = result.copy(repeat = repeat)
            if (shuffle != null) result = result.copy(shuffle = shuffle)
            result
        }.stateIn(scope, SharingStarted.Eagerly, PlayerStatus())

    init {
        // Clear each override as soon as the reported state catches up to it.
        scope.launch {
            reportedStatus.collect { reported ->
                if (optimisticPlaying.value == reported.isPlaying) {
                    optimisticClearJob?.cancel()
                    optimisticPlaying.value = null
                }
                val ov = optimisticVolume.value
                val rv = reported.volume
                if (ov != null && rv != null && abs(rv - ov) < VOLUME_EPSILON) {
                    optimisticVolumeClearJob?.cancel()
                    optimisticVolume.value = null
                }
                if (optimisticRepeat.value == reported.repeat) {
                    optimisticRepeatClearJob?.cancel()
                    optimisticRepeat.value = null
                }
                if (optimisticShuffle.value == reported.shuffle) {
                    optimisticShuffleClearJob?.cancel()
                    optimisticShuffle.value = null
                }
                // Drop the now-playing override once the device reports the track we asked it to play
                // (its queue/position are then authoritative). A different reported track means someone
                // else changed it; let the timeout clear ours so we don't fight them indefinitely.
                val onp = optimisticNowPlaying.value
                if (onp != null && reported.current?.id == onp.current?.id) {
                    optimisticNowPlayingClearJob?.cancel()
                    optimisticNowPlaying.value = null
                }
                // Drop the seek override once the device reports a position consistent with the seek.
                // Position advances, so "consistent" means within tolerance of target + however long the
                // device has been playing since the seek — not the fixed target. A report still near the
                // *old* position (the device hasn't acted yet) stays far from this and keeps the override.
                val op = optimisticPosition.value
                val mark = seekMark
                if (op != null && mark != null) {
                    val expected = op + if (reported.isPlaying) mark.elapsedNow().inWholeMilliseconds else 0L
                    if (abs(reported.positionMs - expected) < SEEK_RESYNC_EPSILON_MS) {
                        optimisticPositionClearJob?.cancel()
                        optimisticPosition.value = null
                    }
                }
            }
        }
    }

    private fun setOptimisticNowPlaying(value: OptimisticNowPlaying) {
        optimisticNowPlaying.value = value
        optimisticNowPlayingClearJob?.cancel()
        optimisticNowPlayingClearJob = scope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            optimisticNowPlaying.value = null
        }
        // A new now-playing track carries its own (usually 0) position; a lingering seek target from the
        // previous track would otherwise force the seek bar to the wrong spot.
        optimisticPositionClearJob?.cancel()
        optimisticPosition.value = null
    }

    private fun setOptimisticPosition(positionMs: Long) {
        optimisticPosition.value = positionMs
        seekMark = TimeSource.Monotonic.markNow()
        optimisticPositionClearJob?.cancel()
        optimisticPositionClearJob = scope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            optimisticPosition.value = null
        }
    }

    // The server's live session push can lag well behind a queue-changing command we just sent (that
    // lag is what leaves the controller's now-playing stale), so pull fresh snapshots over REST a few
    // times right after the command. The authoritative state — and thus the optimistic-overlay clear —
    // then catches up in about a second instead of waiting for the next push.
    private fun resyncBurst() {
        scope.launch {
            repeat(RESYNC_BURST_COUNT) {
                delay(RESYNC_BURST_INTERVAL_MS)
                runCatching { resync() }
            }
        }
    }

    private fun setOptimisticPlaying(playing: Boolean) {
        optimisticPlaying.value = playing
        optimisticClearJob?.cancel()
        optimisticClearJob = scope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            optimisticPlaying.value = null
        }
    }

    private fun SessionInfoDto.toPlayerStatus(): PlayerStatus {
        // NowPlayingQueue is the device's authoritative *ordered* queue; NowPlayingQueueFullItems carries
        // the item details. The server only rebuilds the full-items list when the set of items changes,
        // so it goes stale on a pure reorder — resolve the details in NowPlayingQueue order so a reorder
        // on the controlled device (or one we just pushed) is reflected here.
        val fullItems = nowPlayingQueueFullItems
        val rawOrder = nowPlayingQueue
        val queue = if (!rawOrder.isNullOrEmpty() && !fullItems.isNullOrEmpty()) {
            val byId = fullItems.associateBy { it.id }
            rawOrder.mapNotNull { entry -> byId[entry.id]?.let { api.toTrack(it) } }
        } else {
            fullItems?.map { api.toTrack(it) } ?: emptyList()
        }
        val current = nowPlayingItem?.let { api.toTrack(it) }
        val paused = playState?.isPaused ?: false
        return PlayerStatus(
            current = current,
            queue = queue,
            queueIndex = current?.let { c -> queue.indexOfFirst { it.id == c.id } } ?: -1,
            state = when {
                current == null -> PlaybackState.Idle
                paused -> PlaybackState.Paused
                else -> PlaybackState.Playing
            },
            isPlaying = current != null && !paused,
            // Jellyfin ticks are 100-nanosecond units → milliseconds.
            positionMs = (playState?.positionTicks ?: 0L) / 10_000,
            durationMs = current?.durationTicks?.let { it / 10_000 } ?: 0L,
            // Jellyfin reports volume 0–100; map to the 0f..1f the UI uses.
            volume = playState?.volumeLevel?.let { (it / 100f).coerceIn(0f, 1f) },
            repeat = when (playState?.repeatMode) {
                org.jellyfin.sdk.model.api.RepeatMode.REPEAT_ALL -> RepeatMode.All
                org.jellyfin.sdk.model.api.RepeatMode.REPEAT_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            shuffle = playState?.playbackOrder == PlaybackOrder.SHUFFLE,
        )
    }

    private fun play(command: PlayCommand, tracks: List<Track>, startIndex: Int = 0, startPositionTicks: Long? = null) {
        if (tracks.isEmpty()) return
        // PLAY_NOW replaces the queue and the now-playing track; reflect that immediately rather than
        // waiting for the round-trip. (PLAY_NEXT/PLAY_LAST only append, leaving the current track — and
        // thus the visible now-playing — unchanged, so they need no override.)
        if (command == PlayCommand.PLAY_NOW) {
            val index = startIndex.coerceIn(0, tracks.lastIndex)
            setOptimisticNowPlaying(
                OptimisticNowPlaying(
                    current = tracks[index],
                    queue = tracks,
                    queueIndex = index,
                    positionMs = (startPositionTicks ?: 0L) / 10_000,
                ),
            )
        }
        scope.launch {
            runCatching {
                api.sessionApi.play(
                    sessionId = target.id,
                    playCommand = command,
                    itemIds = tracks.map { UUID.fromString(it.id) },
                    startIndex = startIndex,
                    startPositionTicks = startPositionTicks,
                )
            }
        }
        resyncBurst()
    }

    private fun playstate(command: PlaystateCommand, seekPositionTicks: Long? = null) {
        scope.launch {
            runCatching {
                api.sessionApi.sendPlaystateCommand(
                    sessionId = target.id,
                    command = command,
                    seekPositionTicks = seekPositionTicks,
                    controllingUserId = controllingUserId?.toString(),
                )
            }
        }
    }

    private fun generalCommand(name: GeneralCommandType, arguments: Map<String, String?>) {
        val userId = controllingUserId ?: return
        scope.launch {
            runCatching {
                api.sessionApi.sendFullGeneralCommand(
                    sessionId = target.id,
                    data = GeneralCommand(name = name, controllingUserId = userId, arguments = arguments),
                )
            }
        }
    }

    override fun playNow(tracks: List<Track>, startIndex: Int) = play(PlayCommand.PLAY_NOW, tracks, startIndex)
    override fun playNext(tracks: List<Track>) = play(PlayCommand.PLAY_NEXT, tracks)
    override fun addToQueue(tracks: List<Track>) = play(PlayCommand.PLAY_LAST, tracks)

    override fun play() {
        setOptimisticPlaying(true)
        playstate(PlaystateCommand.UNPAUSE)
    }

    override fun pause() {
        setOptimisticPlaying(false)
        playstate(PlaystateCommand.PAUSE)
    }

    override fun stop() {
        setOptimisticPlaying(false)
        playstate(PlaystateCommand.STOP)
    }

    override fun togglePlayPause() {
        setOptimisticPlaying(!status.value.isPlaying)
        playstate(PlaystateCommand.PLAY_PAUSE)
    }

    // next/previous change the now-playing track too, but which track the device lands on depends on
    // its repeat/shuffle state, so we can't safely predict it for an optimistic override — instead just
    // pull the real state in quickly so the visible track doesn't lag the device by the push interval.
    override fun next() { playstate(PlaystateCommand.NEXT_TRACK); resyncBurst() }
    override fun previous() { playstate(PlaystateCommand.PREVIOUS_TRACK); resyncBurst() }
    override fun seekTo(positionMs: Long) {
        setOptimisticPosition(positionMs)
        playstate(PlaystateCommand.SEEK, positionMs * 10_000)
    }

    // Jellyfin has no "skip to queue index" command; replay the current queue starting at [index].
    override fun skipTo(index: Int) {
        val queue = status.value.queue
        if (index in queue.indices) play(PlayCommand.PLAY_NOW, queue, index)
    }

    override fun setRepeat(mode: RepeatMode) {
        setOptimisticRepeat(mode)
        val value = when (mode) {
            RepeatMode.Off -> org.jellyfin.sdk.model.api.RepeatMode.REPEAT_NONE
            RepeatMode.All -> org.jellyfin.sdk.model.api.RepeatMode.REPEAT_ALL
            RepeatMode.One -> org.jellyfin.sdk.model.api.RepeatMode.REPEAT_ONE
        }
        generalCommand(GeneralCommandType.SET_REPEAT_MODE, mapOf("RepeatMode" to value.serialName))
    }

    override fun setShuffle(enabled: Boolean) {
        setOptimisticShuffle(enabled)
        // The SetShuffleQueue command's "ShuffleMode" argument is a GroupShuffleMode serial name
        // ("Shuffle"/"Sorted"), not the PlaybackOrder one ("Shuffle"/"Default") that nowPlaying reports.
        val mode = if (enabled) GroupShuffleMode.SHUFFLE else GroupShuffleMode.SORTED
        generalCommand(GeneralCommandType.SET_SHUFFLE_QUEUE, mapOf("ShuffleMode" to mode.serialName))
    }

    private fun setOptimisticRepeat(mode: RepeatMode) {
        optimisticRepeat.value = mode
        optimisticRepeatClearJob?.cancel()
        optimisticRepeatClearJob = scope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            optimisticRepeat.value = null
        }
    }

    private fun setOptimisticShuffle(enabled: Boolean) {
        optimisticShuffle.value = enabled
        optimisticShuffleClearJob?.cancel()
        optimisticShuffleClearJob = scope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            optimisticShuffle.value = null
        }
    }

    // The session API has no in-place queue reorder/remove, so — like [skipTo] — we replay the edited
    // queue with PLAY_NOW starting on whatever index the current track lands at. When the current track
    // survives the edit we resume it at its reported position (so it only jumps back by the report lag,
    // a few seconds, instead of restarting); when the edit removes the current track, playback advances
    // to its replacement from the start.
    override fun removeAt(index: Int) {
        val queue = status.value.queue.toMutableList()
        if (index !in queue.indices) return
        val current = status.value.queueIndex
        queue.removeAt(index)
        if (queue.isEmpty()) return clearQueue()
        if (index == current) {
            // Removing the playing track: advance to whatever now sits at that index, from the start.
            play(PlayCommand.PLAY_NOW, queue, current.coerceIn(0, queue.lastIndex))
        } else {
            // The current track shifts down by one if a track before it was removed; resume it in place.
            val newCurrent = if (index < current) current - 1 else current
            play(PlayCommand.PLAY_NOW, queue, newCurrent.coerceIn(0, queue.lastIndex), resumeTicks())
        }
    }

    override fun move(from: Int, to: Int) {
        val queue = status.value.queue.toMutableList()
        if (from !in queue.indices || to !in queue.indices) return
        val current = status.value.queueIndex
        queue.add(to, queue.removeAt(from))
        // Track where the currently-playing track ends up so playback continues on it, in place.
        val newCurrent = when {
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }
        play(PlayCommand.PLAY_NOW, queue, newCurrent.coerceIn(0, queue.lastIndex), resumeTicks())
    }

    // The current playback position as Jellyfin ticks (100-ns units), for resuming the current track
    // after a replay-based queue edit.
    private fun resumeTicks(): Long = status.value.positionMs * 10_000

    override fun clearQueue() = playstate(PlaystateCommand.STOP)

    override val volume: StateFlow<Float?> =
        combine(reportedStatus, optimisticVolume) { reported, optimistic ->
            optimistic ?: reported.volume
        }.stateIn(scope, SharingStarted.Eagerly, null)

    // Controllable if the device advertises a volume command (Yama does, when remote control is on) or
    // reports a level. Capability-based so a device that accepts volume commands but never reports a
    // level is still treated as controllable (so hardware keys engage).
    override val volumeControllable: StateFlow<Boolean> =
        sessions.map { list ->
            val session = list.find { it.id == target.id }
            val commands = session?.supportedCommands.orEmpty()
            session?.playState?.volumeLevel != null ||
                GeneralCommandType.SET_VOLUME in commands ||
                GeneralCommandType.VOLUME_UP in commands ||
                GeneralCommandType.VOLUME_DOWN in commands
        }.stateIn(scope, SharingStarted.Eagerly, false)

    override fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        optimisticVolume.value = clamped
        optimisticVolumeClearJob?.cancel()
        optimisticVolumeClearJob = scope.launch {
            delay(OPTIMISTIC_TIMEOUT_MS)
            optimisticVolume.value = null
        }
        // SET_VOLUME with an explicit level is honoured far more widely than the VOLUME_UP/DOWN step
        // commands (which many clients ignore), so we always send an absolute level.
        generalCommand(GeneralCommandType.SET_VOLUME, mapOf("Volume" to (clamped * 100).toInt().toString()))
    }

    // When the device reports a level, step it with an absolute SET_VOLUME (reliable, accumulates via
    // the optimistic overlay). When it doesn't, fall back to the device's native step command so keys
    // still work on a device that accepts volume commands without reporting a level.
    override fun volumeUp() {
        val level = volume.value
        if (level != null) setVolume(level + VOLUME_STEP)
        else generalCommand(GeneralCommandType.VOLUME_UP, emptyMap())
    }

    override fun volumeDown() {
        val level = volume.value
        if (level != null) setVolume(level - VOLUME_STEP)
        else generalCommand(GeneralCommandType.VOLUME_DOWN, emptyMap())
    }

    override fun refresh() {
        scope.launch { runCatching { resync() } }
    }

    override fun release() {
        scope.cancel()
    }

    // The queue/track a PLAY_NOW selects, held as an optimistic override until the device reports it.
    private data class OptimisticNowPlaying(
        val current: Track?,
        val queue: List<Track>,
        val queueIndex: Int,
        val positionMs: Long,
    )

    private companion object {
        // Safety net so a dropped transport command can't pin the play/pause button to the wrong
        // state indefinitely; a healthy session push normally clears the override well before this.
        const val OPTIMISTIC_TIMEOUT_MS = 4_000L

        // Reported volume is coarse (integer percent); treat anything within this of the optimistic
        // level as "caught up" so the overlay clears cleanly.
        const val VOLUME_EPSILON = 0.02f

        // How close a reported position must be to (seek target + elapsed) to count as the device having
        // honoured the seek, clearing the override. Wide enough to absorb report travel/interval lag
        // (a couple of seconds); a pre-seek report still near the old position stays well outside it.
        const val SEEK_RESYNC_EPSILON_MS = 2_500L

        // After a queue-changing command, pull the session over REST this many times at this spacing so
        // the real now-playing arrives within ~a second rather than on the (sometimes much slower) push.
        const val RESYNC_BURST_COUNT = 4
        const val RESYNC_BURST_INTERVAL_MS = 500L
    }
}