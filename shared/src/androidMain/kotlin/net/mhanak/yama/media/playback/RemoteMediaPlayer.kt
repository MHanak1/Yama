package net.mhanak.yama.media.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Track
import kotlin.math.roundToInt

/**
 * A Media3 [androidx.media3.common.Player] that bridges a Yama [Player] (the active *remote* player,
 * i.e. Jellyfin "Play On") onto the OS media surfaces. `PlaybackService` swaps the [MediaSession]'s
 * player to one of these while casting, so the notification / lockscreen / media keys reflect and
 * control the remote device — without the rest of the app knowing playback isn't local.
 *
 * It owns no playback: [getState] is derived from [player]'s [Player.status] (rebuilt whenever that
 * StateFlow emits, via [invalidateState]) and the transport `handle*` commands forward to [player].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteMediaPlayer(
    private val player: Player,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    init {
        // Re-pull state whenever anything the OS surface shows changes. status carries transport +
        // queue + position; volume / controllability gate the volume slider and its commands.
        scope.launch {
            combine(player.status, player.volume, player.volumeControllable) { _, _, _ -> }
                .collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val status = player.status.value
        val controllable = player.volumeControllable.value
        val level = player.volume.value

        val commands = Media3Player.Commands.Builder()
            .addAll(
                Media3Player.COMMAND_PLAY_PAUSE,
                Media3Player.COMMAND_PREPARE,
                Media3Player.COMMAND_STOP,
                Media3Player.COMMAND_SEEK_TO_NEXT,
                Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_PREVIOUS,
                Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Media3Player.COMMAND_SEEK_BACK,
                Media3Player.COMMAND_SEEK_FORWARD,
                Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_SET_REPEAT_MODE,
                Media3Player.COMMAND_SET_SHUFFLE_MODE,
                Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Media3Player.COMMAND_GET_TIMELINE,
                Media3Player.COMMAND_GET_METADATA,
                Media3Player.COMMAND_RELEASE,
            )
            .apply {
                // Only advertise volume control when the remote device actually supports it, so the
                // notification doesn't show a dead slider.
                if (controllable) {
                    add(Media3Player.COMMAND_GET_DEVICE_VOLUME)
                    add(Media3Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                    add(Media3Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                }
            }
            .build()

        val playlist = status.queue.mapIndexed { index, track -> track.toMediaItemData(index) }
        // SimpleBasePlayer requires STATE_IDLE while the playlist is empty, so fall back to it even if
        // the remote briefly reports a playing item without a queue.
        val playbackState = if (playlist.isEmpty()) Media3Player.STATE_IDLE else when (status.state) {
            PlaybackState.Idle -> Media3Player.STATE_IDLE
            PlaybackState.Buffering -> Media3Player.STATE_BUFFERING
            PlaybackState.Ended -> Media3Player.STATE_ENDED
            PlaybackState.Playing, PlaybackState.Paused -> Media3Player.STATE_READY
        }

        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(status.isPlaying, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(playlist)
            .setRepeatMode(
                when (status.repeat) {
                    RepeatMode.Off -> Media3Player.REPEAT_MODE_OFF
                    RepeatMode.All -> Media3Player.REPEAT_MODE_ALL
                    RepeatMode.One -> Media3Player.REPEAT_MODE_ONE
                },
            )
            .setShuffleModeEnabled(status.shuffle)
            // Remote status pushes are sparse; extrapolate from the last reported position while
            // playing so the notification scrubber advances smoothly between them (paused = constant).
            .setContentPositionMs(
                if (status.isPlaying) PositionSupplier.getExtrapolating(status.positionMs, 1f)
                else PositionSupplier.getConstant(status.positionMs),
            )

        if (status.queueIndex in playlist.indices) {
            builder.setCurrentMediaItemIndex(status.queueIndex)
        }
        if (controllable) {
            builder.setDeviceInfo(REMOTE_DEVICE_INFO)
            level?.let { builder.setDeviceVolume((it.coerceIn(0f, 1f) * VOLUME_RANGE).roundToInt()) }
        }
        return builder.build()
    }

    private fun Track.toMediaItemData(index: Int): MediaItemData {
        val mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(artists?.joinToString(", "))
                    .setAlbumTitle(album)
                    .setArtworkUri(imageUrl?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
        return MediaItemData.Builder(/* uid = */ "$index|$id")
            .setMediaItem(mediaItem)
            .setDurationUs(durationTicks?.let { (it / 10_000) * 1_000 } ?: C.TIME_UNSET)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) player.play() else player.pause()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> = done()

    override fun handleStop(): ListenableFuture<*> {
        player.stop()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Media3Player.COMMAND_SEEK_TO_NEXT,
            Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> player.next()
            Media3Player.COMMAND_SEEK_TO_PREVIOUS,
            Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> player.previous()
            Media3Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
                if (mediaItemIndex != player.status.value.queueIndex) player.skipTo(mediaItemIndex)
                else player.seekTo(positionMs)
            else -> player.seekTo(positionMs)
        }
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        player.setRepeat(
            when (repeatMode) {
                Media3Player.REPEAT_MODE_ALL -> RepeatMode.All
                Media3Player.REPEAT_MODE_ONE -> RepeatMode.One
                else -> RepeatMode.Off
            },
        )
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        player.setShuffle(shuffleModeEnabled)
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        player.setVolume((deviceVolume.toFloat() / VOLUME_RANGE).coerceIn(0f, 1f))
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        player.volumeUp()
        return done()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        player.volumeDown()
        return done()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        // The remote device's volume is reported/controlled in 0f..1f; map it onto a 0..100 scale so
        // the OS volume slider has whole steps to work with.
        const val VOLUME_RANGE = 100
        val REMOTE_DEVICE_INFO: DeviceInfo =
            DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(VOLUME_RANGE)
                .build()
    }
}
