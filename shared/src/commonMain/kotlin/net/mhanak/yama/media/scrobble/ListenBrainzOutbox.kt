package net.mhanak.yama.media.scrobble

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
import net.mhanak.yama.util.logger
import java.io.File

/**
 * One listen awaiting submission to ListenBrainz. Unlike
 * [net.mhanak.yama.media.playback.PlayedEvent] (which stores only a `trackId` and re-resolves via the
 * source at flush time), this snapshots the full metadata: an offline listen must survive even if the
 * track is later evicted from the catalog / the source switches, so there's nothing to re-resolve.
 */
@Serializable
data class QueuedListen(
    val id: String,
    val trackName: String,
    val artistName: String,
    val releaseName: String?,
    val durationMs: Long?,
    val listenedAtEpochSec: Long,
) {
    fun toMetadata(): ListenMetadata = ListenMetadata(trackName, artistName, releaseName, durationMs)
}

/**
 * Durable, JSON-backed outbox for ListenBrainz listens that couldn't be submitted immediately (offline,
 * or a transient server error). Mirrors [net.mhanak.yama.media.playback.ScrobbleOutbox]'s pattern —
 * single JSON file, [Mutex]-guarded, remove-on-ack in [flush] so a partial drain can't double-submit —
 * but is NOT partitioned by sourceKey: there is one ListenBrainz account, and the metadata is captured
 * regardless of which music source produced the play.
 */
class ListenBrainzOutbox(private val file: File) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var seq = 0L
    private val log = logger("Scrobble")

    /** Persist a listen for later submission. */
    fun enqueue(metadata: ListenMetadata, listenedAtEpochSec: Long) {
        val event = QueuedListen(
            id = nextId(),
            trackName = metadata.trackName,
            artistName = metadata.artistName,
            releaseName = metadata.releaseName,
            durationMs = metadata.durationMs,
            listenedAtEpochSec = listenedAtEpochSec,
        )
        scope.launch {
            mutex.withLock {
                val events = read().toMutableList()
                events.add(event)
                write(events)
            }
        }
    }

    /** Replay queued listens through [scrobbler], dropping each one it accepts. Stops on the first
     *  failure (keeps the rest for a later retry). Safe to call repeatedly / on every reconnect. */
    fun flush(scrobbler: Scrobbler) {
        scope.launch {
            val queued = mutex.withLock { read() }
            if (queued.isEmpty()) return@launch
            val acked = mutableSetOf<String>()
            for (e in queued) {
                val ok = runCatching { scrobbler.submitListen(e.toMetadata(), e.listenedAtEpochSec) }
                    .onFailure { log.warn("ListenBrainzOutbox: flush failed for '${e.trackName}'", it) }
                    .getOrDefault(false)
                if (ok) acked.add(e.id) else break
            }
            if (acked.isNotEmpty()) {
                mutex.withLock { write(read().filterNot { it.id in acked }) }
            }
        }
    }

    private fun nextId(): String = "${System.currentTimeMillis()}-${seq++}"

    private fun read(): List<QueuedListen> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<QueuedListen>>(file.readText()) }
            .onFailure { log.warn("ListenBrainzOutbox: failed to read ${file.name} — listens lost", it) }
            .getOrDefault(emptyList())
    }

    private fun write(events: List<QueuedListen>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(events))
        }.onFailure { log.warn("ListenBrainzOutbox: failed to write ${file.name}", it) }
    }
}
