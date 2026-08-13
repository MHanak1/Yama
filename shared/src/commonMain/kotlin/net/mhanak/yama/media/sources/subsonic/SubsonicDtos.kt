package net.mhanak.yama.media.sources.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Leaf types
// ---------------------------------------------------------------------------

/** Subsonic/OpenSubsonic error returned when `status == "failed"`. */
@Serializable
data class SubsonicError(val code: Int, val message: String? = null)

/** A `{name, versions}` entry from `getOpenSubsonicExtensions`. */
@Serializable
data class SubsonicExtensionDto(val name: String, val versions: List<Int> = emptyList())

/** Name-only entity (OpenSubsonic genre list on albums/songs). */
@Serializable
data class SubsonicNamedEntityDto(val name: String)

/** Compact artist reference embedded inside a song (OpenSubsonic `artists` array). */
@Serializable
data class SubsonicArtistRefDto(val id: String, val name: String)

// ---------------------------------------------------------------------------
// Artists
// ---------------------------------------------------------------------------

/**
 * Artist row in `getArtists` index *or* the full response from `getArtist`.
 * When returned by `getArtist` the `albums` field is populated; in `getArtists`
 * it is empty (Subsonic doesn't embed albums there).
 */
@Serializable
data class SubsonicArtistDto(
    val id: String,
    val name: String,
    val coverArt: String? = null,
    val albumCount: Int? = null,
    /** ISO-8601 date when starred; null = not starred. */
    val starred: String? = null,
    /** Populated only in `getArtist` responses. */
    @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
)

@Serializable
data class SubsonicArtistIndexDto(
    val name: String,
    @SerialName("artist") val artists: List<SubsonicArtistDto> = emptyList(),
)

@Serializable
data class SubsonicArtistsResultDto(
    @SerialName("index") val indexes: List<SubsonicArtistIndexDto> = emptyList(),
    val ignoredArticles: String? = null,
    val lastModified: Long? = null,
)

// ---------------------------------------------------------------------------
// Albums
// ---------------------------------------------------------------------------

/**
 * Album from `getAlbumList2`, `getAlbum`, `getArtist`, or `search3`.
 * When returned by `getAlbum` the `songs` field is populated.
 */
@Serializable
data class SubsonicAlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,     // seconds
    val year: Int? = null,
    val genre: String? = null,                        // classic: single genre tag
    val genres: List<SubsonicNamedEntityDto>? = null, // OpenSubsonic: multi-genre list
    val starred: String? = null,
    val playCount: Long? = null,
    /** Populated only in `getAlbum` responses. */
    @SerialName("song") val songs: List<SubsonicSongDto> = emptyList(),
)

@Serializable
data class SubsonicAlbumListDto(
    @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Songs (Subsonic `Child` type)
// ---------------------------------------------------------------------------

@Serializable
data class SubsonicSongDto(
    val id: String,
    val title: String,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val genres: List<SubsonicNamedEntityDto>? = null, // OpenSubsonic
    val coverArt: String? = null,
    val duration: Int? = null,     // seconds
    val starred: String? = null,
    val playCount: Long? = null,
    /** ISO-8601 UTC timestamps. Opaque here — used only as lexicographically-sortable keys for the
     * "Recently Added" / "Recently Played" all-tracks orderings (see [SubsonicSource.getAllTracks]).
     * `played` is an OpenSubsonic addition and may be absent on stricter Subsonic servers. */
    val created: String? = null,
    val played: String? = null,   // OpenSubsonic
    /** OpenSubsonic multi-artist list (more complete than single `artistId`). */
    @SerialName("artists") val artistRefs: List<SubsonicArtistRefDto>? = null,
    @SerialName("albumArtist") val albumArtistName: String? = null,
    val albumArtistId: String? = null,
)

@Serializable
data class SubsonicSongListDto(
    @SerialName("song") val songs: List<SubsonicSongDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Genres
// ---------------------------------------------------------------------------

/** Genre from `getGenres`. Note: Subsonic uses the name as ID (no separate numeric genre ID). */
@Serializable
data class SubsonicGenreDto(
    /** The genre name; used as both display name and ID in this app. */
    @SerialName("value") val name: String,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

@Serializable
data class SubsonicGenresResultDto(
    @SerialName("genre") val genres: List<SubsonicGenreDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Playlists
// ---------------------------------------------------------------------------

@Serializable
data class SubsonicPlaylistDto(
    val id: String,
    val name: String,
    val comment: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null,
    /** Populated only in `getPlaylist` (single-playlist fetch). */
    @SerialName("entry") val songs: List<SubsonicSongDto> = emptyList(),
)

@Serializable
data class SubsonicPlaylistsResultDto(
    @SerialName("playlist") val playlists: List<SubsonicPlaylistDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Music folders (libraries)
// ---------------------------------------------------------------------------

// Subsonic spec mandates integer IDs for musicFolder; all other entity IDs are strings.
@Serializable
data class SubsonicMusicFolderDto(val id: Int, val name: String? = null)

@Serializable
data class SubsonicMusicFoldersResultDto(
    @SerialName("musicFolder") val folders: List<SubsonicMusicFolderDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Search / starred
// ---------------------------------------------------------------------------

@Serializable
data class SubsonicSearchResult3Dto(
    @SerialName("artist") val artists: List<SubsonicArtistDto> = emptyList(),
    @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
    @SerialName("song") val songs: List<SubsonicSongDto> = emptyList(),
)

@Serializable
data class SubsonicStarred2Dto(
    @SerialName("artist") val artists: List<SubsonicArtistDto> = emptyList(),
    @SerialName("album") val albums: List<SubsonicAlbumDto> = emptyList(),
    @SerialName("song") val songs: List<SubsonicSongDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Lyrics
// ---------------------------------------------------------------------------

/** One line in an OpenSubsonic structured-lyrics object. */
@Serializable
data class SubsonicLyricsLineDto(
    /** The lyric text. */
    val value: String,
    /** Start time in milliseconds (null for unsynced lines). */
    val start: Long? = null,
)

/** One structured-lyrics entry (a language variant of the lyrics). */
@Serializable
data class SubsonicStructuredLyricsDto(
    val lang: String? = null,
    val synced: Boolean? = null,
    val displayTitle: String? = null,
    val displayArtist: String? = null,
    val line: List<SubsonicLyricsLineDto> = emptyList(),
)

/** `lyricsList` payload from `getLyricsBySongId` (OpenSubsonic). */
@Serializable
data class SubsonicLyricsListDto(
    val structuredLyrics: List<SubsonicStructuredLyricsDto> = emptyList(),
)

/** `lyrics` payload from classic `getLyrics` endpoint. Value is a plain newline-delimited string. */
@Serializable
data class SubsonicClassicLyricsDto(
    val artist: String? = null,
    val title: String? = null,
    val value: String? = null,
)

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

/**
 * The unified Subsonic JSON response body. Every API call wraps its payload in exactly one of the
 * optional fields here. Using a single class with `ignoreUnknownKeys = true` keeps deserialization
 * simple without a separate sealed hierarchy per endpoint.
 */
@Serializable
data class SubsonicResponseBody(
    val status: String,
    val version: String,
    /** Present in OpenSubsonic-compliant servers. */
    val openSubsonic: Boolean? = null,
    val type: String? = null,          // server implementation name (e.g. "navidrome")
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
    // --- Browse payloads (one populated per call) ---
    val artists: SubsonicArtistsResultDto? = null,
    val artist: SubsonicArtistDto? = null,
    @SerialName("albumList2") val albumList2: SubsonicAlbumListDto? = null,
    val album: SubsonicAlbumDto? = null,
    val genres: SubsonicGenresResultDto? = null,
    val playlists: SubsonicPlaylistsResultDto? = null,
    val playlist: SubsonicPlaylistDto? = null,
    val musicFolders: SubsonicMusicFoldersResultDto? = null,
    // --- Track-list payloads ---
    val randomSongs: SubsonicSongListDto? = null,
    val songsByGenre: SubsonicSongListDto? = null,
    val searchResult3: SubsonicSearchResult3Dto? = null,
    val starred2: SubsonicStarred2Dto? = null,
    val song: SubsonicSongDto? = null,
    // --- Lyrics payloads ---
    val lyricsList: SubsonicLyricsListDto? = null,
    val lyrics: SubsonicClassicLyricsDto? = null,
    // --- OpenSubsonic extensions list (getOpenSubsonicExtensions) ---
    val openSubsonicExtensions: List<SubsonicExtensionDto>? = null,
)

/**
 * Top-level JSON envelope. The hyphenated key `"subsonic-response"` is handled by
 * `@SerialName` — kotlinx.serialization accepts arbitrary strings there regardless of
 * identifier rules.
 */
@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponseBody,
)
