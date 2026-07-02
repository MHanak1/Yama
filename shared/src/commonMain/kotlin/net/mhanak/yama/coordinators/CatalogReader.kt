package net.mhanak.yama.coordinators

import net.mhanak.yama.media.download.CatalogCache
import net.mhanak.yama.media.download.DownloadRepository
import net.mhanak.yama.media.download.TrackListKind
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.model.TrackUserData
import net.mhanak.yama.media.model.TrackUserDataStore
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.media.sources.local.StoredTrack
import java.io.File

/**
 * Read-through track-list cache: online, fetches from the [source] and writes through to [catalogCache]
 * (so an offline visit later works); offline, serves the cached list or falls back to [downloads]'s
 * raw offline rows. Also seeds [userData] from every authoritative online fetch so the store overlay
 * reflects server truth — including favourites changed on another device — wherever these tracks appear.
 *
 * Pure: no coroutine scope of its own — callers provide their own suspend context.
 */
class CatalogReader(
    private val source: () -> MusicSource,
    private val catalogCache: CatalogCache,
    private val downloads: DownloadRepository,
    private val userData: TrackUserDataStore,
) {
    /**
     * Fetch a container's track list with the catalog cache as a fallback tier: online, fetch from the
     * source and write the result through to the cache (so a later offline visit works); offline, serve
     * the cached list. A downloaded album already has its track list cached at download time, so its
     * detail page works offline even if never visited online. Detail views call this instead of the
     * source directly.
     */
    suspend fun tracksFor(kind: TrackListKind, containerId: String, fetch: suspend () -> List<Track>): List<Track> {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey()

        var fetchFailure: Throwable? = null
        if (src.isReachable.value) {
            val result = runCatching { fetch() }
            val fresh = result.getOrNull()
            if (fresh != null && fresh.isNotEmpty()) {
                if (key != null) catalogCache.saveTrackList(key, kind, containerId, fresh)
                seedUserData(fresh)
                return fresh
            }
            fetchFailure = result.exceptionOrNull()
        }

        if (key != null) {
            catalogCache.loadTrackList(key, kind, containerId)?.let { return it }
            offlineTracksFor(key, kind, containerId).takeIf { it.isNotEmpty() }?.let { return it }
        }

        fetchFailure?.let { throw it }
        return emptyList()
    }

    /**
     * All-tracks query with an offline fallback to the downloads index. Reachable → source; offline →
     * downloaded tracks, sorted and paginated in memory. [favoritesOnly] is ignored offline (the
     * downloads index has favourite flags but we don't filter on them here).
     */
    suspend fun getAllTracks(
        limit: Int, offset: Int, sortBy: TrackSortOrder,
        favoritesOnly: Boolean = false, searchTerm: String? = null,
    ): List<Track> {
        val src = source()
        if (src.isReachable.value) {
            val result = runCatching { src.getAllTracks(limit, offset, sortBy, favoritesOnly, searchTerm) }
            val fresh = result.getOrNull()
            if (fresh != null) return fresh.also { seedUserData(it) }
            // Online fetch failed — try offline before surfacing the error.
            val key = (src as? OfflineCapable)?.downloadSourceKey()
            if (key != null) {
                val offline = offlineTracksFor(key, TrackListKind.All, null, limit, offset, sortBy, searchTerm)
                if (offline.isNotEmpty()) return offline
            }
            throw result.exceptionOrNull()!!
        }
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return emptyList()
        return offlineTracksFor(key, TrackListKind.All, null, limit, offset, sortBy, searchTerm)
    }

    /** Artist track list with an offline fallback to downloaded rows for that artist. */
    suspend fun getTracksForArtist(artistId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        val src = source()
        if (src.isReachable.value) {
            val result = runCatching { src.getTracksForArtist(artistId, limit, offset, sortBy) }
            val fresh = result.getOrNull()
            if (fresh != null) return fresh.also { seedUserData(it) }
            val key = (src as? OfflineCapable)?.downloadSourceKey()
            if (key != null) {
                val offline = offlineTracksFor(key, TrackListKind.Artist, artistId, limit, offset, sortBy)
                if (offline.isNotEmpty()) return offline
            }
            throw result.exceptionOrNull()!!
        }
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return emptyList()
        return offlineTracksFor(key, TrackListKind.Artist, artistId, limit, offset, sortBy)
    }

    /** Genre track list with an offline fallback to downloaded rows for that genre. */
    suspend fun getTracksForGenre(genreId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        val src = source()
        if (src.isReachable.value) {
            val result = runCatching { src.getTracksForGenre(genreId, limit, offset, sortBy) }
            val fresh = result.getOrNull()
            if (fresh != null) return fresh.also { seedUserData(it) }
            val key = (src as? OfflineCapable)?.downloadSourceKey()
            if (key != null) {
                val offline = offlineTracksFor(key, TrackListKind.Genre, genreId, limit, offset, sortBy)
                if (offline.isNotEmpty()) return offline
            }
            throw result.exceptionOrNull()!!
        }
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return emptyList()
        return offlineTracksFor(key, TrackListKind.Genre, genreId, limit, offset, sortBy)
    }

    /**
     * Seed the live [userData] store from a freshly fetched (online, authoritative) track list, so the
     * overlay reflects server truth — including favourites changed on another device — wherever these
     * tracks appear. Replaces the old read-time `applyTrackFavorites` overlay: offline correctness now
     * comes from seeding the store with the durable favourite set on partition load, so offline lists are
     * deliberately *not* fed here (their frozen favourites would clobber that seed).
     */
    private fun seedUserData(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        userData.merge(tracks.associate { it.id to TrackUserData(it.favorite, it.playCount) })
    }

    /**
     * Filter, sort, paginate and convert downloaded [StoredTrack] rows to [Track] for offline browsing.
     * Sort fields that aren't on [Track] (year, downloadedAt, lastPlayedAt) are read from [StoredTrack]
     * before conversion. Play count is tracked on the row (bumped on each completed play), so
     * [TrackSortOrder.PlayCount] sorts by it offline too.
     */
    private fun offlineTracksFor(
        sourceKey: String,
        kind: TrackListKind,
        containerId: String?,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
        sortBy: TrackSortOrder = TrackSortOrder.Alphabetical,
        searchTerm: String? = null,
    ): List<Track> {
        val rows = downloads.rawOfflineTracks(sourceKey, kind, containerId)
        val filtered = if (searchTerm.isNullOrBlank()) rows
        else rows.filter { it.title.contains(searchTerm, ignoreCase = true) }
        val sorted = when (sortBy) {
            TrackSortOrder.Alphabetical -> filtered.sortedBy { it.title.lowercase() }
            TrackSortOrder.ReleaseDate -> filtered.sortedByDescending { it.year }
            TrackSortOrder.PlayCount -> filtered.sortedWith(compareByDescending<StoredTrack> { it.playCount }.thenBy { it.title.lowercase() })
            TrackSortOrder.RecentlyAdded -> filtered.sortedByDescending { it.downloadedAt ?: 0L }
            TrackSortOrder.RecentlyPlayed -> filtered.sortedByDescending { it.lastPlayedAt ?: 0L }
            TrackSortOrder.Random -> filtered.shuffled()
        }
        return sorted.drop(offset).take(limit).map { it.toTrack() }
    }
}

private fun StoredTrack.toTrack() = Track(
    id = id,
    name = title,
    albumId = albumId,
    album = album,
    artists = artists.ifEmpty { listOfNotNull(albumArtist) },
    durationTicks = durationMs?.let { it * 10_000L },
    trackNumber = trackNumber,
    discNumber = discNumber,
    // artworkPath is a bare absolute path; convert to a file:// URI so Coil can load it.
    imageUrl = artworkPath?.let { if ("://" in it) it else File(it).toURI().toString() },
    artistIds = artistIds,
    albumArtistId = albumArtistId,
    genres = genres,
    genreIds = genreIds,
    favorite = favorite,
    playCount = playCount,
)
