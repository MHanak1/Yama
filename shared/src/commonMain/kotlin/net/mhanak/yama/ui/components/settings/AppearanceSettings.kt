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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.theme.AlbumTintMode
import net.mhanak.yama.ui.player.PlayerLayoutMode
import net.mhanak.yama.ui.theme.ThemeMode
import net.mhanak.yama.ui.theme.supportsBlurEffects
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.theme.ColorSourceKind
import net.mhanak.yama.ui.theme.SeedColorPicker
import net.mhanak.yama.ui.theme.availableColorSources
import net.mhanak.yama.ui.theme.colorSchemeFilePath

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

        // Colour source: where the base palette's seed comes from (Material You / the desktop shell
        // accent, versus a custom colour). The switch below is only shown when the platform offers a
        // non-custom source; otherwise just the custom seed picker shows. Album-art tinting still layers
        // on top — see the Album art section below.
        val colorSources = remember { availableColorSources() }
        val selectedSource = appContainer.colorSource
        // The one non-custom source this platform offers, if any (Material You on Android, the shell
        // accent on desktop). There is at most one for now, so a single on/off switch between it and the
        // custom seed is enough — matching Android's old "Use Material You" toggle.
        // NOTE: this binary switch only works while there are exactly two sources. When a third is added
        // (e.g. wallpaper extraction), replace it with a multi-option picker over `colorSources`.
        val systemSource = remember(colorSources) { colorSources.firstOrNull { it != ColorSourceKind.Manual } }
        if (systemSource != null) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            ListItem(
                headlineContent = { Text(systemSource.label) },
                supportingContent = { Text(systemSource.description) },
                trailingContent = { Switch(checked = selectedSource == systemSource, onCheckedChange = null) },
                modifier = Modifier.clickable {
                    appContainer.colorSource =
                        if (selectedSource == systemSource) ColorSourceKind.Manual else systemSource
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        // Custom seed colour, shown when the user picks the Manual source (also the sole option on
        // platforms that offer nothing else). On desktop the seed is mirrored to a theme file, whose
        // path is shown so the user can point their theming tools (matugen &c.) at it.
        if (selectedSource == ColorSourceKind.Manual) {
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
                colorSchemeFilePath()?.let { path ->
                    Text(
                        "Synced to $path — point your theming tools here to drive this colour.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
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
            SegmentedButtonRow(
                options = AlbumTintMode.entries,
                selectedOption = appContainer.albumTintMode,
                onOptionSelected = { appContainer.albumTintMode = it },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(it.shortLabel) }
            Text(
                appContainer.albumTintMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── Display ───────────────────────────────────────────────────
        // Sits above Layout: it's about how the UI is painted, not how it's arranged. The whole
        // section is gated on blur support — frosted glass only renders on Android 12+ / desktop;
        // below that Haze falls back to a broken scrim, so it's hidden and blur is forced off in
        // App.kt. Hidden rather than disabled since there's nothing the user can do. With UI scale
        // now under Layout, there's nothing left to show here without blur, so the header hides too.
        if (supportsBlurEffects()) {
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
        }

        // ── Layout ────────────────────────────────────────────────────
        // Everything that shapes how screens are arranged and sized: the Home shelves, the full
        // player, and the global UI scale. (Controller / TV layout moved to Settings → Behavior.)
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
            SegmentedButtonRow(
                options = PlayerLayoutMode.entries,
                selectedOption = appContainer.playerLayoutMode,
                onOptionSelected = { appContainer.playerLayoutMode = it },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(it.shortLabel) }
            Text(
                appContainer.playerLayoutMode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
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

// Each of the segmented controls below pairs a terse [shortLabel] (what fits inside the segment) with
// a fuller [description] (the caption under the row that spells out what the current choice does).

/** Short segment label for each album-tint scope — an increasing reach: off → player → +library → all. */
private val AlbumTintMode.shortLabel: String
    get() = when (this) {
        AlbumTintMode.Never -> "Off"
        AlbumTintMode.Player -> "Player"
        AlbumTintMode.PlayerAndLibrary -> "Library"
        AlbumTintMode.AllUi -> "All"
    }

/** Caption spelling out how far album-colour tinting reaches for the selected mode. */
private val AlbumTintMode.description: String
    get() = when (this) {
        AlbumTintMode.Never -> "No album-colour tint"
        AlbumTintMode.Player -> "Tint the player only"
        AlbumTintMode.PlayerAndLibrary -> "Tint the player and library"
        AlbumTintMode.AllUi -> "Tint the entire app"
    }

/** Short segment label for each player layout mode. */
private val PlayerLayoutMode.shortLabel: String
    get() = when (this) {
        PlayerLayoutMode.Auto -> "Auto"
        PlayerLayoutMode.Vertical -> "Vertical"
        PlayerLayoutMode.Horizontal -> "Horizontal"
    }

/** Caption describing the selected player layout. */
private val PlayerLayoutMode.description: String
    get() = when (this) {
        PlayerLayoutMode.Auto -> "Chosen from the window's aspect ratio"
        PlayerLayoutMode.Vertical -> "Artwork stacked above the controls"
        PlayerLayoutMode.Horizontal -> "Artwork beside the controls"
    }
