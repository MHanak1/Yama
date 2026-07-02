package net.mhanak.yama.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.components.DownloadTrackRow
import net.mhanak.yama.media.sources.local.Retention

/**
 * A flat list of downloaded tracks with per-track change-quality / remove actions. A "Show cached"
 * toggle additionally folds in the recent-tracks cache: those rows carry a Cached badge and a "Keep"
 * action that promotes them to a pinned download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedTracksView(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val manager = appContainer.downloadManager
    val sourceKey = (appContainer.activeMusicSource as? OfflineCapable)?.downloadSourceKey()
    val availableTrackIds by appContainer.downloads.availableTrackIds.collectAsState()

    var showCached by rememberSaveable { mutableStateOf(false) }
    // Whether the cache holds anything — no point showing the toggle otherwise.
    val hasCached = remember(sourceKey, availableTrackIds) {
        sourceKey?.let { appContainer.downloads.downloadedTracks(it, retention = Retention.Cached).isNotEmpty() } ?: false
    }
    // Pinned only by default; with the toggle on, both tiers (retention = null).
    val tracks = remember(sourceKey, availableTrackIds, showCached) {
        val filter = if (showCached) null else Retention.Pinned
        sourceKey?.let { appContainer.downloads.downloadedTracks(it, retention = filter) } ?: emptyList()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Downloaded tracks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (hasCached) {
                        FilterChip(
                            selected = showCached,
                            onClick = { showCached = !showCached },
                            label = { Text("Show cached") },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing downloaded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomContentPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                items(tracks, key = { it.id }) { track ->
                    DownloadTrackRow(
                        track = track,
                        onChangeQuality = { quality -> sourceKey?.let { manager.redownloadStored(it, listOf(track.id), quality) } },
                        onRemove = { sourceKey?.let { manager.removeDownload(it, track.id) } },
                        onKeep = { sourceKey?.let { manager.keepDownloaded(it, track.id) } },
                    )
                }
            }
        }
    }
}
