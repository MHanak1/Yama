package net.mhanak.yama.ui.components.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import net.mhanak.yama.util.StreamingQuality

/**
 * A small modal that lets the user pick a [StreamingQuality] to (re-)download at, defaulting to
 * [current] (the existing downloaded quality) when known. Used by the track menu and the detail-view
 * download affordance for the "re-download at X" action. When [onRemove] is non-null a destructive
 * "Remove download" action is shown at the bottom (the detail view's way to delete a download).
 */
@Composable
fun QualityPickerDialog(
    title: String,
    current: StreamingQuality?,
    onDismiss: () -> Unit,
    onPick: (StreamingQuality) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    var selected by remember { mutableStateOf(current ?: StreamingQuality.Original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                StreamingQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = quality == selected, onClick = { selected = quality })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = quality == selected, onClick = { selected = quality })
                        Text(quality.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (onRemove != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    TextButton(
                        onClick = { onRemove(); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Remove download", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(selected); onDismiss() }) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
