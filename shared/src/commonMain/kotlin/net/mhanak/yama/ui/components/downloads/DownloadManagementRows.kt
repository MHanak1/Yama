package net.mhanak.yama.ui.components.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import net.mhanak.yama.media.download.DownloadedAlbum
import net.mhanak.yama.media.download.DownloadedTrack
import net.mhanak.yama.media.sources.local.Retention
import net.mhanak.yama.util.StreamingQuality
import net.mhanak.yama.util.formatFileSize
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.input.QualityPickerDialog

/** A downloaded album as a management list row: artwork, name, artist • N tracks • quality • size, a
 *  "Stale" chip when its content changed upstream, and an overflow menu (change quality / remove).
 *  Tapping the row opens the album. */
@Composable
fun DownloadAlbumRow(
    album: DownloadedAlbum,
    imageFallback: Painter?,
    onClick: () -> Unit,
    onChangeQuality: (StreamingQuality) -> Unit,
    onRemove: () -> Unit,
) {
    val tracks = if (album.trackCount == 1) "1 track" else "${album.trackCount} tracks"
    val quality = album.quality?.shortLabel ?: "Mixed"
    val size = album.sizeBytes.takeIf { it > 0 }?.let { formatFileSize(it) }
    ListItem(
        leadingContent = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))) {
                CardImage(imageUrl = album.artworkPath, imageFallback = imageFallback)
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (album.cachedOnly) CachedChip()
                if (album.stale) StaleChip()
            }
        },
        // Artist is dropped here: it routinely overflowed and pushed out the more useful tracks/quality/size.
        supportingContent = {
            Text(
                listOf(tracks, quality, size).filterNotNull().joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            DownloadItemMenu(
                currentQuality = album.quality,
                removeConfirmText = "Remove all downloaded tracks of \"${album.name}\"?",
                onChangeQuality = onChangeQuality,
                onRemove = onRemove,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Small "Stale" pill shown when a downloaded album's upstream content has changed since download. */
@Composable
private fun StaleChip() = MiniChip("Stale", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)

/** Small "Cached" pill marking an auto-cached (not explicitly downloaded) track. */
@Composable
private fun CachedChip() = MiniChip("Cached", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)

@Composable
private fun MiniChip(text: String, container: Color, content: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A single downloaded track as a management list row: title (+ a Cached chip for auto-cached rows),
 *  artist • quality, an optional Keep action (promotes a cached row to a pinned download), and the
 *  overflow menu. */
@Composable
fun DownloadTrackRow(
    track: DownloadedTrack,
    onChangeQuality: (StreamingQuality) -> Unit,
    onRemove: () -> Unit,
    onKeep: (() -> Unit)? = null,
) {
    val cached = track.retention == Retention.Cached
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (cached) CachedChip()
            }
        },
        supportingContent = {
            val quality = track.quality?.shortLabel
            Text(
                listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, quality).joinToString(" • "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cached && onKeep != null) {
                    TextButton(onClick = onKeep) { Text("Keep") }
                }
                DownloadItemMenu(
                    currentQuality = track.quality,
                    removeConfirmText = null,
                    onChangeQuality = onChangeQuality,
                    onRemove = onRemove,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * The overflow menu shared by the album/track management rows: Change quality… (re-fetches at the
 * picked quality — defaulting to the current one, so picking the same quality is a no-op) and Remove
 * download. When [removeConfirmText] is non-null, removal is confirmed first (whole-album).
 */
@Composable
fun DownloadItemMenu(
    currentQuality: StreamingQuality?,
    removeConfirmText: String?,
    onChangeQuality: (StreamingQuality) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Change quality…") },
            leadingIcon = { Icon(Icons.Default.HighQuality, contentDescription = null) },
            onClick = { expanded = false; showQuality = true },
        )
        DropdownMenuItem(
            text = { Text("Remove download") },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            onClick = { expanded = false; if (removeConfirmText != null) confirmRemove = true else onRemove() },
        )
    }

    if (showQuality) {
        QualityPickerDialog(
            title = "Change quality…",
            current = currentQuality,
            onDismiss = { showQuality = false },
            onPick = onChangeQuality,
        )
    }

    if (confirmRemove && removeConfirmText != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove download?") },
            text = { Text(removeConfirmText) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Compact quality label for the management rows (the full [StreamingQuality.label] has a bitrate suffix). */
internal val StreamingQuality.shortLabel: String
    get() = when (this) {
        StreamingQuality.Original -> "Original"
        StreamingQuality.High -> "High"
        StreamingQuality.Medium -> "Medium"
        StreamingQuality.Low -> "Low"
    }

/** Single-letter quality tag (O/H/M/L) for the corner badge on the detail-view "downloaded" icon. */
internal val StreamingQuality.letter: String
    get() = when (this) {
        StreamingQuality.Original -> "O"
        StreamingQuality.High -> "H"
        StreamingQuality.Medium -> "M"
        StreamingQuality.Low -> "L"
    }
