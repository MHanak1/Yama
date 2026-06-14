package net.mhanak.yama

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import net.mhanak.yama.platform.MprisService

fun main() = application {
    val mpris = MprisService(AppContainer.shared.playback.local).also { it.start() }

    Window(
        onCloseRequest = {
            mpris.stop()
            exitApplication()
        },
        title = "Yama",
        icon = painterResource("icon.png"),
    ) {
        App()
    }
}