package net.mhanak.yama.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer

/**
 * Settings section for the local-files source: list of watched folders with add/remove, plus a
 * manual rescan. On platforms without a folder picker (Android, which indexes the whole MediaStore)
 * it shows an explanatory note instead of folder management.
 */
@Composable
fun LocalLibrarySettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    val localSource = appContainer.localSource
    val folders by localSource.folders.collectAsState()
    val isRefreshing by localSource.isRefreshing.collectAsState()

    val pickFolder = rememberDirectoryPicker { path -> if (path != null) localSource.addFolder(path) }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Skip tracks without metadata", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Hide local files that have no readable title tag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = appContainer.skipTracksWithoutMetadata,
                onCheckedChange = { appContainer.skipTracksWithoutMetadata = it },
            )
        }

        if (supportsDirectoryPicker) {
            if (folders.isEmpty()) {
                Text(
                    "No folders added yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            folders.forEach { folder ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        folder,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { localSource.removeFolder(folder) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove folder")
                    }
                }
            }
        } else {
            Text(
                "Music is indexed automatically from this device's media library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            if (supportsDirectoryPicker) {
                OutlinedButton(onClick = pickFolder) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add folder", modifier = Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(onClick = { localSource.rescan() }, enabled = !isRefreshing) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = 8.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                }
                Text("Rescan")
            }
        }
    }
}
