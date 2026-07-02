package net.mhanak.yama.media.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.PlayableResolver
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.local.LocalLibraryStore
import net.mhanak.yama.media.sources.local.Retention
import net.mhanak.yama.media.sources.local.StoredTrack
import net.mhanak.yama.util.StreamingQuality
import java.io.File

/**
 * Source-agnostic owner of the offline download/cache index (see DOWNLOADS_PLAN.md, Plan C). Rows live
 * in their own [store] (`downloads_index.json`, distinct from the local-files index) partitioned by
 * `sourceKey`, so a local rescan never rewrites download rows and `clear(sourceKey)` is a scoped wipe.
 *
 * It owns:
 * - **the resolution ladder** ([resolveStream]/[resolveArtwork], via [PlayableResolver]) — local copy
 *   when fresh or offline, origin URL when online & stale (with a re-download enqueued),
 * - **availability** ([availableTrackIds] + derived album/artist/genre sets) the UI grays from,
 * - **eviction / clearing** of a partition's rows + files.
 *
 * Writing rows (the actual byte fetch) is [DownloadManager]'s job; the repository only holds completed
 * entries and derives state from them.
 */
class DownloadRepository(
    private val store: LocalLibraryStore,
    private val downloadsDir: File,
    private val catalogCache: CatalogCache,
) : PlayableResolver {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The partition the UI currently browses; availability is reported for this source only. Set by
    // AppContainer on launch and whenever the active source / session changes.
    private var activeSourceKey: String? = null

    private val _availableTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val availableTrackIds: StateFlow<Set<String>> = _availableTrackIds.asStateFlow()

    private val _availableAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val availableAlbumIds: StateFlow<Set<String>> = _availableAlbumIds.asStateFlow()

    private val _availableArtistIds = MutableStateFlow<Set<String>>(emptySet())
    val availableArtistIds: StateFlow<Set<String>> = _availableArtistIds.asStateFlow()

    private val _availableGenreIds = MutableStateFlow<Set<String>>(emptySet())
    val availableGenreIds: StateFlow<Set<String>> = _availableGenreIds.asStateFlow()

    // Pinned-only subsets — used for the "downloaded" indicator (DownloadButton, track badges).
    // Cached (auto-play) entries are excluded so the UI doesn't falsely imply an explicit download.
    private val _pinnedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedTrackIds: StateFlow<Set<String>> = _pinnedTrackIds.asStateFlow()

    private val _pinnedAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedAlbumIds: StateFlow<Set<String>> = _pinnedAlbumIds.asStateFlow()

    private val _pinnedArtistIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedArtistIds: StateFlow<Set<String>> = _pinnedArtistIds.asStateFlow()

    private val _pinnedGenreIds = MutableStateFlow<Set<String>>(emptySet())
    val pinnedGenreIds: StateFlow<Set<String>> = _pinnedGenreIds.asStateFlow()

    /** Set when a stale row is served online so the manager can re-fetch (Pinned) or evict (Cached). */
    var onStaleOnline: ((sourceKey: String, row: StoredTrack) -> Unit)? = null

    // Partitions whose staleness has already been checked this session, so [refreshStaleness] runs the
    // version-check pass at most once per source per launch (it's a network call per downloaded row).
    private val stalenessChecked = mutableSetOf<String>()

    fun setActiveSourceKey(key: String?) {
        if (key == activeSourceKey) return
        activeSourceKey = key
        recompute()
    }

    /** Recompute the availability sets from the active partition's rows (the manager calls this after
     *  a download lands or a row is evicted). Fans out from the stored rows the same way
     *  `LocalSource.deriveAndEmit` builds its catalog. */
    fun recompute() {
        val key = activeSourceKey
        val rows = if (key == null) emptyList() else store.all(key)
        val pinned = rows.filter { it.retention == Retention.Pinned }
        _availableTrackIds.value = rows.mapTo(HashSet()) { it.id }
        _availableAlbumIds.value = rows.mapNotNullTo(HashSet()) { it.albumId }
        _availableArtistIds.value = rows.flatMapTo(HashSet()) { it.artistIds + listOfNotNull(it.albumArtistId) }
        _availableGenreIds.value = rows.flatMapTo(HashSet()) { it.genreIds }
        _pinnedTrackIds.value = pinned.mapTo(HashSet()) { it.id }
        _pinnedAlbumIds.value = pinned.mapNotNullTo(HashSet()) { it.albumId }
        _pinnedArtistIds.value = pinned.flatMapTo(HashSet()) { it.artistIds + listOfNotNull(it.albumArtistId) }
        _pinnedGenreIds.value = pinned.flatMapTo(HashSet()) { it.genreIds }
    }

    /**
     * Compare each downloaded row's stored [StoredTrack.originVersion] against the source's current
     * content version and mark changed rows `stale`. Once a row is stale the existing ladder serves the
     * origin (when online) and [onStaleOnline] re-fetches a pinned copy / evicts a cached one — so this
     * is the only piece that *sets* staleness; everything downstream already consumes it.
     *
     * Runs in the background (never blocks playback) and at most once per partition per session, and
     * only while the source is reachable (so the once-guard isn't consumed by an offline no-op). Call
     * opportunistically — e.g. after a library refresh or when reachability returns.
     */
    fun refreshStaleness(source: MusicSource) {
        val key = (source as? OfflineCapable)?.downloadSourceKey() ?: return
        if (!source.isReachable.value) return
        synchronized(stalenessChecked) { if (!stalenessChecked.add(key)) return }
        scope.launch {
            val rows = store.all(key)
            if (rows.isEmpty()) return@launch
            // One batch request (or a few chunks) instead of one request per downloaded track.
            // Also refreshes favorite and playCount so user-data stays in sync without extra passes.
            val snapshots = runCatching {
                (source as? OfflineCapable)?.fetchTrackSnapshots(rows.map { it.id }) ?: emptyMap()
            }.getOrDefault(emptyMap())
            if (snapshots.isEmpty()) return@launch

            var changed = false
            // Collect user-data updates to patch the catalog cache after the index pass.
            val catalogUpdates = mutableMapOf<String, Pair<Boolean, Int>>()
            for (row in rows) {
                val snap = snapshots[row.id] ?: continue
                val nowStale = !row.stale && row.originVersion != null
                    && snap.contentVersion != null && snap.contentVersion != row.originVersion
                // Only update favorite/playCount when the server actually returned user data
                // (null means UserData was absent from the response, not that the track is unfavourited).
                val newFavorite = snap.favorite ?: row.favorite
                val newPlayCount = snap.playCount ?: row.playCount
                val userDataChanged = newFavorite != row.favorite || newPlayCount != row.playCount
                if (nowStale || userDataChanged) {
                    store.put(row.copy(
                        stale = row.stale || nowStale,
                        favorite = newFavorite,
                        playCount = newPlayCount,
                    ))
                    changed = true
                }
                if (snap.favorite != null) catalogUpdates[row.id] = snap.favorite to (snap.playCount ?: row.playCount)
            }
            if (changed) recompute()
            // Keep the catalog cache in sync so album/artist/genre detail views also show fresh
            // favorites offline without the user needing to re-open each container while online.
            catalogCache.updateTrackUserData(key, catalogUpdates)
        }
    }

    /**
     * Downloaded content grouped into albums, built straight from the index so it works fully offline
     * (independent of catalog hydration). Tracks with no album are omitted. Sorted by album name.
     * [retention] filters to explicit downloads (`Pinned`) or the recent-tracks cache (`Cached`); null
     * includes both.
     */
    fun downloadedAlbums(sourceKey: String, retention: Retention? = null): List<DownloadedAlbum> =
        store.all(sourceKey)
            .filter { it.albumId != null && (retention == null || it.retention == retention) }
            .groupBy { it.albumId!! }
            .map { (id, rows) ->
                val first = rows.first()
                DownloadedAlbum(
                    id = id,
                    name = first.album ?: first.title,
                    artist = first.albumArtist ?: first.artists.firstOrNull(),
                    artworkPath = rows.firstNotNullOfOrNull { it.artworkPath },
                    trackCount = rows.size,
                    // The shared album quality, or null when its tracks were fetched at mixed qualities.
                    quality = rows.map { it.quality }.distinct().singleOrNull(),
                    sizeBytes = rows.sumOf { rowSize(it) },
                    stale = rows.any { it.stale },
                    // Cached-only (no explicit download): only appears when "Show cached" includes it.
                    cachedOnly = rows.none { it.retention == Retention.Pinned },
                )
            }
            .sortedBy { it.name.lowercase() }

    /** Total on-disk size of a partition's downloads (optionally one [retention] tier), for the
     *  storage summary. Sums recorded [StoredTrack.sizeBytes], statting the file for legacy 0 rows. */
    fun totalSizeBytes(sourceKey: String, retention: Retention? = null): Long =
        store.all(sourceKey)
            .filter { retention == null || it.retention == retention }
            .sumOf { rowSize(it) }

    /** A row's byte size: the value recorded at download time, or a stat of the file for legacy rows
     *  written before [StoredTrack.sizeBytes] existed. */
    private fun rowSize(row: StoredTrack): Long =
        if (row.sizeBytes > 0) row.sizeBytes
        else runCatching { File(row.path).length() }.getOrDefault(0L)

    /** A single downloaded album's summary, or null when nothing of it is downloaded. */
    fun downloadedAlbum(sourceKey: String, albumId: String): DownloadedAlbum? =
        downloadedAlbums(sourceKey).firstOrNull { it.id == albumId }

    /**
     * Downloaded tracks, optionally restricted to one [albumId] and/or a [retention] (explicit `Pinned`
     * downloads vs the `Cached` recent-tracks cache; null = both). Ordered by disc/track number then
     * title. Built from the index so it works offline. Carries each row's stored [StreamingQuality].
     */
    fun downloadedTracks(sourceKey: String, albumId: String? = null, retention: Retention? = null): List<DownloadedTrack> =
        store.all(sourceKey)
            .filter { (albumId == null || it.albumId == albumId) && (retention == null || it.retention == retention) }
            .map {
                DownloadedTrack(
                    id = it.id,
                    title = it.title,
                    artist = it.artists.firstOrNull() ?: it.albumArtist,
                    albumId = it.albumId,
                    album = it.album,
                    trackNumber = it.trackNumber,
                    discNumber = it.discNumber,
                    quality = it.quality,
                    retention = it.retention,
                )
            }
            .sortedWith(
                compareBy(
                    { it.discNumber ?: 0 },
                    { it.trackNumber ?: Int.MAX_VALUE },
                    { it.title.lowercase() },
                ),
            )

    fun isDownloaded(sourceKey: String, id: String): Boolean = store.get(sourceKey, id) != null

    fun row(sourceKey: String, id: String): StoredTrack? = store.get(sourceKey, id)

    /**
     * The streaming quality an album was downloaded at, or null if it isn't downloaded or its tracks
     * were fetched at mixed qualities (a [StreamingQuality]-uniform album reports that one). Drives the
     * per-album quality indicator in the detail view.
     */
    fun albumQuality(sourceKey: String, albumId: String): StreamingQuality? {
        val qualities = store.all(sourceKey)
            .filter { it.albumId == albumId && it.retention == Retention.Pinned }
            .map { it.quality }
        if (qualities.isEmpty()) return null
        return qualities.distinct().singleOrNull()
    }

    fun audioDir(sourceKey: String): File =
        File(File(downloadsDir, sanitizeSourceKey(sourceKey)), "audio").apply { mkdirs() }

    fun artDir(sourceKey: String): File =
        File(File(downloadsDir, sanitizeSourceKey(sourceKey)), "art").apply { mkdirs() }

    // --- Resolution ladder (PlayableResolver) ------------------------------------------------------

    override suspend fun resolveStream(source: MusicSource, track: Track): String {
        val key = (source as? OfflineCapable)?.downloadSourceKey()
        val row = key?.let { store.get(it, track.id) }
        val reachable = source.isReachable.value
        return when {
            // Fresh local copy → play the file.
            row != null && !row.stale -> row.path.toPlayableUri()
            // Stale but online → stream origin now, and re-fetch (pinned) / evict (cached) in the
            // background. Never delete-then-download: the file stays until a fresh copy lands.
            row != null && reachable -> {
                onStaleOnline?.invoke(key!!, row)
                source.getStreamUrl(track.id, row.quality)
            }
            // Stale and offline → keep the stale copy rather than going silent.
            row != null -> row.path.toPlayableUri()
            // No entry → origin (the caller gates play on reachability, so offline never reaches here).
            else -> source.getStreamUrl(track.id)
        }
    }

    override suspend fun resolveArtwork(source: MusicSource, track: Track): String? {
        val key = (source as? OfflineCapable)?.downloadSourceKey()
        val row = key?.let { store.get(it, track.id) }
        // A downloaded cover is preferred whenever the copy is usable (fresh, or we're offline).
        if (row?.artworkPath != null && (!row.stale || !source.isReachable.value)) return row.artworkPath
        return track.imageUrl ?: source.getArtworkUrl(track.id)
    }

    /**
     * All downloaded rows for [sourceKey], filtered by [kind]/[containerId], as raw [StoredTrack]s.
     * Returns the unordered, unfiltered set so the caller can sort and paginate as needed.
     * Used as the offline fallback tier for browse queries when the network is unreachable.
     */
    fun rawOfflineTracks(sourceKey: String, kind: TrackListKind, containerId: String?): List<StoredTrack> {
        val rows = store.all(sourceKey)
        return when (kind) {
            TrackListKind.All -> rows
            TrackListKind.Album -> rows.filter { it.albumId == containerId }
            TrackListKind.Artist -> rows.filter {
                containerId != null && (containerId in it.artistIds || containerId == it.albumArtistId)
            }
            TrackListKind.Genre -> rows.filter { containerId != null && containerId in it.genreIds }
            TrackListKind.Playlist -> emptyList()
        }
    }

    /** Wipe a source's downloads — rows, audio/art files, and its cached catalog — in one shot. */
    fun clear(sourceKey: String) {
        store.replaceAll(sourceKey, emptyList())
        File(downloadsDir, sanitizeSourceKey(sourceKey)).deleteRecursively()
        catalogCache.clear(sourceKey)
        if (sourceKey == activeSourceKey) recompute()
    }
}

/** An album with downloaded tracks, for the downloaded-music screen. [artworkPath] is a local file
 *  URI/path (offline-resolvable). [quality] is null when the album's tracks have mixed qualities. */
data class DownloadedAlbum(
    val id: String,
    val name: String,
    val artist: String?,
    val artworkPath: String?,
    val trackCount: Int,
    val quality: StreamingQuality?,
    val sizeBytes: Long = 0,
    /** True when any of the album's downloaded tracks is stale (origin changed since download). */
    val stale: Boolean = false,
    /** True when none of the album's tracks are explicitly downloaded (all are recent-tracks cache). */
    val cachedOnly: Boolean = false,
)

/** A single downloaded track row, for the downloads-management lists. */
data class DownloadedTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val albumId: String?,
    val album: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val quality: StreamingQuality?,
    val retention: Retention = Retention.Pinned,
)

/** Desktop stores absolute file paths (→ file:// URI); Android download paths are app files (also
 *  absolute). A value that already carries a scheme (`content://`, `file://`) is passed through. */
internal fun String.toPlayableUri(): String = if ("://" in this) this else File(this).toURI().toString()
