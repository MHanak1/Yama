package net.mhanak.yama.media.sources

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.MusicLibrary
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.download.CatalogSnapshot
import net.mhanak.yama.util.StreamingQuality

enum class SourceType {
    Jellyfin,
    Subsonic,
    Local,
}

enum class TrackSortOrder(val label: String) {
    Alphabetical("Name"),
    ReleaseDate("Year"),
    PlayCount("Plays"),
    RecentlyAdded("Added"),
    RecentlyPlayed("Played"),
    Random("Random"),
}

interface MusicSource {
    val type: SourceType
    var isAuthenticated: Boolean

    val albums: StateFlow<List<Album>>
    val artists: StateFlow<List<Artist>>
    /** Artists credited as the primary artist on at least one album. Defaults to [artists] for
     * sources that don't distinguish between contributing and album artists. */
    val albumArtists: StateFlow<List<Artist>> get() = artists
    val playlists: StateFlow<List<Playlist>>
    val genres: StateFlow<List<Genre>>
    val isRefreshing: StateFlow<Boolean>
    val refreshError: StateFlow<Throwable?>

    /**
     * Top-level libraries the source exposes (Jellyfin music views; later Navidrome/local folders).
     * Empty for sources that have no such concept. The user picks which to include via [enabledLibraryIds].
     */
    val libraries: StateFlow<List<MusicLibrary>> get() = NoLibraries

    /**
     * IDs of the libraries currently included in the browsed content (albums, artists, genres —
     * playlists stay global). New libraries are enabled by default. Empty here means *nothing* is
     * shown (the user deselected every library), not "all".
     */
    val enabledLibraryIds: StateFlow<Set<String>> get() = NoLibrarySelection

    /** Toggle a library on/off; the source persists the choice and re-[refresh]es. No-op where unsupported. */
    fun setLibraryEnabled(id: String, enabled: Boolean) {}

    /**
     * Emits whenever the backend signals its library changed (e.g. a Jellyfin scan), so callers can
     * re-[refresh]. Null for backends without a live push channel (the data is pull-only there).
     */
    val libraryChanges: Flow<Unit>? get() = null

    /**
     * Commands pushed to this device by a remote controller ("Play On"). Null for backends that can't
     * be remote-controlled. The controller collects this and drives the local player.
     */
    val remoteCommands: Flow<RemoteCommand>? get() = null

    suspend fun refresh()
    suspend fun getTracksForAlbum(albumId: String): List<Track>
    suspend fun getTracksForArtist(artistId: String, limit: Int = 100, offset: Int = 0, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track>
    suspend fun getTracksForGenre(genreId: String, limit: Int = 100, offset: Int = 0, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track>
    suspend fun getAllTracks(limit: Int = 100, offset: Int = 0, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical, favoritesOnly: Boolean = false, searchTerm: String? = null): List<Track> = emptyList()
    suspend fun getTracksForPlaylist(playlistId: String): List<Track>
    suspend fun getAlbumsForArtist(artistId: String): List<Album>
    suspend fun getAlbumsForGenre(genreId: String): List<Album>

    /** Resolve track IDs to [Track]s, preserving the requested order. Used to fulfil remote "Play On". */
    suspend fun getTracksByIds(ids: List<String>): List<Track> = emptyList()

    /** Whether this source honours [getStreamUrl]'s quality parameter (i.e. supports transcoding). */
    val supportsStreamingQuality: Boolean get() = false

    /**
     * A directly playable audio URL for the given track. [quality] caps the transcode bitrate; null
     * means the source's current default (`AppContainer.streamingQuality`). The downloads layer passes
     * the entry's stored quality so a download's bitrate is fixed at download time, independent of the
     * live global setting.
     */
    suspend fun getStreamUrl(trackId: String, quality: StreamingQuality? = null): String

    /** Primary artwork URL for the given track (used in the player UI and OS media controls), or null. */
    suspend fun getArtworkUrl(trackId: String): String?

    /**
     * Whether the origin can be streamed from *right now*. Drives offline graying: an item is playable
     * iff it is downloaded or the source is reachable. There is no "offline mode" toggle — this is
     * derived from the live connection. Always true for sources with no network (local files); derived
     * from REST/socket reachability for networked ones.
     */
    val isReachable: StateFlow<Boolean> get() = AlwaysReachable

    /**
     * An opaque change token for the track's content, used to detect a downloaded copy going stale
     * against the origin. Null (the default) means the source never restales — a download is assumed
     * good forever. Jellyfin returns the item's etag / last-saved token.
     *
     * Prefer [fetchTrackSnapshots] for bulk checks — this is for single-item lookups (e.g. right
     * after a download completes).
     */
    suspend fun getContentVersion(trackId: String): String? = null

    /**
     * Per-track metadata snapshot used by the download layer to check staleness and sync user data
     * ([favorite], [playCount]) in a single round trip. [favorite] and [playCount] are null when the
     * server didn't return user data for the item — callers must not overwrite stored values in that
     * case, to avoid corrupting locally-known state with a server omission.
     */
    data class TrackSnapshot(val contentVersion: String?, val favorite: Boolean?, val playCount: Int?)

    /**
     * Batch-fetch [TrackSnapshot]s for the given track IDs. Returns a map from track ID to snapshot;
     * IDs absent from the result are skipped silently.
     *
     * Sources SHOULD override this to batch the network request — the default returns an empty map,
     * which causes [net.mhanak.yama.media.download.DownloadRepository.refreshStaleness] to skip the
     * pass entirely. [JellyfinSource] fetches all IDs in chunks via a single `getItems` call.
     */
    suspend fun fetchTrackSnapshots(ids: List<String>): Map<String, TrackSnapshot> = emptyMap()

    /**
     * Partition key for this source's offline rows — downloads *and* the catalog cache — or null when
     * the source persists no offline state (local files rebuild from their own on-disk index). Stable
     * per account so two servers/users never share rows or files. Jellyfin: `"jellyfin:<token>"`.
     */
    fun downloadSourceKey(): String? = null

    /**
     * Seed the browse StateFlows from a persisted catalog snapshot (cold start / when offline). Called
     * by the [net.mhanak.yama.media.download.CatalogCache] before the first refresh so the cached
     * catalog shows instantly and survives process death / going offline. No-op for sources that
     * rebuild their catalog from their own on-disk index (local files).
     */
    fun hydrateCatalog(snapshot: CatalogSnapshot) {}

    /** Lyrics for the given track, or [Lyrics.None] if unavailable. */
    suspend fun getLyrics(trackId: String): Lyrics

    /**
     * Playback reporting hooks. Let the backend track now-playing / play counts / resume positions
     * (and let remote controllers see what this device is doing, including its [volume], [repeat] and
     * [shuffle] state and queue order, so a controller's UI can mirror it). Default no-ops; only
     * sources that support reporting (Jellyfin) override them. [volume] is 0f..1f, or null when
     * unknown. Reported only for *local* playback.
     */
    suspend fun reportPlaybackStarted(
        track: Track, positionMs: Long, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat = RemoteCommand.Repeat.Off, shuffle: Boolean = false,
    ) {}
    suspend fun reportPlaybackProgress(
        track: Track, positionMs: Long, isPaused: Boolean, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat = RemoteCommand.Repeat.Off, shuffle: Boolean = false,
    ) {}
    suspend fun reportPlaybackStopped(track: Track, positionMs: Long) {}

    /**
     * Report a single **completed play** that may be backdated — the durable path for offline scrobbles
     * flushed on reconnect (see the scrobble outbox, DOWNLOADS_PLAN.md Phase 5). [playedAtEpochMs] is
     * when the play completed; backdate fidelity is source-specific (Jellyfin marks the item played with
     * a `DatePlayed`, but honoring it varies by server version — it degrades to "played, count++"
     * otherwise). Returns true if the backend accepted it, so the outbox can drop the event; the default
     * returns false (no scrobble support) so callers keep it queued or discard per policy. Live online
     * scrobbling stays on the [reportPlaybackStopped]/progress path; this is for events that path missed.
     */
    suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long, positionMs: Long): Boolean = false

    /**
     * Favouriting. Liking items is universal, but *which* [FavoritableKind]s can be favourited
     * differs per backend, so [supportsFavorites] declares it per kind — returning false tells the
     * UI to hide the control. The default source supports none; override these together on sources
     * that do.
     */
    fun supportsFavorites(kind: FavoritableKind): Boolean = false

    /** Whether the item is currently favourited. Only called for kinds [supportsFavorites] allows. */
    suspend fun isFavorite(kind: FavoritableKind, id: String): Boolean = false

    /** Persist the favourite state for an item. Only called for kinds [supportsFavorites] allows. */
    suspend fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {}
}

// Shared, immutable defaults for sources that don't support multiple libraries — avoids allocating a
// fresh flow on every property access.
private val NoLibraries = MutableStateFlow<List<MusicLibrary>>(emptyList())
private val NoLibrarySelection = MutableStateFlow<Set<String>>(emptySet())
// Sources with no network are always reachable; share one flow rather than allocating per access.
private val AlwaysReachable = MutableStateFlow(true)
