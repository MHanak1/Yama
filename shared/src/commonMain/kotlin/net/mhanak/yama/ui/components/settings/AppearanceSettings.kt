package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import net.mhanak.yama.isTelevisionDevice
import net.mhanak.yama.ui.screens.LaunchDestination
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.ui.theme.AlbumTintMode
import net.mhanak.yama.ui.player.PlayerLayoutMode
import net.mhanak.yama.ui.theme.ThemeMode
import net.mhanak.yama.ui.theme.supportsDynamicColor
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.theme.SeedColorPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettings(
    // Opens the per-source home-layout editor (the shelf list). Lives here now rather than on the
    // Home screen's app bar.
    onOpenHomeLayout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier) {

        // ── Startup ───────────────────────────────────────────────────
        // Which top-level screen opens after login. Not routed through AppContainer's reactive state
        // since it's only read once at NavHost start — a local mirror keeps the toggle in sync here.
        SettingsSectionHeader("Startup")
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Open on launch",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            var launch by remember { mutableStateOf(AppPreferences.launchDestination) }
            SegmentedButtonRow(
                options = LaunchDestination.entries,
                selectedOption = launch,
                onOptionSelected = { launch = it; AppPreferences.launchDestination = it },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(it.label) }
        }

        // ── Theme ─────────────────────────────────────────────────────
        SettingsSectionHeader("Theme")
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val themeModes = listOf(ThemeMode.Light, ThemeMode.System, ThemeMode.Dark)
            val themeLabels = mapOf(ThemeMode.Light to "Light", ThemeMode.System to "Auto", ThemeMode.Dark to "Dark")
            SegmentedButtonRow(
                options = themeModes,
                selectedOption = appContainer.themeMode,
                onOptionSelected = { appContainer.themeMode = it },
                modifier = Modifier.fillMaxWidth(),
            ) { mode -> Text(themeLabels[mode] ?: mode.name) }
        }

        // System dynamic palette ("Material You"). Hidden where the platform can't provide one
        // (desktop, Android < 12), in which case the app always uses the generated seed scheme.
        val dynamicSupported = supportsDynamicColor()
        if (dynamicSupported) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            ListItem(
                headlineContent = { Text("Use Material You") },
                supportingContent = { Text("Follow your system's dynamic colour palette") },
                trailingContent = { Switch(checked = appContainer.useMaterialYou, onCheckedChange = null) },
                modifier = Modifier.clickable { appContainer.useMaterialYou = !appContainer.useMaterialYou },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // Seed colour drives the generated scheme; only relevant when the system palette isn't in use.
        if (!dynamicSupported || !appContainer.useMaterialYou) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Accent colour",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SeedColorPicker(
                    color = appContainer.seedColor,
                    onColorChange = { appContainer.seedColor = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Album art ─────────────────────────────────────────────────
        SettingsSectionHeader("Album art")
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Tint UI with album colours",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
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
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
        }

        // ── Layout ────────────────────────────────────────────────────
        // Everything that shapes how screens are arranged: the Home shelves, the full player, and
        // whether the whole app runs in the D-pad/TV layout.
        SettingsSectionHeader("Layout")
        ListItem(
            headlineContent = { Text("Home screen") },
            supportingContent = { Text("Choose and reorder the shelves shown on Home") },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.clickable(onClick = onOpenHomeLayout),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Player layout",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            var layoutExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = layoutExpanded,
                onExpandedChange = { layoutExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = appContainer.playerLayoutMode.label,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = layoutExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = layoutExpanded,
                    onDismissRequest = { layoutExpanded = false },
                ) {
                    PlayerLayoutMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                appContainer.playerLayoutMode = mode
                                layoutExpanded = false
                            },
                        )
                    }
                }
            }
        }
        // A real TV reports itself via isTelevisionDevice() and always uses the TV layout, so the
        // manual opt-in is only offered on non-TV hardware (desktop / a phone with a controller).
        if (!isTelevisionDevice()) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            ListItem(
                headlineContent = { Text("Controller / TV layout") },
                supportingContent = { Text("Use D-pad and controller navigation instead of pointer") },
                trailingContent = { Switch(checked = appContainer.forceTvMode, onCheckedChange = null) },
                modifier = Modifier.clickable { appContainer.forceTvMode = !appContainer.forceTvMode },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // ── Display ───────────────────────────────────────────────────
        SettingsSectionHeader("Display")
        ListItem(
            headlineContent = { Text("Blur effects") },
            supportingContent = { Text("Frosted glass on UI panels") },
            trailingContent = { Switch(checked = appContainer.blurEnabled, onCheckedChange = null) },
            modifier = Modifier.clickable { appContainer.blurEnabled = !appContainer.blurEnabled },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        if (appContainer.blurEnabled) {
            SliderItem(
                label = "Opacity",
                displayValue = "${(appContainer.uiOpacity * 100).toInt()}%",
                value = appContainer.uiOpacity,
                onValueChange = { appContainer.uiOpacity = it },
                steps = 9,
                valueRange = 0f..1f,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SliderItem(
            label = "UI scale",
            displayValue = "${(appContainer.uiScale * 100).toInt()}%",
            value = appContainer.uiScale,
            onValueChange = { appContainer.uiScale = it },
            // 50%–150% in 10% steps.
            steps = 9,
            valueRange = 0.5f..1.5f,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SliderItem(
    label: String,
    displayValue: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    steps: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                displayValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            steps = steps,
            valueRange = valueRange,
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

/** Human-readable label for each player layout mode, shown in the dropdown. */
private val PlayerLayoutMode.label: String
    get() = when (this) {
        PlayerLayoutMode.Auto -> "Auto (by aspect ratio)"
        PlayerLayoutMode.Vertical -> "Vertical"
        PlayerLayoutMode.Horizontal -> "Horizontal"
    }
