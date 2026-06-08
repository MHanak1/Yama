package net.mhanak.yama.media.model

data class Album(
    val id: String,
    val name: String,
    val albumArtist: String?,
    val year: Int?,
    val songCount: Int?,
    val imageUrl: String?,
    val imageHash: String?,
)

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val imageHash: String?,
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
)

data class Playlist(
    val id: String,
    val name: String,
    val itemCount: Int?,
    val imageUrl: String?,
    val imageHash: String?,
)

data class Genre(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val imageHash: String?,
)
