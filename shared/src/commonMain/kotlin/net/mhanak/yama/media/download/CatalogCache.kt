package net.mhanak.yama.media.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.getAppDataDir
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import java.io.File

/**
 * A source-agnostic, framework-free snapshot of a source's browse catalog — the five lists the
 * library grids render. The domain models stay annotation-free (per conventions), so the cache layer
 * owns the serializable DTOs and maps to/from these.
 */
data class CatalogSnapshot(
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albumArtists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean
        get() = albums.isEmpty() && artists.isEmpty() && albumArtists.isEmpty() &&
            genres.isEmpty() && playlists.isEmpty()

    /** This snapshot field-wise over [old]: keep each of this snapshot's non-empty lists, but fall back
     *  to [old]'s for any list that's empty here — so a partial/failed emit can't drop a list the cache
     *  already had. */
    fun mergedOver(old: CatalogSnapshot?): CatalogSnapshot {
        if (old == null) return this
        return CatalogSnapshot(
            albums = albums.ifEmpty { old.albums },
            artists = artists.ifEmpty { old.artists },
            albumArtists = albumArtists.ifEmpty { old.albumArtists },
            genres = genres.ifEmpty { old.genres },
            playlists = playlists.ifEmpty { old.playlists },
        )
    }
}

/** The container kinds whose track lists are cached, keying [CatalogCache.loadTrackList]. */
enum class TrackListKind { Album, Artist, Genre, Playlist, All }

/**
 * The disk tier on the sources' in-memory stale-while-revalidate. It persists whatever the active
 * source emits (browse lists + visited track lists), partitioned by `sourceKey`, and serves it back on
 * cold start / when the source is unreachable — so the catalog survives process death and going
 * offline. It is *not* the download layer: it holds the last catalog we saw, and its diff against the
 * download index is what produces the availability set the UI grays from.
 *
 * Sources are otherwise unchanged — they expose their browse StateFlows (collected here for
 * persistence) and gain a single [net.mhanak.yama.media.sources.MusicSource.hydrateCatalog] hook to be
 * seeded from disk.
 */
class CatalogCache(private val baseDir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private fun partitionDir(sourceKey: String): File =
        File(baseDir, sanitizeSourceKey(sourceKey)).apply { mkdirs() }

    private fun snapshotFile(sourceKey: String) = File(partitionDir(sourceKey), "snapshot.json")
    private fun trackListFile(sourceKey: String) = File(partitionDir(sourceKey), "tracklists.json")
    private fun favoritesFile(sourceKey: String) = File(partitionDir(sourceKey), "favorites.json")

    fun saveSnapshot(sourceKey: String, snapshot: CatalogSnapshot) = synchronized(lock) {
        // Never let an empty list overwrite a previously-saved non-empty one. The browse flows fill
        // asynchronously (and a refresh can fail or land partway), so an intermediate / failed emit
        // would otherwise wipe a complete on-disk catalog — and with it the ability to browse and reach
        // downloads offline. Merge field-wise over what's on disk so the snapshot only ever grows richer.
        val merged = snapshot.mergedOver(readSnapshot(sourceKey))
        runCatching { snapshotFile(sourceKey).writeText(json.encodeToString(merged.toDto())) }
        Unit
    }

    fun loadSnapshot(sourceKey: String): CatalogSnapshot? = synchronized(lock) { readSnapshot(sourceKey) }

    private fun readSnapshot(sourceKey: String): CatalogSnapshot? {
        val f = snapshotFile(sourceKey)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<CatalogSnapshotDto>(f.readText()).toDomain() }.getOrNull()
    }

    /** Write-through one container's track list; called after every successful online fetch. */
    fun saveTrackList(sourceKey: String, kind: TrackListKind, containerId: String, tracks: List<Track>) =
        synchronized(lock) {
            val map = readTrackLists(sourceKey).toMutableMap()
            map[trackListKey(kind, containerId)] = tracks.map { it.toDto() }
            runCatching { trackListFile(sourceKey).writeText(json.encodeToString(map)) }
            Unit
        }

    /** The last-seen track list for a container, or null if it was never visited online. */
    fun loadTrackList(sourceKey: String, kind: TrackListKind, containerId: String): List<Track>? =
        synchronized(lock) {
            readTrackLists(sourceKey)[trackListKey(kind, containerId)]?.map { it.toDomain() }
        }

    /**
     * Patch favorite/playCount for specific track IDs across all cached track lists for [sourceKey].
     * Called after a successful [refreshStaleness] pass so the catalog cache stays in sync with the
     * downloads index without requiring the user to re-open every album while online.
     * Only writes if at least one value actually changed.
     */
    fun updateTrackUserData(sourceKey: String, updates: Map<String, Pair<Boolean, Int>>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            val lists = readTrackLists(sourceKey).toMutableMap()
            var changed = false
            for ((key, tracks) in lists) {
                val patched = tracks.map { track ->
                    val (fav, pc) = updates[track.id] ?: return@map track
                    if (track.favorite == fav && track.playCount == pc) return@map track
                    changed = true
                    track.copy(favorite = fav, playCount = pc)
                }
                lists[key] = patched
            }
            if (changed) runCatching { trackListFile(sourceKey).writeText(json.encodeToString(lists)) }
        }
    }

    /**
     * The persisted set of favourite **track** ids for [sourceKey] — the offline source of truth for a
     * track's heart, independent of which containers' track lists happen to be cached. Album/artist/
     * genre/playlist favourites already survive offline in the browse snapshot; tracks aren't held in any
     * browse list, so they need this dedicated set. [refreshFavorites] seeds it wholesale from the server,
     * a toggle updates it via [setTrackFavorite], and reads overlay it onto track lists so a heart is
     * correct even when it was favourited after its container was cached, on another device, or offline.
     */
    fun favoriteTrackIds(sourceKey: String): Set<String> = synchronized(lock) { readFavorites(sourceKey) }

    /** Add or remove a single track id from the favourite set (a local toggle), persisting if it changed. */
    fun setTrackFavorite(sourceKey: String, id: String, favorite: Boolean) = synchronized(lock) {
        val set = readFavorites(sourceKey).toMutableSet()
        val changed = if (favorite) set.add(id) else set.remove(id)
        if (changed) writeFavorites(sourceKey, set)
        Unit
    }

    /** Replace the whole favourite-track set (a full refresh from the server). */
    fun replaceFavoriteTrackIds(sourceKey: String, ids: Set<String>) = synchronized(lock) {
        writeFavorites(sourceKey, ids)
    }

    private fun readFavorites(sourceKey: String): Set<String> {
        val f = favoritesFile(sourceKey)
        if (!f.exists()) return emptySet()
        return runCatching { json.decodeFromString<Set<String>>(f.readText()) }.getOrDefault(emptySet())
    }

    private fun writeFavorites(sourceKey: String, ids: Set<String>) {
        runCatching { favoritesFile(sourceKey).writeText(json.encodeToString(ids)) }
    }

    /** Drop a container's cached track list (e.g. when its last offline-playable track is removed, so a
     *  container with nothing playable offline keeps no track rows). No-op if it wasn't cached. */
    fun removeTrackList(sourceKey: String, kind: TrackListKind, containerId: String) = synchronized(lock) {
        val map = readTrackLists(sourceKey).toMutableMap()
        if (map.remove(trackListKey(kind, containerId)) != null) {
            runCatching { trackListFile(sourceKey).writeText(json.encodeToString(map)) }
        }
        Unit
    }

    /** Wipe one source's cached catalog + track lists (wired to source removal alongside the downloads clear). */
    fun clear(sourceKey: String) = synchronized(lock) {
        partitionDir(sourceKey).deleteRecursively()
        Unit
    }

    private fun readTrackLists(sourceKey: String): Map<String, List<TrackDto>> {
        val f = trackListFile(sourceKey)
        if (!f.exists()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, List<TrackDto>>>(f.readText()) }
            .getOrDefault(emptyMap())
    }

    private fun trackListKey(kind: TrackListKind, containerId: String) = "${kind.name}:$containerId"

    companion object {
        fun create(): CatalogCache =
            CatalogCache(File(File(getAppDataDir().toString()), "catalog"))
    }
}

/** `:` and path separators aren't safe in directory names — collapse them to `_`. */
internal fun sanitizeSourceKey(sourceKey: String): String =
    sourceKey.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }.joinToString("")

// --- Serializable DTOs + mappers (the domain models stay framework-free) ---------------------------

@Serializable
private data class CatalogSnapshotDto(
    val albums: List<AlbumDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val albumArtists: List<ArtistDto> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
)

@Serializable
private data class AlbumDto(
    val id: String, val name: String, val albumArtist: String?, val year: Int?,
    val songCount: Int?, val imageUrl: String?, val imageHash: String?,
    val favorite: Boolean = false, val genres: List<String> = emptyList(),
)

@Serializable
private data class ArtistDto(
    val id: String, val name: String, val imageUrl: String?, val imageHash: String?,
    val favorite: Boolean = false, val genres: List<String> = emptyList(),
)

@Serializable
private data class GenreDto(
    val id: String, val name: String, val imageUrl: String?, val imageHash: String?,
    val favorite: Boolean = false,
)

@Serializable
private data class PlaylistDto(
    val id: String, val name: String, val itemCount: Int?, val imageUrl: String?,
    val imageHash: String?, val favorite: Boolean = false, val genres: List<String> = emptyList(),
)

@Serializable
internal data class TrackDto(
    val id: String, val name: String, val albumId: String?, val album: String?,
    val artists: List<String>?, val durationTicks: Long?, val trackNumber: Int?,
    val discNumber: Int?, val imageUrl: String? = null,
    val artistIds: List<String> = emptyList(), val albumArtistId: String? = null,
    val genres: List<String> = emptyList(), val genreIds: List<String> = emptyList(),
    val favorite: Boolean = false, val playCount: Int = 0,
)

private fun CatalogSnapshot.toDto() = CatalogSnapshotDto(
    albums = albums.map { it.toDto() },
    artists = artists.map { it.toDto() },
    albumArtists = albumArtists.map { it.toDto() },
    genres = genres.map { it.toDto() },
    playlists = playlists.map { it.toDto() },
)

private fun CatalogSnapshotDto.toDomain() = CatalogSnapshot(
    albums = albums.map { it.toDomain() },
    artists = artists.map { it.toDomain() },
    albumArtists = albumArtists.map { it.toDomain() },
    genres = genres.map { it.toDomain() },
    playlists = playlists.map { it.toDomain() },
)

private fun Album.toDto() = AlbumDto(id, name, albumArtist, year, songCount, imageUrl, imageHash, favorite, genres)
private fun AlbumDto.toDomain() = Album(id, name, albumArtist, year, songCount, imageUrl, imageHash, favorite, genres)
private fun Artist.toDto() = ArtistDto(id, name, imageUrl, imageHash, favorite, genres)
private fun ArtistDto.toDomain() = Artist(id, name, imageUrl, imageHash, favorite, genres)
private fun Genre.toDto() = GenreDto(id, name, imageUrl, imageHash, favorite)
private fun GenreDto.toDomain() = Genre(id, name, imageUrl, imageHash, favorite)
private fun Playlist.toDto() = PlaylistDto(id, name, itemCount, imageUrl, imageHash, favorite, genres)
private fun PlaylistDto.toDomain() = Playlist(id, name, itemCount, imageUrl, imageHash, favorite, genres)

internal fun Track.toDto() = TrackDto(id, name, albumId, album, artists, durationTicks, trackNumber, discNumber, imageUrl, artistIds, albumArtistId, genres, genreIds, favorite, playCount)
internal fun TrackDto.toDomain() = Track(id, name, albumId, album, artists, durationTicks, trackNumber, discNumber, imageUrl, artistIds, albumArtistId, genres, genreIds, favorite = favorite, playCount = playCount)
