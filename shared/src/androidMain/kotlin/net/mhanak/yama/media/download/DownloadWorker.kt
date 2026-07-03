package net.mhanak.yama.media.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import net.mhanak.yama.AppContainer
import net.mhanak.yama.media.sources.local.Retention
import net.mhanak.yama.util.StreamingQuality
import net.mhanak.yama.util.logger

/**
 * Runs one [DownloadRequest]'s byte fetch as a WorkManager foreground worker — what keeps a download
 * going when the app is backgrounded / closed, and shows the system notification. It drives
 * [DownloadManager.executeRequest] through `AppContainer.shared` (constructed on demand if WorkManager
 * restarted us in a fresh process), so the download resolves its stream URL and writes the index exactly
 * as an in-app download would.
 *
 * Deliberately a **synchronous** [Worker] (not a `CoroutineWorker`): its [doWork] runs on — and blocks —
 * a thread of WorkManager's configured executor for the whole download, so the fixed-size pool in
 * [net.mhanak.yama.MyApplication.workManagerConfiguration] caps how many downloads run at once
 * (a `CoroutineWorker` would run on `Dispatchers.Default`, ignoring that cap). Surplus downloads wait in
 * the executor queue — not started, so no extra notifications/wakelocks.
 *
 * Always returns [Result.success]: the outcome (completed / failed) is recorded in the downloads index +
 * flow, so a failed download must not trigger WorkManager's retry/backoff or affect other work.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    private val log = logger("Downloads")

    override fun doWork(): Result {
        val request = inputData.toDownloadRequest() ?: return Result.success()
        // Promote to a foreground service so the OS keeps us running while the app is away.
        runCatching { setForegroundAsync(buildForegroundInfo()).get() }
            .onFailure { log.warn("setForegroundAsync failed for track=${request.trackId}", it) }
        runCatching { runBlocking { AppContainer.shared.downloadManager.executeRequest(request) } }
            .onFailure { log.error("executeRequest failed in WorkManager for track=${request.trackId}", it) }
        return Result.success()
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading music")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "yama-downloads"
        private const val NOTIFICATION_ID = 4242

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}

// --- DownloadRequest <-> WorkManager Data (top-level so the scheduler can build the input data) -------

private const val KEY_SOURCE = "sourceKey"
private const val KEY_TRACK = "trackId"
private const val KEY_QUALITY = "quality"
private const val KEY_RETENTION = "retention"
private const val KEY_FORCE = "force"

internal fun DownloadRequest.toWorkData(): Data = Data.Builder()
    .putString(KEY_SOURCE, sourceKey)
    .putString(KEY_TRACK, trackId)
    .putString(KEY_QUALITY, quality.name)
    .putString(KEY_RETENTION, retention.name)
    .putBoolean(KEY_FORCE, force)
    .build()

private fun Data.toDownloadRequest(): DownloadRequest? {
    val sourceKey = getString(KEY_SOURCE) ?: return null
    val trackId = getString(KEY_TRACK) ?: return null
    val quality = getString(KEY_QUALITY)?.let { runCatching { StreamingQuality.valueOf(it) }.getOrNull() }
        ?: return null
    val retention = getString(KEY_RETENTION)?.let { runCatching { Retention.valueOf(it) }.getOrNull() }
        ?: Retention.Pinned
    return DownloadRequest(sourceKey, trackId, quality, retention, getBoolean(KEY_FORCE, false))
}
