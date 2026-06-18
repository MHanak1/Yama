package net.mhanak.yama.media.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.mhanak.yama.MyApplication

/**
 * Android [DownloadScheduler] backed by WorkManager, so downloads survive the app being backgrounded /
 * swiped away / its process killed, and run as a foreground service with a notification ([DownloadWorker]).
 *
 * Each download is a unique [androidx.work.OneTimeWorkRequest] keyed by `trackId` (so re-enqueuing the
 * same track de-dupes and per-track cancel is clean), tagged [DOWNLOAD_TAG]. The Wi-Fi-only setting maps
 * to a [NetworkType.UNMETERED] constraint. The worker calls [DownloadManager.executeRequest] via
 * `AppContainer.shared`, so [execute] / [onWaiting] (used by the desktop queue) are ignored here.
 *
 * Limitations vs. the desktop queue (WorkManager has no pause and constraints can't change after
 * enqueue): [setPaused] / [runNow] are no-ops and [paused] stays false. Concurrency is WorkManager's
 * (not strictly one-at-a-time).
 */
actual class DownloadScheduler actual constructor(
    private val wifiOnly: () -> Boolean,
    execute: suspend (DownloadRequest) -> Unit,
    onWaiting: (trackId: String) -> Unit,
) {
    private val workManager get() = WorkManager.getInstance(MyApplication.appContext)

    private val _paused = MutableStateFlow(false)
    actual val paused: StateFlow<Boolean> = _paused.asStateFlow()

    actual fun enqueue(request: DownloadRequest) {
        val networkType = if (wifiOnly() && !request.force) NetworkType.UNMETERED else NetworkType.CONNECTED
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(request.toWorkData())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
            .addTag(DOWNLOAD_TAG)
            .build()
        // KEEP: an in-flight/queued download for this track isn't duplicated (mirrors the in-app dedup).
        workManager.enqueueUniqueWork(uniqueName(request.trackId), ExistingWorkPolicy.KEEP, work)
    }

    actual fun cancel(trackId: String) {
        workManager.cancelUniqueWork(uniqueName(trackId))
    }

    actual fun cancelAll() {
        workManager.cancelAllWorkByTag(DOWNLOAD_TAG)
    }

    // No-ops on Android — see the class doc. Kept for the shared API surface.
    actual fun runNow(trackId: String) {}
    actual fun setPaused(value: Boolean) {}
    actual suspend fun consumePauseRequeue(trackId: String): Boolean = false

    companion object {
        const val DOWNLOAD_TAG = "yama-download"
        private fun uniqueName(trackId: String) = "yama-download:$trackId"
    }
}
