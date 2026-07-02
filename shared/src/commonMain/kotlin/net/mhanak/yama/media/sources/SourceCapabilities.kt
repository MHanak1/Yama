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
