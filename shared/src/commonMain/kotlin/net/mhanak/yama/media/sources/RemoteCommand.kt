package net.mhanak.yama.media.sources

import net.mhanak.yama.media.model.Track

/**
 * A playback command pushed to this device by a remote controller (e.g. another Jellyfin client
 * doing "Play On"). Emitted by a [MusicSource]'s live channel and routed onto the local player by
 * `PlaybackController.handleRemoteCommand`.
 *
 * Deliberately free of any playback-layer types so sources don't depend on `media.playback`; the
 * controller does the translation. Transport rides the Jellyfin playstate channel; volume, repeat
 * and shuffle ride the general-command channel.
 */
sealed interface RemoteCommand {
    /**
     * Replace the queue with [tracks], start at [startIndex], and resume at [startPositionMs]. The
     * position lets the player recognise a controller's reorder/removal (same now-playing track at
     * roughly the same position) and rearrange the live queue in place instead of restarting.
     */
    data class Play(val tracks: List<Track>, val startIndex: Int, val startPositionMs: Long = 0) : RemoteCommand
    /** Insert [tracks] right after the current item. */
    data class PlayNext(val tracks: List<Track>) : RemoteCommand
    /** Append [tracks] to the end of the queue. */
    data class AddToQueue(val tracks: List<Track>) : RemoteCommand

    data object Resume : RemoteCommand
    data object Pause : RemoteCommand
    data object PlayPause : RemoteCommand
    data object Stop : RemoteCommand
    data object Next : RemoteCommand
    data object Previous : RemoteCommand
    data class Seek(val positionMs: Long) : RemoteCommand

    /** Set absolute volume, 0f..1f. */
    data class SetVolume(val level: Float) : RemoteCommand
    data object VolumeUp : RemoteCommand
    data object VolumeDown : RemoteCommand

    /** Set the repeat mode on the active queue. */
    data class SetRepeat(val mode: Repeat) : RemoteCommand
    /** Enable or disable shuffle on the active queue. */
    data class SetShuffle(val enabled: Boolean) : RemoteCommand

    /** Repeat states, mapped to the playback layer's `RepeatMode` by the controller. */
    enum class Repeat { Off, All, One }
}
