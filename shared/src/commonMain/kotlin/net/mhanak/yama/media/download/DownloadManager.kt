package net.mhanak.yama.media.download

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.local.LocalLibraryStore
import net.mhanak.yama.media.sources.local.Retention
import net.mhanak.yama.media.sources.local.StoredTrack
import net.mhanak.yama.util.StreamingQuality
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A single track's progress through the download pipeline, surfaced to the Downloads UI. Carries the
 * track's container ids/name so the UI can filter jobs by album/artist/genre (the detail-view button)
 * and group them by album (the Downloads hub) without a round-trip.
 */
@Immutable
data class DownloadJob(
    val trackId: String,
    val title: String,
    val state: DownloadState,
    val albumId: String? = null,
    val album: String? = null,
    val artistIds: List<String> = emptyList(),
    val genreIds: List<String> = emptyList(),
)

sealed interface DownloadState {
    data object Queued : DownloadState
    /** Held by the Wi-Fi-only scheduler, waiting for an unmetered network. */
    data object WaitingForNetwork : DownloadState
    /** [progress] in 0f..1f, or -1f when the server sends no Content-Length. */
    data class Running(val progress: Float) : DownloadState
    data object Completed : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Drives the actual byte fetch for downloads. Foreground/manual for now (Phase 2): it resolves a
 * stream URL at the chosen quality, streams the bytes to the partition's `audio/` dir, stores the
 * album cover once, and writes the completed [StoredTrack] row into the [DownloadRepository]'s index.
 * A background, Wi-Fi-aware scheduler (Phase 3) will later wrap [downloadOne] without changing it.
 */
class DownloadManager(
    private val store: LocalLibraryStore,
    private val repo: DownloadRepository,
    private val catalogCache: CatalogCache,
    private val source: () -> MusicSource,
    private val defaultQuality: () -> StreamingQuality,
    private val cacheQuality: () -> StreamingQuality = defaultQuality,
    private val wifiOnly: () -> Boolean = { false },
    private val backgroundDownloads: () -> Boolean = { true },
    private val cacheBudgetMb: () -> Int = { 1024 },
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _downloads = MutableStateFlow<List<DownloadJob>>(emptyList())
    val downloads: StateFlow<List<DownloadJob>> = _downloads.asStateFlow()

    // The full [Track] for each in-flight/queued/failed job, so a scheduled request (which carries only
    // an id) and a retry can recover the track without a round-trip. Kept until the job succeeds or is
    // cancelled (a Failed entry stays so [retry] can re-queue it).
    private val pendingTracks = ConcurrentHashMap<String, Track>()

    // Serial, Wi-Fi-aware background queue. When "Background downloads" is off, requests run
    // immediately on [scope] instead (the foreground fallback the plan calls for). [onWaiting] flips a
    // held job's row to WaitingForNetwork so the UI can explain the stall (and offer "Download now").
    private val scheduler = DownloadScheduler(
        wifiOnly = wifiOnly,
        onWaiting = { trackId -> updateState(trackId, DownloadState.WaitingForNetwork) },
        execute = { executeRequest(it) },
    )

    init {
        // The resolution ladder hands stale-but-online entries back here: re-fetch a pinned download,
        // drop a cached one (no point re-caching what may never replay).
        repo.onStaleOnline = { key, row -> onStaleOnline(key, row) }
    }

    /** Route a request through the background scheduler, or run it at once when background downloads
     *  are disabled (the user tapped download and wants it now, metered or not). */
    private fun submit(request: DownloadRequest) {
        if (backgroundDownloads()) scheduler.enqueue(request)
        else scope.launch { runCatching { executeRequest(request) } }
    }

    // --- Enqueue entry points ----------------------------------------------------------------------

    fun enqueueTracks(tracks: List<Track>, quality: StreamingQuality? = null) {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        val q = quality ?: defaultQuality()
        val pending = tracks.filter { addJob(it) }
        if (pending.isEmpty()) return
        for (t in pending) {
            pendingTracks[t.id] = t
            submit(DownloadRequest(key, t.id, q, Retention.Pinned, force = false))
        }
    }

    fun enqueueAlbum(albumId: String, quality: StreamingQuality? = null) =
        enqueueContainer(quality) { src, key ->
            src.getTracksForAlbum(albumId).also { catalogCache.saveTrackList(key, TrackListKind.Album, albumId, it) }
        }

    fun enqueueArtist(artistId: String, quality: StreamingQuality? = null) =
        enqueueContainer(quality) { src, key ->
            src.getTracksForArtist(artistId, limit = 1_000).also {
                catalogCache.saveTrackList(key, TrackListKind.Artist, artistId, it)
            }
        }

    fun enqueueGenre(genreId: String, quality: StreamingQuality? = null) =
        enqueueContainer(quality) { src, key ->
            src.getTracksForGenre(genreId, limit = 1_000).also {
                catalogCache.saveTrackList(key, TrackListKind.Genre, genreId, it)
            }
        }

    fun enqueuePlaylist(playlistId: String, quality: StreamingQuality? = null) =
        enqueueContainer(quality) { src, key ->
            src.getTracksForPlaylist(playlistId).also {
                catalogCache.saveTrackList(key, TrackListKind.Playlist, playlistId, it)
            }
        }

    fun redownloadAlbum(albumId: String, quality: StreamingQuality) =
        redownloadContainer(quality) { src, key ->
            src.getTracksForAlbum(albumId).also { catalogCache.saveTrackList(key, TrackListKind.Album, albumId, it) }
        }

    fun redownloadArtist(artistId: String, quality: StreamingQuality) =
        redownloadContainer(quality) { src, _ -> src.getTracksForArtist(artistId, limit = 1_000) }

    fun redownloadGenre(genreId: String, quality: StreamingQuality) =
        redownloadContainer(quality) { src, _ -> src.getTracksForGenre(genreId, limit = 1_000) }

    fun redownloadPlaylist(playlistId: String, quality: StreamingQuality) =
        redownloadContainer(quality) { src, _ -> src.getTracksForPlaylist(playlistId) }

    /** Re-download a container's *already-downloaded* tracks at [quality]; untouched (never-downloaded)
     *  tracks are skipped so "re-download at X" doesn't pull a whole album the user never saved. */
    private fun redownloadContainer(quality: StreamingQuality, fetch: suspend (MusicSource, String) -> List<Track>) {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        scope.launch {
            val tracks = runCatching { fetch(src, key) }.getOrDefault(emptyList())
                .filter { store.get(key, it.id) != null }
            if (tracks.isNotEmpty()) redownload(tracks, quality)
        }
    }

    /** Change the stored quality of already-downloaded tracks to [quality]; forces a refetch. Keeps
     *  each row's existing retention (a cached entry stays cached). A track already stored at exactly
     *  [quality] is skipped — picking the current quality is a no-op. */
    fun redownload(tracks: List<Track>, quality: StreamingQuality) {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        // Nothing to do for a copy already at this quality (covers "Change quality" → current quality).
        val changed = tracks.filter { store.get(key, it.id)?.quality != quality }
        val pending = changed.filter { addJob(it) }
        for (t in pending) {
            pendingTracks[t.id] = t
            val retention = store.get(key, t.id)?.retention ?: Retention.Pinned
            submit(DownloadRequest(key, t.id, quality, retention, force = true))
        }
    }

    /**
     * Record that [track] was just played: refresh its LRU timestamp if it's already an offline row, or
     * (when [cache] is on) fetch it as a [Retention.Cached] recent-track. Cached fetches are quiet — no
     * [DownloadJob] is shown for them — and trigger an LRU trim once they land.
     */
    fun onTrackPlayed(track: Track, cache: Boolean) {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        val existing = store.get(key, track.id)
        if (existing != null) {
            scope.launch { store.put(existing.copy(lastPlayedAt = System.currentTimeMillis())) }
            return
        }
        if (!cache) return
        enqueueCachedTrack(track, key)
    }

    /**
     * Pre-cache an upcoming queue track before it plays. Suspends until the download completes (or
     * returns immediately when the track is already stored or in-flight), so the caller can sequence
     * look-ahead downloads one at a time. Does not touch LRU timestamps.
     */
    suspend fun cacheUpcoming(track: Track) {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        enqueueCachedTrack(track, key)?.join()
    }

    /**
     * Shared submit path for all auto-cache requests (played and look-ahead). Deduplicates against
     * both the store (already on disk) and [pendingTracks] (already in-flight), so neither caller
     * needs to carry that logic. No [DownloadJob] row — cached fetches are invisible in the Downloads
     * list. Bypasses the scheduler so the Wi-Fi-only constraint never blocks auto-caching.
     * Returns the launched [Job] so a sequential caller can [Job.join] on it, or null when skipped.
     */
    private fun enqueueCachedTrack(track: Track, key: String): Job? {
        if (store.get(key, track.id) != null) return null
        if (pendingTracks.containsKey(track.id)) return null
        pendingTracks[track.id] = track
        val request = DownloadRequest(key, track.id, cacheQuality(), Retention.Cached, force = false)
        return scope.launch { runCatching { executeRequest(request) } }
    }

    /** Evict cached (never pinned) rows oldest-first by [StoredTrack.lastPlayedAt] until the cache is
     *  under the configured size budget. Runs after each auto-cache and when the budget changes. */
    fun trimCache() {
        scope.launch {
            val key = (source() as? OfflineCapable)?.downloadSourceKey() ?: return@launch
            val budgetBytes = cacheBudgetMb().toLong() * 1024 * 1024
            val cached = store.all(key).filter { it.retention == Retention.Cached }
            var total = cached.sumOf { fileSize(it.path) }
            if (total <= budgetBytes) return@launch
            val ordered = cached.sortedBy { it.lastPlayedAt ?: it.downloadedAt ?: 0L }
            var evicted = false
            val touchedAlbums = mutableSetOf<String>()
            for (row in ordered) {
                if (total <= budgetBytes) break
                total -= fileSize(row.path)
                deleteFiles(key, row.id)
                store.remove(key, listOf(row.id))
                row.albumId?.let { touchedAlbums += it }
                evicted = true
            }
            touchedAlbums.forEach { pruneAlbumTrackListIfEmpty(key, it) }
            if (evicted) repo.recompute()
        }
    }

    private fun fileSize(path: String): Long =
        runCatching { File(path).length() }.getOrDefault(0L)

    /** Remove every downloaded track of an album, plus its files and shared cover. */
    fun removeAlbum(sourceKey: String, albumId: String) {
        scope.launch {
            val ids = store.all(sourceKey).filter { it.albumId == albumId }.map { it.id }
            if (ids.isEmpty()) return@launch
            ids.forEach { deleteFiles(sourceKey, it) }
            // Album art is shared per-album, so it's only safe to delete now the whole album is going.
            repo.artDir(sourceKey).listFiles()?.filter { it.nameWithoutExtension == albumId }?.forEach { it.delete() }
            store.remove(sourceKey, ids)
            pruneAlbumTrackListIfEmpty(sourceKey, albumId)
            _downloads.update { list -> list.filterNot { j -> j.trackId in ids } }
            repo.recompute()
        }
    }

    /** Re-download already-stored tracks (resolved by id from the index) at [quality], using their
     *  stored metadata rather than re-fetching the container list. */
    fun redownloadStored(sourceKey: String, ids: List<String>, quality: StreamingQuality) {
        val tracks = ids.mapNotNull { store.get(sourceKey, it)?.toTrack() }
        if (tracks.isNotEmpty()) redownload(tracks, quality)
    }

    /** Re-download all of an album's stored tracks at [quality] (from the index, no container fetch). */
    fun redownloadAlbumStored(sourceKey: String, albumId: String, quality: StreamingQuality) =
        redownloadStored(sourceKey, store.all(sourceKey).filter { it.albumId == albumId }.map { it.id }, quality)

    /** Promote a cached (recent-tracks) row to a pinned download so it's kept past the cache trim. The
     *  file is already present — only the row's retention changes. No-op for an already-pinned row. */
    fun keepDownloaded(sourceKey: String, trackId: String) {
        scope.launch {
            val row = store.get(sourceKey, trackId) ?: return@launch
            if (row.retention == Retention.Pinned) return@launch
            store.put(row.copy(retention = Retention.Pinned))
            repo.recompute()
        }
    }

    /** Remove a download's row + files. */
    fun removeDownload(sourceKey: String, trackId: String) {
        scope.launch {
            val albumId = store.get(sourceKey, trackId)?.albumId
            deleteFiles(sourceKey, trackId)
            store.remove(sourceKey, listOf(trackId))
            pruneAlbumTrackListIfEmpty(sourceKey, albumId)
            _downloads.update { it.filterNot { j -> j.trackId == trackId } }
            repo.recompute()
        }
    }

    /** Clear a finished/failed job from the list once the user has seen it. */
    fun dismissJob(trackId: String) = _downloads.update { it.filterNot { j -> j.trackId == trackId } }

    /** Cancel a queued or in-flight download (Downloads screen). Drops its job row. */
    fun cancelJob(trackId: String) {
        scheduler.cancel(trackId)
        pendingTracks.remove(trackId)
        _downloads.update { it.filterNot { j -> j.trackId == trackId } }
    }

    /** Re-queue a failed download (Downloads screen). Reuses the remembered [Track]. */
    fun retry(trackId: String) {
        val track = pendingTracks[trackId] ?: return
        enqueueTracks(listOf(track))
    }

    /** Override the Wi-Fi-only hold for a single job (the "Download now" action on a WaitingForNetwork
     *  row): the scheduler runs it on the next pump regardless of the metered-network check. */
    fun downloadNow(trackId: String) = scheduler.runNow(trackId)

    /** Whether the background queue is paused ("Pause all"). Pausing cancels the in-flight download and
     *  re-queues it (no byte-level resume — it restarts on resume); queued ones hold until [setPaused]
     *  resumes. No effect when background downloads are off (each runs immediately). */
    val paused: StateFlow<Boolean> = scheduler.paused
    fun setPaused(value: Boolean) = scheduler.setPaused(value)

    /** Re-download every stale row in the active partition at its stored quality (the "Update all"
     *  action). Stale rows keep serving their old file until the fresh copy lands. */
    fun redownloadStale() {
        scope.launch {
            val key = (source() as? OfflineCapable)?.downloadSourceKey() ?: return@launch
            store.all(key).filter { it.stale }
                .groupBy { it.quality }
                .forEach { (quality, rows) -> redownload(rows.map { it.toTrack() }, quality ?: defaultQuality()) }
        }
    }

    /** Remove every downloaded track of an artist (and their files). Mirrors [removeAlbum]; album art
     *  is left to `clear(sourceKey)` since covers are shared per-album, not per-artist. */
    fun removeArtist(sourceKey: String, artistId: String) =
        removeWhere(sourceKey) { artistId in it.artistIds || it.albumArtistId == artistId }

    /** Remove every downloaded track of a genre (and their files). */
    fun removeGenre(sourceKey: String, genreId: String) =
        removeWhere(sourceKey) { genreId in it.genreIds }

    private fun removeWhere(sourceKey: String, predicate: (StoredTrack) -> Boolean) {
        scope.launch {
            val rows = store.all(sourceKey).filter(predicate)
            val ids = rows.map { it.id }
            if (ids.isEmpty()) return@launch
            val albums = rows.mapNotNull { it.albumId }.toSet()
            ids.forEach { deleteFiles(sourceKey, it) }
            store.remove(sourceKey, ids)
            albums.forEach { pruneAlbumTrackListIfEmpty(sourceKey, it) }
            _downloads.update { list -> list.filterNot { j -> j.trackId in ids } }
            repo.recompute()
        }
    }

    /**
     * Run a single queued [DownloadRequest] — the unit the scheduler (or the immediate fallback)
     * invokes. Resolves the [Track] from [pendingTracks] (or, if it was lost, by id from the source),
     * fetches the bytes, and reflects the outcome in the [downloads] flow. A user cancellation drops
     * the job silently; any other failure leaves it Failed (and its [Track] cached for [retry]).
     */
    suspend fun executeRequest(request: DownloadRequest) {
        val src = source()
        // The active source/session changed while this sat in the queue — its partition no longer
        // matches, so the download would land in the wrong place. Drop it.
        if ((src as? OfflineCapable)?.downloadSourceKey() != request.sourceKey) {
            markFailed(request.trackId, IllegalStateException("Source changed"))
            return
        }
        val track = pendingTracks[request.trackId]
            ?: runCatching { src.getTracksByIds(listOf(request.trackId)) }.getOrNull()?.firstOrNull()
        if (track == null) {
            markFailed(request.trackId, IllegalStateException("Track unavailable"))
            return
        }
        // Ensure a visible job row exists (Cached auto-caches stay hidden). Normally `enqueueTracks`
        // already added it, but on Android a WorkManager worker may run in a fresh process where the
        // in-memory job list was lost — re-add so the Downloads screen reflects the in-flight download.
        if (request.retention != Retention.Cached) addJob(track)
        try {
            downloadOne(src, request.sourceKey, track, request.quality, force = request.force, retention = request.retention)
            markCompleted(request.trackId)
            pendingTracks.remove(request.trackId)
            if (request.retention == Retention.Cached) trimCache()
        } catch (c: CancellationException) {
            // A pause re-queue keeps the job (reset to Queued so it resumes); a user cancel drops it.
            if (scheduler.consumePauseRequeue(request.trackId)) {
                updateState(request.trackId, DownloadState.Queued)
            } else {
                _downloads.update { it.filterNot { j -> j.trackId == request.trackId } }
                pendingTracks.remove(request.trackId)
            }
            throw c
        } catch (t: Throwable) {
            markFailed(request.trackId, t) // keep pendingTracks entry so retry() can re-queue it
            return
        }
        repo.recompute()
    }

    private fun enqueueContainer(
        quality: StreamingQuality?,
        fetch: suspend (MusicSource, String) -> List<Track>,
    ) {
        val src = source()
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        scope.launch {
            val tracks = runCatching { fetch(src, key) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) enqueueTracks(tracks, quality)
        }
    }

    // --- The fetch ---------------------------------------------------------------------------------

    private suspend fun downloadOne(
        src: MusicSource, key: String, track: Track, quality: StreamingQuality,
        force: Boolean, retention: Retention = Retention.Pinned,
    ) {
        val existing = store.get(key, track.id)
        if (!force && existing != null && !existing.stale) {
            if (retention == Retention.Cached) return  // auto-cache never re-downloads an existing row
            // Explicit download (Pinned): if already pinned at the same quality, nothing to do.
            if (existing.retention == Retention.Pinned && existing.quality == quality) return
            // Cached at the same quality: promote to Pinned without re-downloading the bytes.
            if (existing.retention == Retention.Cached && existing.quality == quality) {
                store.put(existing.copy(retention = Retention.Pinned))
                return
            }
            // Quality differs (Cached or Pinned at wrong quality): fall through to re-download.
        }
        updateState(track.id, DownloadState.Running(-1f))

        val url = src.getStreamUrl(track.id, quality)
        val tmp = File(repo.audioDir(key), "${track.id}.tmp")
        val result = HttpDownloader.download(url, tmp) { d, t ->
            updateState(track.id, DownloadState.Running(if (t > 0) (d.toFloat() / t).coerceIn(0f, 1f) else -1f))
        }
        // Resolve the real container from the response and give the file its proper extension.
        val ext = contentTypeToExtension(result.contentType).let { if (it == "bin") "mp3" else it }
        val audioFile = File(repo.audioDir(key), "${track.id}.$ext")
        if (audioFile.exists()) audioFile.delete()
        if (!tmp.renameTo(audioFile)) { tmp.copyTo(audioFile, overwrite = true); tmp.delete() }
        // Remove any prior copy at a different extension (a re-download that changed container).
        repo.audioDir(key).listFiles()
            ?.filter { it.nameWithoutExtension == track.id && it != audioFile }
            ?.forEach { it.delete() }

        val artworkPath = track.albumId?.let { ensureAlbumArt(src, key, it, track) }
        val version = runCatching { (src as? OfflineCapable)?.getContentVersion(track.id) }.getOrNull()

        store.put(track.toStoredTrack(
            sourceKey = key,
            path = audioFile.absolutePath,
            sizeBytes = audioFile.length(),
            artworkPath = artworkPath ?: existing?.artworkPath,
            quality = quality,
            originVersion = version,
            // Explicit download (Pinned) always wins: promotes a cached row rather than keeping Cached.
            // Auto-cache (Cached) never downgrades an existing Pinned row.
            retention = if (retention == Retention.Pinned) Retention.Pinned else existing?.retention ?: retention,
        ))
        // Album-scoped invariant: as soon as an album has any offline-playable track (explicit download
        // or recent-tracks cache), persist its full track list so its detail page works offline — even
        // for a single-track download / a cached track, not just whole-album downloads.
        track.albumId?.let { ensureAlbumTrackList(src, key, it) }
    }

    /** Cache an album's full track list if it isn't already cached (so a partially-downloaded album
     *  still shows all its tracks offline). Best-effort — needs the source online, which it is during a
     *  download. */
    private suspend fun ensureAlbumTrackList(src: MusicSource, key: String, albumId: String) {
        if (catalogCache.loadTrackList(key, TrackListKind.Album, albumId) != null) return
        val tracks = runCatching { src.getTracksForAlbum(albumId) }.getOrDefault(emptyList())
        if (tracks.isNotEmpty()) catalogCache.saveTrackList(key, TrackListKind.Album, albumId, tracks)
    }

    /** Drop an album's cached track list once it has no offline-playable tracks left, so an album with
     *  nothing playable offline keeps no stored track rows. */
    private fun pruneAlbumTrackListIfEmpty(key: String, albumId: String?) {
        if (albumId == null) return
        if (store.all(key).none { it.albumId == albumId }) {
            catalogCache.removeTrackList(key, TrackListKind.Album, albumId)
        }
    }

    /** Fetch the album cover into the partition's `art/` dir once per album; reuse if already present. */
    private suspend fun ensureAlbumArt(src: MusicSource, key: String, albumId: String, track: Track): String? {
        repo.artDir(key).listFiles()
            ?.firstOrNull { it.nameWithoutExtension == albumId && it.extension != "tmp" }
            ?.let { return it.toURI().toString() }
        val artUrl = track.imageUrl ?: src.getArtworkUrl(track.id) ?: return null
        return runCatching {
            val tmp = File(repo.artDir(key), "$albumId.tmp")
            val res = HttpDownloader.download(artUrl, tmp)
            val ext = contentTypeToExtension(res.contentType).let { if (it == "bin") "jpg" else it }
            val artFile = File(repo.artDir(key), "$albumId.$ext")
            if (artFile.exists()) artFile.delete()
            if (!tmp.renameTo(artFile)) { tmp.copyTo(artFile, overwrite = true); tmp.delete() }
            artFile.toURI().toString()
        }.getOrNull()
    }

    private fun deleteFiles(key: String, trackId: String) {
        repo.audioDir(key).listFiles()?.filter { it.nameWithoutExtension == trackId }?.forEach { it.delete() }
        // Album art is shared per-album, so it isn't deleted here — `clear(sourceKey)` handles bulk removal.
    }

    private fun onStaleOnline(key: String, row: StoredTrack) {
        when (row.retention) {
            Retention.Pinned -> enqueueTracks(listOf(row.toTrack()), row.quality)
            Retention.Cached -> removeDownload(key, row.id)
        }
    }

    // --- Job list bookkeeping ----------------------------------------------------------------------

    /** Add a Queued job if one isn't already active for this track; returns false if a job exists. */
    private fun addJob(track: Track): Boolean {
        var added = false
        _downloads.update { list ->
            val active = list.firstOrNull { it.trackId == track.id }
            if (active != null && active.state !is DownloadState.Failed) list
            else {
                added = true
                list.filterNot { it.trackId == track.id } + DownloadJob(
                    trackId = track.id,
                    title = track.name,
                    state = DownloadState.Queued,
                    albumId = track.albumId,
                    album = track.album,
                    artistIds = track.artistIds,
                    genreIds = track.genreIds,
                )
            }
        }
        return added
    }

    private fun updateState(trackId: String, state: DownloadState) =
        _downloads.update { list -> list.map { if (it.trackId == trackId) it.copy(state = state) else it } }

    private fun markCompleted(trackId: String) = updateState(trackId, DownloadState.Completed)
    private fun markFailed(trackId: String, t: Throwable) =
        updateState(trackId, DownloadState.Failed(t.message ?: "Download failed"))
}

/** Build a completed download row from a freshly-fetched [Track]. Carries the track's artist/genre IDs
 *  so availability fans out to the artist/genre grids (not just albumId + trackId). */
private fun Track.toStoredTrack(
    sourceKey: String,
    path: String,
    sizeBytes: Long,
    artworkPath: String?,
    quality: StreamingQuality,
    originVersion: String?,
    retention: Retention,
) = StoredTrack(
    sourceKey = sourceKey,
    id = id,
    path = path,
    sizeBytes = sizeBytes,
    title = name,
    albumId = albumId,
    album = album,
    albumArtist = artists?.firstOrNull(),
    albumArtistId = albumArtistId,
    artists = artists ?: emptyList(),
    artistIds = artistIds,
    genres = genres,
    genreIds = genreIds,
    trackNumber = trackNumber,
    discNumber = discNumber,
    durationMs = durationTicks?.let { it / 10_000 },
    year = null,
    artworkPath = artworkPath,
    lastModified = System.currentTimeMillis(),
    hasMetadata = true,
    retention = retention,
    quality = quality,
    originVersion = originVersion,
    downloadedAt = System.currentTimeMillis(),
    lastPlayedAt = null,
    stale = false,
    // Capture the server's favourite/play-count at download time so the offline copy starts in sync.
    favorite = favorite,
    playCount = playCount,
)

/** Reconstruct a playable [Track] from a stored download row (for re-download / cached playback).
 *  Private to avoid colliding with `LocalSource`'s same-named extension at module scope. */
private fun StoredTrack.toTrack() = Track(
    id = id,
    name = title,
    albumId = albumId,
    album = album,
    artists = artists,
    durationTicks = durationMs?.let { it * 10_000 },
    trackNumber = trackNumber,
    discNumber = discNumber,
    imageUrl = artworkPath,
    artistIds = artistIds,
    albumArtistId = albumArtistId,
    genres = genres,
    genreIds = genreIds,
    favorite = favorite,
    playCount = playCount,
)
