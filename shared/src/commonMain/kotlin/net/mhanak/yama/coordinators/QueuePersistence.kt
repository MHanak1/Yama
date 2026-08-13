package net.mhanak.yama.coordinators

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mhanak.yama.media.playback.LocalPlayer
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.util.AppPreferences

/**
 * Persists the local player's queue to [AppPreferences] on every change and restores it on startup.
 * Keyed by the active source type so Jellyfin and Local queues are stored and restored independently.
 * [AppContainer] calls [restore] from its [init] block on an IO coroutine.
 */
class QueuePersistence(
    private val player: LocalPlayer,
    private val source: () -> MusicSource,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        persistStructureOnChange()
        persistPositionWhilePlaying()
    }

    /**
     * Persists the track list + current item whenever the queue structure changes. Position is written here too,
     * so a track change (where [PlayerStatus.positionMs] has just reset to ~0) keeps the saved current and offset
     * consistent even if the app is killed before the next position sample.
     */
    private fun persistStructureOnChange() {
        scope.launch {
            player.status
                .distinctUntilChangedBy { Pair(it.queue.map { t -> t.id }, it.current?.id) }
                .drop(1) // Skip the initial empty-queue state so it doesn't overwrite the saved queue before restore reads it
                .collect { status ->
                    val sourceType = source().type.name
                    AppPreferences.setSavedQueueTrackIds(sourceType, status.queue.map { it.id })
                    AppPreferences.setSavedQueueCurrentId(sourceType, status.current?.id)
                    AppPreferences.setSavedQueuePosition(sourceType, status.positionMs)
                }
        }
    }

    /**
     * Keeps the saved position fresh as the current track plays. Sampled (not per-tick) to avoid hammering the
     * settings store, and gated on a non-null current so the idle startup state never clobbers the saved offset
     * before [restore] runs.
     */
    private fun persistPositionWhilePlaying() {
        scope.launch {
            player.status
                .filter { it.current != null }
                .map { it.positionMs }
                .sample(POSITION_PERSIST_INTERVAL_MS)
                .distinctUntilChanged()
                .collect { positionMs ->
                    AppPreferences.setSavedQueuePosition(source().type.name, positionMs)
                }
        }
    }

    suspend fun restore() {
        val src = source()
        val sourceType = src.type.name
        val ids = AppPreferences.savedQueueTrackIds(sourceType)
        val currentId = AppPreferences.savedQueueCurrentId(sourceType)
        val positionMs = AppPreferences.savedQueuePosition(sourceType)
        if (ids.isEmpty()) return
        runCatching {
            val tracks = src.getTracksByIds(ids)
            if (tracks.isEmpty()) return
            val index = if (currentId != null) {
                tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            } else 0
            withContext(Dispatchers.Main) { player.loadQueue(tracks, index, positionMs) }
        }
    }

    private companion object {
        const val POSITION_PERSIST_INTERVAL_MS = 3_000L
    }
}
