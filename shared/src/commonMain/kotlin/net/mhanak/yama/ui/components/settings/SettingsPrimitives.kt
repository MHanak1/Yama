package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the settings screens. Previously each `*Settings.kt` file kept its own
 * private copy of these, which had drifted apart (header top-padding, an `enabled` param on some
 * toggles but not others). One source of truth keeps every settings category visually identical.
 */

/** Small primary-coloured section label, e.g. "STREAMING", separating groups within a category. */
@Composable
fun SettingsSectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A standard on/off row: title, supporting subtitle and a trailing [Switch], with the whole row
 * clickable. When [enabled] is false the row is dimmed and non-interactive — used where a toggle only
 * makes sense once a parent toggle is on (e.g. Wi-Fi-only under Background downloads).
 */
@Composable
fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .alpha(if (enabled) 1f else 0.4f),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
