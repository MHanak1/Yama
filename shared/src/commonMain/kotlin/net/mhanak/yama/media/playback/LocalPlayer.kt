package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.MusicSource
import kotlin.math.abs

/**
 * Plays on this device. Owns the authoritative [Track] queue and drives a [MediaPlayerEngine]; the
 * engine only ever sees [PlayableMedia], so all domain knowledge stays here.
 *
 * Stream/artwork URLs are resolved lazily through [source] (for Jellyfin these are just built strings,
 * so resolution is cheap). [PlayerStatus] is derived by combining the engine's index-based
 * [EngineStatus] with the track list this class holds.
 */
class LocalPlayer(
    private val engine: MediaPlayerEngine,
    private val source: () -> MusicSource,
) : Player {
    override val displayName: String = "This device"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val tracks = MutableStateFlow<List<Track>>(emptyList())

    // A queue change driven by a suspend URL-resolution; cancel an in-flight one when a newer
    // playNow arrives so the latest request wins.
    private var queueJob: Job? = null

    override val status: StateFlow<PlayerStatus> =
        combine(tracks, engine.status, engine.volume) { trackList, engineStatus, vol ->
            PlayerStatus(
                current = trackList.getOrNull(engineStatus.queueIndex),
                queue = trackList,
                queueIndex = engineStatus.queueIndex,
                state = engineStatus.state,
                isPlaying = engineStatus.isPlaying,
                positionMs = engineStatus.positionMs,
                durationMs = engineStatus.durationMs,
                repeat = engineStatus.repeat,
                shuffle = engineStatus.shuffle,
                volume = vol,
            )
        }.stateIn(scope, SharingStarted.Eagerly, PlayerStatus())

    private suspend fun Track.toPlayable(): PlayableMedia {
        val src = source()
        return PlayableMedia(
            id = id,
            uri = src.getStreamUrl(id),
            title = name,
            artist = artists?.joinToString(", "),
            album = album,
            artworkUri = imageUrl ?: src.getArtworkUrl(id),
            // Jellyfin run-time ticks are 100-nanosecond units → milliseconds.
            durationMs = durationTicks?.let { it / 10_000 },
        )
    }

    override fun playNow(tracks: List<Track>, startIndex: Int) {
        queueJob?.cancel()
        queueJob = scope.launch {
            val items = tracks.map { it.toPlayable() }
            this@LocalPlayer.tracks.value = tracks
            engine.setQueue(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        }
    }

    /**
     * Apply a queue + start position pushed by a remote controller ("Play On this device"). When the
     * push is really just a reorder/removal of the queue we're already playing — same now-playing
     * track, a start position within [REORDER_TOLERANCE_MS] of where we are, and no new tracks — we
     * rearrange the live queue in place (via the engine's move/removeAt, which don't interrupt the
     * current item) instead of restarting playback. Otherwise it's a fresh play and we (re)load it.
     *
     * Returns false if playback was (re)started, true if it was applied as an in-place edit — the
     * caller uses this to avoid popping the full player open on every remote queue tweak.
     */
    fun applyRemotePlay(tracks: List<Track>, startIndex: Int, startPositionMs: Long): Boolean {
        if (canReorderInPlace(tracks, startIndex, startPositionMs)) {
            reorderInPlace(tracks)
            return true
        }
        playNow(tracks, startIndex)
        return false
    }

    private fun canReorderInPlace(target: List<Track>, startIndex: Int, startPositionMs: Long): Boolean {
        val current = status.value
        val currentTrack = current.current ?: return false
        if (startIndex !in target.indices) return false
        // Same now-playing song...
        if (target[startIndex].id != currentTrack.id) return false
        // ...at a similar position (otherwise it's a deliberate restart/seek, not a reorder).
        if (abs(startPositionMs - current.positionMs) > REORDER_TOLERANCE_MS) return false
        // The playing track must be unambiguous so a removal/move can't accidentally drop it.
        if (current.queue.count { it.id == currentTrack.id } != 1) return false
        // A reorder/removal never introduces new tracks; anything else is a genuine new queue.
        val have = current.queue.groupingBy { it.id }.eachCount()
        return target.groupingBy { it.id }.eachCount().all { (id, n) -> (have[id] ?: 0) >= n }
    }

    /**
     * Reconcile the live engine queue to [target] (a reorder/subset of the current queue) using
     * in-place moves/removes, leaving the currently-playing item untouched. Precondition (checked by
     * [canReorderInPlace]): the current track is unique and [target] adds no tracks.
     */
    private fun reorderInPlace(target: List<Track>) {
        val work = tracks.value.toMutableList()
        val currentIndex = status.value.queueIndex
        val currentId = work.getOrNull(currentIndex)?.id

        // Removals: keep the first `target` count of each id, but always keep the playing item.
        val budget = target.groupingBy { it.id }.eachCount().toMutableMap()
        val toRemove = mutableListOf<Int>()
        for (i in work.indices) {
            val id = work[i].id
            if (i == currentIndex && id == currentId) { budget[id] = (budget[id] ?: 0) - 1; continue }
            val left = budget[id] ?: 0
            if (left > 0) budget[id] = left - 1 else toRemove.add(i)
        }
        for (i in toRemove.sortedDescending()) { engine.removeAt(i); work.removeAt(i) }

        // Reorder the survivors to match target order (instances of the same id are interchangeable).
        for (i in target.indices) {
            if (i >= work.size || work[i].id == target[i].id) continue
            val j = (i + 1 until work.size).firstOrNull { work[it].id == target[i].id } ?: continue
            engine.move(j, i)
            work.add(i, work.removeAt(j))
        }

        tracks.value = target
    }

    override fun playNext(tracks: List<Track>) {
        scope.launch {
            val items = tracks.map { it.toPlayable() }
            val insertAt = (status.value.queueIndex + 1).coerceIn(0, this@LocalPlayer.tracks.value.size)
            this@LocalPlayer.tracks.value =
                this@LocalPlayer.tracks.value.toMutableList().apply { addAll(insertAt, tracks) }
            engine.addNext(items)
        }
    }

    override fun addToQueue(tracks: List<Track>) {
        scope.launch {
            val items = tracks.map { it.toPlayable() }
            this@LocalPlayer.tracks.value = this@LocalPlayer.tracks.value + tracks
            engine.addToQueue(items)
        }
    }

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun stop() { engine.pause(); clearQueue() }
    override fun togglePlayPause() = if (status.value.isPlaying) engine.pause() else engine.play()
    override fun next() = engine.next()
    override fun previous() = engine.previous()
    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    override fun skipTo(index: Int) = engine.seekToIndex(index)

    override fun removeAt(index: Int) {
        if (index !in tracks.value.indices) return
        tracks.value = tracks.value.toMutableList().apply { removeAt(index) }
        engine.removeAt(index)
    }

    override fun move(from: Int, to: Int) {
        val list = tracks.value
        if (from !in list.indices || to !in list.indices) return
        tracks.value = list.toMutableList().apply { add(to, removeAt(from)) }
        engine.move(from, to)
    }

    override fun clearQueue() {
        queueJob?.cancel()
        tracks.value = emptyList()
        engine.clear()
    }

    override fun setRepeat(mode: RepeatMode) = engine.setRepeat(mode)
    override fun setShuffle(enabled: Boolean) = engine.setShuffle(enabled)

    // StateFlow is covariant, so the engine's non-null volume satisfies the nullable Player contract.
    override val volume: StateFlow<Float?> = engine.volume
    override val volumeControllable: StateFlow<Boolean> = MutableStateFlow(true)
    override fun setVolume(level: Float) = engine.setVolume(level)

    /** Whether [setVolume] drives the device (media stream) volume or an in-app gain. Local-only, so
     *  it lives here rather than on [Player]; remote devices manage their own volume scale. */
    fun setVolumeMode(useDeviceVolume: Boolean) = engine.setVolumeMode(useDeviceVolume)

    override fun release() = engine.release()

    private companion object {
        // A remote "Play" whose start position is within this of our own counts as a reorder of the
        // current queue (rearranged in place) rather than a fresh start (which restarts playback).
        const val REORDER_TOLERANCE_MS = 5_000L
    }
}
