package net.mhanak.yama.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
actual fun ImmersiveMode(enabled: Boolean) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, enabled) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            // Let the user swipe a bar back transiently without leaving immersive mode.
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
