package net.mhanak.yama.media.sources.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.mhanak.yama.db.YamaDatabase
import net.mhanak.yama.util.StreamingQuality

/**
 * [LocalLibraryStore] backed by SQLDelight (one `trackRow` table, partitioned by `sourceKey`). Replaces
 * [FileLibraryStore]: writes are incremental row upserts (no whole-file re-serialise, which is what
 * made large local libraries and bulk downloads expensive) and lookups/search hit indexes.
 *
 * List-valued columns are stored as JSON text and enum/boolean columns as TEXT/INTEGER, (de)serialised
 * here so the schema needs no SQLDelight column adapters. The same DB holds the local scan
 * (`sourceKey = "local"`) and downloads (`"jellyfin:<token>"`); ownership is the partition exactly as
 * before.
 */
class SqlLibraryStore(private val db: YamaDatabase) : LocalLibraryStore {
    private val q = db.trackQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    override fun all(sourceKey: String): List<StoredTrack> =
        q.allForSource(sourceKey, ::mapRow).executeAsList()

    override fun replaceAll(sourceKey: String, tracks: List<StoredTrack>) {
        q.transaction {
            q.deleteForSource(sourceKey)
            tracks.forEach { insert(it) }
        }
    }

    override fun get(id: String): StoredTrack? = q.getById(id, ::mapRow).executeAsOneOrNull()

    override fun get(sourceKey: String, id: String): StoredTrack? =
        q.getByKey(sourceKey, id, ::mapRow).executeAsOneOrNull()

    override fun put(track: StoredTrack) = insert(track)

    override fun putAll(tracks: Collection<StoredTrack>) {
        q.transaction { tracks.forEach { insert(it) } }
    }

    override fun remove(sourceKey: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        q.deleteByIds(sourceKey, ids.toList())
    }

    private fun insert(t: StoredTrack) {
        q.upsert(
        sourceKey = t.sourceKey,
        id = t.id,
        path = t.path,
        title = t.title,
        albumId = t.albumId,
        album = t.album,
        albumArtist = t.albumArtist,
        albumArtistId = t.albumArtistId,
        artists = json.encodeToString(stringList, t.artists),
        artistIds = json.encodeToString(stringList, t.artistIds),
        genres = json.encodeToString(stringList, t.genres),
        genreIds = json.encodeToString(stringList, t.genreIds),
        trackNumber = t.trackNumber?.toLong(),
        discNumber = t.discNumber?.toLong(),
        durationMs = t.durationMs,
        year = t.year?.toLong(),
        artworkPath = t.artworkPath,
        lastModified = t.lastModified,
        hasMetadata = if (t.hasMetadata) 1L else 0L,
        retention = t.retention.name,
        quality = t.quality?.name,
        originVersion = t.originVersion,
        downloadedAt = t.downloadedAt,
        lastPlayedAt = t.lastPlayedAt,
        stale = if (t.stale) 1L else 0L,
        sizeBytes = t.sizeBytes,
        favorite = if (t.favorite) 1L else 0L,
        playCount = t.playCount.toLong(),
        )
    }

    @Suppress("LongParameterList")
    private fun mapRow(
        sourceKey: String, id: String, path: String, title: String, albumId: String?, album: String?,
        albumArtist: String?, albumArtistId: String?, artists: String, artistIds: String, genres: String,
        genreIds: String, trackNumber: Long?, discNumber: Long?, durationMs: Long?, year: Long?,
        artworkPath: String?, lastModified: Long, hasMetadata: Long, retention: String, quality: String?,
        originVersion: String?, downloadedAt: Long?, lastPlayedAt: Long?, stale: Long, sizeBytes: Long,
        favorite: Long, playCount: Long,
    ) = StoredTrack(
        sourceKey = sourceKey,
        id = id,
        path = path,
        title = title,
        albumId = albumId,
        album = album,
        albumArtist = albumArtist,
        albumArtistId = albumArtistId,
        artists = decode(artists),
        artistIds = decode(artistIds),
        genres = decode(genres),
        genreIds = decode(genreIds),
        trackNumber = trackNumber?.toInt(),
        discNumber = discNumber?.toInt(),
        durationMs = durationMs,
        year = year?.toInt(),
        artworkPath = artworkPath,
        lastModified = lastModified,
        hasMetadata = hasMetadata != 0L,
        retention = runCatching { Retention.valueOf(retention) }.getOrDefault(Retention.Pinned),
        quality = quality?.let { runCatching { StreamingQuality.valueOf(it) }.getOrNull() },
        originVersion = originVersion,
        downloadedAt = downloadedAt,
        lastPlayedAt = lastPlayedAt,
        stale = stale != 0L,
        sizeBytes = sizeBytes,
        favorite = favorite != 0L,
        playCount = playCount.toInt(),
    )

    private fun decode(jsonText: String): List<String> =
        runCatching { json.decodeFromString(stringList, jsonText) }.getOrDefault(emptyList())
}
