package net.mhanak.yama

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

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

    // Tapping the media notification re-launches us with this extra; forward it to the player UI.
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(PlaybackService.OPEN_PLAYER_EXTRA, false) == true) {
            AppContainer.shared.playback.openPlayerRequest = true
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
