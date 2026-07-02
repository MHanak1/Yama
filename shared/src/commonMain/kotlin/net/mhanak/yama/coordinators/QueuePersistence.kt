package net.mhanak.yama.coordinators

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
        persistOnChange()
    }

    private fun persistOnChange() {
        scope.launch {
            player.status
                .map { Pair(it.queue.map { t -> t.id }, it.current?.id) }
                .distinctUntilChanged()
                .drop(1) // Skip the initial empty-queue state so it doesn't overwrite the saved queue before restore reads it
                .collect { (ids, currentId) ->
                    val sourceType = source().type.name
                    AppPreferences.setSavedQueueTrackIds(sourceType, ids)
                    AppPreferences.setSavedQueueCurrentId(sourceType, currentId)
                }
        }
    }

    suspend fun restore() {
        val src = source()
        val sourceType = src.type.name
        val ids = AppPreferences.savedQueueTrackIds(sourceType)
        val currentId = AppPreferences.savedQueueCurrentId(sourceType)
        if (ids.isEmpty()) return
        runCatching {
            val tracks = src.getTracksByIds(ids)
            if (tracks.isEmpty()) return
            val index = if (currentId != null) {
                tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            } else 0
            withContext(Dispatchers.Main) { player.loadQueue(tracks, index) }
        }
    }
}
