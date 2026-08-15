package net.mhanak.yama.ui.views.downloaded

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.ui.components.downloads.DownloadItemMenu
import net.mhanak.yama.ui.components.downloads.DownloadTrackRow
import net.mhanak.yama.ui.components.interaction.ContentFocusHost

/**
 * Dedicated management view for one downloaded album: its downloaded tracks with per-track actions, and
 * album-level actions (re-download all / change quality / remove) in the top bar. Built from the index.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedAlbumView(
    albumId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val manager = appContainer.downloadManager
    val sourceKey = (appContainer.activeMusicSource as? OfflineCapable)?.downloadSourceKey()
    val availableTrackIds by appContainer.downloads.availableTrackIds.collectAsState()
    val album = remember(sourceKey, albumId, availableTrackIds) {
        sourceKey?.let { appContainer.downloads.downloadedAlbum(it, albumId) }
    }
    val tracks = remember(sourceKey, albumId, availableTrackIds) {
        sourceKey?.let { appContainer.downloads.downloadedTracks(it, albumId) } ?: emptyList()
    }

    // The album was fully removed (last track deleted) — pop back rather than show an empty shell.
    if (sourceKey != null && album == null) {
        LaunchedEffectOnce(onBack)
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(album?.name ?: "Album", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sourceKey != null && album != null) {
                        DownloadItemMenu(
                            currentQuality = album.quality,
                            removeConfirmText = "Remove all downloaded tracks of \"${album.name}\"?",
                            onChangeQuality = { quality -> manager.redownloadAlbumStored(sourceKey, albumId, quality) },
                            onRemove = { manager.removeAlbum(sourceKey, albumId); onBack() },
                        )
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
            if (album?.artist?.isNotBlank() == true) {
                item {
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(tracks, key = { it.id }) { track ->
                DownloadTrackRow(
                    track = track,
                    onChangeQuality = { quality -> sourceKey?.let { manager.redownloadStored(it, listOf(track.id), quality) } },
                    onRemove = { sourceKey?.let { manager.removeDownload(it, track.id) } },
                    onKeep = { sourceKey?.let { manager.keepDownloaded(it, track.id) } },
                )
            }
            if (tracks.isEmpty()) {
                item {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No downloaded tracks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        }
    }
}

/** Run [action] once when this composable first enters composition (used to pop a now-empty album). */
@Composable
private fun LaunchedEffectOnce(action: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { action() }
}
