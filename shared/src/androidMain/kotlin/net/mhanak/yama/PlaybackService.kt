package net.mhanak.yama

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshotFlow
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.mhanak.yama.media.playback.RemoteMediaPlayer
import net.mhanak.yama.shared.R

/**
 * Hosts the ExoPlayer that actually decodes audio on Android. Running it inside a
 * [MediaSessionService] is what gives us the media notification, lockscreen controls, media-key
 * handling and background playback — Media3 derives all of it from the player's own playlist.
 *
 * [net.mhanak.yama.media.playback.MediaPlayerEngine] drives the ExoPlayer directly (in-process, no
 * IPC hop) by subscribing to [exoPlayerFlow]. It keeps the service alive via a plain [bindService]
 * binding ([android.content.Context.BIND_AUTO_CREATE]), so Media3 can promote the service to
 * foreground when playback starts.
 *
 * While casting ("Play On"), [observeActivePlayer] swaps the session's player from the ExoPlayer to a
 * [RemoteMediaPlayer] bridging the active remote player, so the OS notification / lockscreen / media
 * keys reflect and control the remote device. The ExoPlayer itself keeps running — Phase 2 of the
 * two-axis model (see PLAYBACK_PLAN.md) removes the mutual-exclusion between local and remote.
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
        // Container-less downloads from Jellyfin's transcoder (raw ADTS AAC, MP3) carry no seek table,
        // so by default ExoPlayer reports them unseekable and snaps every seekTo() to position 0.
        // Enabling constant-bitrate seeking lets the extractors extrapolate a seek position from the
        // bitrate, making the slider work on downloaded files (online/local containers seek natively).
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
        // Buffer further ahead than the ~50s default so a brief network drop is ridden out from the
        // buffer instead of stalling. Audio is cheap to hold, so we keep up to ~2 min queued and a
        // 30s back-buffer for instant re-seeks; the playback thresholds are left at their defaults so
        // startup and post-rebuffer resume stay snappy. Longer outages fall through to the engine's
        // reconnect-and-resume path (see MediaPlayerEngine.onPlayerError).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                /* bufferForPlaybackAfterRebufferMs = */ DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .setBackBuffer(/* backBufferDurationMs = */ 30_000, /* retainBackBufferFromKeyframe = */ true)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setLoadControl(loadControl)
            // Request audio focus and pause on focus loss / when headphones are unplugged.
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Let the engine drive the device (media stream) volume, not just the in-app gain, so the
            // user (and remote controllers) can move the actual output level. Without this the
            // device-volume commands are unavailable and the engine falls back to in-app gain.
            .setDeviceVolumeControlEnabled(true)
            .build()
        localPlayer = player
        exoPlayerFlow.value = player
        mediaSession = MediaSession.Builder(this, player)
            // Tapping the media notification launches the app and asks it to open the full player.
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()
        // Register the session with the service explicitly. MediaSessionService doesn't auto-add the
        // session you build — normally it's added when a MediaController connects (triggering
        // onGetSession). The engine drives the ExoPlayer in-process and never connects a controller, so
        // without this the notification controller never connects, MediaNotificationManager's
        // shouldRunInForeground() stays false, and no media notification / foreground promotion ever
        // happens. Adding it here makes Media3 manage the notification + foreground for whatever
        // session.player currently is — the ExoPlayer or the swapped-in remote bridge.
        mediaSession?.let { addSession(it) }
        observeActivePlayer()
    }

    /**
     * Reflect [net.mhanak.yama.media.playback.PlaybackController.viewed] onto the OS media session, so
     * the notification follows whatever player the UI is showing. When viewing this device the session
     * is backed by the [ExoPlayer]; while viewing a remote ("Play On") it's backed by a
     * [RemoteMediaPlayer] bridging that remote player, so the notification / lockscreen / media keys
     * drive the remote device. `AppContainer.shared` is the process-wide singleton and the service
     * shares its process, so this observes it directly.
     *
     * The ExoPlayer is not paused on hand-off (Phase 2 of the two-axis model — see PLAYBACK_PLAN.md):
     * local decode keeps running as an endpoint while the UI shows and controls the remote. Only the
     * notification surface moves.
     */
    private fun observeActivePlayer() {
        val playback = AppContainer.shared.playback
        scope.launch {
            snapshotFlow { playback.viewed }.distinctUntilChanged().collect { viewed ->
                val session = mediaSession ?: return@collect
                val exo = localPlayer ?: return@collect
                if (viewed === playback.local) {
                    session.player = exo
                    remotePlayer?.release()
                    remotePlayer = null
                } else {
                    val bridge = RemoteMediaPlayer(viewed, Looper.getMainLooper())
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
        // Signal the engine to detach before releasing the player so it can remove its listener cleanly.
        exoPlayerFlow.value = null
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

        /**
         * The active [ExoPlayer] for in-process direct access. Set in [onCreate] and cleared in
         * [onDestroy] before the player is released, so subscribers can remove their listeners cleanly.
         * [net.mhanak.yama.media.playback.MediaPlayerEngine] subscribes to drive the player without
         * going through the IPC hop of a `MediaController`.
         */
        val exoPlayerFlow = MutableStateFlow<ExoPlayer?>(null)
    }
}
