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

interface MusicSource {
    val type: SourceType
    var isAuthenticated: Boolean

    val albums: StateFlow<List<Album>>
    val artists: StateFlow<List<Artist>>
    val playlists: StateFlow<List<Playlist>>
    val genres: StateFlow<List<Genre>>
    val isRefreshing: StateFlow<Boolean>

    suspend fun refresh()
    suspend fun getTracksForAlbum(albumId: String): List<Track>
    suspend fun getTracksForArtist(artistId: String): List<Track>
    suspend fun getTracksForGenre(genreId: String): List<Track>
    suspend fun getTracksForPlaylist(playlistId: String): List<Track>
}
