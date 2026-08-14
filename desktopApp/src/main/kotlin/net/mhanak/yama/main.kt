package net.mhanak.yama

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import net.mhanak.yama.platform.DesktopTray
import net.mhanak.yama.platform.MprisService
import net.mhanak.yama.platform.SingleInstance
import net.mhanak.yama.platform.TrayEvent
import net.mhanak.yama.platform.TrayPlaybackState
import net.mhanak.yama.util.AppPreferences
import java.awt.Frame

@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    // Enforce a single instance. If another is already running it's been told to surface itself, so
    // this launch just exits — otherwise we'd spawn a second window (and a second, conflicting tray).
    val instance = SingleInstance.tryAcquire() ?: return

    application {
        // Created once and must survive recomposition (the content recomposes whenever the window's
        // visibility changes), so they're remembered rather than rebuilt each pass.
        val mpris = remember {
            MprisService(
                AppContainer.shared.playback.local,
                // Resolve lyrics through the active source, same seam the full player uses.
                lyricsProvider = { id -> AppContainer.shared.activeMusicSource.getLyrics(id) },
            ).also { it.start() }
        }
        val tray = remember { DesktopTray.install(title = "Yama") }  // null where no tray backend works

        // The window can be hidden (to tray) without ending the process, so its visibility is state.
        var isVisible by remember { mutableStateOf(true) }

        // The only genuine exit path: release the single-instance lock, tear down MPRIS + tray, and end
        // the app. Must NOT run on a mere hide-to-tray, or those integrations would drop while playing.
        fun quit() {
            instance.close()
            mpris.stop()
            tray?.dispose()
            exitApplication()
        }

        val playback = remember { AppContainer.shared.playback }

        // Tray → app: tray clicks arrive on D-Bus/AWT threads; handle them here on the UI thread.
        // Transport commands go to the *viewed* player, matching the app's own transport buttons.
        LaunchedEffect(tray) {
            tray?.events?.collect { event ->
                when (event) {
                    TrayEvent.ToggleVisibility -> isVisible = !isVisible
                    TrayEvent.PlayPause -> playback.viewed.togglePlayPause()
                    TrayEvent.Previous -> playback.viewed.previous()
                    TrayEvent.Next -> playback.viewed.next()
                    TrayEvent.Quit -> quit()
                }
            }
        }
        // App → tray: keep the menu label ("Show"/"Hide") in sync with the window's visibility.
        LaunchedEffect(tray, isVisible) { tray?.setVisibleState(isVisible) }
        // App → tray: feed transport state, re-subscribing when the viewed player swaps (e.g. Play-On).
        LaunchedEffect(tray) {
            snapshotFlow { playback.viewed }
                .flatMapLatest { it.status }
                .collect { s ->
                    tray?.setPlaybackState(
                        TrayPlaybackState(
                            isPlaying = s.isPlaying,
                            hasTrack = s.current != null,
                            canGoNext = s.queue.isNotEmpty() && s.queueIndex in 0 until s.queue.size - 1,
                            canGoPrevious = s.queueIndex > 0,
                        )
                    )
                }
        }

        Window(
            onCloseRequest = {
                // Hide to tray when enabled and a tray is actually running; otherwise closing quits.
                if (AppPreferences.hideToTrayOnClose && tray != null) isVisible = false else quit()
            },
            visible = isVisible,
            title = "Yama",
            icon = painterResource("icon.png"),
        ) {
            App()

            // Raise + focus the window. Driven off the show *event*, not the visibility state, so a
            // second launch steals focus even when the window is already visible (isVisible unchanged).
            fun surface() {
                isVisible = true
                window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                window.toFront()
                window.requestFocus()
            }

            // A second launch always surfaces + focuses us, regardless of current visibility.
            LaunchedEffect(Unit) { instance.showRequests.collect { surface() } }
            // Re-showing from the tray ("Show Yama") focuses once the window becomes visible.
            LaunchedEffect(isVisible) { if (isVisible) surface() }
        }
    }
}
