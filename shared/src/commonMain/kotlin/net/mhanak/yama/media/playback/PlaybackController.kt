package net.mhanak.yama.media.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.RemoteCommand
import net.mhanak.yama.util.AppPreferences

/**
 * The single entry point the UI uses to control playback. Models playback as two independent axes
 * (see PLAYBACK_PLAN.md):
 *
 * - **Endpoint axis** — [local]: this device's own playback, always alive and (Phase 2+) always
 *   reported, driven by server "Play On" pushes ([handleRemoteCommand]) and/or by the UI.
 * - **View axis** — [viewed] / [viewedTarget]: the fleet member the UI currently shows and drives
 *   (this device or a remote target).
 *
 * [viewed] is Compose-observable so switching from [LocalPlayer] to a remote player (Jellyfin
 * "Play On" via [selectTarget]) rebinds the UI without rewiring — mirroring how `AppContainer`
 * swaps `activeMusicSource`.
 *
 * NOTE (temporary): the two axes are currently coupled — selecting a remote hands playback off, so a
 * device can't play locally *and* control a remote at once. That exclusivity is not permanent; see
 * PLAYBACK_PLAN.md (Phase 2) for the planned decoupling.
 */
class PlaybackController(private val source: () -> MusicSource) {
    // Playback on this device (the endpoint axis). Exposed so the reporter can observe local-only
    // playback and so remote "Play On" requests can always be routed here regardless of what's viewed.
    val local = LocalPlayer(MediaPlayerEngine(), source)

    // The player the UI currently shows and drives (the view axis): [local] or a remote player.
    var viewed: Player by mutableStateOf(local)
        private set

    // The remote target the view is directed to, or null when viewing this device.
    var viewedTarget: RemoteTarget? by mutableStateOf(null)
        private set

    val status get() = viewed.status

    // Set when something outside the UI (e.g. tapping the Android media notification) asks to open
    // the full-screen player. MainScreen observes this and consumes it (resets it to false).
    var openPlayerRequest: Boolean by mutableStateOf(false)

    // Emits when a volume change should surface the in-app volume indicator — a remote command, or a
    // local hardware-key press while the OS won't show its own panel (casting / in-app gain). The
    // indicator itself is additionally gated on the viewed player not controlling the system volume.
    private val _volumeChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val volumeChanged: SharedFlow<Unit> = _volumeChanged

    /** Signal a volume change so the in-app indicator shows (e.g. from a hardware-key handler). */
    fun notifyVolumeChanged() { _volumeChanged.tryEmit(Unit) }

    /**
     * Direct the view to [target] (a "cast" device), or back to this device when null. Builds a
     * remote [Player] via the active source's [RemotePlaybackProvider] and swaps it in as [viewed];
     * the previously viewed remote player is released. No-op if the source can't cast.
     *
     * When [remember] is true (the default — a user picking a device in the cast sheet) the choice is
     * persisted per source so switching away and back to this source restores it. Pass false for
     * choices that aren't the user's own (a remote "Play On" pushed here, or [restoreTargetForActiveSource]
     * replaying a saved choice) so they don't overwrite what the user last asked for.
     */
    fun selectTarget(target: RemoteTarget?, remember: Boolean = true) {
        if (target?.id == viewedTarget?.id) return
        val previous = viewed
        val next = if (target == null) {
            local
        } else {
            (source() as? RemotePlaybackProvider)?.createPlayer(target) ?: return
        }
        viewed = next
        viewedTarget = if (next === local) null else target
        if (previous !== local && previous !== next) previous.release()
        if (remember) AppPreferences.setLastRemoteTarget(source().type.name, viewedTarget?.encode())
    }

    /**
     * Apply the [RemoteTarget] last chosen for the currently active source, or local playback when the
     * source can't cast ([RemotePlaybackProvider]) or has no remembered target. Call this right after
     * the active source changes (see `AppContainer.selectSource`) so each source resumes its own cast
     * device. Restoring isn't a fresh user choice, so it doesn't re-persist.
     */
    fun restoreTargetForActiveSource() {
        val src = source()
        // Only attempt a remote restore once the source is actually usable — a provider whose backend
        // client isn't connected yet would fail to build a player.
        val remembered = (src as? RemotePlaybackProvider)
            ?.takeIf { src.isAuthenticated }
            ?.let { AppPreferences.lastRemoteTarget(src.type.name)?.let(::decodeTarget) }
        selectTarget(remembered, remember = false)
    }

    /**
     * Recreate the viewed remote player against the source's freshly rebuilt backend client. The
     * Jellyfin remote player captures its client at construction, so after the source rebuilds it on
     * device wake (see [net.mhanak.yama.media.sources.JellyfinSource.reconnect]) the old player's
     * transport commands would keep hitting the dead client. No-op when playing locally.
     *
     * This is needed only because the current remote player holds the rebuilt client; a source whose
     * remote player survives a connection refresh (or has none) wouldn't need this. Since [createPlayer]
     * just rebuilds a player for the same target, calling it for such a source is harmless if wasteful.
     */
    fun rebuildViewedRemotePlayer() {
        val target = viewedTarget ?: return
        val previous = viewed
        val next = (source() as? RemotePlaybackProvider)?.createPlayer(target) ?: return
        viewed = next
        if (previous !== local) previous.release()
    }

    /**
     * Whether the current local playback was started by the user on this device ([SelfInitiated]) or
     * pushed here by a remote controller ([ServerDriven]). Null when the local player is idle.
     * Used in Phase 3+ to decide whether local decode should keep running when a remote target is
     * selected (server-driven endpoint stays alive; self-initiated hands off if the user requests it).
     */
    enum class PlaybackOrigin { SelfInitiated, ServerDriven }
    var localPlaybackOrigin: PlaybackOrigin? = null
        private set

    /**
     * Apply a command pushed by a remote controller ("Play On" from another client). All commands
     * are routed to the [local] player (the endpoint axis) — these are commands addressed *to this
     * device*, not to whatever the UI happens to be viewing. The view is left alone so a user who is
     * controlling another device continues to see that device's state while also being a playback
     * endpoint (Phase 2 two-axis model — see PLAYBACK_PLAN.md).
     */
    fun handleRemoteCommand(command: RemoteCommand) {
        when (command) {
            is RemoteCommand.Play -> {
                localPlaybackOrigin = PlaybackOrigin.ServerDriven
                // A controller's reorder/removal arrives as a fresh Play with the same now-playing track;
                // applyRemotePlay rearranges the live queue in place when it can. Only a genuine new
                // playback (which restarts) should surface the full player, like a notification tap.
                val restarted = !local.applyRemotePlay(command.tracks, command.startIndex, command.startPositionMs)
                if (restarted) openPlayerRequest = true
            }
            is RemoteCommand.PlayNext -> local.playNext(command.tracks)
            is RemoteCommand.AddToQueue -> local.addToQueue(command.tracks)
            // Transport commands from a remote controller always target the local endpoint.
            RemoteCommand.Resume -> local.play()
            RemoteCommand.Pause -> local.pause()
            RemoteCommand.PlayPause -> local.togglePlayPause()
            RemoteCommand.Stop -> local.stop()
            RemoteCommand.Next -> local.next()
            RemoteCommand.Previous -> local.previous()
            is RemoteCommand.Seek -> local.seekTo(command.positionMs)
            is RemoteCommand.SetVolume -> { local.setVolume(command.level); _volumeChanged.tryEmit(Unit) }
            RemoteCommand.VolumeUp -> { local.volumeUp(); _volumeChanged.tryEmit(Unit) }
            RemoteCommand.VolumeDown -> { local.volumeDown(); _volumeChanged.tryEmit(Unit) }
            is RemoteCommand.SetRepeat -> local.setRepeat(
                when (command.mode) {
                    RemoteCommand.Repeat.Off -> RepeatMode.Off
                    RemoteCommand.Repeat.All -> RepeatMode.All
                    RemoteCommand.Repeat.One -> RepeatMode.One
                },
            )
            is RemoteCommand.SetShuffle -> local.setShuffle(command.enabled)
        }
    }

    /**
     * Transfer the queue from [from] to [to]: captures [from]'s queue, position, and play state;
     * stops [from]; and loads the queue into [to] (playing or paused to match [from]'s state).
     *
     * Source-agnostic — works for any [Player] pair via the [Player] interface. Each player's own
     * implementation handles any backend-specific details (e.g. [JellyfinRemotePlayer] chains a
     * pause after the play command to guarantee ordering; its [Player.stop] uses a fire-and-forget
     * scope so it survives an immediately following [release]).
     */
    fun transferQueue(from: Player, to: Player) {
        val status = from.status.value
        from.stop()
        val queue = status.queue
        if (queue.isEmpty()) return
        val index = status.queueIndex.coerceAtLeast(0)
        val positionMs = status.positionMs
        if (status.isPlaying) to.playNow(queue, index, positionMs)
        else to.loadQueuePaused(queue, index, positionMs)
    }

    /**
     * Transfer the current local queue to [target] and switch the view to it. Switches the view
     * first so [viewed] is the remote player by the time [transferQueue] runs.
     */
    fun transferQueueToTarget(target: RemoteTarget) {
        selectTarget(target)
        if (viewedTarget?.id != target.id) return  // source can't cast; selectTarget was a no-op
        transferQueue(from = local, to = viewed)
    }

    /**
     * Transfer the currently viewed remote queue to this device and switch the view back to local.
     * Transfers first so the remote is stopped before [selectTarget] releases its player.
     */
    fun transferQueueToLocal() {
        transferQueue(from = viewed, to = local)
        selectTarget(null)
    }

    // RemoteTarget round-trips through prefs as JSON — safe for XML-backed stores (java.util.prefs).
    private fun RemoteTarget.encode(): String = Json.encodeToString(this)

    private fun decodeTarget(raw: String): RemoteTarget? =
        runCatching { Json.decodeFromString<RemoteTarget>(raw) }.getOrNull()
}
