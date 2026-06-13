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
    suspend fun getAllTracks(limit: Int = 100, offset: Int = 0, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track> = emptyList()
    suspend fun getTracksForPlaylist(playlistId: String): List<Track>
    suspend fun getAlbumsForArtist(artistId: String): List<Album>
    suspend fun getAlbumsForGenre(genreId: String): List<Album>

    /** Resolve track IDs to [Track]s, preserving the requested order. Used to fulfil remote "Play On". */
    suspend fun getTracksByIds(ids: List<String>): List<Track> = emptyList()

    /** A directly playable audio URL for the given track. */
    suspend fun getStreamUrl(trackId: String): String

    /** Primary artwork URL for the given track (used in the player UI and OS media controls), or null. */
    suspend fun getArtworkUrl(trackId: String): String?

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
