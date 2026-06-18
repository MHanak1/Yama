package net.mhanak.yama

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mhanak.yama.media.download.CatalogCache
import net.mhanak.yama.media.download.CatalogSnapshot
import net.mhanak.yama.media.download.DownloadManager
import net.mhanak.yama.media.download.DownloadRepository
import net.mhanak.yama.media.download.TrackListKind
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.db.createYamaDatabase
import net.mhanak.yama.media.sources.local.LocalLibraryStore
import net.mhanak.yama.media.sources.local.SqlLibraryStore
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.PlaybackReporter
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.playback.FavoriteOutbox
import net.mhanak.yama.media.playback.ScrobbleOutbox
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.JellyfinSource
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.media.sources.local.LocalSource
import net.mhanak.yama.media.sources.local.StoredTrack
import net.mhanak.yama.session.JellyfinSessionRepository
import net.mhanak.yama.util.AlbumTintMode
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.util.SecureStorage
import net.mhanak.yama.util.StreamingQuality
import net.mhanak.yama.util.ThemeMode
import java.io.File

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

class AppContainer {
    val jellyfinSessionRepository = JellyfinSessionRepository(SecureStorage("jellyfin"))
    val jellyfinSource = JellyfinSource(jellyfinSessionRepository)

    // The shared offline index: SQLite (SQLDelight) holding both the local scan (`sourceKey = "local"`)
    // and downloads (`"jellyfin:<token>"`), partitioned by sourceKey. Replaces the per-feature JSON
    // FileLibraryStores so writes are incremental and search is indexed. No JSON→DB migration: the local
    // library just rescans, and downloads start fresh (delete the old `downloads/` dir + JSON indexes).
    private val database = createYamaDatabase()
    val libraryStore: LocalLibraryStore = SqlLibraryStore(database)

    // The on-device local-files source. Always usable (no auth); scans + indexes lazily on its own
    // IO scope, so constructing it here is cheap.
    val localSource = LocalSource.create(libraryStore)

    // Reopen on the source the user last had active (Jellyfin by default / on first run). If the
    // restored source isn't usable (e.g. last on Jellyfin but the session is gone), App.kt still falls
    // back to the login screen via isAuthenticated, so picking it here is safe.
    var activeMusicSource: MusicSource by mutableStateOf(sourceForType(AppPreferences.lastSourceType))
        private set
    var showLoginScreen: Boolean by mutableStateOf(false)

    private fun sourceForType(typeName: String?): MusicSource = when (typeName) {
        SourceType.Local.name -> localSource
        else -> jellyfinSource
    }

    /**
     * Switch the active music source, persist it as the last-used source, and restore that source's
     * remembered "Play On" target (falling back to local playback when the source can't cast). The
     * single entry point for changing source so the choice and its cast target stay in sync — mirrors
     * how `PlaybackController.selectTarget` swaps the active player.
     */
    fun selectSource(source: MusicSource) {
        if (activeMusicSource === source) return
        activeMusicSource = source
        AppPreferences.lastSourceType = source.type.name
        playback.restoreTargetForActiveSource()
    }

    val playback = PlaybackController { activeMusicSource }

    // --- Offline catalog + downloads (source-agnostic; see DOWNLOADS_PLAN.md) ----------------------
    // The disk tier on the source SWR — serves the last-seen catalog when offline / on cold start.
    val catalogCache = CatalogCache.create()
    // Downloads share the SQL index with the local scan (partitioned by sourceKey), so a local rescan's
    // replaceAll("local", …) never touches download rows.
    private val downloadsStore = libraryStore
    // Resolution ladder + availability the UI grays from; also the LocalPlayer's PlayableResolver.
    val downloads = DownloadRepository(
        store = downloadsStore,
        downloadsDir = File(getAppDataDir().toString(), "downloads"),
        catalogCache = catalogCache,
    )
    val downloadManager = DownloadManager(
        store = downloadsStore,
        repo = downloads,
        catalogCache = catalogCache,
        source = { activeMusicSource },
        defaultQuality = { downloadQuality },
        cacheQuality = { streamingQuality },
        wifiOnly = { downloadOverWifiOnly },
        backgroundDownloads = { backgroundDownloads },
        cacheBudgetMb = { cacheSizeBudgetMb },
    )

    // Durable offline-scrobble queue: completed plays that happened offline, flushed on reconnect.
    val scrobbleOutbox = ScrobbleOutbox(
        file = File(getAppDataDir().toString(), "scrobble_outbox.json"),
        source = { activeMusicSource },
        recordOffline = { recordOfflinePlays },
    )

    // Durable offline-favourite queue: hearts toggled while offline, flushed back to the server on
    // reconnect. Online toggles write through immediately and skip the queue (see FavoriteOutbox).
    val favoriteOutbox = FavoriteOutbox(
        file = File(getAppDataDir().toString(), "favorite_outbox.json"),
        source = { activeMusicSource },
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _allowRemoteControl = mutableStateOf(AppPreferences.allowRemoteControl)
    var allowRemoteControl: Boolean
        get() = _allowRemoteControl.value
        set(value) {
            _allowRemoteControl.value = value
            AppPreferences.allowRemoteControl = value
            jellyfinSource.setRemoteControlEnabled(value)
        }

    private val _useDeviceVolume = mutableStateOf(AppPreferences.useDeviceVolume)
    var useDeviceVolume: Boolean
        get() = _useDeviceVolume.value
        set(value) {
            _useDeviceVolume.value = value
            AppPreferences.useDeviceVolume = value
            playback.local.setVolumeMode(value)
        }

    private val _keepScreenOn = mutableStateOf(AppPreferences.keepScreenOnWhilePlaying)
    var keepScreenOn: Boolean
        get() = _keepScreenOn.value
        set(value) {
            _keepScreenOn.value = value
            AppPreferences.keepScreenOnWhilePlaying = value
        }

    private val _streamingQuality = mutableStateOf(AppPreferences.streamingQuality)
    var streamingQuality: StreamingQuality
        get() = _streamingQuality.value
        set(value) { _streamingQuality.value = value; AppPreferences.streamingQuality = value }

    private val _downloadQuality = mutableStateOf(AppPreferences.downloadQuality)
    var downloadQuality: StreamingQuality
        get() = _downloadQuality.value
        set(value) { _downloadQuality.value = value; AppPreferences.downloadQuality = value }

    private val _downloadOverWifiOnly = mutableStateOf(AppPreferences.downloadOverWifiOnly)
    var downloadOverWifiOnly: Boolean
        get() = _downloadOverWifiOnly.value
        set(value) { _downloadOverWifiOnly.value = value; AppPreferences.downloadOverWifiOnly = value }

    private val _backgroundDownloads = mutableStateOf(AppPreferences.backgroundDownloads)
    var backgroundDownloads: Boolean
        get() = _backgroundDownloads.value
        set(value) { _backgroundDownloads.value = value; AppPreferences.backgroundDownloads = value }

    private val _cacheRecentTracks = mutableStateOf(AppPreferences.cacheRecentTracks)
    var cacheRecentTracks: Boolean
        get() = _cacheRecentTracks.value
        set(value) { _cacheRecentTracks.value = value; AppPreferences.cacheRecentTracks = value }

    private val _cacheSizeBudgetMb = mutableStateOf(AppPreferences.cacheSizeBudgetMb)
    var cacheSizeBudgetMb: Int
        get() = _cacheSizeBudgetMb.value
        set(value) {
            _cacheSizeBudgetMb.value = value
            AppPreferences.cacheSizeBudgetMb = value
            downloadManager.trimCache()
        }

    private val _recordOfflinePlays = mutableStateOf(AppPreferences.recordOfflinePlays)
    var recordOfflinePlays: Boolean
        get() = _recordOfflinePlays.value
        set(value) { _recordOfflinePlays.value = value; AppPreferences.recordOfflinePlays = value }

    private val _skipTracksWithoutMetadata = mutableStateOf(AppPreferences.skipTracksWithoutMetadata)
    var skipTracksWithoutMetadata: Boolean
        get() = _skipTracksWithoutMetadata.value
        set(value) {
            _skipTracksWithoutMetadata.value = value
            AppPreferences.skipTracksWithoutMetadata = value
            localSource.reapplyMetadataFilter()
        }

    init {
        // Apply the persisted volume mode to the engine on startup.
        playback.local.setVolumeMode(_useDeviceVolume.value)
        // Resume the active source's remembered cast target (or local playback) on launch, the same way
        // selectSource does when switching — restoreSession in JellyfinSource has already restored the
        // session/client by now, so a remote target can build its player.
        playback.restoreTargetForActiveSource()
        // The socket seeds its controlled-mode state from AppPreferences itself; this only routes
        // remote "Play On" commands from the source's push channel onto the local player. Collected
        // on Main because the transport calls reach the engine directly (Android's Media3
        // MediaController must be called from the main thread).
        scope.launch(Dispatchers.Main) { jellyfinSource.remoteCommands.collect(playback::handleRemoteCommand) }
        // Mirror local playback back to the backend (now-playing, play counts, resume). The gate is
        // always true since Phase 2: the engine goes directly to ExoPlayer, so localStatus never
        // mirrors the remote bridge. The reporter's own track-null / ACTIVE_STATES guard covers idle.
        PlaybackReporter(
            playback.local.status, { true }, { activeMusicSource },
            onCompletedPlay = { track, positionMs ->
                scrobbleOutbox.recordPlay(track.id, positionMs)
                recordLocalPlay(track)
            },
        ).start()
        observeCastTargetAvailability()
        persistQueueOnChange()
        wireOfflineCatalog()
        observeRecentTrackCaching()
        scope.launch(Dispatchers.IO) { restoreSavedQueue() }
    }

    /**
     * Toggle the favourite state for a library item — the single seam the UI calls instead of the source
     * directly. It (1) persists the new state offline for tracks — in the catalog cache's favourite-track
     * set (the offline source of truth, [CatalogCache.favoriteTrackIds]) and on the downloaded row if any
     * — so the heart survives a restart and shows offline; (2) writes through the active source (which
     * updates its cached browse lists and, when online, the backend); and (3) queues the change in
     * [favoriteOutbox], which only persists it while offline and replays it on reconnect.
     */
    fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        val src = activeMusicSource
        scope.launch(Dispatchers.IO) {
            if (kind == FavoritableKind.Track) {
                src.downloadSourceKey()?.let { key ->
                    // The offline source of truth for track hearts (shown wherever a track appears
                    // offline, even in containers whose track lists were never cached).
                    catalogCache.setTrackFavorite(key, id, favorite)
                    libraryStore.get(key, id)?.let { libraryStore.put(it.copy(favorite = favorite)) }
                }
                // The live queue holds frozen Track snapshots; patch it so the player/queue hearts
                // reflect the toggle (the queue is the one long-lived Track holder; see updateTrackFavorite).
                playback.local.updateTrackFavorite(id, favorite)
            }
            runCatching { src.setFavorite(kind, id, favorite) }
            favoriteOutbox.record(kind, id, favorite)
        }
    }

    /**
     * Record a completed play locally so offline sort-by-plays / recently-played stay fresh: bump the
     * play count on the active source's offline row (the local-files index for [LocalSource], the
     * downloads partition otherwise). The server count stays authoritative online; offline plays reach
     * the server via [scrobbleOutbox].
     */
    private fun recordLocalPlay(track: Track) {
        when (val src = activeMusicSource) {
            is LocalSource -> src.recordPlay(track.id)
            else -> {
                val key = src.downloadSourceKey() ?: return
                val row = libraryStore.get(key, track.id) ?: return
                libraryStore.put(row.copy(playCount = row.playCount + 1, lastPlayedAt = System.currentTimeMillis()))
            }
        }
    }

    /**
     * When a new track starts on the local player, let the download layer refresh its LRU timestamp (if
     * already offline) and — when the recent-tracks cache is enabled — auto-cache it. Keyed on the
     * track id so it fires once per track, not on every status tick.
     */
    private fun observeRecentTrackCaching() {
        scope.launch {
            playback.local.status
                .map { it.current }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { track -> track?.let { downloadManager.onTrackPlayed(it, cacheRecentTracks) } }
        }
        scope.launch {
            var lookAheadJob: Job? = null
            playback.local.status
                .map { it.queueIndex to it.queue }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (index, queue) ->
                    // Cancel any in-progress batch from the previous queue position.
                    lookAheadJob?.cancel()
                    if (!cacheRecentTracks) return@collect
                    lookAheadJob = scope.launch {
                        val maxMs = 20 * 60 * 1000L
                        var totalMs = 0L
                        var count = 0
                        for (track in queue.drop(index + 1)) {
                            if (count >= 3) break
                            val dur = track.durationTicks?.let { it / 10_000 } ?: 0L
                            if (totalMs + dur > maxMs) break
                            totalMs += dur
                            count++
                            downloadManager.cacheUpcoming(track) // suspends until done before next
                        }
                    }
                }
        }
    }

    /**
     * Wire the offline catalog + downloads to the active source:
     * - route the local player's stream/artwork resolution through the downloads ladder,
     * - point availability at the active partition and hydrate its browse flows from the cached
     *   snapshot (so the catalog shows instantly and survives going offline),
     * - persist the source's browse flows on every emit (the disk tier of its SWR).
     *
     * Keyed on `(source, downloadSourceKey)` so a source switch *or* a Jellyfin session change (which
     * leaves the source instance the same but changes the partition) both re-target everything.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun wireOfflineCatalog() {
        playback.local.resolver = downloads
        scope.launch(Dispatchers.Main) {
            snapshotFlow { activeMusicSource to activeMusicSource.downloadSourceKey() }
                .distinctUntilChanged()
                .collect { (src, key) ->
                    downloads.setActiveSourceKey(key)
                    if (key != null) catalogCache.loadSnapshot(key)?.let { src.hydrateCatalog(it) }
                }
        }
        scope.launch {
            snapshotFlow { activeMusicSource to activeMusicSource.downloadSourceKey() }
                .distinctUntilChanged()
                .flatMapLatest { (src, key) ->
                    if (key == null) flowOf<Pair<String, CatalogSnapshot>?>(null)
                    else combine(src.albums, src.artists, src.albumArtists, src.genres, src.playlists) { a, ar, aa, g, p ->
                        key to CatalogSnapshot(a, ar, aa, g, p)
                    }
                }
                .collect { it?.let { (key, snap) -> if (!snap.isEmpty) catalogCache.saveSnapshot(key, snap) } }
        }
        // Once the active source is reachable, run the staleness version-check pass for its partition
        // (background, at most once per source per session) so downloaded copies that changed upstream
        // re-fetch (pinned) or evict (cached) via the resolution ladder, flush any offline scrobbles and
        // favourite changes that accumulated while it was unreachable, and rebuild the offline
        // favourite-track set from the backend so hearts are correct offline even for unopened containers.
        scope.launch(Dispatchers.Main) {
            snapshotFlow { activeMusicSource }
                .flatMapLatest { src -> src.isReachable.map { reachable -> src to reachable } }
                .collect { (src, reachable) ->
                    if (reachable) {
                        downloads.refreshStaleness(src)
                        scrobbleOutbox.flush()
                        favoriteOutbox.flush()
                        refreshFavorites(src)
                    }
                }
        }
    }

    /**
     * Drop back to local playback when the viewed "Play On" target disappears from the discovered list
     * — whether it was never reachable (a remembered target restored on launch that's now offline) or it
     * went away mid-session. Gated on a *non-empty* target list so we don't reset before targets have
     * actually been fetched: an empty list can simply mean discovery hasn't produced results yet, and
     * resetting then would undo a perfectly valid restore. Restoring local isn't a user choice, so it
     * doesn't overwrite their remembered target.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCastTargetAvailability() {
        scope.launch(Dispatchers.Main) {
            snapshotFlow { activeMusicSource }
                .flatMapLatest { (it as? RemotePlaybackProvider)?.remoteTargets ?: flowOf(emptyList()) }
                .collect { targets ->
                    val target = playback.viewedTarget ?: return@collect
                    if (targets.isNotEmpty() && targets.none { it.id == target.id }) {
                        playback.selectTarget(null, remember = false)
                    }
                }
        }
    }

    /**
     * Called when the device wakes / the app returns to the foreground (Android only — see
     * [net.mhanak.yama.components.PlatformDeviceWakeEffect]). A WebSocket left backgrounded can be
     * silently half-open; rebuild the source's connection (and the active remote player bound to its
     * client) so remote control resyncs at once instead of after OkHttp's ~30s timeout.
     *
     * Both steps are **Jellyfin-specific** today: the connection rebuild works around the Jellyfin
     * SDK's lack of a force-reconnect, and the player rebuild is only needed because
     * [net.mhanak.yama.media.playback.JellyfinRemotePlayer] captures its client at construction. A
     * future source may recover its live connection differently (or transparently) and not need
     * either — generalise this (e.g. an optional `onDeviceWake` on the source/provider) when a second
     * source actually arrives, rather than guessing the shape now.
     */
    fun onDeviceWake() {
        if (!jellyfinSource.isAuthenticated) return
        // The half-open socket this works around only matters when the live link is actually down (or
        // stale and awaiting OkHttp's write timeout). If it's still healthy — a short sleep, or the OS
        // never froze us — recreating the client would needlessly tear down a working WebSocket (and
        // rebuild the remote player bound to it), so leave it be.
        if (jellyfinSource.isLiveLinkHealthy()) return
        jellyfinSource.reconnect()
        playback.rebuildViewedRemotePlayer()
    }

    /**
     * Fetch a container's track list with the catalog cache as a fallback tier: online, fetch from the
     * source and write the result through to the cache (so a later offline visit works); offline, serve
     * the cached list. A downloaded album already has its track list cached at download time, so its
     * detail page works offline even if never visited online. Detail views call this instead of the
     * source directly.
     */
    suspend fun tracksFor(kind: TrackListKind, containerId: String, fetch: suspend () -> List<Track>): List<Track> {
        val key = activeMusicSource.downloadSourceKey()
        if (activeMusicSource.isReachable.value) {
            val fresh = runCatching { fetch() }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) {
                if (key != null) catalogCache.saveTrackList(key, kind, containerId, fresh)
                return fresh
            }
        }
        if (key != null) {
            catalogCache.loadTrackList(key, kind, containerId)?.let { return applyTrackFavorites(it) }
            offlineTracksFor(key, kind, containerId).takeIf { it.isNotEmpty() }?.let { return applyTrackFavorites(it) }
        }
        return runCatching { fetch() }.getOrDefault(emptyList())
    }

    /**
     * Overlay the persisted offline favourite-track set ([CatalogCache.favoriteTrackIds]) onto [tracks]
     * so a track's heart is correct wherever it appears offline — even if it was favourited after its
     * container's track list was cached, on another device, or while offline. A no-op while online (the
     * source's fresh per-track [Track.favorite] is authoritative then) and on sources with no offline
     * partition. The set is kept current by [refreshFavorites] (a full server pass on reconnect) and by
     * every [setFavorite] toggle.
     */
    private fun applyTrackFavorites(tracks: List<Track>): List<Track> {
        if (activeMusicSource.isReachable.value) return tracks
        val key = activeMusicSource.downloadSourceKey() ?: return tracks
        val favs = catalogCache.favoriteTrackIds(key)
        return tracks.map { if ((it.id in favs) == it.favorite) it else it.copy(favorite = it.id in favs) }
    }

    // Partitions whose favourite set has been refreshed from the server this session, so
    // [refreshFavorites] runs its full pass at most once per source per launch (it pages the backend).
    private val favoritesRefreshed = mutableSetOf<String>()

    /**
     * Rebuild the offline favourite-track set ([CatalogCache.favoriteTrackIds]) for a reachable source
     * from the backend's complete list of favourite tracks, so hearts are correct offline even for
     * containers never opened online (the second half of making track favourites work offline; the
     * first is persisting each toggle in [setFavorite]). Runs at most once per partition per session.
     *
     * Pending offline toggles ([FavoriteOutbox]) are read first and merged on top of the server set, so
     * a change not yet replayed isn't dropped by server data that doesn't reflect it yet (the outbox's
     * concurrent [FavoriteOutbox.flush] may ack/clear entries while this runs).
     */
    private fun refreshFavorites(src: MusicSource) {
        val key = src.downloadSourceKey() ?: return
        if (!src.isReachable.value || !src.supportsFavorites(FavoritableKind.Track)) return
        synchronized(favoritesRefreshed) { if (!favoritesRefreshed.add(key)) return }
        scope.launch(Dispatchers.IO) {
            val pending = favoriteOutbox.pendingTrackFavorites(key)
            val serverFavs = runCatching { fetchAllFavoriteTrackIds(src) }.getOrNull()
            if (serverFavs == null) {
                synchronized(favoritesRefreshed) { favoritesRefreshed.remove(key) } // let a later reconnect retry
                return@launch
            }
            val merged = serverFavs.toMutableSet()
            for ((id, fav) in pending) if (fav) merged.add(id) else merged.remove(id)
            catalogCache.replaceFavoriteTrackIds(key, merged)
        }
    }

    /** Page the source's favourite tracks into a flat id set (favourites can be large; one page at a time). */
    private suspend fun fetchAllFavoriteTrackIds(src: MusicSource): Set<String> {
        val ids = HashSet<String>()
        val pageSize = 500
        var offset = 0
        while (true) {
            val page = src.getAllTracks(pageSize, offset, TrackSortOrder.Alphabetical, favoritesOnly = true)
            if (page.isEmpty()) break
            page.mapTo(ids) { it.id }
            if (page.size < pageSize) break
            offset += pageSize
        }
        return ids
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
        if (activeMusicSource.isReachable.value) {
            return runCatching {
                activeMusicSource.getAllTracks(limit, offset, sortBy, favoritesOnly, searchTerm)
            }.getOrDefault(emptyList())
        }
        val key = activeMusicSource.downloadSourceKey() ?: return emptyList()
        return applyTrackFavorites(offlineTracksFor(key, TrackListKind.All, null, limit, offset, sortBy, searchTerm))
    }

    /** Artist track list with an offline fallback to downloaded rows for that artist. */
    suspend fun getTracksForArtist(artistId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        if (activeMusicSource.isReachable.value) {
            return runCatching {
                activeMusicSource.getTracksForArtist(artistId, limit, offset, sortBy)
            }.getOrDefault(emptyList())
        }
        val key = activeMusicSource.downloadSourceKey() ?: return emptyList()
        return applyTrackFavorites(offlineTracksFor(key, TrackListKind.Artist, artistId, limit, offset, sortBy))
    }

    /** Genre track list with an offline fallback to downloaded rows for that genre. */
    suspend fun getTracksForGenre(genreId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        if (activeMusicSource.isReachable.value) {
            return runCatching {
                activeMusicSource.getTracksForGenre(genreId, limit, offset, sortBy)
            }.getOrDefault(emptyList())
        }
        val key = activeMusicSource.downloadSourceKey() ?: return emptyList()
        return applyTrackFavorites(offlineTracksFor(key, TrackListKind.Genre, genreId, limit, offset, sortBy))
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

    private fun persistQueueOnChange() {
        scope.launch {
            playback.local.status
                .map { Pair(it.queue.map { t -> t.id }, it.current?.id) }
                .distinctUntilChanged()
                .drop(1) // Skip the initial empty-queue state so it doesn't overwrite the saved queue before restoreSavedQueue reads it
                .collect { (ids, currentId) ->
                    val sourceType = activeMusicSource.type.name
                    AppPreferences.setSavedQueueTrackIds(sourceType, ids)
                    AppPreferences.setSavedQueueCurrentId(sourceType, currentId)
                }
        }
    }

    private suspend fun restoreSavedQueue() {
        val sourceType = activeMusicSource.type.name
        val ids = AppPreferences.savedQueueTrackIds(sourceType)
        val currentId = AppPreferences.savedQueueCurrentId(sourceType)
        if (ids.isEmpty()) return
        runCatching {
            val tracks = activeMusicSource.getTracksByIds(ids)
            if (tracks.isEmpty()) return
            val index = if (currentId != null) {
                tracks.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            } else 0
            withContext(Dispatchers.Main) { playback.local.loadQueue(tracks, index) }
        }
    }

    private val _blurEnabled = mutableStateOf(AppPreferences.blurEnabled)
    var blurEnabled: Boolean
        get() = _blurEnabled.value
        set(value) { _blurEnabled.value = value; AppPreferences.blurEnabled = value }

    private val _uiOpacity = mutableStateOf(AppPreferences.uiOpacity)
    var uiOpacity: Float
        get() = _uiOpacity.value
        set(value) { _uiOpacity.value = value; AppPreferences.uiOpacity = value }

    private val _uiScale = mutableStateOf(AppPreferences.uiScale)
    var uiScale: Float
        get() = _uiScale.value
        set(value) { _uiScale.value = value; AppPreferences.uiScale = value }

    private val _themeMode = mutableStateOf(AppPreferences.themeMode)
    var themeMode: ThemeMode
        get() = _themeMode.value
        set(value) { _themeMode.value = value; AppPreferences.themeMode = value }

    private val _useMaterialYou = mutableStateOf(AppPreferences.useMaterialYou)
    var useMaterialYou: Boolean
        get() = _useMaterialYou.value
        set(value) { _useMaterialYou.value = value; AppPreferences.useMaterialYou = value }

    private val _seedColor = mutableStateOf(Color(AppPreferences.seedColor))
    var seedColor: Color
        get() = _seedColor.value
        set(value) { _seedColor.value = value; AppPreferences.seedColor = value.toArgb() }

    private val _albumTintMode = mutableStateOf(AppPreferences.albumTintMode)
    var albumTintMode: AlbumTintMode
        get() = _albumTintMode.value
        set(value) { _albumTintMode.value = value; AppPreferences.albumTintMode = value }

    companion object {
        // Process-wide singleton. On Android the Activity (and thus the Compose tree) can be
        // recreated when the app is backgrounded and reopened, while the playback foreground service
        // keeps the process alive — recreating AppContainer there would drop the playback queue and
        // library state. Sharing one instance across recreations keeps that state intact. Desktop has
        // a single process/window, so this is simply created once.
        val shared: AppContainer by lazy { AppContainer() }
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
