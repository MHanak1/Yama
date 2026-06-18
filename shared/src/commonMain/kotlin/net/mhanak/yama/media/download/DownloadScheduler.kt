package net.mhanak.yama.media.download

import kotlinx.coroutines.flow.StateFlow
import net.mhanak.yama.media.sources.local.Retention
import net.mhanak.yama.util.StreamingQuality

/**
 * A single track's deferred download. Carries everything the byte fetch needs so the request is
 * self-contained — the scheduler (and, on Android, a WorkManager worker running in a possibly fresh
 * process) never touches the source or the index to know what to fetch.
 */
data class DownloadRequest(
    val sourceKey: String,
    val trackId: String,
    val quality: StreamingQuality,
    val retention: Retention,
    val force: Boolean,
)

/**
 * Background download queue — the platform seam (see DOWNLOADS_PLAN.md). Applies the "Download over
 * Wi-Fi only" constraint and serialises the byte fetch ([DownloadManager.executeRequest]).
 *
 * - **JVM desktop** ([DownloadScheduler.jvm]) — an in-process serial coroutine queue, alive while the
 *   app runs (desktop has no process-death/background concept to survive).
 * - **Android** ([DownloadScheduler.android]) — WorkManager, so downloads continue when the app is
 *   backgrounded / swiped away / its process is killed, running as a foreground service with a
 *   notification. The worker drives [DownloadManager.executeRequest] via `AppContainer.shared`, so
 *   [execute] and [onWaiting] are used only by the desktop queue; the Android actual ignores them.
 *   Pause / "Download now" are desktop-only (WorkManager has no pause and constraints can't be changed
 *   after enqueue); on Android they are no-ops and [paused] stays false.
 */
expect class DownloadScheduler(
    wifiOnly: () -> Boolean,
    execute: suspend (DownloadRequest) -> Unit,
    onWaiting: (trackId: String) -> Unit,
) {
    /** Whether the queue is paused ("Pause all"). Always false on Android. */
    val paused: StateFlow<Boolean>

    fun enqueue(request: DownloadRequest)

    /** Drop a queued request, or cancel it mid-flight if it's currently downloading. */
    fun cancel(trackId: String)

    fun cancelAll()

    /** Let a request bypass the Wi-Fi-only hold ("Download now"). Desktop-only; no-op on Android. */
    fun runNow(trackId: String)

    fun setPaused(value: Boolean)

    /** True (consuming the flag) when [trackId]'s cancellation was a pause re-queue rather than a user
     *  cancel — desktop only; always false on Android. */
    suspend fun consumePauseRequeue(trackId: String): Boolean
}
