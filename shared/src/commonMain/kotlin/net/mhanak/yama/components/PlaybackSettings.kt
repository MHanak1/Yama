package net.mhanak.yama.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.util.StreamingQuality

@OptIn(ExperimentalMaterial3Api::class)
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
            subtitle = "Whether the app should use its own volume slider.",
            checked = appContainer.useDeviceVolume,
            onCheckedChange = { appContainer.useDeviceVolume = it },
        )
        SettingToggle(
            title = "Keep screen on while playing",
            subtitle = "Stop the screen from dimming while the full player is open",
            checked = appContainer.keepScreenOn,
            onCheckedChange = { appContainer.keepScreenOn = it },
        )
        if (appContainer.activeMusicSource.supportsStreamingQuality) {
            Text(
                "Streaming quality",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = appContainer.streamingQuality.label,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    supportingText = { Text("Applies from the next track") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    StreamingQuality.entries.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(quality.label) },
                            onClick = {
                                appContainer.streamingQuality = quality
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
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
