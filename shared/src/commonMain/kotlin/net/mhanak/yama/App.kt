package net.mhanak.yama

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import dev.chrisbanes.haze.rememberHazeState
import net.mhanak.yama.components.AppColorTheme
import net.mhanak.yama.components.DetailTint
import net.mhanak.yama.components.LocalDetailTint
import net.mhanak.yama.components.LocalHazeState
import net.mhanak.yama.components.LocalUiOpacity
import net.mhanak.yama.components.RequestLocalAudioPermission
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.screens.LoginScreen
import net.mhanak.yama.screens.MainScreen
import net.mhanak.yama.util.AppTheme
import net.mhanak.yama.util.ThemeMode

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App() {
    // Compose's focus save/restore is off by default in CMP 1.11. Turn it on (idempotent, set once)
    // so `focusRestorer` actually remembers the focused item across navigation — without it the
    // framework never saves the focused child, so returning to a screen can't restore D-pad focus.
    remember { ComposeUiFlags.isFocusRestorationEnabled = true }
    val appContainer = remember { AppContainer.shared }
    val hazeState = rememberHazeState()
    // The artwork of whichever detail screen is open; recolours the whole app and is painted as the
    // app background. Provided here (above MainScreen) so both the theme wrapper below and the shell
    // can read it. Detail views register into it via RegisterDetailTint.
    val detailTint = remember { DetailTint() }
    val darkTheme = when (appContainer.themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    // Scale the whole UI by overriding the density: every dp/sp resolves through
    // LocalDensity, so multiplying density + fontScale scales layout and text uniformly.
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, appContainer.uiScale) {
        Density(baseDensity.density * appContainer.uiScale, baseDensity.fontScale * appContainer.uiScale)
    }
    CompositionLocalProvider(
        LocalAppContainer provides appContainer,
        LocalHazeState provides if (appContainer.blurEnabled) hazeState else null,
        LocalUiOpacity provides appContainer.uiOpacity,
        LocalDensity provides scaledDensity,
        LocalDetailTint provides detailTint,
    ) {
        AppTheme(darkTheme = darkTheme) {
            // Recolour the whole app to the open detail screen's item (overriding the player) or, at the
            // "All UI" level, to the currently playing album; the default theme otherwise. A no-op
            // wrapper at lower levels, where only the player/detail screens tint themselves.
            AppColorTheme(appContainer.albumTintMode) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val source = appContainer.activeMusicSource
                val jellyfinSource = appContainer.jellyfinSource

                // When a new session is saved, dismiss the "Add Source" login screen.
                LaunchedEffect(jellyfinSource.sessions.size) {
                    if (appContainer.showLoginScreen) appContainer.showLoginScreen = false
                }

                if (source.isAuthenticated && !appContainer.showLoginScreen) {
                    // The local source needs OS read-audio permission before its scan returns anything;
                    // request it as soon as it's the active source, then kick a rescan once granted.
                    if (source.type == SourceType.Local) {
                        RequestLocalAudioPermission { granted -> if (granted) appContainer.localSource.rescan() }
                    }
                    MainScreen()
                } else {
                    LoginScreen(
                        onDismiss = if (appContainer.showLoginScreen) {
                            { appContainer.showLoginScreen = false }
                        } else null
                    )
                }
            }
            }
        }
    }
}
