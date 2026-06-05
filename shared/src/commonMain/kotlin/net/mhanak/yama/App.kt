package net.mhanak.yama

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.chrisbanes.haze.rememberHazeState
import net.mhanak.yama.components.LocalHazeState
import net.mhanak.yama.components.LocalUiOpacity
import net.mhanak.yama.screens.LoginScreen
import net.mhanak.yama.screens.MainScreen
import net.mhanak.yama.util.AppTheme
import net.mhanak.yama.util.ThemeMode

@Composable
@Preview
fun App() {
    val appContainer = remember { AppContainer() }
    val hazeState = rememberHazeState()
    val darkTheme = when (appContainer.themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalAppContainer provides appContainer,
        LocalHazeState provides if (appContainer.blurEnabled) hazeState else null,
        LocalUiOpacity provides appContainer.uiOpacity,
    ) {
        AppTheme(darkTheme = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val source = appContainer.activeMusicSource
                val jellyfinSource = appContainer.jellyfinSource

                // When a new session is saved, dismiss the "Add Source" login screen.
                LaunchedEffect(jellyfinSource.sessions.size) {
                    if (appContainer.showLoginScreen) appContainer.showLoginScreen = false
                }

                if (source.isAuthenticated && !appContainer.showLoginScreen) {
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
