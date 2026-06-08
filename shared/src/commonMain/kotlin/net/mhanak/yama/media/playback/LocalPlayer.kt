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
        combine(tracks, engine.status) { trackList, engineStatus ->
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

    override fun release() = engine.release()
}
