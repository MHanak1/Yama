package net.mhanak.yama.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.components.glassSource

/**
 * Home landing screen. Placeholder for now — recently played, top tracks, etc. go here.
 *
 * [onMenuClick] is non-null only on the slim layout (it opens the modal nav rail); on
 * medium/wide the persistent rail is always visible so no menu affordance is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeView(
    onMenuClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassEffect(MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    TopAppBar(
                        title = { Text("Home") },
                        modifier = Modifier.height(48.dp),
                        navigationIcon = {
                            if (onMenuClick != null) {
                                IconButton(onClick = onMenuClick) {
                                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .glassSource(zIndex = 1f)
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = bottomContentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Home",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
