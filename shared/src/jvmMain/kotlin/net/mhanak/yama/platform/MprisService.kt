package net.mhanak.yama.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.mhanak.yama.media.playback.LocalPlayer
import net.mhanak.yama.media.playback.PlaybackState
import net.mhanak.yama.media.playback.PlayerStatus
import net.mhanak.yama.media.playback.RepeatMode
import net.mhanak.yama.util.logger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

private const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
private const val MPRIS_SERVICE = "org.mpris.MediaPlayer2.yama"
private const val IFACE_ROOT = "org.mpris.MediaPlayer2"
private const val IFACE_PLAYER = "org.mpris.MediaPlayer2.Player"

/**
 * MPRIS2 D-Bus integration for Linux. Publishes [MPRIS_SERVICE] on the session bus so desktop
 * environments, media keys, taskbars, and tools like playerctl can see and control Yama.
 *
 * No-ops silently on non-Linux platforms or when the session bus is unavailable (e.g. headless SSH).
 * Transport is discovered at runtime via ServiceLoader from dbus-java-transport-native-unixsocket.
 */
class MprisService(private val player: LocalPlayer) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var conn: DBusConnection? = null
    private val log = logger("MprisService")

    fun start() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        runCatching {
            val c = DBusConnectionBuilder.forSessionBus().build()
            conn = c
            c.requestBusName(MPRIS_SERVICE)
            val handler = MprisHandler(c, player)
            c.exportObject(MPRIS_PATH, handler)
            scope.launch { player.status.collect { handler.onStatusChanged(it) } }
        }.onFailure {
            log.warn("D-Bus unavailable — MPRIS disabled", it)
        }
    }

    fun stop() {
        scope.cancel()
        runCatching { conn?.close() }
        conn = null
    }
}

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    /** Relative seek in microseconds; positive = forward, negative = backward. */
    fun Seek(x: Long)
    /** Absolute seek to [position] microseconds; only valid when [trackId] matches the current track. */
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

private class MprisHandler(
    private val conn: DBusConnection,
    private val player: LocalPlayer,
) : MprisRoot, MprisPlayer, Properties {

    @Volatile private var status = PlayerStatus()

    override fun isRemote() = false
    override fun getObjectPath() = MPRIS_PATH

    // --- org.mpris.MediaPlayer2 ---

    override fun Raise() {}
    override fun Quit() {}

    // --- org.mpris.MediaPlayer2.Player ---

    override fun Next() = player.next()
    override fun Previous() = player.previous()
    override fun Pause() = player.pause()
    override fun PlayPause() = player.togglePlayPause()
    override fun Stop() = player.stop()
    override fun Play() = player.play()

    override fun Seek(x: Long) {
        val newMs = (status.positionMs + x / 1000L).coerceAtLeast(0L)
        player.seekTo(newMs)
    }

    override fun SetPosition(trackId: DBusPath, position: Long) {
        val current = status.current ?: return
        if (trackId.path != current.id.toTrackPath()) return
        player.seekTo(position / 1000L)
    }

    override fun OpenUri(uri: String) {}

    // --- org.freedesktop.DBus.Properties ---

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interface_name: String, property_name: String): A {
        val map = when (interface_name) {
            IFACE_ROOT -> rootProps()
            IFACE_PLAYER -> playerProps()
            else -> emptyMap()
        }
        return (map[property_name] ?: Variant("")) as A
    }

    override fun GetAll(interface_name: String): Map<String, Variant<*>> = when (interface_name) {
        IFACE_ROOT -> rootProps()
        IFACE_PLAYER -> playerProps()
        else -> emptyMap()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Set(interface_name: String, property_name: String, value: A) {
        if (interface_name != IFACE_PLAYER) return
        // dbus-java may pass the D-Bus variant as a Variant<*> wrapper or already-unwrapped.
        val v = if (value is Variant<*>) value.value else value
        when (property_name) {
            "Volume" -> (v as? Double)?.let { player.setVolume(it.toFloat()) }
            "LoopStatus" -> player.setRepeat(
                when (v as? String) {
                    "Track" -> RepeatMode.One
                    "Playlist" -> RepeatMode.All
                    else -> RepeatMode.Off
                }
            )
            "Shuffle" -> (v as? Boolean)?.let { player.setShuffle(it) }
        }
    }

    // Called from the status-collector coroutine whenever LocalPlayer emits a new PlayerStatus.
    fun onStatusChanged(new: PlayerStatus) {
        val old = status
        status = new
        val changed = mutableMapOf<String, Variant<*>>()

        if (new.isPlaying != old.isPlaying || new.state != old.state)
            changed["PlaybackStatus"] = Variant(new.playbackStatus(), "s")
        if (new.current?.id != old.current?.id || new.durationMs != old.durationMs)
            changed["Metadata"] = Variant(new.metadata(), "a{sv}")
        new.volume?.let { v -> if (v != old.volume) changed["Volume"] = Variant(v.toDouble(), "d") }
        if (new.repeat != old.repeat)
            changed["LoopStatus"] = Variant(new.loopStatus(), "s")
        if (new.shuffle != old.shuffle)
            changed["Shuffle"] = Variant(new.shuffle, "b")
        if (new.queueIndex != old.queueIndex || new.queue.size != old.queue.size) {
            changed["CanGoNext"] = Variant(new.canGoNext(), "b")
            changed["CanGoPrevious"] = Variant(new.canGoPrevious(), "b")
            changed["CanPlay"] = Variant(new.queue.isNotEmpty(), "b")
            changed["CanPause"] = Variant(new.isPlaying, "b")
            changed["CanSeek"] = Variant(new.current != null, "b")
        }

        if (changed.isEmpty()) return
        runCatching {
            conn.sendMessage(
                Properties.PropertiesChanged(MPRIS_PATH, IFACE_PLAYER, changed, emptyList())
            )
        }
    }

    private fun rootProps(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(false, "b"),
        "CanRaise" to Variant(false, "b"),
        "HasTrackList" to Variant(false, "b"),
        "Identity" to Variant("Yama", "s"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    private fun playerProps(): Map<String, Variant<*>> {
        val s = status
        return mapOf(
            "PlaybackStatus" to Variant(s.playbackStatus(), "s"),
            "LoopStatus" to Variant(s.loopStatus(), "s"),
            "Rate" to Variant(1.0, "d"),
            "Shuffle" to Variant(s.shuffle, "b"),
            "Metadata" to Variant(s.metadata(), "a{sv}"),
            "Volume" to Variant((s.volume ?: 1f).toDouble(), "d"),
            "Position" to Variant(s.positionMs * 1000L, "x"),
            "MinimumRate" to Variant(1.0, "d"),
            "MaximumRate" to Variant(1.0, "d"),
            "CanGoNext" to Variant(s.canGoNext(), "b"),
            "CanGoPrevious" to Variant(s.canGoPrevious(), "b"),
            "CanPlay" to Variant(s.queue.isNotEmpty(), "b"),
            "CanPause" to Variant(s.isPlaying, "b"),
            "CanSeek" to Variant(s.current != null, "b"),
            "CanControl" to Variant(true, "b"),
        )
    }

    private fun PlayerStatus.playbackStatus() = when {
        state == PlaybackState.Idle || state == PlaybackState.Ended -> "Stopped"
        isPlaying -> "Playing"
        else -> "Paused"
    }

    private fun PlayerStatus.loopStatus() = when (repeat) {
        RepeatMode.Off -> "None"
        RepeatMode.All -> "Playlist"
        RepeatMode.One -> "Track"
    }

    private fun PlayerStatus.canGoNext() = queue.isNotEmpty() && queueIndex >= 0 && queueIndex < queue.size - 1
    private fun PlayerStatus.canGoPrevious() = queueIndex > 0

    private fun PlayerStatus.metadata(): Map<String, Variant<*>> {
        val track = current ?: return mapOf(
            "mpris:trackid" to Variant(
                DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack"), "o"
            )
        )
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(track.id.toTrackPath()), "o"))
            put("mpris:length", Variant(durationMs * 1000L, "x"))
            put("xesam:title", Variant(track.name, "s"))
            track.artists?.takeIf { it.isNotEmpty() }?.let {
                put("xesam:artist", Variant(it, "as"))
            }
            track.album?.let { put("xesam:album", Variant(it, "s")) }
            track.imageUrl?.let { put("mpris:artUrl", Variant(it, "s")) }
        }
    }

    // D-Bus object paths allow only [A-Za-z0-9_/]; replace anything else with underscore.
    private fun String.toTrackPath(): String {
        val safe = replace(Regex("[^A-Za-z0-9]"), "_")
        return "/net/mhanak/yama/Track/$safe"
    }
}
