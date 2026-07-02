package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.util.StreamingQuality

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier) {
        SettingToggle(
            title = "Allow remote control",
            subtitle = "Let other clients play to this device (\"Play On\")",
            checked = appContainer.allowRemoteControl,
            onCheckedChange = { appContainer.allowRemoteControl = it },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SettingToggle(
            title = "Use device volume",
            subtitle = "Use the system volume instead of an in-app slider",
            checked = appContainer.useDeviceVolume,
            onCheckedChange = { appContainer.useDeviceVolume = it },
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SettingToggle(
            title = "Keep screen on while playing",
            subtitle = "Stop the screen from dimming while the full player is open",
            checked = appContainer.keepScreenOn,
            onCheckedChange = { appContainer.keepScreenOn = it },
        )
        if (appContainer.activeMusicSource.supportsStreamingQuality) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            SettingsSectionHeader("Streaming")
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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
                        label = { Text("Streaming quality") },
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
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

