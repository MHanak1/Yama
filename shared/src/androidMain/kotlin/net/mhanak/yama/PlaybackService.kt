package net.mhanak.yama

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.mhanak.yama.media.playback.RemoteMediaPlayer
import net.mhanak.yama.shared.R

/**
 * Hosts the ExoPlayer that actually decodes audio on Android. Running it inside a
 * [MediaSessionService] is what gives us the media notification, lockscreen controls, media-key
 * handling and background playback — Media3 derives all of it from the player's own playlist.
 *
 * The UI never touches this directly; [net.mhanak.yama.media.playback.MediaPlayerEngine] connects a
 * `MediaController` to it.
 *
 * While casting ("Play On"), [observeActivePlayer] swaps the session's player from the ExoPlayer to a
 * [RemoteMediaPlayer] bridging the active remote player, so the OS media surfaces reflect and control
 * the remote device instead of this one.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    // The local engine that actually decodes audio on this device. Kept so the session can be swapped
    // back to it (and paused on hand-off) when casting toggles.
    private var localPlayer: ExoPlayer? = null
    // The Media3 bridge for the active remote player while casting, else null. Released on each swap.
    private var remotePlayer: RemoteMediaPlayer? = null

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Use the app's waveform (the launcher's monochrome icon) as the notification's status-bar
        // small icon instead of Media3's default. The system renders it as a tinted silhouette.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_waveform) },
        )
        val player = ExoPlayer.Builder(this)
            // Request audio focus and pause on focus loss / when headphones are unplugged.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Let the engine drive the device (media stream) volume, not just the in-app gain, so the
            // user (and remote controllers) can move the actual output level. Without this the
            // device-volume commands are unavailable and the engine falls back to in-app gain.
            .setDeviceVolumeControlEnabled(true)
            .build()
        localPlayer = player
        mediaSession = MediaSession.Builder(this, player)
            // Tapping the media notification launches the app and asks it to open the full player.
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()
        observeActivePlayer()
    }

    /**
     * Reflect [net.mhanak.yama.media.playback.PlaybackController.active] onto the OS media session.
     * When playing locally the session is backed by the [ExoPlayer]; while casting ("Play On") it's
     * backed by a [RemoteMediaPlayer] bridging the active remote player, so the notification /
     * lockscreen / media keys drive the remote device. `AppContainer.shared` is the process-wide
     * singleton and the service shares its process, so this observes it directly.
     */
    private fun observeActivePlayer() {
        val playback = AppContainer.shared.playback
        scope.launch {
            snapshotFlow { playback.active }.distinctUntilChanged().collect { active ->
                val session = mediaSession ?: return@collect
                val exo = localPlayer ?: return@collect
                if (active === playback.local) {
                    session.player = exo
                    remotePlayer?.release()
                    remotePlayer = null
                } else {
                    // Hand off: stop decoding here so audio doesn't keep playing locally while the
                    // user controls the remote device.
                    exo.pause()
                    val bridge = RemoteMediaPlayer(active, Looper.getMainLooper())
                    session.player = bridge
                    remotePlayer?.release()
                    remotePlayer = bridge
                }
            }
        }
    }

    private fun sessionActivityIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { putExtra(OPEN_PLAYER_EXTRA, true) }
            ?: return null
        return PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // If the user swipes the app away and nothing is playing, don't linger as a service.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // The session's current player may be the remote bridge, so release the engine and bridge
        // explicitly rather than only whatever the session happens to hold right now.
        mediaSession?.release()
        localPlayer?.release()
        remotePlayer?.release()
        mediaSession = null
        localPlayer = null
        remotePlayer = null
        super.onDestroy()
    }

    companion object {
        /** Intent extra MainActivity reads to open the full-screen player on launch. */
        const val OPEN_PLAYER_EXTRA = "net.mhanak.yama.OPEN_PLAYER"
    }
}
