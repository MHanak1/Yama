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
import net.mhanak.yama.media.sources.MusicSource
import java.io.File

/** A durable record of one completed play, persisted until the backend acknowledges it. */
@Serializable
data class PlayedEvent(
    val id: String,
    val sourceKey: String,
    val trackId: String,
    val playedAtEpochMs: Long,
    val positionMs: Long,
)

/**
 * A durable, JSON-backed outbox for **completed plays that happened offline** (DOWNLOADS_PLAN.md
 * Phase 5). Live online scrobbling stays on [PlaybackReporter]'s report* path — calling the backend
 * again here would double-count — so [recordPlay] only persists an event when the source is unreachable
 * (and only if the user opted into offline-play recording). On reconnect, [flush] replays the queued
 * events through [MusicSource.reportPlayed] (a backdated mark-played) and drops each one the backend
 * acknowledges (remove-on-ack, so a partial flush can't double-count).
 *
 * Events are partitioned by `sourceKey` so only the active source's plays flush, and so two accounts
 * never cross-report.
 */
class ScrobbleOutbox(
    private val file: File,
    private val source: () -> MusicSource,
    private val recordOffline: () -> Boolean,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var seq = 0L

    /**
     * Record that [trackId] was played to completion at [positionMs]. No-op while online (live
     * reporting already scrobbled it) and when offline-play recording is disabled; otherwise the event
     * is persisted for the next [flush]. Times the play at "now".
     */
    fun recordPlay(trackId: String, positionMs: Long) {
        val src = source()
        val key = src.downloadSourceKey() ?: return
        if (src.isReachable.value) return
        if (!recordOffline()) return
        val event = PlayedEvent(nextId(), key, trackId, System.currentTimeMillis(), positionMs)
        scope.launch {
            mutex.withLock {
                val events = read().toMutableList()
                events.add(event)
                write(events)
            }
        }
    }

    /** Replay queued events for the active source, dropping each one the backend acknowledges. Safe to
     *  call repeatedly (e.g. on every reconnect); a no-op when offline or there's nothing queued. */
    fun flush() {
        scope.launch {
            val src = source()
            val key = src.downloadSourceKey() ?: return@launch
            if (!src.isReachable.value) return@launch
            val toFlush = mutex.withLock { read().filter { it.sourceKey == key } }
            if (toFlush.isEmpty()) return@launch
            val acked = mutableSetOf<String>()
            for (e in toFlush) {
                val ok = runCatching { src.reportPlayed(e.trackId, e.playedAtEpochMs, e.positionMs) }
                    .getOrDefault(false)
                if (ok) acked.add(e.id) else break // stop on the first failure; retry the rest later
            }
            if (acked.isNotEmpty()) {
                mutex.withLock { write(read().filterNot { it.id in acked }) }
            }
        }
    }

    private fun nextId(): String = "${System.currentTimeMillis()}-${seq++}"

    private fun read(): List<PlayedEvent> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<PlayedEvent>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun write(events: List<PlayedEvent>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(events))
        }
    }
}
