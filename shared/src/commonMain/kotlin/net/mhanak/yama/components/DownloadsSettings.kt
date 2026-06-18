package net.mhanak.yama.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer

/**
 * Settings for the offline/downloads layer (see DOWNLOADS_PLAN.md): the default download quality, the
 * background-queue + Wi-Fi-only constraints, the recent-tracks cache and its size budget, and
 * offline-play recording. Managing actual downloads lives in the Downloads hub, not here.
 */
@Composable
fun DownloadsSettings(
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalAppContainer.current
    val downloadKey = appContainer.activeMusicSource.downloadSourceKey()
    var confirmClear by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SettingsSectionHeader("Downloading")
        SettingsRow(
            title = "Default download quality",
            subtitle = appContainer.downloadQuality.label,
            onClick = { showQuality = true },
        )
        SettingToggle(
            title = "Background downloads",
            subtitle = "Queue downloads instead of starting each immediately",
            checked = appContainer.backgroundDownloads,
            onCheckedChange = { appContainer.backgroundDownloads = it },
        )
        SettingToggle(
            title = "Download over Wi-Fi only",
            subtitle = "Hold queued downloads until on an unmetered network",
            checked = appContainer.downloadOverWifiOnly,
            onCheckedChange = { appContainer.downloadOverWifiOnly = it },
            enabled = appContainer.backgroundDownloads,
        )

        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SettingsSectionHeader("Recent tracks cache")
        SettingToggle(
            title = "Cache recent tracks",
            subtitle = "Keep recently-played tracks offline, trimmed to a size budget",
            checked = appContainer.cacheRecentTracks,
            onCheckedChange = { appContainer.cacheRecentTracks = it },
        )
        if (appContainer.cacheRecentTracks) {
            CacheBudgetSlider(
                valueMb = appContainer.cacheSizeBudgetMb,
                onValueChange = { appContainer.cacheSizeBudgetMb = it },
            )
        }

        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SettingsSectionHeader("Offline")
        SettingToggle(
            title = "Record offline plays",
            subtitle = "Save plays that happen offline and sync them on reconnect",
            checked = appContainer.recordOfflinePlays,
            onCheckedChange = { appContainer.recordOfflinePlays = it },
        )

        if (downloadKey != null) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            SettingsRow(
                title = "Clear downloads",
                subtitle = "Remove all downloaded tracks and cached catalog for this source",
                onClick = { confirmClear = true },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showQuality) {
        QualityPickerDialog(
            title = "Default download quality",
            current = appContainer.downloadQuality,
            onDismiss = { showQuality = false },
            onPick = { appContainer.downloadQuality = it },
        )
    }

    if (confirmClear && downloadKey != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear downloads?") },
            text = { Text("This removes every downloaded track and the cached catalog for the current source. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    appContainer.downloads.clear(downloadKey)
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CacheBudgetSlider(valueMb: Int, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Cache size", style = MaterialTheme.typography.bodyMedium)
            Text(
                formatMb(valueMb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 256 MB … 8 GB in 256 MB steps.
        Slider(
            value = valueMb.toFloat(),
            onValueChange = { onValueChange((it / 256f).toInt() * 256) },
            valueRange = 256f..8192f,
            steps = (8192 - 256) / 256 - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatMb(mb: Int): String =
    // Avoid String.format (JVM-only); round the GB value to one decimal by hand.
    if (mb >= 1024) "${(mb * 10 / 1024) / 10f} GB" else "$mb MB"

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.4f
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .alpha(alpha),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
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
