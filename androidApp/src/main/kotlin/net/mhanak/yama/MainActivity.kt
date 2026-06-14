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

    // Hardware volume keys are only intercepted during remote (cast) playback — to nudge the remote
    // device's volume and show the in-app indicator (the OS has no panel for a device on the network).
    // During local playback the keys are always passed through to the OS:
    //  - device-volume mode: the OS raises/lowers the media stream and shows its own volume panel.
    //  - in-app-gain mode: the OS does nothing audible (stream gain stays at 100%), and the user
    //    controls volume exclusively through the in-app slider.
    // The matching key-up is consumed whenever we handled the down so the OS doesn't also act on it.
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

    // Only intercept during remote playback where a controllable target is selected.
    private fun handlesVolumeKeys(): Boolean {
        val playback = AppContainer.shared.playback
        if (playback.viewedTarget == null) return false
        return playback.viewed.volumeControllable.value
    }

    private fun handleVolumeKeyDown(keyCode: Int): Boolean {
        val direction = volumeKeyDirection(keyCode) ?: return false
        val playback = AppContainer.shared.playback
        // Local playback: let the OS own the keys (device-volume panel or no-op).
        if (playback.viewedTarget == null) return false
        // Remote playback: step the remote player and show the in-app indicator.
        val viewed = playback.viewed
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
