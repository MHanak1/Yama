package net.mhanak.yama.coordinators

import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.PlaylistWritable

/**
 * Outcome of a playlist edit, surfaced to the UI so it can navigate / confirm / warn.
 */
sealed interface PlaylistEditResult {
    /** A create succeeded; [playlistId] is the new playlist (for navigation / seeding). */
    data class Created(val playlistId: String) : PlaylistEditResult
    /** A non-create edit succeeded. */
    data object Ok : PlaylistEditResult
    /** The active source is unreachable — the edit was not attempted (offline edits are a later seam). */
    data object Offline : PlaylistEditResult
    /** The source is reachable but the edit failed. */
    data class Failed(val error: Throwable) : PlaylistEditResult
}

/**
 * Single seam for all playlist mutations, mirroring [FavoritesCoordinator]. The UI calls this rather
 * than touching the source directly, so the offline-outbox path (see TODO.exclude.md) can later be
 * dropped in here without changing a single call site.
 *
 * First pass is **online-only**: every method gates on `source.isReachable` and returns
 * [PlaylistEditResult.Offline] when the source is down, so no phantom local edit is made. The source's
 * `_playlists` StateFlow is updated by the [PlaylistWritable] implementation itself, so the browse/detail
 * UI reflects the change without an explicit refresh here.
 *
 * A source that doesn't implement [PlaylistWritable] (e.g. LocalSource) yields
 * [PlaylistEditResult.Failed]; the UI should already have hidden the affordance via
 * `(source as? PlaylistWritable) != null`, so this is a defensive fallback.
 */
class PlaylistsCoordinator(private val source: () -> MusicSource) {

    /** Whether the active source can be edited *right now* (implements the capability and is reachable). */
    fun canEdit(): Boolean {
        val src = source()
        return src is PlaylistWritable && src.isReachable.value
    }

    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): PlaylistEditResult =
        guarded { writable()?.let { PlaylistEditResult.Created(it.createPlaylist(name, trackIds)) } }

    suspend fun renamePlaylist(playlistId: String, name: String): PlaylistEditResult =
        guarded { writable()?.let { it.renamePlaylist(playlistId, name); PlaylistEditResult.Ok } }

    suspend fun deletePlaylist(playlistId: String): PlaylistEditResult =
        guarded { writable()?.let { it.deletePlaylist(playlistId); PlaylistEditResult.Ok } }

    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>): PlaylistEditResult =
        guarded { writable()?.let { it.addTracksToPlaylist(playlistId, trackIds); PlaylistEditResult.Ok } }

    suspend fun removeTracksFromPlaylist(playlistId: String, entryIndexes: List<Int>): PlaylistEditResult =
        guarded { writable()?.let { it.removeTracksFromPlaylist(playlistId, entryIndexes); PlaylistEditResult.Ok } }

    /** Capability cast; reachability is enforced separately by [guarded]. Null → source can't edit. */
    private fun writable(): PlaylistWritable? = source() as? PlaylistWritable

    /**
     * Enforces the reachability gate, then runs [block], turning its states into a [PlaylistEditResult]:
     * offline short-circuits to [PlaylistEditResult.Offline], a null from [writable] means the source is
     * not editable, and any thrown exception becomes [PlaylistEditResult.Failed].
     */
    private inline fun guarded(block: () -> PlaylistEditResult?): PlaylistEditResult {
        if (!source().isReachable.value) return PlaylistEditResult.Offline
        return runCatching { block() }
            .fold(
                onSuccess = { it ?: PlaylistEditResult.Failed(IllegalStateException("Source cannot edit playlists")) },
                onFailure = { PlaylistEditResult.Failed(it) },
            )
    }
}
