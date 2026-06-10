package net.mhanak.yama.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer

@Composable
fun PlaybackSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SettingToggle(
            title = "Allow remote control",
            subtitle = "Let other clients play to this device (\"Play On\")",
            checked = appContainer.allowRemoteControl,
            onCheckedChange = { appContainer.allowRemoteControl = it },
        )
        SettingToggle(
            title = "Use device volume",
            subtitle = "Volume controls the system media volume; otherwise an in-app level",
            checked = appContainer.useDeviceVolume,
            onCheckedChange = { appContainer.useDeviceVolume = it },
        )
        SettingToggle(
            title = "Keep screen on while playing",
            subtitle = "Stop the screen from dimming while the full player is open",
            checked = appContainer.keepScreenOn,
            onCheckedChange = { appContainer.keepScreenOn = it },
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
