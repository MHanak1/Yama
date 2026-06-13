package net.mhanak.yama.media.sources.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One indexed track row. Deliberately a flat, ingestion-agnostic shape: it carries denormalised
 * album/artist/genre names *and* their stable IDs so browse queries are direct lookups and never
 * need to re-hash. A scanned local file and a future downloaded track are the same row shape — see
 * the "Reuse for offline / downloads" notes in LOCAL_SOURCE_PLAN.md.
 *
 * [sourceKey] discriminates which offline library a row belongs to (`"local"` today; later
 * `"jellyfin:<serverId>"` for downloads), so one index can hold several without collision. [id] and
 * [path] are independent on purpose: local files derive [id] from [path], but a download keeps the
 * remote ID while owning an app-managed [path].
 */
@Serializable
data class StoredTrack(
    val sourceKey: String,
    val id: String,
    val path: String,
    val title: String,
    val albumId: String?,
    val album: String?,
    val albumArtist: String?,
    val albumArtistId: String?,
    val artists: List<String>,
    val artistIds: List<String>,
    val genres: List<String>,
    val genreIds: List<String>,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long?,
    val year: Int?,
    val artworkPath: String?,
    val lastModified: Long,
    /** Whether the file carried real embedded metadata (a readable title). Files without it can be
     * hidden via the "Skip tracks without metadata" setting. Defaults true for rows ingested before
     * this flag existed and for non-scanned rows (e.g. downloads). */
    val hasMetadata: Boolean = true,
)

/**
 * An on-disk index of "things I have locally". Deliberately **ingestion-agnostic**: it stores and
 * reads rows and knows nothing about scanning, tags, or files — the scanner/tag-reader are a
 * separate ingester that writes into it. That separation is what lets this same store later back a
 * downloads feature, and lets the JSON implementation here be swapped for a SQLDelight-backed one
 * without touching [LocalSource].
 */
interface LocalLibraryStore {
    /** All rows for [sourceKey], in stored order. */
    fun all(sourceKey: String): List<StoredTrack>

    /** Replace the full row set for [sourceKey] (used after a scan completes). */
    fun replaceAll(sourceKey: String, tracks: List<StoredTrack>)

    /** A single row by track [id], or null. */
    fun get(id: String): StoredTrack?
}

/**
 * [LocalLibraryStore] backed by a single JSON file. The whole index is held in memory (keyed by id)
 * for instant browse and re-serialised on each [replaceAll]; local libraries are small enough that
 * this is cheaper and simpler than an embedded SQL engine. All access is synchronised since the
 * scan writes from a background IO scope while browse queries read.
 */
class FileLibraryStore(private val file: File) : LocalLibraryStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    // Insertion-ordered so [all] returns rows in the order they were stored.
    private val rows: LinkedHashMap<String, StoredTrack> = synchronized(lock) {
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<List<StoredTrack>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
        LinkedHashMap<String, StoredTrack>().apply { loaded.forEach { put(it.id, it) } }
    }

    override fun all(sourceKey: String): List<StoredTrack> = synchronized(lock) {
        rows.values.filter { it.sourceKey == sourceKey }
    }

    override fun replaceAll(sourceKey: String, tracks: List<StoredTrack>) = synchronized(lock) {
        // Keep rows from other source keys untouched; swap out only this key's rows.
        val others = rows.values.filter { it.sourceKey != sourceKey }
        rows.clear()
        others.forEach { rows[it.id] = it }
        tracks.forEach { rows[it.id] = it }
        persist()
    }

    override fun get(id: String): StoredTrack? = synchronized(lock) { rows[id] }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(rows.values.toList()))
        }
    }
}
