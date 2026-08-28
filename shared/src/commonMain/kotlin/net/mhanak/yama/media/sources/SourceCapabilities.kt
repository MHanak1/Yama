package net.mhanak.yama.media.sources

import net.mhanak.yama.media.download.CatalogSnapshot
import net.mhanak.yama.media.model.Track

/**
 * Capability interfaces that segregate optional [MusicSource] behaviours. Sources that don't support
 * a capability simply omit the interface; call sites detect presence with `(source as? Cap)?.*`.
 *
 * This mirrors the existing [net.mhanak.yama.media.playback.RemotePlaybackProvider] pattern: a
 * standalone interface, only implemented by backends that support the feature, detected at runtime.
 *
 * Current capabilities:
 * - [FavoriteCapable]   — favouriting items (Jellyfin: yes; LocalSource: no)
 * - [PlaybackReporting] — now-playing / scrobble reporting (Jellyfin: yes; LocalSource: no)
 * - [OfflineCapable]    — offline downloads, catalog hydration, staleness checking (Jellyfin: yes; LocalSource: no)
 * - [PlaylistWritable]  — creating/editing playlists (Jellyfin & Subsonic: yes; LocalSource: not yet)
 */

/**
 * Implemented by sources that support favouriting library items. Callers detect presence with
 * `(source as? FavoriteCapable)?.supportsFavorites(kind) == true` and skip the control for sources
 * that omit this interface.
 */
interface FavoriteCapable {
    /**
     * Whether this source honours favouriting for [kind]. Returning false tells the UI to hide the
     * favourite control for items of that kind.
     */
    fun supportsFavorites(kind: FavoritableKind): Boolean

    /** Whether the item is currently favourited. Only called for kinds [supportsFavorites] allows. */
    suspend fun isFavorite(kind: FavoritableKind, id: String): Boolean

    /** Persist the favourite state for an item. Only called for kinds [supportsFavorites] allows. */
    suspend fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean)
}

/**
 * Implemented by sources that support creating and editing playlists. Callers detect presence with
 * `(source as? PlaylistWritable)` and hide the edit affordances (create / add-to-playlist / rename /
 * delete / remove-track) for sources that omit the interface (e.g. LocalSource, which has no playlist
 * concept yet).
 *
 * Implementations update their own `_playlists` StateFlow after a successful mutation so the UI
 * reflects the change without waiting for a full refresh — the same stale-while-revalidate contract
 * [FavoriteCapable.setFavorite] follows for cached browse lists.
 *
 * All calls are made only when the source is reachable; [net.mhanak.yama.coordinators.PlaylistsCoordinator]
 * gates on `isReachable` (offline playlist edits are a later seam — see TODO.exclude.md).
 */
interface PlaylistWritable {
    /** Create a playlist, optionally seeded with tracks (in order). Returns the new playlist's id. */
    suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): String

    /** Rename an existing playlist. */
    suspend fun renamePlaylist(playlistId: String, name: String)

    /** Delete a playlist. */
    suspend fun deletePlaylist(playlistId: String)

    /** Append tracks (in order) to the end of a playlist. */
    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>)

    /**
     * Remove entries by their **position** in the playlist — the cross-source common denominator (a
     * track can appear more than once, so track ids are ambiguous). Subsonic removes by `songIndexToRemove`;
     * Jellyfin maps each index to its entry's `playlistItemId` internally.
     */
    suspend fun removeTracksFromPlaylist(playlistId: String, entryIndexes: List<Int>)
}

/**
 * Implemented by sources that report local playback state to a backend (now-playing, progress,
 * scrobbles). Callers detect presence with `(source as? PlaybackReporting)?.*` and skip reporting
 * for sources that omit this interface (e.g. local files, where there is nowhere to report to).
 */
interface PlaybackReporting {
    /**
     * Report that local playback started. Let the backend track now-playing / play counts / resume
     * positions and let remote controllers mirror this device's state. [volume] is 0f..1f, or null
     * when unknown.
     */
    suspend fun reportPlaybackStarted(
        track: Track, positionMs: Long, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat, shuffle: Boolean,
    )

    suspend fun reportPlaybackProgress(
        track: Track, positionMs: Long, isPaused: Boolean, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat, shuffle: Boolean,
    )

    suspend fun reportPlaybackStopped(track: Track, positionMs: Long)

    /**
     * Report a single **completed play** that may be backdated — the durable path for offline
     * scrobbles flushed on reconnect. [playedAtEpochMs] is when the play completed. Returns true if
     * the backend accepted it so the outbox can drop the event; false means keep queued.
     */
    suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long, positionMs: Long): Boolean

    /**
     * Whether a completed play must be submitted explicitly via [reportPlayed] while **online**.
     *
     * Sources that infer the play from the ongoing report* stream return **false** (the default):
     * Jellyfin's server auto-marks-played (play count + server-side ListenBrainz) once the per-track
     * [reportPlaybackStopped] lands near the end, so an extra [reportPlayed] would double-count.
     *
     * Sources with no such inference return **true**: Subsonic/Navidrome only count a play — and only
     * forward a ListenBrainz scrobble — on `scrobble?submission=true`, which is [reportPlayed]; the
     * now-playing report ([reportPlaybackStarted], `submission=false`) never counts.
     *
     * The offline path is unaffected either way: [net.mhanak.yama.media.playback.ScrobbleOutbox]
     * always replays via [reportPlayed] on reconnect.
     */
    val submitCompletedPlayOnline: Boolean get() = false
}

/**
 * Source-agnostic descriptor for one selectable identity in the source switcher: a logged-in
 * account (Jellyfin/Subsonic) or a single implicit identity (Local Files). Produced by
 * [AccountedSource.accounts] so the switcher UI never imports [net.mhanak.yama.session.JellyfinSession]
 * or any other source-specific session type.
 *
 * @param avatarUrl Remote profile image URL, or null when there is no per-account image (e.g.
 *   Local Files). [net.mhanak.yama.ui.components.library.SourceAvatar] falls back to the source
 *   logo drawable when this is null.
 * @param stableKey The account's durable partition identity — the same value
 *   [OfflineCapable.downloadSourceKey] returns when this account is active (e.g. `"jellyfin:<hash>"`).
 *   Stable across re-login (derived from server + user, not the ephemeral session id), so it's the key
 *   for per-account settings like the scrobble mode.
 */
data class SourceAccount(
    val id: String,
    val sourceType: SourceType,
    val name: String,
    val subtitle: String?,
    val avatarUrl: String?,
    val stableKey: String,
)

/**
 * Implemented by sources that expose one or more switchable accounts in the source switcher.
 * Mirrors the [FavoriteCapable]/[OfflineCapable] pattern: a standalone interface, only implemented
 * by backends that support the feature, detected at runtime via `(source as? AccountedSource)`.
 *
 * [accounts] and [currentAccountId] should be backed by Compose snapshot state (`mutableStateOf`)
 * in implementations so reads inside composition trigger recomposition without a separate StateFlow.
 */
interface AccountedSource {
    val accounts: List<SourceAccount>
    val currentAccountId: String?
    fun selectAccount(id: String)
    val supportsLogout: Boolean get() = false
    suspend fun logout(id: String) {}
}

/**
 * Implemented by sources that persist offline state: downloads, catalog snapshots, and staleness
 * checking. Callers detect presence with `(source as? OfflineCapable)?.*`; a null result means
 * the source has no offline partition (e.g. local files, which rebuild from their own on-disk index).
 */
interface OfflineCapable {
    /**
     * Partition key for this source's offline rows — downloads *and* the catalog cache — or null when
     * the source persists no offline state. Stable per account so two servers/users never share rows
     * or files. Jellyfin: `"jellyfin:<token>"`.
     */
    fun downloadSourceKey(): String?

    /**
     * An opaque change token for the track's content, used to detect a downloaded copy going stale.
     * Null means the source never restales — a download is assumed good forever.
     */
    suspend fun getContentVersion(trackId: String): String?

    /**
     * Per-track metadata snapshot used by the download layer to check staleness and sync user data
     * in a single round trip. [favorite] and [playCount] are null when the server didn't return user
     * data — callers must not overwrite stored values in that case.
     */
    data class TrackSnapshot(val contentVersion: String?, val favorite: Boolean?, val playCount: Int?)

    /**
     * Batch-fetch [TrackSnapshot]s for the given track IDs. Returns a map from track ID to snapshot;
     * IDs absent from the result are skipped silently. Sources SHOULD override this to batch the
     * network request; returning an empty map causes the staleness pass to be skipped entirely.
     */
    suspend fun fetchTrackSnapshots(ids: List<String>): Map<String, TrackSnapshot>

    /**
     * Seed the browse StateFlows from a persisted catalog snapshot (cold start / when offline). Called
     * before the first refresh so the cached catalog shows instantly and survives process death /
     * going offline. No-op for sources that rebuild their catalog from their own on-disk index.
     */
    fun hydrateCatalog(snapshot: CatalogSnapshot)
}
