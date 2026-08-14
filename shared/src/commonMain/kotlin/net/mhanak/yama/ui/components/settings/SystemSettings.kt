package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.util.AppPreferences

@Composable
fun SystemSettings(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        // Read once here and mirror locally: the value is only consulted at window-close time (in the
        // desktop entrypoint), so it doesn't need an AppContainer reactive field — same pattern as the
        // "Open on launch" setting in Appearance.
        var hideToTray by remember { mutableStateOf(AppPreferences.hideToTrayOnClose) }
        ListItem(
            headlineContent = { Text("Hide to tray on close") },
            supportingContent = { Text("Keep Yama running in the system tray when you close the window") },
            trailingContent = { Switch(checked = hideToTray, onCheckedChange = null) },
            modifier = Modifier.clickable {
                hideToTray = !hideToTray
                AppPreferences.hideToTrayOnClose = hideToTray
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Spacer(Modifier.height(8.dp))
    }
}
