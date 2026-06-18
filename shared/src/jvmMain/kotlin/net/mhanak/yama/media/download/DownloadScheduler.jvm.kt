package net.mhanak.yama.media.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Desktop [DownloadScheduler]: serializes downloads onto a single background queue and applies the
 * "Download over Wi-Fi only" constraint ([isNetworkUnmetered]) — a request waits until an unmetered
 * network is available before it runs. One download at a time keeps progress legible and avoids
 * saturating a connection. [execute] is the actual byte fetch ([DownloadManager.executeRequest]); the
 * scheduler only owns *when* it runs. Desktop has no process-death to survive, so the queue lives
 * in-process while the app runs (Android uses WorkManager instead).
 */
actual class DownloadScheduler actual constructor(
    private val wifiOnly: () -> Boolean,
    private val execute: suspend (DownloadRequest) -> Unit,
    // Called once when a request starts waiting on the Wi-Fi-only hold, so the UI can show why it's
    // parked. No "stopped waiting" callback is needed: [execute] flips the row to Running itself.
    private val onWaiting: (trackId: String) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val queue = ArrayDeque<DownloadRequest>()
    private val cancelled = mutableSetOf<String>()
    // Requests the user asked to run now: they skip the Wi-Fi-only hold on their next pump.
    private val forcedNow = mutableSetOf<String>()
    // Requests cancelled by "Pause" rather than by the user: they're re-queued (front) and must NOT be
    // purged from the jobs list when their coroutine unwinds (see [consumePauseRequeue]).
    private val requeuedForPause = mutableSetOf<String>()
    private var currentId: String? = null
    private var currentJob: Job? = null
    private var currentReq: DownloadRequest? = null
    // Conflated wake-up signal: the pump drains the whole queue, then parks here until the next enqueue.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // "Pause all": while set, the pump parks before dequeuing the next request and the in-flight
    // download (if any) is cancelled and re-queued. Exposed so the UI can show / toggle it.
    private val _paused = MutableStateFlow(false)
    actual val paused: StateFlow<Boolean> = _paused.asStateFlow()

    actual fun setPaused(value: Boolean) {
        _paused.value = value
        if (value) {
            // Stop the running download too: re-queue it at the front and cancel it, flagged so its
            // unwinding doesn't drop the job (it'll resume from scratch — there's no byte-level resume).
            scope.launch {
                mutex.withLock {
                    currentReq?.let { req ->
                        requeuedForPause.add(req.trackId)
                        queue.addFirst(req)
                        currentJob?.cancel()
                    }
                }
            }
        } else {
            wake.trySend(Unit) // resume: nudge the parked pump to continue.
        }
    }

    actual suspend fun consumePauseRequeue(trackId: String): Boolean =
        mutex.withLock { requeuedForPause.remove(trackId) }

    init {
        scope.launch { pump() }
    }

    actual fun enqueue(request: DownloadRequest) {
        scope.launch {
            mutex.withLock { queue.addLast(request); cancelled.remove(request.trackId) }
            wake.trySend(Unit)
        }
    }

    actual fun cancel(trackId: String) {
        scope.launch {
            mutex.withLock {
                cancelled.add(trackId)
                queue.removeAll { it.trackId == trackId }
                if (currentId == trackId) currentJob?.cancel()
            }
        }
    }

    actual fun cancelAll() {
        scope.launch {
            mutex.withLock { queue.clear(); currentJob?.cancel() }
        }
    }

    actual fun runNow(trackId: String) {
        scope.launch {
            mutex.withLock { forcedNow.add(trackId) }
            wake.trySend(Unit)
        }
    }

    private suspend fun pump() {
        while (true) {
            // Hold the whole queue while paused; the next wake (enqueue or resume) re-checks.
            if (_paused.value) { wake.receive(); continue }
            val req = mutex.withLock { queue.removeFirstOrNull() }
            if (req == null) { wake.receive(); continue }
            if (mutex.withLock { cancelled.remove(req.trackId) }) continue
            // Hold the request until an unmetered network is available (Wi-Fi-only), unless the user
            // forced it through. Re-check cancellation while waiting so a cancel during the hold takes
            // effect promptly, and flag the row WaitingForNetwork once so the UI can explain the stall.
            var notifiedWaiting = false
            while (wifiOnly() && !isNetworkUnmetered()) {
                if (mutex.withLock { req.trackId in cancelled || req.trackId in forcedNow }) break
                if (!notifiedWaiting) { notifiedWaiting = true; onWaiting(req.trackId) }
                delay(WIFI_RECHECK_MS)
            }
            if (mutex.withLock { cancelled.remove(req.trackId).also { forcedNow.remove(req.trackId) } }) continue
            // Paused in the instant between the top-of-loop check and here — put it back and park.
            if (mutex.withLock { _paused.value.also { if (it) queue.addFirst(req) } }) continue
            // Starting fresh — clear any leftover pause flag so a later genuine cancel isn't mistaken
            // for a re-queue (covers the rare "completed exactly as we paused" race).
            val job = scope.launch { execute(req) }
            mutex.withLock { currentId = req.trackId; currentJob = job; currentReq = req; requeuedForPause.remove(req.trackId) }
            runCatching { job.join() }
            mutex.withLock { currentId = null; currentJob = null; currentReq = null }
        }
    }

    private companion object {
        const val WIFI_RECHECK_MS = 5_000L
    }
}
