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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.mhanak.yama.coordinators.CatalogReader
import net.mhanak.yama.coordinators.FavoritesCoordinator
import net.mhanak.yama.coordinators.OfflineSyncOrchestrator
import net.mhanak.yama.coordinators.PlayCountRecorder
import net.mhanak.yama.coordinators.QueuePersistence
import net.mhanak.yama.media.download.CatalogCache
import net.mhanak.yama.media.download.DownloadManager
import net.mhanak.yama.media.download.DownloadRepository
import net.mhanak.yama.media.model.InMemoryTrackUserDataStore
import net.mhanak.yama.media.model.TrackUserDataStore
import net.mhanak.yama.db.createYamaDatabase
import net.mhanak.yama.media.sources.local.LocalLibraryStore
import net.mhanak.yama.media.sources.local.SqlLibraryStore
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.PlaybackReporter
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.playback.FavoriteOutbox
import net.mhanak.yama.media.playback.ScrobbleOutbox
import net.mhanak.yama.media.sources.AccountedSource
import net.mhanak.yama.media.sources.JellyfinSource
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.media.sources.local.LocalSource
import net.mhanak.yama.session.JellyfinSessionRepository
import net.mhanak.yama.ui.theme.AlbumTintMode
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.util.SecureStorage
import net.mhanak.yama.util.StreamingQuality
import net.mhanak.yama.ui.theme.ThemeMode
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

    /** All registered sources in display order. The switcher iterates this instead of reading
     *  concrete fields so adding a new source later requires only one change here. */
    val sources: List<MusicSource> = listOf(jellyfinSource, localSource)

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

    /**
     * Switch to [source] and select the given account within it. The two-axis nature of source
     * switching — axis 1: active source, axis 2: account within that source — is kept in one place
     * here so callers (the switcher UI) never need to call both [selectSource] and
     * `[AccountedSource.selectAccount]` separately.
     */
    fun selectAccount(source: MusicSource, accountId: String) {
        selectSource(source)
        (source as? AccountedSource)?.selectAccount(accountId)
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

    // The single in-memory owner of every track's mutable user-data (favorite/playCount). Surfaces read
    // through it (via LocalTrackUserData + rememberReconciled) and every toggle/play writes it once;
    // catalogCache/libraryStore/favoriteOutbox/backend are subscribers of those writes, not parallel
    // writers. Partition-scoped: cleared + reseeded on a source/partition switch in OfflineSyncOrchestrator.
    val userData: TrackUserDataStore = InMemoryTrackUserDataStore()

    // --- Stateful coordinators -------------------------------------------------------------------
    // Each coordinator owns one domain concern. AppContainer constructs them in dependency order
    // (favorites before offlineSync, which depends on it) and exposes them as public vals so the UI
    // can reach them directly (appContainer.favorites.setFavorite(...) etc.).

    /** Single seam for favourite toggles + per-session server-favourites refresh. */
    val favorites = FavoritesCoordinator(
        source = { activeMusicSource },
        userData = userData,
        catalogCache = catalogCache,
        libraryStore = libraryStore,
        favoriteOutbox = favoriteOutbox,
    )

    /** Bumps the play-count in the live [userData] store and persists to the offline row. */
    val playCount = PlayCountRecorder(
        source = { activeMusicSource },
        userData = userData,
        libraryStore = libraryStore,
    )

    /** Read-through track-list cache with offline fallback to the downloads index. */
    val catalog = CatalogReader(
        source = { activeMusicSource },
        catalogCache = catalogCache,
        downloads = downloads,
        userData = userData,
    )

    /** Persists and restores the local player's queue across launches. */
    val queue = QueuePersistence(
        player = playback.local,
        source = { activeMusicSource },
    )

    /**
     * Wires the offline catalog + downloads to the active source: partition switch, snapshot
     * persistence, and the reachable-edge pass (staleness + outbox flush + favourites refresh).
     */
    val offlineSync = OfflineSyncOrchestrator(
        source = { activeMusicSource },
        playback = playback,
        downloads = downloads,
        catalogCache = catalogCache,
        userData = userData,
        scrobbleOutbox = scrobbleOutbox,
        favoriteOutbox = favoriteOutbox,
        favorites = favorites,
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
                playCount.recordLocalPlay(track)
            },
        ).start()
        observeCastTargetAvailability()
        observeRecentTrackCaching()
        scope.launch(Dispatchers.IO) { queue.restore() }
    }

    /**
     * Called when the device wakes / the app returns to the foreground (Android only — see
     * [net.mhanak.yama.ui.platform.PlatformDeviceWakeEffect]). A WebSocket left backgrounded can be
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
