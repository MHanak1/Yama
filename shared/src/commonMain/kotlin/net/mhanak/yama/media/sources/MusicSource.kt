package net.mhanak.yama.media.sources

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track

enum class SourceType {
    Jellyfin,
    Subsonic,
    Local,
}

enum class TrackSortOrder {
    Alphabetical,
    ReleaseDate,
    PlayCount,
    RecentlyAdded,
    RecentlyPlayed,
    Random,
}

interface MusicSource {
    val type: SourceType
    var isAuthenticated: Boolean

    val albums: StateFlow<List<Album>>
    val artists: StateFlow<List<Artist>>
    val playlists: StateFlow<List<Playlist>>
    val genres: StateFlow<List<Genre>>
    val isRefreshing: StateFlow<Boolean>
    val refreshError: StateFlow<Throwable?>

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
    suspend fun getTracksForArtist(artistId: String, limit: Int = 100, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track>
    suspend fun getTracksForGenre(genreId: String, limit: Int = 100, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track>
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
     * (and let remote controllers see what this device is doing, including its [volume] so a
     * controller can show and drive a volume slider). Default no-ops; only sources that support
     * reporting (Jellyfin) override them. [volume] is 0f..1f, or null when unknown. Reported only for
     * *local* playback.
     */
    suspend fun reportPlaybackStarted(track: Track, positionMs: Long, queue: List<Track>, volume: Float?) {}
    suspend fun reportPlaybackProgress(track: Track, positionMs: Long, isPaused: Boolean, queue: List<Track>, volume: Float?) {}
    suspend fun reportPlaybackStopped(track: Track, positionMs: Long) {}
}
