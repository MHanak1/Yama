package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.util.StreamingQuality

/**
 * The single quality selector used across settings, so the streaming-quality and download-quality
 * pickers stay identical. An [ExposedDropdownMenuBox] over [StreamingQuality]; the selection applies
 * immediately via [onSelect]. Callers supply the field [label] and optional [supportingText].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityDropdown(
    label: String,
    selected: StreamingQuality,
    onSelect: (StreamingQuality) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selected.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                supportingText = supportingText?.let { { Text(it) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
                            onSelect(quality)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
