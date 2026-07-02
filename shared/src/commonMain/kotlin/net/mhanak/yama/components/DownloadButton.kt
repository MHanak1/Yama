package net.mhanak.yama.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.download.DownloadJob
import net.mhanak.yama.media.download.DownloadState
import net.mhanak.yama.util.StreamingQuality

/** The container kinds a [DownloadButton] can enqueue for offline use. */
enum class DownloadableKind { Album, Artist, Genre, Playlist }

/**
 * Detail-view affordance to download a whole container (album/artist/genre/playlist) for offline use.
 * Hidden when the active source doesn't persist downloads. It reflects live state from the download
 * manager: idle (outline) → downloading (a progress ring filling as the container's tracks land, tap to
 * cancel) → done (filled). **Tap** enqueues at the default download quality (or cancels while in
 * flight); **long-press** opens a quality picker to (re-)download at a chosen quality. For an album it
 * also shows the quality its tracks were downloaded at.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadButton(
    kind: DownloadableKind,
    id: String,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    // Source doesn't support downloads (e.g. local files, or not signed in) — nothing to offer.
    val key = (source as? OfflineCapable)?.downloadSourceKey() ?: return

    val availability = LocalAvailability.current
    // Use pinned-only sets: cached (auto-play) entries don't count as explicitly downloaded.
    val downloaded = when (kind) {
        DownloadableKind.Album -> id in availability.pinnedAlbumIds
        DownloadableKind.Artist -> id in availability.pinnedArtistIds
        DownloadableKind.Genre -> id in availability.pinnedGenreIds
        DownloadableKind.Playlist -> false
    }
    // Re-derived whenever availability changes (i.e. after a download lands / is removed).
    val albumQuality = remember(availability, kind, id) {
        if (kind == DownloadableKind.Album && downloaded) appContainer.downloads.albumQuality(key, id) else null
    }

    // Live jobs belonging to this container (playlists carry no job linkage, so they get no live ring).
    val jobs by appContainer.downloadManager.downloads.collectAsState()
    val containerJobs = remember(jobs, kind, id) { jobs.filter { it.belongsTo(kind, id) } }
    val activeJobs = containerJobs.filter { it.state.isInFlight }
    val downloading = activeJobs.isNotEmpty()
    // Ring fraction: each in-container job counts as done(1) / running(frac) / pending(0), averaged.
    val progress = remember(containerJobs) {
        val counted = containerJobs.filter { it.state !is DownloadState.Failed }
        if (counted.isEmpty()) 0f
        else (counted.sumOf { it.state.fraction.toDouble() } / counted.size).toFloat()
    }

    var showQualityDialog by remember { mutableStateOf(false) }

    val download = {
        when (kind) {
            DownloadableKind.Album -> appContainer.downloadManager.enqueueAlbum(id)
            DownloadableKind.Artist -> appContainer.downloadManager.enqueueArtist(id)
            DownloadableKind.Genre -> appContainer.downloadManager.enqueueGenre(id)
            DownloadableKind.Playlist -> appContainer.downloadManager.enqueuePlaylist(id)
        }
    }
    val downloadAt = { quality: StreamingQuality ->
        when (kind) {
            DownloadableKind.Album -> appContainer.downloadManager.enqueueAlbum(id, quality)
            DownloadableKind.Artist -> appContainer.downloadManager.enqueueArtist(id, quality)
            DownloadableKind.Genre -> appContainer.downloadManager.enqueueGenre(id, quality)
            DownloadableKind.Playlist -> appContainer.downloadManager.enqueuePlaylist(id, quality)
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = {
                    if (downloading) activeJobs.forEach { appContainer.downloadManager.cancelJob(it.trackId) }
                    else download()
                },
                onLongClick = { showQualityDialog = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            downloading -> {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 2.dp,
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel download",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            downloaded -> DownloadedIcon(quality = albumQuality)
            else -> Icon(Icons.Outlined.Download, contentDescription = "Download")
        }
    }

    if (showQualityDialog) {
        QualityPickerDialog(
            title = if (downloaded) "Change quality…" else "Download at…",
            current = albumQuality ?: appContainer.downloadQuality,
            onDismiss = { showQualityDialog = false },
            onPick = { quality ->
                // Re-download already-downloaded containers (force), else a plain enqueue at that quality.
                if (downloaded) when (kind) {
                    DownloadableKind.Album -> appContainer.downloadManager.redownloadAlbum(id, quality)
                    DownloadableKind.Artist -> appContainer.downloadManager.redownloadArtist(id, quality)
                    DownloadableKind.Genre -> appContainer.downloadManager.redownloadGenre(id, quality)
                    DownloadableKind.Playlist -> appContainer.downloadManager.redownloadPlaylist(id, quality)
                } else downloadAt(quality)
            },
            // Remove is offered for downloaded containers we can resolve to stored tracks (not playlists,
            // which carry no stored container id).
            onRemove = if (downloaded && kind != DownloadableKind.Playlist) {
                {
                    when (kind) {
                        DownloadableKind.Album -> appContainer.downloadManager.removeAlbum(key, id)
                        DownloadableKind.Artist -> appContainer.downloadManager.removeArtist(key, id)
                        DownloadableKind.Genre -> appContainer.downloadManager.removeGenre(key, id)
                        DownloadableKind.Playlist -> Unit
                    }
                }
            } else null,
        )
    }
}

/** The "downloaded" affordance: a filled check with the download's quality as a small corner badge
 *  (O/H/M/L). The badge is omitted when the quality is unknown (mixed across the container). */
@Composable
private fun DownloadedIcon(quality: StreamingQuality?) {
    BadgedBox(
        badge = {
            // TODO: reconsider the badge design
            /*
            if (quality != null) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) { Text(quality.letter) }
            }*/
        },
    ) {

        Icon(Icons.Filled.OfflinePin, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
    }
}

/** Whether a job's track belongs to the given container, by the ids the job carries. */
private fun DownloadJob.belongsTo(kind: DownloadableKind, id: String): Boolean = when (kind) {
    DownloadableKind.Album -> albumId == id
    DownloadableKind.Artist -> id in artistIds
    DownloadableKind.Genre -> id in genreIds
    DownloadableKind.Playlist -> false
}

/** A job is "in flight" while queued, waiting on the network, or actively running. */
internal val DownloadState.isInFlight: Boolean
    get() = this is DownloadState.Queued || this is DownloadState.WaitingForNetwork || this is DownloadState.Running

/** Progress contribution of a single job to a container ring: done = 1, running = its fraction
 *  (indeterminate counts as 0), queued/waiting/failed = 0. */
internal val DownloadState.fraction: Float
    get() = when (this) {
        is DownloadState.Completed -> 1f
        is DownloadState.Running -> progress.coerceIn(0f, 1f)
        else -> 0f
    }
