package net.mhanak.yama.media.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.MusicSource
import java.io.File

/** A durable record of one favourite toggle made offline, persisted until the backend accepts it. */
@Serializable
data class FavoriteChange(
    val sourceKey: String,
    /** A [FavoritableKind] name. */
    val kind: String,
    val itemId: String,
    val favorite: Boolean,
)

/**
 * A durable, JSON-backed outbox for **favourite toggles made offline**, mirroring [ScrobbleOutbox].
 * Liking an item already writes through to the backend when online (and updates the cached browse
 * lists / offline row immediately either way), so [record] only persists a change while the source is
 * unreachable; on reconnect [flush] replays the queued changes through [MusicSource.setFavorite].
 *
 * Changes are keyed by `(sourceKey, kind, itemId)` with last-write-wins, so toggling a heart on and off
 * while offline collapses to the final state rather than queuing two opposing writes. Favouriting is
 * idempotent, so replaying a change the backend already has is harmless.
 *
 * Partitioned by `sourceKey` so only the active source's changes flush and two accounts never cross.
 */
class FavoriteOutbox(
    private val file: File,
    private val source: () -> MusicSource,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Record that [id] of [kind] was set to [favorite]. No-op while online (the live [setFavorite]
     * already wrote it) or on sources with no offline partition; otherwise the desired state is
     * persisted (replacing any earlier queued change for the same item) for the next [flush].
     */
    fun record(kind: FavoritableKind, id: String, favorite: Boolean) {
        val src = source()
        val key = src.downloadSourceKey() ?: return
        if (src.isReachable.value) return
        val change = FavoriteChange(key, kind.name, id, favorite)
        scope.launch {
            mutex.withLock {
                val items = read().toMutableList()
                items.removeAll { it.sourceKey == key && it.kind == change.kind && it.itemId == id }
                items.add(change)
                write(items)
            }
        }
    }

    /** Replay queued changes for the active source, dropping each one the backend accepts. Safe to call
     *  repeatedly (e.g. on every reconnect); a no-op when offline or there's nothing queued. */
    fun flush() {
        scope.launch {
            val src = source()
            val key = src.downloadSourceKey() ?: return@launch
            if (!src.isReachable.value) return@launch
            val toFlush = mutex.withLock { read().filter { it.sourceKey == key } }
            if (toFlush.isEmpty()) return@launch
            val acked = mutableListOf<FavoriteChange>()
            for (c in toFlush) {
                val kind = runCatching { FavoritableKind.valueOf(c.kind) }.getOrNull()
                if (kind == null) { acked.add(c); continue } // unknown kind — drop, can't replay
                val ok = runCatching { src.setFavorite(kind, c.itemId, c.favorite); true }.getOrDefault(false)
                if (ok) acked.add(c) else break // stop on the first failure; retry the rest later
            }
            if (acked.isNotEmpty()) {
                mutex.withLock { write(read().filterNot { it in acked }) }
            }
        }
    }

    /**
     * The pending (un-flushed) **track** favourite toggles for [sourceKey] as `id → desired state`.
     * A server-side favourites refresh merges this on top of what the backend returns, so a change made
     * offline (and not yet replayed) isn't dropped by server data that doesn't reflect it yet.
     */
    fun pendingTrackFavorites(sourceKey: String): Map<String, Boolean> =
        read().filter { it.sourceKey == sourceKey && it.kind == FavoritableKind.Track.name }
            .associate { it.itemId to it.favorite }

    private fun read(): List<FavoriteChange> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<FavoriteChange>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun write(changes: List<FavoriteChange>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(changes))
        }
    }
}
