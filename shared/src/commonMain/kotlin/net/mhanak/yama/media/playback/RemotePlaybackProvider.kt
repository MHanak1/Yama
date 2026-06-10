package net.mhanak.yama.media.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * A device this app can "cast" to — another session the backend lets us remote-control (a Jellyfin
 * "Play On" target). [id] is the backend session id; [name]/[client] are for display.
 */
data class RemoteTarget(
    val id: String,
    val name: String,
    val client: String?,
)

/**
 * A [MusicSource][net.mhanak.yama.media.sources.MusicSource] that can hand off playback to another
 * device. The source discovers reachable targets ([remoteTargets]) and builds a [Player] that drives
 * one of them ([createPlayer]); `PlaybackController.selectTarget` swaps that player in as `active`,
 * so the existing UI controls the remote device with no further wiring.
 *
 * Lives in `media.playback` (alongside [Player]) and is implemented by the source, mirroring how
 * [RemoteCommand][net.mhanak.yama.media.sources.RemoteCommand] keeps the controlled-device direction
 * free of playback-layer types.
 */
interface RemotePlaybackProvider {
    /** Currently reachable cast targets (excludes this device). Empty when none / not connected. */
    val remoteTargets: StateFlow<List<RemoteTarget>>

    /** Build a [Player] that controls [target]. The caller owns its lifecycle (calls [Player.release]). */
    fun createPlayer(target: RemoteTarget): Player

    /**
     * Force a refresh of the target list and any controlled device's state, bypassing the live push
     * (which can go stale after backgrounding). Called when opening the cast UI. Fire-and-forget — it
     * runs on the provider's own scope so the caller (a composition) is never blocked on the network.
     */
    fun refreshTargets()
}
