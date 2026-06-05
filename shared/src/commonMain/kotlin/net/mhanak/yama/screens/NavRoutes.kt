package net.mhanak.yama.screens

import kotlinx.serialization.Serializable

@Serializable
object LibraryRoute

@Serializable
data class AlbumDetailRoute(val albumId: String)

@Serializable
data class ArtistDetailRoute(val artistId: String)

@Serializable
data class GenreDetailRoute(val genreId: String)

@Serializable
data class PlaylistDetailRoute(val playlistId: String)
