package net.mhanak.yama.media.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
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
 * [Context.bindService] call (with [Context.BIND_AUTO_CREATE]) starts the service and keeps it alive
 * for the engine's lifetime so Media3 can promote it to foreground when playback starts.
 *
 * Driving the ExoPlayer directly ensures [status] always reflects true local decode state — never the
 * [net.mhanak.yama.media.playback.RemoteMediaPlayer] bridge that [PlaybackService] swaps into the
 * [androidx.media3.session.MediaSession] while casting.
 */
actual class MediaPlayerEngine actual constructor() {
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

    private val playerListener = object : Media3Player.Listener {
        override fun onEvents(p: Media3Player, events: Media3Player.Events) {
            pushStatus()
            (p as? ExoPlayer)?.let { pushVolume(it) }
        }
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

    actual fun play() = withPlayer { p -> p.play(); ensurePolling() }
    actual fun pause() = withPlayer { p -> p.pause() }
    actual fun seekTo(positionMs: Long) = withPlayer { p -> p.seekTo(positionMs) }
    actual fun next() = withPlayer { p -> p.seekToNextMediaItem() }
    actual fun previous() = withPlayer { p -> p.seekToPrevious() }
    actual fun seekToIndex(index: Int) = withPlayer { p -> p.seekTo(index, 0) }

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
            p.playbackState == Media3Player.STATE_BUFFERING -> PlaybackState.Buffering
            p.playbackState == Media3Player.STATE_ENDED -> PlaybackState.Ended
            p.playbackState == Media3Player.STATE_IDLE -> PlaybackState.Idle
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
        )
    }
}
