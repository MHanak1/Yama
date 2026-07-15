package net.mhanak.yama.media.sources.subsonic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.media.download.CatalogSnapshot
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.LyricsLine
import net.mhanak.yama.media.model.MusicLibrary
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.AccountedSource
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.PlaybackReporting
import net.mhanak.yama.media.sources.RemoteCommand
import net.mhanak.yama.media.sources.SourceAccount
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.media.sources.StaleWhileRevalidateSource
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.session.SubsonicSession
import net.mhanak.yama.session.SubsonicSessionRepository
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.util.StreamingQuality
import java.security.MessageDigest
import java.util.UUID

class SubsonicSource(private val sessionRepository: SubsonicSessionRepository) :
    StaleWhileRevalidateSource(), FavoriteCapable, PlaybackReporting, OfflineCapable, AccountedSource {

    // --- MusicSource identity -----------------------------------------------

    override val type = SourceType.Subsonic
    override var isAuthenticated by mutableStateOf(false)

    // --- Session state (Compose snapshot state for automatic recomposition) -

    private var sessions by mutableStateOf<List<SubsonicSession>>(emptyList())
    override var currentAccountId by mutableStateOf<String?>(null)

    // Active HTTP client — rebuilt when the current session changes.
    private var api: SubsonicApi? = null

    // Whether the current session's server supports OpenSubsonic extensions.
    private var openSubsonic = false

    // --- Network health (drives isReachable / graying) ----------------------

    private val _isReachable = MutableStateFlow(true)
    override val isReachable: StateFlow<Boolean> = _isReachable.asStateFlow()

    // --- Libraries (music folders) ------------------------------------------

    private val _libraries = MutableStateFlow<List<MusicLibrary>>(emptyList())
    override val libraries: StateFlow<List<MusicLibrary>> = _libraries.asStateFlow()

    private val _enabledLibraryIds = MutableStateFlow<Set<String>>(emptySet())
    override val enabledLibraryIds: StateFlow<Set<String>> = _enabledLibraryIds.asStateFlow()

    // --- In-memory starred sets (seeded from getStarred2 during refresh) ----
    // Subsonic has no per-item isFavorite query; we maintain local sets and sync
    // them on refresh / fetchTrackSnapshots instead of N round-trips per UI row.

    private val starredTrackIds = mutableSetOf<String>()
    private val starredAlbumIds = mutableSetOf<String>()
    private val starredArtistIds = mutableSetOf<String>()

    // --- Init ---------------------------------------------------------------

    init {
        val stored = sessionRepository.loadAll()
        sessions = stored
        stored.firstOrNull()?.let { restoreSession(it) }
    }

    private fun restoreSession(session: SubsonicSession) {
        api = SubsonicApi(
            serverUrl = session.serverUrl,
            username = session.username,
            password = session.password,
            apiVersion = session.apiVersion,
        )
        openSubsonic = session.openSubsonic
        currentAccountId = session.id
        isAuthenticated = true
    }

    // --- AccountedSource ----------------------------------------------------

    override val accounts: List<SourceAccount>
        get() = sessions.map { session ->
            SourceAccount(
                id = session.id,
                sourceType = SourceType.Subsonic,
                name = session.username,
                subtitle = session.serverUrl,
                // Subsonic avatar URLs carry per-request salted auth params — embedding a fixed
                // salt here would make the URL stable, but Coil would cache a potentially stale
                // avatar across password changes. Returning null is safer; the switcher falls back
                // to the Subsonic logo drawable.
                avatarUrl = null,
                stableKey = sessionKey(session),
            )
        }

    override val supportsLogout = true

    override fun selectAccount(id: String) {
        val session = sessions.find { it.id == id } ?: return
        clearLibrary()
        restoreSession(session)
        scope.launch { runCatching { refresh() } }
    }

    override suspend fun logout(id: String) {
        sessionRepository.delete(id)
        val remaining = sessionRepository.loadAll()
        sessions = remaining
        if (currentAccountId == id) {
            val next = remaining.firstOrNull()
            if (next != null) {
                clearLibrary()
                restoreSession(next)
                runCatching { refresh() }
            } else {
                api = null
                currentAccountId = null
                isAuthenticated = false
                openSubsonic = false
                clearLibrary()
            }
        }
    }

    // --- Connection / login -------------------------------------------------

    /**
     * Probe candidate URLs derived from [rawServerUrl] and return the first one that responds.
     * Throws if no candidate is reachable. Does NOT validate credentials.
     */
    suspend fun connect(rawServerUrl: String): String {
        val candidates = expandSubsonicCandidateUrls(rawServerUrl)
        var lastError: Exception = Exception("Could not reach server at $rawServerUrl")
        for (url in candidates) {
            try {
                // Dummy credentials: a SubsonicException means the server is there (wrong creds
                // expected). Any other exception means the server isn't at this URL.
                val probe = SubsonicApi(url, "_", "_")
                try { probe.ping() } catch (_: SubsonicException) { /* server responded */ }
                return url
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                return url // server responded (wrong creds during probe is fine)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }

    /**
     * Validate [username]/[password] against the already-probed [serverUrl], detect OpenSubsonic
     * support, persist a new session, and kick off an initial refresh.
     *
     * Call [connect] first to obtain [serverUrl]; it returns the normalised URL for the server.
     */
    suspend fun login(serverUrl: String, username: String, password: String) {
        val loginApi = SubsonicApi(serverUrl, username, password)
        loginApi.ping() // throws SubsonicException(40) on wrong credentials

        val hasOpenSubsonic = runCatching {
            loginApi.getOpenSubsonicExtensions().isNotEmpty()
        }.getOrDefault(false)

        val sessionId = UUID.randomUUID().toString()
        val session = SubsonicSession(
            id = sessionId,
            serverUrl = serverUrl,
            serverName = null,
            username = username,
            password = password,
            apiVersion = "1.16.1",
            openSubsonic = hasOpenSubsonic,
        )
        sessionRepository.save(session)
        sessions = sessionRepository.loadAll()
        restoreSession(session)
        scope.launch { runCatching { refresh() } }
    }

    // --- MusicSource --------------------------------------------------------

    override val supportsStreamingQuality = true

    override suspend fun refresh() = runRefresh {
        val currentApi = api ?: return@runRefresh
        val session = sessions.find { it.id == currentAccountId } ?: return@runRefresh

        // 1. Fetch music folders and compute the enabled set
        val folders = currentApi.getMusicFolders()
        _libraries.value = folders.map { MusicLibrary(id = it.id.toString(), name = it.name ?: "Music") }

        val prefsKey = "${session.serverUrl}|${session.username}"
        val excluded = AppPreferences.excludedLibraries(prefsKey)
        val allIds = folders.map { it.id.toString() }.toSet()
        val enabled = (allIds - excluded).ifEmpty { allIds }
        _enabledLibraryIds.value = enabled

        // 2. Determine the folder IDs to scope browsing queries.
        // `null` in the list means "no folder filter" (all folders).
        val folderIds: List<String?> = if (enabled == allIds) listOf(null) else enabled.toList()

        // 3. Parallel fetch of all five browse lists + starred sets
        coroutineScope {
            val artistsDeferred = async {
                val map = linkedMapOf<String, SubsonicArtistDto>()
                folderIds.forEach { folderId ->
                    currentApi.getArtists(musicFolderId = folderId).forEach { map[it.id] = it }
                }
                map.values.toList()
            }
            val albumsDeferred = async {
                val map = linkedMapOf<String, SubsonicAlbumDto>()
                folderIds.forEach { folderId ->
                    var offset = 0
                    while (true) {
                        val page = currentApi.getAlbumList2(
                            type = "alphabeticalByName",
                            size = 500,
                            offset = offset,
                            musicFolderId = folderId,
                        )
                        page.forEach { map[it.id] = it }
                        if (page.size < 500) break
                        offset += page.size
                    }
                }
                map.values.toList()
            }
            val genresDeferred = async { currentApi.getGenres() }
            val playlistsDeferred = async { currentApi.getPlaylists() }

            val rawArtists = artistsDeferred.await()
            val rawAlbums = albumsDeferred.await()
            val rawGenres = genresDeferred.await()
            val rawPlaylists = playlistsDeferred.await()

            // Sync starred sets (best-effort — a failure here shouldn't abort the refresh)
            runCatching { syncStarredSets(currentApi) }

            _artists.value = rawArtists.map { it.toArtist(currentApi) }
            _albumArtists.value = _artists.value  // Subsonic doesn't distinguish
            _albums.value = rawAlbums.map { it.toAlbum(currentApi) }
            _genres.value = rawGenres.map { it.toGenre() }
            _playlists.value = rawPlaylists.map { it.toPlaylist(currentApi) }
        }

        _isReachable.value = true
    }

    override suspend fun getTracksForAlbum(albumId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return runCatching { currentApi.getAlbum(albumId).songs.map { it.toTrack(currentApi) } }
            .getOrDefault(emptyList())
    }

    override suspend fun getTracksForArtist(
        artistId: String, limit: Int, offset: Int, sortBy: TrackSortOrder,
    ): List<Track> {
        val currentApi = api ?: return emptyList()
        return runCatching {
            val artistFull = currentApi.getArtist(artistId)
            // Fetch all albums in parallel, then flatten tracks.
            coroutineScope {
                artistFull.albums
                    .map { album -> async { runCatching { currentApi.getAlbum(album.id).songs }.getOrElse { emptyList() } } }
                    .flatMap { it.await() }
                    .map { it.toTrack(currentApi) }
                    .let { tracks ->
                        when (sortBy) {
                            TrackSortOrder.Alphabetical -> tracks.sortedBy { it.name.lowercase() }
                            TrackSortOrder.PlayCount -> tracks.sortedByDescending { it.playCount }
                            else -> tracks
                        }
                    }
                    .drop(offset).take(limit)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getTracksForGenre(
        genreId: String, limit: Int, offset: Int, sortBy: TrackSortOrder,
    ): List<Track> {
        val currentApi = api ?: return emptyList()
        // genreId == genre name in Subsonic (no separate numeric ID)
        return runCatching {
            currentApi.getSongsByGenre(genre = genreId, count = limit, offset = offset)
                .map { it.toTrack(currentApi) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getAllTracks(
        limit: Int, offset: Int, sortBy: TrackSortOrder, favoritesOnly: Boolean, searchTerm: String?,
    ): List<Track> {
        val currentApi = api ?: return emptyList()
        return runCatching {
            when {
                favoritesOnly -> {
                    currentApi.getStarred2().songs
                        .map { it.toTrack(currentApi) }
                        .drop(offset).take(limit)
                }
                searchTerm != null -> {
                    currentApi.search3(query = searchTerm, songCount = limit, songOffset = offset)
                        .songs.map { it.toTrack(currentApi) }
                }
                sortBy == TrackSortOrder.Random -> {
                    currentApi.getRandomSongs(size = limit).map { it.toTrack(currentApi) }
                }
                else -> {
                    // Empty-query search gives a stable paginated all-songs list on Navidrome.
                    currentApi.search3(query = "", songCount = limit, songOffset = offset)
                        .songs.map { it.toTrack(currentApi) }
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getTracksForPlaylist(playlistId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return runCatching {
            currentApi.getPlaylist(playlistId).songs.map { it.toTrack(currentApi) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getAlbumsForArtist(artistId: String): List<Album> {
        val currentApi = api ?: return emptyList()
        return runCatching {
            currentApi.getArtist(artistId).albums.map { it.toAlbum(currentApi) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getAlbumsForGenre(genreId: String): List<Album> {
        val currentApi = api ?: return emptyList()
        return runCatching {
            currentApi.getAlbumList2(type = "byGenre", genre = genreId, size = 500, offset = 0)
                .map { it.toAlbum(currentApi) }
        }.getOrDefault(emptyList())
    }

    override suspend fun getTracksByIds(ids: List<String>): List<Track> {
        val currentApi = api ?: return emptyList()
        return runCatching {
            coroutineScope {
                ids.map { id -> async { runCatching { currentApi.getSong(id) }.getOrNull() } }
                    .mapNotNull { it.await() }
                    .map { it.toTrack(currentApi) }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun getStreamUrl(trackId: String, quality: StreamingQuality?): String {
        val currentApi = api ?: error("Not connected to a Subsonic server")
        val maxKbps = (quality ?: AppPreferences.streamingQuality).maxBitrateBps?.div(1000)
        return currentApi.streamUrl(trackId, maxBitrateKbps = maxKbps)
    }

    override suspend fun getArtworkUrl(trackId: String): String? = api?.coverArtUrl(trackId)

    override suspend fun getLyrics(trackId: String): Lyrics {
        val currentApi = api ?: return Lyrics.None
        // OpenSubsonic path: structured/synced lyrics
        if (openSubsonic) {
            runCatching {
                val lyricsList = currentApi.getLyricsBySongId(trackId)
                val structured = lyricsList?.structuredLyrics?.firstOrNull()
                if (structured != null) {
                    return if (structured.synced == true) {
                        val lines = structured.line.mapNotNull { line ->
                            val startMs = line.start ?: return@mapNotNull null
                            LyricsLine(text = line.value, startMs = startMs)
                        }
                        if (lines.isEmpty()) Lyrics.None else Lyrics.Timed(lines)
                    } else {
                        val lines = structured.line.map { it.value }
                        if (lines.isEmpty()) Lyrics.None else Lyrics.Unsynced(lines)
                    }
                }
            }
        }
        // Classic fallback: unsynced plain text
        return runCatching {
            val classic = currentApi.getLyrics(trackId = trackId)
            val raw = classic?.value ?: return@runCatching Lyrics.None
            val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) Lyrics.None else Lyrics.Unsynced(lines)
        }.getOrDefault(Lyrics.None)
    }

    // --- Library picker -----------------------------------------------------

    override fun setLibraryEnabled(id: String, enabled: Boolean) {
        val session = sessions.find { it.id == currentAccountId } ?: return
        val prefsKey = "${session.serverUrl}|${session.username}"
        val excluded = AppPreferences.excludedLibraries(prefsKey).toMutableSet()
        if (enabled) excluded.remove(id) else excluded.add(id)
        AppPreferences.setExcludedLibraries(prefsKey, excluded)
        val allIds = _libraries.value.map { it.id }.toSet()
        _enabledLibraryIds.value = (allIds - excluded).ifEmpty { allIds }
        scope.launch { runCatching { refresh() } }
    }

    // --- FavoriteCapable ----------------------------------------------------

    // Genres and playlists have no star/unstar endpoint in Subsonic.
    override fun supportsFavorites(kind: FavoritableKind) =
        kind == FavoritableKind.Track || kind == FavoritableKind.Album || kind == FavoritableKind.Artist

    override suspend fun isFavorite(kind: FavoritableKind, id: String) = when (kind) {
        FavoritableKind.Track -> id in starredTrackIds
        FavoritableKind.Album -> id in starredAlbumIds
        FavoritableKind.Artist -> id in starredArtistIds
        else -> false
    }

    override suspend fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        val currentApi = api ?: return
        if (favorite) {
            when (kind) {
                FavoritableKind.Track -> { currentApi.star(songId = id); starredTrackIds.add(id) }
                FavoritableKind.Album -> { currentApi.star(albumId = id); starredAlbumIds.add(id) }
                FavoritableKind.Artist -> { currentApi.star(artistId = id); starredArtistIds.add(id) }
                else -> return
            }
        } else {
            when (kind) {
                FavoritableKind.Track -> { currentApi.unstar(songId = id); starredTrackIds.remove(id) }
                FavoritableKind.Album -> { currentApi.unstar(albumId = id); starredAlbumIds.remove(id) }
                FavoritableKind.Artist -> { currentApi.unstar(artistId = id); starredArtistIds.remove(id) }
                else -> return
            }
        }
    }

    // --- PlaybackReporting --------------------------------------------------

    override suspend fun reportPlaybackStarted(
        track: Track, positionMs: Long, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat, shuffle: Boolean,
    ) {
        val currentApi = api ?: return
        // Subsonic now-playing: scrobble with submission=false
        runCatching { currentApi.scrobble(trackId = track.id, submission = false) }
    }

    override suspend fun reportPlaybackProgress(
        track: Track, positionMs: Long, isPaused: Boolean, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat, shuffle: Boolean,
    ) {
        // Subsonic has no progress endpoint; avoid spamming the server.
    }

    override suspend fun reportPlaybackStopped(track: Track, positionMs: Long) {
        // Nothing to report for Subsonic on stop.
    }

    override suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long, positionMs: Long): Boolean {
        val currentApi = api ?: return false
        return runCatching {
            currentApi.scrobble(trackId = trackId, submission = true, time = playedAtEpochMs)
            true
        }.getOrDefault(false)
    }

    // Subsonic has no stop/progress-based play counting: a play (and its ListenBrainz scrobble) only
    // registers on scrobble submission=true, so the completed play must be submitted online here.
    override val submitCompletedPlayOnline: Boolean get() = true

    // --- OfflineCapable -----------------------------------------------------

    override fun downloadSourceKey(): String? =
        sessions.find { it.id == currentAccountId }?.let { sessionKey(it) }

    // SHA-256 of "serverUrl|username", first 16 hex chars — stable per account, mirrors the pattern
    // JellyfinSource uses. Shared by downloadSourceKey and SourceAccount.stableKey.
    private fun sessionKey(session: SubsonicSession): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${session.serverUrl}|${session.username}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "subsonic:$hash"
    }

    // Subsonic exposes no content etag/version; downloads are assumed valid indefinitely.
    override suspend fun getContentVersion(trackId: String): String? = null

    override suspend fun fetchTrackSnapshots(ids: List<String>): Map<String, OfflineCapable.TrackSnapshot> {
        val currentApi = api ?: return emptyMap()
        return runCatching {
            // Sync the entire starred set in one round-trip — cheaper than per-id queries.
            val starred = currentApi.getStarred2()
            val starredIds = starred.songs.map { it.id }.toSet()
            // Update in-memory set while we're here.
            starredTrackIds.clear(); starredTrackIds.addAll(starredIds)
            // Build snapshots for every requested ID.
            // playCount is null: Subsonic requires a per-song getSong call to retrieve it,
            // which would be N requests; callers won't overwrite a non-null stored value
            // when we return null, so existing download rows keep their last-known count.
            ids.associateWith { id ->
                OfflineCapable.TrackSnapshot(
                    contentVersion = null,
                    favorite = id in starredIds,
                    playCount = null,
                )
            }
        }.getOrDefault(emptyMap())
    }

    override fun hydrateCatalog(snapshot: CatalogSnapshot) {
        hydrateIfEmpty(
            albums = snapshot.albums,
            artists = snapshot.artists,
            albumArtists = snapshot.albumArtists,
            genres = snapshot.genres,
            playlists = snapshot.playlists,
        )
    }

    // --- Internal helpers ---------------------------------------------------

    private fun clearLibrary() {
        _albums.value = emptyList()
        _artists.value = emptyList()
        _albumArtists.value = emptyList()
        _playlists.value = emptyList()
        _genres.value = emptyList()
        _libraries.value = emptyList()
        _enabledLibraryIds.value = emptySet()
        starredTrackIds.clear()
        starredAlbumIds.clear()
        starredArtistIds.clear()
        clearRefreshError()
    }

    private suspend fun syncStarredSets(api: SubsonicApi) {
        val starred = api.getStarred2()
        starredTrackIds.clear(); starredTrackIds.addAll(starred.songs.map { it.id })
        starredAlbumIds.clear(); starredAlbumIds.addAll(starred.albums.map { it.id })
        starredArtistIds.clear(); starredArtistIds.addAll(starred.artists.map { it.id })
    }

    // --- DTO → domain model conversions ------------------------------------

    private fun SubsonicSongDto.toTrack(api: SubsonicApi): Track {
        val coverArtId = coverArt ?: albumId
        // Subsonic duration is in seconds; the domain model uses Jellyfin ticks (100 ns units).
        // ms = ticks / 10_000, so ticks = seconds * 10_000_000.
        val durationTicks = duration?.let { it.toLong() * 10_000_000L }
        val genreList = genres?.map { it.name } ?: genre?.let { listOf(it) } ?: emptyList()
        // Prefer OpenSubsonic multi-artist refs (richer); fall back to single artist string.
        val artistNames = artistRefs?.map { it.name } ?: artist?.let { listOf(it) }
        val artistIdList = artistRefs?.map { it.id } ?: artistId?.let { listOf(it) } ?: emptyList()
        return Track(
            id = id,
            name = title,
            albumId = albumId,
            album = album,
            artists = artistNames,
            durationTicks = durationTicks,
            trackNumber = track,
            discNumber = discNumber,
            imageUrl = coverArtId?.let { api.coverArtUrl(it) },
            artistIds = artistIdList,
            albumArtistId = albumArtistId,
            genres = genreList,
            // Subsonic has no separate numeric genre IDs; name serves as ID everywhere.
            genreIds = genreList,
            favorite = id in starredTrackIds,
            playCount = playCount?.toInt() ?: 0,
        )
    }

    private fun SubsonicAlbumDto.toAlbum(api: SubsonicApi): Album = Album(
        id = id,
        name = name,
        albumArtist = artist,
        year = year,
        songCount = songCount,
        imageUrl = coverArt?.let { api.coverArtUrl(it) },
        imageHash = null,
        favorite = id in starredAlbumIds,
        genres = genres?.map { it.name } ?: genre?.let { listOf(it) } ?: emptyList(),
    )

    private fun SubsonicArtistDto.toArtist(api: SubsonicApi): Artist = Artist(
        id = id,
        name = name,
        imageUrl = coverArt?.let { api.coverArtUrl(it) },
        imageHash = null,
        favorite = id in starredArtistIds,
    )

    private fun SubsonicGenreDto.toGenre(): Genre = Genre(
        id = name,   // Subsonic: genre name IS the identifier (no numeric ID)
        name = name,
        imageUrl = null,
        imageHash = null,
    )

    private fun SubsonicPlaylistDto.toPlaylist(api: SubsonicApi): Playlist = Playlist(
        id = id,
        name = name,
        itemCount = songCount,
        imageUrl = coverArt?.let { api.coverArtUrl(it) },
        imageHash = null,
        favorite = false,  // Subsonic classic has no playlist starring
    )
}
