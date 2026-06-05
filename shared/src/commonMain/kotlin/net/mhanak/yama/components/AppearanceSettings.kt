package net.mhanak.yama.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.util.ThemeMode

@Composable
fun AppearanceSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Theme", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
        val themeModes = listOf(ThemeMode.Light, ThemeMode.System, ThemeMode.Dark)
        val themeLabels = listOf("Light", "Auto", "Dark")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            themeModes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = appContainer.themeMode == mode,
                    onClick = { appContainer.themeMode = mode },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = themeModes.size),
                    label = { Text(themeLabels[index]) },
                )
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
    }
}
