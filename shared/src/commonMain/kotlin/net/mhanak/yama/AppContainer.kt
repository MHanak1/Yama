package net.mhanak.yama

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.PlaybackReporter
import net.mhanak.yama.media.sources.JellyfinSource
import net.mhanak.yama.media.sources.MusicSource
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

    var activeMusicSource: MusicSource by mutableStateOf(jellyfinSource)
    var showLoginScreen: Boolean by mutableStateOf(false)

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

    init {
        // Apply the persisted volume mode to the engine on startup.
        playback.local.setVolumeMode(_useDeviceVolume.value)
        // The socket seeds its controlled-mode state from AppPreferences itself; this only routes
        // remote "Play On" commands from the source's push channel onto the local player. Collected
        // on Main because the transport calls reach the engine directly (Android's Media3
        // MediaController must be called from the main thread).
        scope.launch(Dispatchers.Main) { jellyfinSource.remoteCommands.collect(playback::handleRemoteCommand) }
        // Mirror local playback back to the backend (now-playing, play counts, resume).
        PlaybackReporter(playback.local.status, { activeMusicSource }).start()
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
