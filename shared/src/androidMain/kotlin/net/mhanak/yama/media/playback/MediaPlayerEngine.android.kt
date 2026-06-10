package net.mhanak.yama.media.playback

import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as Media3Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
 * Android engine: drives a Media3 [MediaController] bound to [PlaybackService]. The controller
 * connects asynchronously, so commands issued before it is ready are buffered and replayed.
 */
actual class MediaPlayerEngine actual constructor() {
    private val _status = MutableStateFlow(EngineStatus())
    actual val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    actual val volume: StateFlow<Float> = _volume.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var pollJob: Job? = null

    // When true, volume acts on the device (media stream) level; otherwise on the in-app gain. Falls
    // back to in-app gain whenever device-volume control isn't actually available on the controller.
    private var useDeviceVolume = true

    init {
        val context = MyApplication.appContext
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Media3Player.Listener {
                override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                    pushStatus()
                    pushVolume(c)
                }
            })
            pending.forEach { it(c) }
            pending.clear()
            pushStatus()
            pushVolume(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private inline fun withController(crossinline action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pending.add { action(it) }
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

    actual fun setQueue(items: List<PlayableMedia>, startIndex: Int) = withController { c ->
        if (items.isEmpty()) {
            c.clearMediaItems()
            return@withController
        }
        c.setMediaItems(items.map { it.toMediaItem() }, startIndex.coerceIn(0, items.size - 1), 0)
        c.prepare()
        c.play()
        ensurePolling()
    }

    actual fun addToQueue(items: List<PlayableMedia>) = withController { c ->
        c.addMediaItems(items.map { it.toMediaItem() })
    }

    actual fun addNext(items: List<PlayableMedia>) = withController { c ->
        val at = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItems(at, items.map { it.toMediaItem() })
    }

    actual fun removeAt(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    actual fun move(from: Int, to: Int) = withController { c -> c.moveMediaItem(from, to) }
    actual fun clear() = withController { c -> c.clearMediaItems() }

    actual fun play() = withController { c -> c.play(); ensurePolling() }
    actual fun pause() = withController { c -> c.pause() }
    actual fun seekTo(positionMs: Long) = withController { c -> c.seekTo(positionMs) }
    actual fun next() = withController { c -> c.seekToNextMediaItem() }
    actual fun previous() = withController { c -> c.seekToPrevious() }
    actual fun seekToIndex(index: Int) = withController { c -> c.seekTo(index, 0) }

    actual fun setRepeat(mode: RepeatMode) = withController { c ->
        c.repeatMode = when (mode) {
            RepeatMode.Off -> Media3Player.REPEAT_MODE_OFF
            RepeatMode.All -> Media3Player.REPEAT_MODE_ALL
            RepeatMode.One -> Media3Player.REPEAT_MODE_ONE
        }
    }

    actual fun setShuffle(enabled: Boolean) = withController { c -> c.shuffleModeEnabled = enabled }

    actual fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        withController { c ->
            if (c.usingDevice()) {
                val info = c.deviceInfo
                val target = info.minVolume + ((info.maxVolume - info.minVolume) * clamped).roundToInt()
                c.setDeviceVolume(target, 0)
            } else {
                c.volume = clamped
            }
            pushVolume(c)
        }
    }

    actual fun setVolumeMode(useDeviceVolume: Boolean) {
        this.useDeviceVolume = useDeviceVolume
        withController { pushVolume(it) }
    }

    // Device (media-stream) volume is only usable when the session player has it enabled (see
    // PlaybackService.setDeviceVolumeControlEnabled) and reports a real range; otherwise fall back.
    private fun MediaController.usingDevice(): Boolean =
        useDeviceVolume &&
            isCommandAvailable(Media3Player.COMMAND_GET_DEVICE_VOLUME) &&
            isCommandAvailable(Media3Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS) &&
            deviceInfo.maxVolume > deviceInfo.minVolume

    // Mirror whichever volume we're driving into [_volume] as a normalized 0f..1f value.
    private fun pushVolume(c: MediaController) {
        _volume.value = if (c.usingDevice()) {
            val info = c.deviceInfo
            val range = (info.maxVolume - info.minVolume).coerceAtLeast(1)
            ((c.deviceVolume - info.minVolume).toFloat() / range).coerceIn(0f, 1f)
        } else {
            c.volume.coerceIn(0f, 1f)
        }
    }

    actual fun release() {
        pollJob?.cancel()
        controller?.release()
        controller = null
        scope.cancel()
    }

    // Media3 only emits discrete events; position advances continuously, so poll while playing.
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (c.isPlaying) pushStatus()
                delay(500)
            }
        }
    }

    private fun pushStatus() {
        val c = controller ?: return
        val state = when {
            c.playbackState == Media3Player.STATE_BUFFERING -> PlaybackState.Buffering
            c.playbackState == Media3Player.STATE_ENDED -> PlaybackState.Ended
            c.playbackState == Media3Player.STATE_IDLE -> PlaybackState.Idle
            c.isPlaying -> PlaybackState.Playing
            else -> PlaybackState.Paused
        }
        _status.value = EngineStatus(
            state = state,
            queueIndex = if (c.mediaItemCount == 0) -1 else c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = if (c.duration == C.TIME_UNSET) 0 else c.duration,
            isPlaying = c.isPlaying,
            repeat = when (c.repeatMode) {
                Media3Player.REPEAT_MODE_ALL -> RepeatMode.All
                Media3Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
            shuffle = c.shuffleModeEnabled,
        )
    }
}
