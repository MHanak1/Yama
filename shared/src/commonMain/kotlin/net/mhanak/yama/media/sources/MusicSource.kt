package net.mhanak.yama.media.sources

import kotlinx.coroutines.flow.StateFlow
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
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

    suspend fun refresh()
    suspend fun getTracksForAlbum(albumId: String): List<Track>
    suspend fun getTracksForArtist(artistId: String, limit: Int = 100, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track>
    suspend fun getTracksForGenre(genreId: String, limit: Int = 100, sortBy: TrackSortOrder = TrackSortOrder.Alphabetical): List<Track>
    suspend fun getTracksForPlaylist(playlistId: String): List<Track>
    suspend fun getAlbumsForArtist(artistId: String): List<Album>
    suspend fun getAlbumsForGenre(genreId: String): List<Album>
}
