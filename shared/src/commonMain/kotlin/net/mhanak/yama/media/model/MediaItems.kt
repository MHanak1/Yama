package net.mhanak.yama.media.model

data class Album(
    val id: String,
    val name: String,
    val albumArtist: String?,
    val year: Int?,
    val songCount: Int?,
    val imageUrl: String?,
    val imageHash: String?,
    val favorite: Boolean = false,
    val genres: List<String> = emptyList(),
)

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val imageHash: String?,
    val favorite: Boolean = false,
    val genres: List<String> = emptyList(),
)

data class Track(
    val id: String,
    val name: String,
    val albumId: String?,
    val album: String?,
    val artists: List<String>?,
    val durationTicks: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val imageUrl: String? = null,
    // Stable IDs for the track's artists/genres, so a downloaded row can fan out to artist/genre
    // availability (otherwise those grids gray offline even with the album downloaded). Default empty
    // for sources that don't supply them.
    val artistIds: List<String> = emptyList(),
    val albumArtistId: String? = null,
    val genres: List<String> = emptyList(),
    val genreIds: List<String> = emptyList(),
    // The user's favourite ("liked") state and play count, carried on the model so the UI never has to
    // fetch them per row. Populated from the source's user data (Jellyfin) or the offline row
    // (downloads/local). Defaulted for sources that don't supply them.
    val favorite: Boolean = false,
    val playCount: Int = 0,
)

data class Playlist(
    val id: String,
    val name: String,
    val itemCount: Int?,
    val imageUrl: String?,
    val imageHash: String?,
    val favorite: Boolean = false,
    val genres: List<String> = emptyList(),
)

data class Genre(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val imageHash: String?,
    val favorite: Boolean = false,
)

/**
 * A top-level library/folder the source exposes (a Jellyfin music view, and in future a Navidrome
 * folder or a local-files directory). The user can toggle which libraries are included in the
 * browsed albums/artists/genres.
 */
data class MusicLibrary(
    val id: String,
    val name: String,
)
