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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.PlaybackReporter
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.sources.JellyfinSource
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.media.sources.local.LocalSource
import net.mhanak.yama.session.JellyfinSessionRepository
import net.mhanak.yama.util.AlbumTintMode
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.util.SecureStorage
import net.mhanak.yama.util.ThemeMode

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

class AppContainer {
    val jellyfinSessionRepository = JellyfinSessionRepository(SecureStorage("jellyfin"))
    val jellyfinSource = JellyfinSource(jellyfinSessionRepository)

    // The on-device local-files source. Always usable (no auth); scans + indexes lazily on its own
    // IO scope, so constructing it here is cheap.
    val localSource = LocalSource.create()

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
        // Mirror local playback back to the backend (now-playing, play counts, resume).
        PlaybackReporter(playback.local.status, { playback.viewedTarget == null }, { activeMusicSource }).start()
        observeCastTargetAvailability()
        persistQueueOnChange()
        scope.launch(Dispatchers.IO) { restoreSavedQueue() }
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
