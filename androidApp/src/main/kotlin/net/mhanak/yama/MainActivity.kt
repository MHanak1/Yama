package net.mhanak.yama

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import net.mhanak.yama.components.UserInteractionBus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // Fired for every touch/key/D-pad event dispatched to the activity (and not for synthetic
    // layout events). Feeds the player's idle/zen timer so any real interaction — including a TV
    // D-pad press after the controls have hidden, which no longer reaches the Compose focus tree —
    // wakes the controls back up.
    override fun onUserInteraction() {
        super.onUserInteraction()
        UserInteractionBus.notifyInteraction()
    }

    // Tapping the media notification re-launches us with this extra; forward it to the player UI.
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.OPEN_PLAYER_EXTRA, false) == true) {
            AppContainer.shared.playback.openPlayerRequest = true
        }
    }

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // Hardware volume keys are handled by whether the active player drives *this device's* system
    // media stream:
    //  - It does (local playback in device-volume mode): nudge the system stream with FLAG_SHOW_UI so
    //    the OS shows its normal volume panel and relative stepping. We do this ourselves rather than
    //    let Media3's device-volume control handle the key, because that path moves it silently.
    //  - It doesn't (casting, or local in-app gain): step the player directly on its own scale and
    //    flag the change so the in-app indicator shows — the OS has no panel for those.
    // The matching key-up is consumed whenever we handled the down so the system doesn't also act on it.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleVolumeKeyDown(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeyDirection(keyCode) != null && handlesVolumeKeys()) return true
        return super.onKeyUp(keyCode, event)
    }

    // null for non-volume keys; +1 for up, -1 for down.
    private fun volumeKeyDirection(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> +1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        else -> null
    }

    // We take over the keys when the viewed player drives the system stream (to show the OS panel
    // ourselves) or otherwise accepts volume commands (casting to a controllable device / in-app gain).
    private fun handlesVolumeKeys(): Boolean {
        val viewed = AppContainer.shared.playback.viewed
        return viewed.controlsSystemVolume.value || viewed.volumeControllable.value
    }

    private fun handleVolumeKeyDown(keyCode: Int): Boolean {
        val direction = volumeKeyDirection(keyCode) ?: return false
        val playback = AppContainer.shared.playback
        val viewed = playback.viewed
        if (viewed.controlsSystemVolume.value) {
            // The player drives the OS media stream — relative change with the standard volume panel.
            val adjust = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
            return true
        }
        // Casting or in-app gain: step the player and surface the in-app indicator (no OS panel here).
        if (!viewed.volumeControllable.value) return false
        if (direction > 0) viewed.volumeUp() else viewed.volumeDown()
        playback.notifyVolumeChanged()
        return true
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
