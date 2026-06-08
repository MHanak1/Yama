package net.mhanak.yama

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Hosts the ExoPlayer that actually decodes audio on Android. Running it inside a
 * [MediaSessionService] is what gives us the media notification, lockscreen controls, media-key
 * handling and background playback — Media3 derives all of it from the player's own playlist.
 *
 * The UI never touches this directly; [net.mhanak.yama.media.playback.MediaPlayerEngine] connects a
 * `MediaController` to it.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Request audio focus and pause on focus loss / when headphones are unplugged.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            // Tapping the media notification launches the app and asks it to open the full player.
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** Intent extra MainActivity reads to open the full-screen player on launch. */
        const val OPEN_PLAYER_EXTRA = "net.mhanak.yama.OPEN_PLAYER"
    }
}
