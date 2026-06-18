package net.mhanak.yama.media.sources.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.util.StreamingQuality
import java.io.File

/**
 * Retention policy for an offline row. [Pinned] is an explicit download — never evicted; a stale
 * pinned entry re-fetches. [Cached] is an auto-pin on play under an LRU size budget — evictable, and
 * simply dropped (not re-fetched) when stale. Promoting a download flips [Cached] → [Pinned].
 */
@Serializable
enum class Retention { Pinned, Cached }

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
    // --- Downloads / cache fields (all defaulted so existing local rows and old JSON deserialize
    // unchanged). Unused by the local-files scanner; written by the download layer. ---
    /** Download vs. recent-tracks cache; see [Retention]. */
    val retention: Retention = Retention.Pinned,
    /** The streaming quality this copy was fetched at, fixed at download time. */
    val quality: StreamingQuality? = null,
    /** The origin's content version ([MusicSource.getContentVersion]) when last fetched, for staleness. */
    val originVersion: String? = null,
    /** When this copy finished downloading (epoch millis). */
    val downloadedAt: Long? = null,
    /** When this track was last played (epoch millis), for cache LRU eviction. */
    val lastPlayedAt: Long? = null,
    /** Set when the origin version has moved past [originVersion]: a pinned entry re-fetches, a cached
     * one is evicted. The local file is kept until a fresh copy lands (download-then-swap). */
    val stale: Boolean = false,
    /** Size of the downloaded audio file in bytes, recorded at download time. 0 for legacy rows (the
     * download repo falls back to statting [path] for those) and for non-download local rows. */
    val sizeBytes: Long = 0,
    /** The user's favourite ("liked") state for this track, kept on the row so it survives offline. For
     * downloaded sources it's synced back to the server via the favourite outbox; the local-files source
     * keeps favourites in preferences and leaves this at the default. */
    val favorite: Boolean = false,
    /** A local play tally, bumped on each completed play, so tracks can be sorted by play count even
     * offline. The server count stays authoritative when online (it overwrites this on the next fetch);
     * offline plays are mirrored back via the scrobble outbox. */
    val playCount: Int = 0,
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

    /**
     * A single row by composite [sourceKey] + [id], or null. Downloads are keyed by the *remote* id,
     * so two sources with colliding UUIDs must be disambiguated by partition — always resolve download
     * rows this way rather than via the global [get].
     */
    fun get(sourceKey: String, id: String): StoredTrack? = get(id)?.takeIf { it.sourceKey == sourceKey }

    /** Insert or replace a single row (used by the download layer, which writes one entry at a time). */
    fun put(track: StoredTrack) {}

    /** Insert or replace many rows in one batch (a single transaction in SQL-backed stores) — used by
     *  the JSON→DB migration and any bulk write. Defaults to a per-row loop. */
    fun putAll(tracks: Collection<StoredTrack>) { tracks.forEach { put(it) } }

    /** Remove rows by [sourceKey] + [id]s (cache eviction / per-download removal). */
    fun remove(sourceKey: String, ids: Collection<String>) {}
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

    override fun get(sourceKey: String, id: String): StoredTrack? =
        synchronized(lock) { rows[id]?.takeIf { it.sourceKey == sourceKey } }

    override fun put(track: StoredTrack) = synchronized(lock) {
        rows[track.id] = track
        persist()
    }

    override fun remove(sourceKey: String, ids: Collection<String>) = synchronized(lock) {
        val idSet = ids.toSet()
        var changed = false
        idSet.forEach { id ->
            if (rows[id]?.sourceKey == sourceKey) { rows.remove(id); changed = true }
        }
        if (changed) persist()
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(rows.values.toList()))
        }
    }
}
