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
