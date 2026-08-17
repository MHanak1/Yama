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
import net.mhanak.yama.ui.theme.AppColorTheme
import net.mhanak.yama.ui.theme.DetailTint
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.theme.LocalDetailTint
import net.mhanak.yama.ui.theme.LocalHazeState
import net.mhanak.yama.ui.components.state.LocalTrackUserData
import net.mhanak.yama.ui.theme.LocalUiOpacity
import net.mhanak.yama.ui.theme.supportsBlurEffects
import net.mhanak.yama.ui.components.state.rememberAvailability
import net.mhanak.yama.ui.platform.RequestLocalAudioPermission
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.ui.screens.LoginScreen
import net.mhanak.yama.ui.screens.MainScreen
import net.mhanak.yama.ui.theme.AppTheme
import net.mhanak.yama.ui.theme.ThemeMode

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App() {
    // Compose's implicit focus save/restore is off by default in CMP 1.11. Content grids/lists now
    // use explicit per-item FocusRequester registration (ContentFocusRegistry, TvFocus.kt) rather
    // than focusRestorer, so this flag no longer drives content focus. Left in case other focusable
    // widgets benefit from framework-level restoration.
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
        // Blur is off unless the user enabled it *and* the platform can actually render it (Android
        // 12+); on older devices Haze degrades to a broken scrim, so we force it off regardless of the
        // stored preference. See supportsBlurEffects().
        LocalHazeState provides if (appContainer.blurEnabled && supportsBlurEffects()) hazeState else null,
        LocalUiOpacity provides appContainer.uiOpacity,
        LocalDensity provides scaledDensity,
        LocalDetailTint provides detailTint,
        LocalAvailability provides rememberAvailability(appContainer),
        LocalTrackUserData provides appContainer.userData,
        // isTelevisionDevice() is the hardware reality; forceTvMode lets non-TV devices (desktop,
        // phone with controller) opt into the TV layout and D-pad focus system.
        LocalIsTvMode provides (isTelevisionDevice() || appContainer.forceTvMode),
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
