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
import androidx.compose.material3.Slider
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
import net.mhanak.yama.util.AlbumTintMode
import net.mhanak.yama.util.ThemeMode
import net.mhanak.yama.util.supportsDynamicColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Theme", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        val themeModes = listOf(ThemeMode.Light, ThemeMode.System, ThemeMode.Dark)
        val themeLabels = mapOf(ThemeMode.Light to "Light", ThemeMode.System to "Auto", ThemeMode.Dark to "Dark")
        SegmentedButtonRow(
            options = themeModes,
            selectedOption = appContainer.themeMode,
            onOptionSelected = { appContainer.themeMode = it },
            modifier = Modifier.fillMaxWidth(),
        ) { mode -> Text(themeLabels[mode] ?: mode.name) }

        // System dynamic palette ("Material You"). Hidden where the platform can't provide one
        // (desktop, Android < 12), in which case the app always uses the generated seed scheme.
        val dynamicSupported = supportsDynamicColor()
        if (dynamicSupported) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Use Material You", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = appContainer.useMaterialYou,
                    onCheckedChange = { appContainer.useMaterialYou = it },
                )
            }
        }

        // Seed colour drives the generated scheme; only relevant when the system palette isn't in use.
        if (!dynamicSupported || !appContainer.useMaterialYou) {
            Text("Accent colour", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            SeedColorPicker(
                color = appContainer.seedColor,
                onColorChange = { appContainer.seedColor = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            "Tint UI with album colours",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        var tintExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = tintExpanded,
            onExpandedChange = { tintExpanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = appContainer.albumTintMode.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tintExpanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = tintExpanded,
                onDismissRequest = { tintExpanded = false },
            ) {
                AlbumTintMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            appContainer.albumTintMode = mode
                            tintExpanded = false
                        },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Blur", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = appContainer.blurEnabled,
                onCheckedChange = { appContainer.blurEnabled = it },
            )
        }
        if (appContainer.blurEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Opacity", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${(appContainer.uiOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = appContainer.uiOpacity,
                onValueChange = { appContainer.uiOpacity = it },
                steps = 9,
                valueRange = 0.0f..1.0f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("UI scale", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(appContainer.uiScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = appContainer.uiScale,
            onValueChange = { appContainer.uiScale = it },
            // 50%–150% in 10% steps.
            steps = 9,
            valueRange = 0.5f..1.5f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Human-readable label for each tint level, shown in the dropdown. */
private val AlbumTintMode.label: String
    get() = when (this) {
        AlbumTintMode.Never -> "Never"
        AlbumTintMode.Player -> "Player only"
        AlbumTintMode.PlayerAndLibrary -> "Player & library"
        AlbumTintMode.AllUi -> "Entire app"
    }
