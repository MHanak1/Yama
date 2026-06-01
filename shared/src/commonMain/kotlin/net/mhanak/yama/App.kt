package net.mhanak.yama

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import net.mhanak.yama.screens.LoginScreen
import net.mhanak.yama.util.AppTheme

@Composable
@Preview
fun App() {
    val appContainer = remember { AppContainer() }
    CompositionLocalProvider(LocalAppContainer provides appContainer) {
        AppTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                LoginScreen()
            }
        }
    }
}