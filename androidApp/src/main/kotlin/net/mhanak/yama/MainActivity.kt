package net.mhanak.yama

import android.content.Intent
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

    // While casting to a device whose volume we can actually control, the hardware volume keys should
    // drive *that* device rather than this phone's media stream (which plays nothing). We only
    // intercept then; otherwise the system handles them normally — including when the remote device
    // exposes no volume level, so the keys aren't swallowed for nothing. Consuming both down (with key
    // repeat for press-and-hold) and the matching up suppresses the system volume dialog.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleVolumeKey(keyCode)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (shouldInterceptVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun shouldInterceptVolumeKey(keyCode: Int): Boolean {
        val playback = AppContainer.shared.playback
        val controllable = playback.activeTarget != null && playback.active.volumeControllable.value
        return controllable && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
    }

    private fun handleVolumeKey(keyCode: Int): Boolean {
        if (!shouldInterceptVolumeKey(keyCode)) return false
        val player = AppContainer.shared.playback.active
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> player.volumeUp()
            KeyEvent.KEYCODE_VOLUME_DOWN -> player.volumeDown()
        }
        return true
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
