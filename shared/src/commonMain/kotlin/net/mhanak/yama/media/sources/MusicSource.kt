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
    /** Artists credited as the primary artist on at least one album. Defaults to [artists] for
     * sources that don't distinguish between contributing and album artists. */
    val albumArtists: StateFlow<List<Artist>> get() = artists
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
     * Rating / favouriting. Liking items is universal, but the *style* (heart vs stars) and which
     * [RateableKind]s are rateable differ per backend, so [ratingStyle] declares both for a given
     * kind — returning [RatingStyle.None] tells the UI to hide the control. The default source
     * supports no rating at all; override these together on sources that do.
     */
    fun ratingStyle(kind: RateableKind): RatingStyle = RatingStyle.None

    /** Current rating of an item, or [Rating.Unrated] when unknown. Only called for rateable kinds. */
    suspend fun getRating(kind: RateableKind, id: String): Rating = Rating.Unrated

    /** Persist [rating] for an item. Only called for rateable kinds. */
    suspend fun setRating(kind: RateableKind, id: String, rating: Rating) {}
}
