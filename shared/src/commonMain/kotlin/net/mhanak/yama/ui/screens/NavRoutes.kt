package net.mhanak.yama.ui.screens

import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object LibraryRoute

@Serializable
object SettingsRoute

@Serializable
data class AlbumDetailRoute(val albumId: String)

@Serializable
data class ArtistDetailRoute(val artistId: String)

@Serializable
data class GenreDetailRoute(val genreId: String)

@Serializable
data class PlaylistDetailRoute(val playlistId: String)

@Serializable
data class ArtistTracksRoute(val artistId: String)

@Serializable
data class GenreTracksRoute(val genreId: String)

@Serializable
object AppearanceSettingsRoute

@Serializable
object PlaybackSettingsRoute

@Serializable
object ScrobblingSettingsRoute

@Serializable
object LocalLibrarySettingsRoute

/** Top-level screen: downloads home — a managed list of downloaded albums. */
@Serializable
object DownloadedMusicRoute

/** A single downloaded album's management view. */
@Serializable
data class DownloadedAlbumRoute(val albumId: String)

/** A flat list of downloaded tracks, with an in-view toggle to also show the recent-tracks cache. */
@Serializable
object DownloadedTracksRoute

@Serializable
object DownloadsSettingsRoute
