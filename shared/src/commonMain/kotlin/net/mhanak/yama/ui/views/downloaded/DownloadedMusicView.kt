package net.mhanak.yama.ui.views.downloaded

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.ui.components.downloads.DownloadAlbumRow
import net.mhanak.yama.ui.components.downloads.downloadJobsSection
import net.mhanak.yama.ui.components.input.isInFlight
import net.mhanak.yama.ui.components.interaction.ContentFocusHost
import net.mhanak.yama.ui.components.interaction.contentFocusItem
import net.mhanak.yama.media.sources.local.Retention
import net.mhanak.yama.util.formatFileSize
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album

/**
 * Downloads hub: a single place for everything offline. While downloads are running a badged top-bar
 * icon opens the live job section in a sheet (aggregate progress + pause/cancel-all + per-album jobs).
 * The list itself holds entries to the downloaded/cached tracks view and download settings, a "needs
 * updating" banner for stale rows, a storage summary, and the **list** of downloaded albums (each with
 * change-quality / remove actions). Built from the index so it works fully offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedMusicView(
    onAlbumClick: (String) -> Unit,
    onTracksClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onMenuClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val manager = appContainer.downloadManager
    val sourceKey = (appContainer.activeMusicSource as? OfflineCapable)?.downloadSourceKey()
    val availableTrackIds by appContainer.downloads.availableTrackIds.collectAsState()
    val jobs by manager.downloads.collectAsState()

    // "Show cached" on → everything downloaded (retention = null); off → only albums with an explicit
    // (pinned) download, which is exactly what filtering rows to Pinned yields. Saveable so it survives
    // navigating into an album and back (plain remember resets when the hub leaves composition).
    var showCached by rememberSaveable { mutableStateOf(false) }
    val retentionFilter = if (showCached) null else Retention.Pinned
    val hasCached = remember(sourceKey, availableTrackIds) {
        sourceKey?.let { appContainer.downloads.downloadedTracks(it, retention = Retention.Cached).isNotEmpty() } ?: false
    }
    val albums = remember(sourceKey, availableTrackIds, retentionFilter) {
        sourceKey?.let { appContainer.downloads.downloadedAlbums(it, retentionFilter) } ?: emptyList()
    }
    val totalSize = remember(sourceKey, availableTrackIds, retentionFilter) {
        sourceKey?.let { appContainer.downloads.totalSizeBytes(it, retentionFilter) } ?: 0L
    }
    val staleCount = albums.count { it.stale }
    val fallback = painterResource(Res.drawable.album)

    // Active downloads live in a sheet behind a top-bar icon, so they don't push the library down.
    var showJobsSheet by remember { mutableStateOf(false) }
    val activeJobCount = jobs.count { it.state.isInFlight }
    // Close the sheet once everything finishes/clears so it doesn't hang around empty.
    LaunchedEffect(jobs.isEmpty()) { if (jobs.isEmpty()) showJobsSheet = false }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    if (onMenuClick != null) {
                        IconButton(onClick = onMenuClick) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                },
                actions = {
                    if (jobs.isNotEmpty()) {
                        IconButton(onClick = { showJobsSheet = true }) {
                            BadgedBox(badge = { if (activeJobCount > 0) Badge { Text("$activeJobCount") } }) {
                                Icon(Icons.Default.Downloading, contentDescription = "Active downloads")
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        ContentFocusHost(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomContentPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        ) {
            item {
                NavRow(
                    title = "Downloaded tracks",
                    subtitle = "Downloaded tracks, with cached shown on request",
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    onClick = onTracksClick,
                    focusKey = "nav_tracks",
                )
            }
            item {
                NavRow(
                    title = "Download settings",
                    subtitle = "Quality, Wi-Fi only, cache, offline plays",
                    icon = Icons.Default.Settings,
                    onClick = onSettingsClick,
                    focusKey = "nav_settings",
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            // Some downloads went stale upstream — offer to refresh them all at once.
            if (staleCount > 0) {
                item { StaleBanner(count = staleCount, onUpdateAll = { manager.redownloadStale() }) }
            }
            item {
                SectionHeader(
                    title = "Downloaded albums",
                    // The library's total on-disk footprint, shown next to the header.
                    trailing = if (totalSize > 0) formatFileSize(totalSize) else null,
                )
            }
            // Fold the recent-tracks cache into the list (cached-only albums are badged "Cached").
            if (hasCached) {
                item { ShowCachedToggle(checked = showCached, onCheckedChange = { showCached = it }) }
            }

            if (albums.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Nothing downloaded yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(albums, key = { it.id }) { album ->
                    DownloadAlbumRow(
                        album = album,
                        imageFallback = fallback,
                        onClick = { onAlbumClick(album.id) },
                        onChangeQuality = { quality ->
                            sourceKey?.let { manager.redownloadAlbumStored(it, album.id, quality) }
                        },
                        onRemove = { sourceKey?.let { manager.removeAlbum(it, album.id) } },
                        focusKey = "album_${album.id}",
                    )
                }
            }
        }
        }
    }

    if (showJobsSheet && jobs.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showJobsSheet = false }) {
            // Bounded height so the LazyColumn doesn't measure against the sheet's unbounded content
            // constraints; it scrolls when the queue is long.
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                downloadJobsSection(jobs, manager)
            }
        }
    }
}

/** Row toggling whether the albums list also includes recent-tracks-cache content. */
@Composable
private fun ShowCachedToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text("Show cached", style = MaterialTheme.typography.bodyMedium) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** A tappable banner shown when some downloads are stale: re-downloads all of them at once. */
@Composable
private fun StaleBanner(count: Int, onUpdateAll: () -> Unit) {
    val label = if (count == 1) "1 download needs updating" else "$count downloads need updating"
    ListItem(
        leadingContent = { Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer) },
        headlineContent = { Text(label) },
        trailingContent = { TextButton(onClick = onUpdateAll) { Text("Update all") } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun NavRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, focusKey: String? = null) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.contentFocusItem(focusKey).clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
