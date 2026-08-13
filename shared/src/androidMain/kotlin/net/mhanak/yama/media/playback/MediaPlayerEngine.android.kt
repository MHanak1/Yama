package net.mhanak.yama.media.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.IBinder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import net.mhanak.yama.util.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.mhanak.yama.MyApplication
import net.mhanak.yama.PlaybackService
import kotlin.math.roundToInt

/**
 * Android engine: drives the [ExoPlayer] hosted by [PlaybackService] directly (in-process, no IPC
 * hop via `MediaController`). Subscribes to [PlaybackService.exoPlayerFlow] to get the player as
 * soon as the service starts; commands issued before that are buffered and replayed. A plain
 * [Context.bindService] call (with [Context.BIND_AUTO_CREATE]) creates the service and keeps it alive
 * for the engine's lifetime. The foreground promotion / media notification is handled by Media3
 * itself once the session is registered (see `PlaybackService.addSession`): it self-starts the
 * foreground service when playback begins.
 *
 * Driving the ExoPlayer directly ensures [status] always reflects true local decode state — never the
 * [net.mhanak.yama.media.playback.RemoteMediaPlayer] bridge that [PlaybackService] swaps into the
 * [androidx.media3.session.MediaSession] while casting.
 */
actual class MediaPlayerEngine actual constructor() {
    private val log = logger("Playback")

    private val _status = MutableStateFlow(EngineStatus())
    actual val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    actual val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _controlsSystemVolume = MutableStateFlow(false)
    actual val controlsSystemVolume: StateFlow<Boolean> = _controlsSystemVolume.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var player: ExoPlayer? = null
    private val pending = mutableListOf<(ExoPlayer) -> Unit>()
    private var pollJob: Job? = null
    private var serviceConn: ServiceConnection? = null

    // When true, volume acts on the device (media stream) level; otherwise on the in-app gain. Falls
    // back to in-app gain whenever device-volume control isn't actually available on the player.
    private var useDeviceVolume = true

    private val connectivityManager =
        MyApplication.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    // Registered only while [reconnecting], so we hold no system callback during normal playback.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // True while a transient network fault has stalled playback (buffer drained with no connection) and
    // we're waiting to resume from the same position once the network returns. Overlays the player's
    // STATE_IDLE as [PlaybackState.Reconnecting] so the UI shows a spinner instead of looking stopped.
    private var reconnecting = false
    // A fatal (non-recoverable) fault message — surfaced once via [EngineStatus.error]. Waiting won't
    // fix these (missing file, bad HTTP status, undecodable stream), so playback stays stopped.
    private var pendingError: String? = null

    private val playerListener = object : Media3Player.Listener {
        override fun onEvents(p: Media3Player, events: Media3Player.Events) {
            // Recovered from a transient stall: once the player leaves STATE_IDLE (a re-prepare took
            // hold), stop overlaying Reconnecting and drop the network callback we were holding.
            if (reconnecting && (p as? ExoPlayer)?.playbackState != Media3Player.STATE_IDLE) {
                reconnecting = false
                unregisterNetworkCallback()
            }
            pushStatus()
            (p as? ExoPlayer)?.let { pushVolume(it) }
        }

        // ExoPlayer errors silently collapse to STATE_IDLE without this override, making "play failed"
        // indistinguishable from a normal stop. Log the error, then split by cause: a transient network
        // fault holds position and waits for the network to return; anything else is surfaced as fatal.
        override fun onPlayerError(error: PlaybackException) {
            val p = player
            val uri = p?.currentMediaItem?.localConfiguration?.uri?.toString()
                ?: p?.currentMediaItem?.mediaId
                ?: "<unknown>"
            val idx = p?.currentMediaItemIndex ?: -1
            log.error(
                "ExoPlayer error [${error.errorCodeName}/${error.errorCode}] " +
                    "at index $idx uri=$uri: ${error.message}",
                error,
            )
            if (error.isTransientNetwork()) {
                // Hold the queue/position (playWhenReady is retained across the error) and wait for the
                // network rather than collapsing into an indistinguishable Idle. See [awaitNetworkThenRetry].
                reconnecting = true
                pendingError = null
                awaitNetworkThenRetry()
            } else {
                // Non-recoverable by waiting — stop and show a message. playWhenReady is cleared so a
                // later manual retry (which re-prepares) doesn't inherit a stale "keep playing" intent.
                reconnecting = false
                pendingError = error.userMessage()
                p?.playWhenReady = false
                unregisterNetworkCallback()
            }
            pushStatus()
        }
    }

    private fun PlaybackException.isTransientNetwork(): Boolean = errorCode in TRANSIENT_NETWORK_CODES

    private fun PlaybackException.userMessage(): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This track is unavailable."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server couldn't play this track."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "This track can't be accessed."
        else -> "This track couldn't be played."
    }

    /**
     * Register a one-off [ConnectivityManager] callback (if not already waiting) that re-prepares the
     * player as soon as the device has a network again. ExoPlayer resumes from [ExoPlayer.getCurrentPosition]
     * on [ExoPlayer.prepare], and playWhenReady is retained across the error, so it picks up right where
     * the buffer ran dry. The callback is dropped again once recovery is observed (see [onEvents]).
     */
    private fun awaitNetworkThenRetry() {
        val cm = connectivityManager ?: return
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Fires on a binder thread — hop to the main thread before touching the player.
                scope.launch { retryAfterReconnect() }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { log.warn("Couldn't register network callback for playback recovery", it) }
    }

    private fun retryAfterReconnect() {
        val p = player ?: return
        if (reconnecting && p.playbackState == Media3Player.STATE_IDLE) {
            log.info("Network available again — re-preparing to resume from ${p.currentPosition}ms")
            p.prepare()
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }

    // Abandon any in-flight recovery — called when a manual command supersedes the stalled item.
    private fun clearRecovery() {
        reconnecting = false
        pendingError = null
        unregisterNetworkCallback()
    }

    init {
        val context = MyApplication.appContext
        // Keep the service alive via a binding. BIND_AUTO_CREATE starts it if it isn't running;
        // the binding is held until release() so the service outlives any foreground/background
        // transition and Media3 can promote it to foreground when playback starts.
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {}
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        serviceConn = conn
        context.bindService(Intent(context, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)

        // Attach to the ExoPlayer as soon as the service publishes it and re-attach if it ever
        // restarts. Null emissions mean the service is being destroyed; detach cleanly so the player
        // can be released safely.
        scope.launch {
            PlaybackService.exoPlayerFlow.collect { exo ->
                player?.removeListener(playerListener)
                player = exo
                if (exo != null) {
                    exo.addListener(playerListener)
                    pending.forEach { it(exo) }
                    pending.clear()
                    pushStatus()
                    pushVolume(exo)
                }
            }
        }
    }

    private inline fun withPlayer(crossinline action: (ExoPlayer) -> Unit) {
        val p = player
        if (p != null) action(p) else pending.add { action(it) }
    }

    private fun PlayableMedia.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    actual fun setQueue(items: List<PlayableMedia>, startIndex: Int) = withPlayer { p ->
        clearRecovery()
        if (items.isEmpty()) {
            p.clearMediaItems()
            return@withPlayer
        }
        p.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.size - 1), 0)
        p.prepare()
        p.play()
        ensurePolling()
    }

    actual fun loadQueue(items: List<PlayableMedia>, startIndex: Int) = withPlayer { p ->
        clearRecovery()
        if (items.isEmpty()) {
            p.clearMediaItems()
            return@withPlayer
        }
        p.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.size - 1), 0)
        p.prepare()
        // No p.play() — loads the queue in a paused/ready state
    }

    actual fun addToQueue(items: List<PlayableMedia>) = withPlayer { p ->
        p.addMediaItems(items.map { it.toMediaItem() })
    }

    actual fun addNext(items: List<PlayableMedia>) = withPlayer { p ->
        val at = (p.currentMediaItemIndex + 1).coerceIn(0, p.mediaItemCount)
        p.addMediaItems(at, items.map { it.toMediaItem() })
    }

    actual fun removeAt(index: Int) = withPlayer { p ->
        if (index in 0 until p.mediaItemCount) p.removeMediaItem(index)
    }

    actual fun move(from: Int, to: Int) = withPlayer { p -> p.moveMediaItem(from, to) }
    actual fun clear() = withPlayer { p -> p.clearMediaItems() }

    // A manual play while stalled/errored is a retry: re-prepare an idle player so it resumes from the
    // held position (or the failed track), then play. A healthy player is never in STATE_IDLE here.
    actual fun play() = withPlayer { p ->
        if (p.playbackState == Media3Player.STATE_IDLE) { clearRecovery(); p.prepare() }
        p.play(); ensurePolling()
    }
    actual fun pause() = withPlayer { p -> p.pause() }
    actual fun seekTo(positionMs: Long) = withPlayer { p -> p.seekTo(positionMs) }
    // Skipping tracks abandons recovery of the stalled item; re-prepare if the player was left idle.
    actual fun next() = withPlayer { p -> clearRecovery(); p.seekToNextMediaItem(); reprepareIfIdle(p) }
    actual fun previous() = withPlayer { p -> clearRecovery(); p.seekToPrevious(); reprepareIfIdle(p) }
    actual fun seekToIndex(index: Int) = withPlayer { p -> clearRecovery(); p.seekTo(index, 0); reprepareIfIdle(p) }

    private fun reprepareIfIdle(p: ExoPlayer) { if (p.playbackState == Media3Player.STATE_IDLE) p.prepare() }

    actual fun setRepeat(mode: RepeatMode) = withPlayer { p ->
        p.repeatMode = when (mode) {
            RepeatMode.Off -> Media3Player.REPEAT_MODE_OFF
            RepeatMode.All -> Media3Player.REPEAT_MODE_ALL
            RepeatMode.One -> Media3Player.REPEAT_MODE_ONE
        }
    }

    actual fun setShuffle(enabled: Boolean) = withPlayer { p -> p.shuffleModeEnabled = enabled }

    actual fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        withPlayer { p ->
            if (p.usingDevice()) {
                val info = p.deviceInfo
                val target = info.minVolume + ((info.maxVolume - info.minVolume) * clamped).roundToInt()
                p.setDeviceVolume(target, 0)
            } else {
                p.volume = clamped
            }
            pushVolume(p)
        }
    }

    actual fun setVolumeMode(useDeviceVolume: Boolean) {
        this.useDeviceVolume = useDeviceVolume
        player?.let { pushVolume(it) }
    }

    // Device (media-stream) volume is only usable when the player has it enabled (see
    // PlaybackService.setDeviceVolumeControlEnabled) and reports a real range; otherwise fall back.
    private fun ExoPlayer.usingDevice(): Boolean =
        useDeviceVolume &&
            isCommandAvailable(Media3Player.COMMAND_GET_DEVICE_VOLUME) &&
            isCommandAvailable(Media3Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS) &&
            deviceInfo.maxVolume > deviceInfo.minVolume

    // Mirror whichever volume we're driving into [_volume] as a normalized 0f..1f value, and publish
    // whether we're actually on the device (system) stream so the UI can defer to the OS volume panel.
    private fun pushVolume(exo: ExoPlayer) {
        val usingDevice = exo.usingDevice()
        _controlsSystemVolume.value = usingDevice
        if (usingDevice) {
            // Pin the in-app gain so the device volume isn't additionally attenuated.
            exo.volume = 1f
            val info = exo.deviceInfo
            val range = (info.maxVolume - info.minVolume).coerceAtLeast(1)
            _volume.value = ((exo.deviceVolume - info.minVolume).toFloat() / range).coerceIn(0f, 1f)
        } else {
            _volume.value = exo.volume.coerceIn(0f, 1f)
        }
    }

    actual fun release() {
        pollJob?.cancel()
        unregisterNetworkCallback()
        player?.removeListener(playerListener)
        player = null
        scope.cancel()
        serviceConn?.let { runCatching { MyApplication.appContext.unbindService(it) } }
        serviceConn = null
    }

    // Media3 only emits discrete events; position advances continuously, so poll while playing.
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val p = player ?: break
                if (p.isPlaying) pushStatus()
                delay(500)
            }
        }
    }

    private fun pushStatus() {
        val p = player ?: return
        val state = when {
            // A transient network stall overlays the underlying STATE_IDLE so the UI shows a spinner
            // (and holds position) instead of the "stopped" that a raw Idle would render.
            reconnecting -> PlaybackState.Reconnecting
            p.playbackState == Media3Player.STATE_BUFFERING -> PlaybackState.Buffering
            p.playbackState == Media3Player.STATE_ENDED -> {
                log.debug("Playback ended at index ${p.currentMediaItemIndex}")
                PlaybackState.Ended
            }
            p.playbackState == Media3Player.STATE_IDLE -> {
                // STATE_IDLE can mean either a normal stop or an error (onPlayerError fires first in the
                // error case, so by here the cause is already logged if it was an error).
                log.debug("Playback idle (index=${p.currentMediaItemIndex} hasError=${p.playerError != null})")
                PlaybackState.Idle
            }
            p.isPlaying -> PlaybackState.Playing
            else -> PlaybackState.Paused
        }
        _status.value = EngineStatus(
            state = state,
            queueIndex = if (p.mediaItemCount == 0) -1 else p.currentMediaItemIndex,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.duration == C.TIME_UNSET) 0 else p.duration,
            isPlaying = p.isPlaying,
            repeat = when (p.repeatMode) {
                Media3Player.REPEAT_MODE_ALL -> RepeatMode.All
                Media3Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            shuffle = p.shuffleModeEnabled,
            error = pendingError,
        )
    }

    private companion object {
        // ExoPlayer error codes that mean "the connection dropped" rather than "this track is bad" —
        // the only ones worth waiting out for the network to return.
        val TRANSIENT_NETWORK_CODES = intArrayOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }
}
