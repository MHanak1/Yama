package net.mhanak.yama.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mhanak.yama.media.download.DownloadJob
import net.mhanak.yama.media.download.DownloadManager
import net.mhanak.yama.media.download.DownloadState

/**
 * The live downloads section: an aggregate header (count + overall progress, Cancel-all / Clear-finished)
 * followed by the per-track job rows grouped by album. Emitted into a host [LazyListScope] so it can sit
 * at the top of the Downloads hub *or* fill a dedicated screen. No-op when [jobs] is empty.
 */
fun LazyListScope.downloadJobsSection(jobs: List<DownloadJob>, manager: DownloadManager) {
    if (jobs.isEmpty()) return

    val active = jobs.filter { it.state.isInFlight }
    val finished = jobs.filter { it.state is DownloadState.Completed || it.state is DownloadState.Failed }

    item(key = "active-header") {
        val paused by manager.paused.collectAsState()
        ActiveDownloadsHeader(
            jobs = jobs,
            activeCount = active.size,
            paused = paused,
            onTogglePause = { manager.setPaused(!paused) },
            onCancelAll = { active.forEach { manager.cancelJob(it.trackId) } },
            onClearFinished = if (finished.isNotEmpty()) {
                { finished.forEach { manager.dismissJob(it.trackId) } }
            } else null,
        )
    }

    // Group by album so a multi-track album reads as one unit; tracks with no album fall under "Other".
    jobs.groupBy { it.album }.forEach { (album, groupJobs) ->
        item(key = "group-${album ?: "_"}") { JobGroupHeader(album ?: "Other") }
        items(groupJobs, key = { it.trackId }) { job ->
            DownloadJobRow(
                job = job,
                onCancel = { manager.cancelJob(job.trackId) },
                onRetry = { manager.retry(job.trackId) },
                onDismiss = { manager.dismissJob(job.trackId) },
                onDownloadNow = { manager.downloadNow(job.trackId) },
            )
        }
    }
}

@Composable
private fun ActiveDownloadsHeader(
    jobs: List<DownloadJob>,
    activeCount: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: (() -> Unit)?,
) {
    val completed = jobs.count { it.state is DownloadState.Completed }
    // Overall progress across the batch (failed rows don't count toward the bar).
    val counted = jobs.filter { it.state !is DownloadState.Failed }
    val overall = if (counted.isEmpty()) 0f else (counted.sumOf { it.state.fraction.toDouble() } / counted.size).toFloat()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    activeCount == 0 -> "Downloads finished"
                    paused -> "Paused · $completed of ${jobs.size}"
                    else -> "Downloading $completed of ${jobs.size}"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (activeCount > 0) {
                IconButton(onClick = onTogglePause) {
                    Icon(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (paused) "Resume downloads" else "Pause downloads",
                    )
                }
                TextButton(onClick = onCancelAll) { Text("Cancel all") }
            }
            if (onClearFinished != null) {
                TextButton(onClick = onClearFinished) { Text("Clear finished") }
            }
        }
        if (activeCount > 0) {
            LinearProgressIndicator(
                progress = { overall },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun JobGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun DownloadJobRow(
    job: DownloadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDownloadNow: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            when (val state = job.state) {
                DownloadState.Queued -> Text("Queued")
                DownloadState.WaitingForNetwork ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Waiting for Wi-Fi")
                    }
                is DownloadState.Running ->
                    if (state.progress < 0f) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    } else {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                DownloadState.Completed -> Text("Downloaded", color = MaterialTheme.colorScheme.primary)
                is DownloadState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (job.state) {
                    DownloadState.WaitingForNetwork -> {
                        TextButton(onClick = onDownloadNow) { Text("Download now") }
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                    }
                    DownloadState.Queued, is DownloadState.Running ->
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                    is DownloadState.Failed -> {
                        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, contentDescription = "Retry") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                    }
                    DownloadState.Completed ->
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Dismiss") }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
