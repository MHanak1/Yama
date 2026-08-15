package net.mhanak.yama.ui.views.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.components.interaction.ContentFocusHost
import net.mhanak.yama.ui.components.interaction.contentFocusItem
import net.mhanak.yama.ui.screens.AboutRoute
import net.mhanak.yama.ui.screens.AppearanceSettingsRoute
import net.mhanak.yama.ui.screens.DownloadsSettingsRoute
import net.mhanak.yama.ui.screens.LocalLibrarySettingsRoute
import net.mhanak.yama.ui.screens.PlaybackSettingsRoute
import net.mhanak.yama.ui.screens.ScrobblingSettingsRoute
import net.mhanak.yama.ui.screens.SystemSettingsRoute
import net.mhanak.yama.supportsSystemTray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsView(
    onMenuClick: (() -> Unit)?,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onMenuClick != null) {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        ContentFocusHost(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                SettingsCategoryCard(
                    icon = Icons.Default.Palette,
                    iconContainerColor = Color.hsv(200f, 0.6f, 1f),
                    iconContentColor = Color.hsv(200f, 1f, 0.4f),
                    title = "Appearance",
                    subtitle = "Theme, colours, blur & album art",
                    onClick = { onNavigate(AppearanceSettingsRoute) },
                )
                Spacer(Modifier.height(8.dp))
                SettingsCategoryCard(
                    icon = Icons.Default.MusicNote,
                    iconContainerColor = Color.hsv(150f, 0.6f, 1f),
                    iconContentColor = Color.hsv(150f, 1f, 0.4f),
                    title = "Playback",
                    subtitle = "Volume, quality & remote control",
                    onClick = { onNavigate(PlaybackSettingsRoute) },
                )
                Spacer(Modifier.height(8.dp))
                SettingsCategoryCard(
                    icon = Icons.Default.CloudUpload,
                    iconContainerColor = Color.hsv(340f, 0.6f, 1f),
                    iconContentColor = Color.hsv(340f, 1f, 0.4f),
                    title = "Scrobbling",
                    subtitle = "Submit your listens to ListenBrainz",
                    onClick = { onNavigate(ScrobblingSettingsRoute) },
                )
                Spacer(Modifier.height(8.dp))
                SettingsCategoryCard(
                    icon = Icons.Default.Download,
                    iconContainerColor = Color.hsv(100f, 0.6f, 1f),
                    iconContentColor = Color.hsv(100f, 1f, 0.4f),
                    title = "Downloads",
                    subtitle = "Offline tracks, cache & Wi-Fi-only",
                    onClick = { onNavigate(DownloadsSettingsRoute) },
                )
                Spacer(Modifier.height(8.dp))
                SettingsCategoryCard(
                    icon = Icons.Default.FolderOpen,
                    iconContainerColor = Color.hsv(50f, 0.6f, 1f),
                    iconContentColor = Color.hsv(50f, 1f, 0.4f),
                    title = "Local Library",
                    subtitle = "Music folders & scan settings",
                    onClick = { onNavigate(LocalLibrarySettingsRoute) },
                )
                // Desktop-only: window/tray behaviour. Absent on Android and on desktops with no
                // working tray, since there's nothing to configure there.
                if (supportsSystemTray()) {
                    Spacer(Modifier.height(8.dp))
                    SettingsCategoryCard(
                        icon = Icons.Default.Computer,
                        iconContainerColor = Color.hsv(260f, 0.6f, 1f),
                        iconContentColor = Color.hsv(260f, 1f, 0.4f),
                        title = "System",
                        subtitle = "Tray & window behaviour",
                        onClick = { onNavigate(SystemSettingsRoute) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                SettingsCategoryCard(
                    icon = Icons.Outlined.Info,
                    iconContainerColor = Color.hsv(0f, 0f, 0.75f),
                    iconContentColor = Color.hsv(0f, 0f, 0.25f),
                    title = "About",
                    subtitle = "Version, license & open-source credits",
                    onClick = { onNavigate(AboutRoute) },
                )
            }
            Spacer(Modifier.height(bottomContentPadding))
        }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        // TV D-pad: keyed by title so entry lands on the first category and back-from-subpage restores
        // the one just visited. contentFocusItem precedes the card's internal clickable node.
        modifier = Modifier.contentFocusItem(title).fillMaxWidth(),
    ) {
        ListItem(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconContentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
