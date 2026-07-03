package net.mhanak.yama.media.sources.subsonic

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Thrown when the server returns `status == "failed"`. */
class SubsonicException(val code: Int, message: String) :
    Exception(humanReadableMessage(code, message)) {
    companion object {
        private fun humanReadableMessage(code: Int, raw: String): String = when (code) {
            40 -> "Wrong username or password"
            41 -> "Token authentication not supported — try plain password auth"
            50 -> "User is not authorised for this operation"
            60 -> "Trial period over; the server requires a licence"
            70 -> "Requested data not found"
            else -> "Subsonic error $code: $raw"
        }
    }
}

/**
 * Thin, typed HTTP client for the Subsonic REST API.
 *
 * Auth scheme: every request includes `u`, `t` (md5(password+salt)), `s` (random salt), `v`,
 * `c=Yama`, `f=json`. The salt is random per-request for live API calls (better security), but a
 * stable [fixedSalt] is pre-computed at construction time for URL building (stream + cover-art URLs
 * handed to ExoPlayer/libvlc and Coil, where a changing URL defeats the disk cache).
 */
class SubsonicApi(
    /** Normalised base URL, no trailing slash. e.g. `https://music.example.com:4533`. */
    val serverUrl: String,
    val username: String,
    private val password: String,
    val apiVersion: String = "1.16.1",
) {
    private val json = Json { ignoreUnknownKeys = true }

    // One stable salt per Api instance — used to keep stream/art URLs cache-friendly.
    private val fixedSalt: String = buildSalt()
    private val fixedToken: String = md5Hex(password + fixedSalt)

    private val client: HttpClient = createSubsonicHttpClient(json)

    // --- Auth helpers -------------------------------------------------------

    /** Returns a fresh random salt (8 lowercase letters). */
    private fun buildSalt(): String = (1..8).map { ('a'..'z').random() }.joinToString("")

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // --- Core request -------------------------------------------------------

    /**
     * Execute a GET against [endpoint] (e.g. `"getArtists"`) with the given extra parameters.
     * Auth params are appended automatically. Returns the deserialized [SubsonicResponseBody],
     * throwing [SubsonicException] on `status == "failed"`.
     */
    private suspend fun get(endpoint: String, params: Map<String, String?> = emptyMap()): SubsonicResponseBody {
        val salt = buildSalt()
        val token = md5Hex(password + salt)
        val envelope: SubsonicEnvelope = client.get("$serverUrl/rest/$endpoint") {
            parameter("u", username)
            parameter("t", token)
            parameter("s", salt)
            parameter("v", apiVersion)
            parameter("c", "Yama")
            parameter("f", "json")
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
        }.body()
        val body = envelope.response
        if (body.status == "failed") {
            val err = body.error ?: SubsonicError(0, "Unknown error")
            throw SubsonicException(err.code, err.message ?: "Unknown error")
        }
        return body
    }

    // --- URL builders (stable auth for disk-cache-friendly URLs) -----------

    /** Directly-playable stream URL, ready to hand to ExoPlayer/libvlc. Carries fixed-salt auth. */
    fun streamUrl(trackId: String, maxBitrateKbps: Int? = null, format: String = "mp3"): String =
        buildAuthUrl("stream", buildMap {
            put("id", trackId)
            if (maxBitrateKbps != null) put("maxBitRate", maxBitrateKbps.toString())
            put("format", if (maxBitrateKbps == null) "raw" else format)
        })

    /** Cover-art URL for the given item ID (album, song, artist — Navidrome accepts all). */
    fun coverArtUrl(id: String, size: Int? = null): String =
        buildAuthUrl("getCoverArt", buildMap {
            put("id", id)
            if (size != null) put("size", size.toString())
        })

    /** Avatar URL for [username] (OpenSubsonic). */
    fun avatarUrl(user: String): String = buildAuthUrl("getAvatar", mapOf("username" to user))

    /** Builds a stable (fixed-salt) authed URL for media/image endpoints. */
    private fun buildAuthUrl(endpoint: String, extra: Map<String, String> = emptyMap()): String =
        URLBuilder("$serverUrl/rest/$endpoint").apply {
            parameters.append("u", username)
            parameters.append("t", fixedToken)
            parameters.append("s", fixedSalt)
            parameters.append("v", apiVersion)
            parameters.append("c", "Yama")
            parameters.append("f", "json")
            extra.forEach { (k, v) -> parameters.append(k, v) }
        }.buildString()

    // --- API calls ----------------------------------------------------------

    /** Verify the server is reachable and credentials are valid. */
    suspend fun ping() { get("ping") }

    /** Returns the list of OpenSubsonic extensions the server supports; empty = not OpenSubsonic. */
    suspend fun getOpenSubsonicExtensions(): List<SubsonicExtensionDto> =
        get("getOpenSubsonicExtensions").openSubsonicExtensions ?: emptyList()

    /** All music folders configured on the server. */
    suspend fun getMusicFolders(): List<SubsonicMusicFolderDto> =
        get("getMusicFolders").musicFolders?.folders ?: emptyList()

    /**
     * Artists indexed by first letter (`getArtists`). Optionally scoped to one music folder.
     * Returns the flat artist list (indexes unwrapped).
     */
    suspend fun getArtists(musicFolderId: String? = null): List<SubsonicArtistDto> {
        val body = get("getArtists", mapOf("musicFolderId" to musicFolderId))
        return body.artists?.indexes?.flatMap { it.artists } ?: emptyList()
    }

    /** Full artist detail including embedded album list (`getArtist?id=`). */
    suspend fun getArtist(id: String): SubsonicArtistDto =
        get("getArtist", mapOf("id" to id)).artist ?: error("getArtist returned no artist for $id")

    /**
     * Paginated album list (`getAlbumList2`).
     * [type] e.g. `"alphabeticalByName"`, `"byGenre"`, `"newest"`, `"random"`.
     */
    suspend fun getAlbumList2(
        type: String,
        size: Int = 500,
        offset: Int = 0,
        genre: String? = null,
        musicFolderId: String? = null,
    ): List<SubsonicAlbumDto> {
        val body = get("getAlbumList2", buildMap {
            put("type", type)
            put("size", size.toString())
            put("offset", offset.toString())
            if (genre != null) put("genre", genre)
            if (musicFolderId != null) put("musicFolderId", musicFolderId)
        })
        return body.albumList2?.albums ?: emptyList()
    }

    /** Full album detail including track list (`getAlbum?id=`). */
    suspend fun getAlbum(id: String): SubsonicAlbumDto =
        get("getAlbum", mapOf("id" to id)).album ?: error("getAlbum returned no album for $id")

    /** Genres available on the server (`getGenres`). */
    suspend fun getGenres(): List<SubsonicGenreDto> =
        get("getGenres").genres?.genres ?: emptyList()

    /** All playlists visible to this user (`getPlaylists`). */
    suspend fun getPlaylists(): List<SubsonicPlaylistDto> =
        get("getPlaylists").playlists?.playlists ?: emptyList()

    /** Playlist detail including track list (`getPlaylist?id=`). */
    suspend fun getPlaylist(id: String): SubsonicPlaylistDto =
        get("getPlaylist", mapOf("id" to id)).playlist ?: error("getPlaylist returned no playlist for $id")

    /** Songs for a given genre, paginated (`getSongsByGenre`). */
    suspend fun getSongsByGenre(
        genre: String, count: Int = 100, offset: Int = 0, musicFolderId: String? = null,
    ): List<SubsonicSongDto> {
        val body = get("getSongsByGenre", buildMap {
            put("genre", genre)
            put("count", count.toString())
            put("offset", offset.toString())
            if (musicFolderId != null) put("musicFolderId", musicFolderId)
        })
        return body.songsByGenre?.songs ?: emptyList()
    }

    /**
     * Random songs (`getRandomSongs`). Useful as an "all songs" approximation when no
     * server-side sort is needed.
     */
    suspend fun getRandomSongs(size: Int = 50, musicFolderId: String? = null): List<SubsonicSongDto> {
        val body = get("getRandomSongs", buildMap {
            put("size", size.toString())
            if (musicFolderId != null) put("musicFolderId", musicFolderId)
        })
        return body.randomSongs?.songs ?: emptyList()
    }

    /** Full-text search across songs, albums, and artists (`search3`). */
    suspend fun search3(
        query: String,
        songCount: Int = 20, songOffset: Int = 0,
        albumCount: Int = 0, albumOffset: Int = 0,
        artistCount: Int = 0, artistOffset: Int = 0,
    ): SubsonicSearchResult3Dto {
        val body = get("search3", mapOf(
            "query" to query,
            "songCount" to songCount.toString(),
            "songOffset" to songOffset.toString(),
            "albumCount" to albumCount.toString(),
            "albumOffset" to albumOffset.toString(),
            "artistCount" to artistCount.toString(),
            "artistOffset" to artistOffset.toString(),
        ))
        return body.searchResult3 ?: SubsonicSearchResult3Dto()
    }

    /** All starred items (tracks, albums, artists) for this user (`getStarred2`). */
    suspend fun getStarred2(): SubsonicStarred2Dto =
        get("getStarred2").starred2 ?: SubsonicStarred2Dto()

    /** Single song by ID (`getSong`). */
    suspend fun getSong(id: String): SubsonicSongDto =
        get("getSong", mapOf("id" to id)).song ?: error("getSong returned no song for $id")

    /** Star (favourite) one item. Exactly one of the optional IDs should be provided. */
    suspend fun star(songId: String? = null, albumId: String? = null, artistId: String? = null) {
        get("star", buildMap {
            if (songId != null) put("id", songId)
            if (albumId != null) put("albumId", albumId)
            if (artistId != null) put("artistId", artistId)
        })
    }

    /** Unstar (unfavourite) one item. Exactly one of the optional IDs should be provided. */
    suspend fun unstar(songId: String? = null, albumId: String? = null, artistId: String? = null) {
        get("unstar", buildMap {
            if (songId != null) put("id", songId)
            if (albumId != null) put("albumId", albumId)
            if (artistId != null) put("artistId", artistId)
        })
    }

    /**
     * Scrobble a song. When `submission = false` this sends a "now playing" update (no `time`
     * needed). When `submission = true` this reports a completed play and [time] should be the
     * epoch millisecond timestamp when the track finished.
     */
    suspend fun scrobble(trackId: String, submission: Boolean, time: Long? = null) {
        get("scrobble", buildMap {
            put("id", trackId)
            put("submission", submission.toString())
            if (time != null && submission) put("time", time.toString())
        })
    }

    /**
     * OpenSubsonic synced lyrics (`getLyricsBySongId`). Returns null when the server doesn't
     * support the endpoint (will throw [SubsonicException] which the caller ignores).
     */
    suspend fun getLyricsBySongId(songId: String): SubsonicLyricsListDto? =
        get("getLyricsBySongId", mapOf("id" to songId)).lyricsList

    /**
     * Classic unsynced lyrics (`getLyrics`). Best-effort — many servers return nothing here.
     * [artist] and [title] hints are used by some servers to look up the track.
     */
    suspend fun getLyrics(trackId: String, artist: String? = null, title: String? = null): SubsonicClassicLyricsDto? =
        get("getLyrics", buildMap {
            put("id", trackId)
            if (artist != null) put("artist", artist)
            if (title != null) put("title", title)
        }).lyrics
}

/**
 * Expands a raw user-entered URL into prioritised candidate URLs to probe.
 * Tries https first (Navidrome default) then http; does not guess the port.
 */
internal fun expandSubsonicCandidateUrls(input: String): List<String> {
    val trimmed = input.trim().trimEnd('/')
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> listOf(trimmed)
        else -> listOf("https://$trimmed", "http://$trimmed")
    }
}

/** Platform-specific factory — implemented in androidMain and jvmMain (both use OkHttp). */
expect fun createSubsonicHttpClient(json: Json): HttpClient
