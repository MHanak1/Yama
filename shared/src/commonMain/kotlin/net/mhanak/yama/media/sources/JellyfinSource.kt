package net.mhanak.yama.media.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.getDeviceName
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.LyricsCue
import net.mhanak.yama.media.model.LyricsLine
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.session.JellyfinSession
import net.mhanak.yama.session.JellyfinSessionRepository
import net.mhanak.yama.util.AppPreferences
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import org.jellyfin.sdk.api.client.extensions.musicGenresApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.QueueItem
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.collections.get
import org.jellyfin.sdk.model.UUID as JellyfinUUID

class JellyfinSource(private val sessionRepository: JellyfinSessionRepository) : MusicSource {
    override val type: SourceType = SourceType.Jellyfin
    val jellyfin = createJellyfinInstance(
        clientInfo = ClientInfo(name = "Yama - Yet another music app", version = "0.0.1"),
        deviceInfo = DeviceInfo(id = getDeviceId(), name = getDeviceName()),
    )
    var api: ApiClient? = null
    override var isAuthenticated: Boolean by mutableStateOf(false)

    var sessions: List<JellyfinSession> by mutableStateOf(emptyList())
        private set
    var currentSessionId: String? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val socket = JellyfinSocket(this)
    override val libraryChanges get() = socket.libraryChanges
    override val remoteCommands get() = socket.remoteCommands

    /** Toggle whether other clients can "Play On" this device (re-advertises capabilities). */
    fun setRemoteControlEnabled(enabled: Boolean) = socket.setRemoteControlEnabled(enabled)

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    override val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    override val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _genres = MutableStateFlow<List<Genre>>(emptyList())
    override val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshError = MutableStateFlow<Throwable?>(null)
    override val refreshError: StateFlow<Throwable?> = _refreshError.asStateFlow()

    private var pendingQuickConnectClient: ApiClient? = null
    private var pendingQuickConnectSecret: String? = null
    private var pendingQuickConnectDeviceId: String? = null

    init {
        val stored = sessionRepository.loadAll()
        sessions = stored
        stored.firstOrNull()?.let { restoreSession(it) }
    }

    suspend fun connect(baseUrl: String) {
        val api = jellyfin.createApi(baseUrl)
        if (api.systemApi.postPingSystem().status == 200) {
            this.api = api
        } else {
            throw Error("Could not connect to Jellyfin.")
        }
    }

    // Tries every candidate URL derived from the user's raw input in order,
    // returning on the first successful ping.
    suspend fun connectToAddress(input: String) {
        val candidates = expandCandidateUrls(input)
        var lastError: Exception = Exception("No addresses to try")
        for (url in candidates) {
            try {
                connect(url)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }

    suspend fun login(username: String, password: String) {
        val baseUrl = requireNotNull(api?.baseUrl) { "Not connected to a server" }
        val sessionDeviceId = getDeviceIdForUser(username)
        val newApi = jellyfin.createApi(
            baseUrl = baseUrl,
            deviceInfo = DeviceInfo(id = sessionDeviceId, name = getDeviceName()),
        )
        val result = newApi.userApi.authenticateUserByName(username = username, password = password).content
        newApi.update(accessToken = result.accessToken)
        api = newApi

        val sessionId = UUID.randomUUID().toString()
        sessionRepository.save(
            JellyfinSession(
                id = sessionId,
                serverUrl = baseUrl,
                serverName = null,
                userId = result.user?.id?.toString(),
                userName = result.user?.name,
                accessToken = requireNotNull(result.accessToken),
                sessionDeviceId = sessionDeviceId,
            )
        )
        sessions = sessionRepository.loadAll()
        currentSessionId = sessionId
        isAuthenticated = true
        socket.bind(newApi)
        scope.launch { runCatching { refresh() } }
    }

    suspend fun initiateQuickConnect(): String {
        val baseUrl = requireNotNull(api?.baseUrl) { "Not connected to a server" }
        val sessionDeviceId = UUID.randomUUID().toString()
        val newApi = jellyfin.createApi(
            baseUrl = baseUrl,
            deviceInfo = DeviceInfo(id = sessionDeviceId, name = getDeviceName()),
        )
        val state = newApi.quickConnectApi.initiateQuickConnect().content
        pendingQuickConnectClient = newApi
        pendingQuickConnectSecret = state.secret
        pendingQuickConnectDeviceId = state.deviceId
        return state.code
    }

    suspend fun pollQuickConnect(): Boolean {
        val client = requireNotNull(pendingQuickConnectClient) { "No active Quick Connect session" }
        val secret = requireNotNull(pendingQuickConnectSecret) { "No active Quick Connect session" }
        val state = client.quickConnectApi.getQuickConnectState(secret = secret).content
        return state.authenticated
    }

    suspend fun completeQuickConnect() {
        val client = requireNotNull(pendingQuickConnectClient) { "No active Quick Connect session" }
        val secret = requireNotNull(pendingQuickConnectSecret) { "No active Quick Connect session" }
        val result = client.userApi.authenticateWithQuickConnect(secret).content
        val accessToken = result.accessToken
            ?: error("Server did not return an access token for Quick Connect session")
        client.update(accessToken = accessToken)
        api = client

        val sessionId = UUID.randomUUID().toString()
        sessionRepository.save(
            JellyfinSession(
                id = sessionId,
                serverUrl = requireNotNull(client.baseUrl),
                serverName = null,
                userId = result.user?.id?.toString(),
                userName = result.user?.name,
                accessToken = accessToken,
                sessionDeviceId = requireNotNull(pendingQuickConnectDeviceId),
            )
        )
        pendingQuickConnectClient = null
        pendingQuickConnectSecret = null
        pendingQuickConnectDeviceId = null
        sessions = sessionRepository.loadAll()
        currentSessionId = sessionId
        isAuthenticated = true
        socket.bind(client)
        scope.launch { runCatching { refresh() } }
    }

    suspend fun logout() {
        runCatching { api?.sessionApi?.reportSessionEnded() }
        socket.unbind()
        sessionRepository.loadAll().forEach { sessionRepository.delete(it.id) }
        api = null
        currentSessionId = null
        sessions = emptyList()
        isAuthenticated = false
        clearLibrary()
    }

    fun cancelQuickConnect() {
        pendingQuickConnectClient = null
        pendingQuickConnectSecret = null
        pendingQuickConnectDeviceId = null
    }

    fun switchSession(session: JellyfinSession) {
        api = jellyfin.createApi(
            baseUrl = session.serverUrl,
            deviceInfo = DeviceInfo(id = session.sessionDeviceId, name = getDeviceName()),
            accessToken = session.accessToken,
        )
        currentSessionId = session.id
        isAuthenticated = true
        api?.let { socket.bind(it) }
        scope.launch {
            clearLibrary()
            runCatching { refresh() }
        }
    }

    suspend fun logoutSession(sessionId: String) {
        val isActive = sessionId == currentSessionId
        if (isActive) {
            runCatching { api?.sessionApi?.reportSessionEnded() }
            socket.unbind()
            api = null
            currentSessionId = null
        }
        sessionRepository.delete(sessionId)
        val remaining = sessionRepository.loadAll()
        sessions = remaining
        if (isActive) {
            remaining.firstOrNull()?.let { switchSession(it) } ?: run {
                isAuthenticated = false
                clearLibrary()
            }
        }
    }

    override suspend fun refresh() {
        val currentApi = api ?: return
        val userId = sessions.find { it.id == currentSessionId }?.userId
            ?.let { JellyfinUUID.fromString(it) }
        _refreshError.value = null
        _isRefreshing.value = true
        try {
            coroutineScope {
                launch { _albums.value = currentApi.fetchAlbums() }
                launch { _artists.value = currentApi.fetchArtists() }
                launch { _playlists.value = currentApi.fetchPlaylists(userId) }
                launch { _genres.value = currentApi.fetchGenres() }
            }
        } catch (e: Exception) {
            _refreshError.value = e
        } finally {
            _isRefreshing.value = false
        }
    }

    override suspend fun getTracksForAlbum(albumId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            parentId = JellyfinUUID.fromString(albumId),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            limit = 1_000,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getTracksForArtist(artistId: String, limit: Int, sortBy: TrackSortOrder): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            artistIds = listOf(JellyfinUUID.fromString(artistId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = sortBy.toJellyfinSortBy(),
            sortOrder = listOf(sortBy.toSortOrder()),
            limit = limit,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getTracksForGenre(genreId: String, limit: Int, sortBy: TrackSortOrder): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            genreIds = listOf(JellyfinUUID.fromString(genreId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = sortBy.toJellyfinSortBy(),
            sortOrder = listOf(sortBy.toSortOrder()),
            limit = limit,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getAlbumsForArtist(artistId: String): List<Album> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            artistIds = listOf(JellyfinUUID.fromString(artistId)),
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR),
            sortOrder = listOf(SortOrder.DESCENDING),
            fields = listOf(ItemFields.CHILD_COUNT),
            limit = 1_000,
        ).content.items?.map { item ->
            Album(
                id = item.id.toString(),
                name = item.name ?: "",
                albumArtist = item.albumArtist,
                year = item.productionYear,
                songCount = item.childCount,
                imageUrl = currentApi.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
                imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY))
            )
        } ?: emptyList()
    }

    override suspend fun getAlbumsForGenre(genreId: String): List<Album> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            genreIds = listOf(JellyfinUUID.fromString(genreId)),
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            sortBy = listOf(ItemSortBy.NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = listOf(ItemFields.CHILD_COUNT),
            limit = 1_000,
        ).content.items?.map { item ->
            Album(
                id = item.id.toString(),
                name = item.name ?: "",
                albumArtist = item.albumArtist,
                year = item.productionYear,
                songCount = item.childCount,
                imageUrl = currentApi.imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
                imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY))
            )
        } ?: emptyList()
    }

    override suspend fun getTracksForPlaylist(playlistId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.playlistsApi.getPlaylistItems(
            playlistId = JellyfinUUID.fromString(playlistId),
            limit = 1_000,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getTracksByIds(ids: List<String>): List<Track> {
        val currentApi = api ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val items = currentApi.itemsApi.getItems(
            ids = ids.map { JellyfinUUID.fromString(it) },
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            limit = ids.size,
        ).content.items ?: return emptyList()
        // getItems doesn't preserve the requested order, so re-order by the id list.
        val byId = items.associateBy { it.id.toString() }
        return ids.mapNotNull { id -> byId[id]?.let { currentApi.toTrack(it) } }
    }

    override suspend fun reportPlaybackStarted(track: Track, positionMs: Long, queue: List<Track>) {
        val currentApi = api ?: return
        runCatching {
            currentApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = JellyfinUUID.fromString(track.id),
                    positionTicks = positionMs * 10_000,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    nowPlayingQueue = queue.toQueueItems(),
                )
            )
        }
    }

    override suspend fun reportPlaybackProgress(track: Track, positionMs: Long, isPaused: Boolean, queue: List<Track>) {
        val currentApi = api ?: return
        runCatching {
            currentApi.playStateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = JellyfinUUID.fromString(track.id),
                    positionTicks = positionMs * 10_000,
                    canSeek = true,
                    isPaused = isPaused,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                    nowPlayingQueue = queue.toQueueItems(),
                )
            )
        }
    }

    override suspend fun reportPlaybackStopped(track: Track, positionMs: Long) {
        val currentApi = api ?: return
        runCatching {
            currentApi.playStateApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = JellyfinUUID.fromString(track.id),
                    positionTicks = positionMs * 10_000,
                    failed = false,
                )
            )
        }
    }

    override suspend fun getStreamUrl(trackId: String): String {
        val currentApi = api ?: error("Not connected to a server")
        val session = sessions.find { it.id == currentSessionId }
        val baseUrl = (currentApi.baseUrl ?: session?.serverUrl)?.trimEnd('/')
            ?: error("No base URL")
        // The SDK's getUniversalAudioStreamUrl builds the URL with includeCredentials = false, so it
        // omits api_key. A raw stream URL handed to ExoPlayer/libvlc carries no auth header, so we
        // must put the token in the query ourselves. The universal endpoint then direct-plays
        // (302 redirect) when the source container matches, or transcodes otherwise.
        val token = currentApi.accessToken ?: session?.accessToken ?: error("No access token")
        val deviceId = session?.sessionDeviceId ?: getDeviceId()
        val userId = session?.userId
        val containers = "opus,webm,mp3,aac,m4a,flac,webma,wav,ogg"
        return buildString {
            append(baseUrl).append("/Audio/").append(trackId).append("/universal")
            append("?DeviceId=").append(deviceId)
            if (userId != null) append("&UserId=").append(userId)
            append("&Container=").append(containers)
            append("&EnableRedirection=true")
            append("&api_key=").append(token)
        }
    }

    override suspend fun getArtworkUrl(trackId: String): String? {
        val currentApi = api ?: return null
        // For audio items Jellyfin serves the embedded/album primary art at this path.
        return currentApi.imageApi.getItemImageUrl(JellyfinUUID.fromString(trackId), ImageType.PRIMARY)
    }

    override suspend fun getLyrics(trackId: String): Lyrics {
        val currentApi = api ?: return Lyrics.None
        return try {
            val dto = currentApi.lyricsApi.getLyrics(JellyfinUUID.fromString(trackId)).content
            if (dto.metadata.isSynced == false) {
                val lines = dto.lyrics.map { it.text }
                if (lines.isEmpty()) Lyrics.None else Lyrics.Unsynced(lines)
            } else {
                val lines = dto.lyrics.mapNotNull { line ->
                    val startMs = (line.start ?: return@mapNotNull null) / 10_000
                    val cues = line.cues?.map { cue ->
                        LyricsCue(
                            startMs = cue.start / 10_000,
                            endMs = cue.end?.let { it / 10_000 },
                            lineStartIndex = cue.position.coerceIn(0, line.text.length),
                            lineEndIndex = cue.endPosition.coerceIn(0, line.text.length),
                        )
                    } ?: emptyList()
                    LyricsLine(text = line.text, startMs = startMs, cues = cues)
                }
                if (lines.isEmpty()) Lyrics.None else Lyrics.Timed(lines)
            }
        } catch (_: Exception) {
            Lyrics.None
        }
    }

    private fun clearLibrary() {
        _albums.value = emptyList()
        _artists.value = emptyList()
        _playlists.value = emptyList()
        _genres.value = emptyList()
        _refreshError.value = null
    }

    private fun restoreSession(session: JellyfinSession) {
        api = jellyfin.createApi(
            baseUrl = session.serverUrl,
            deviceInfo = DeviceInfo(id = session.sessionDeviceId, name = getDeviceName()),
            accessToken = session.accessToken,
        )
        currentSessionId = session.id
        isAuthenticated = true
        api?.let { socket.bind(it) }
        scope.launch { runCatching { refresh() } }
    }
}

private suspend fun ApiClient.fetchAlbums(): List<Album> =
    itemsApi.getItems(
        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
        recursive = true,
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        fields = listOf(ItemFields.CHILD_COUNT),
        limit = 1_000,
    ).content.items?.map { item ->
        Album(
            id = item.id.toString(),
            name = item.name ?: "",
            albumArtist = item.albumArtist,
            year = item.productionYear,
            songCount = item.childCount,
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)) //WOAH! WHAT A CODEFUL!
        )
    } ?: emptyList()

private suspend fun ApiClient.fetchArtists(): List<Artist> =
    artistsApi.getAlbumArtists(
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        limit = 1_000,
    ).content.items?.map { item ->
        Artist(
            id = item.id.toString(),
            name = item.name ?: "",
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY))
        )
    } ?: emptyList()

private suspend fun ApiClient.fetchPlaylists(userId: JellyfinUUID? = null): List<Playlist> =
    itemsApi.getItems(
        userId = userId,
        includeItemTypes = listOf(BaseItemKind.PLAYLIST),
        recursive = true,
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        fields = listOf(ItemFields.CHILD_COUNT),
        limit = 1_000,
    ).content.items?.map { item ->
        Playlist(
            id = item.id.toString(),
            name = item.name ?: "",
            itemCount = item.childCount,
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY))
        )
    } ?: emptyList()

private suspend fun ApiClient.fetchGenres(): List<Genre> =
    musicGenresApi.getMusicGenres(
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        limit = 1_000,
    ).content.items?.map { item ->
        Genre(
            id = item.id.toString(),
            name = item.name ?: "",
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY))
        )
    } ?: emptyList()

private fun ApiClient.toTrack(item: org.jellyfin.sdk.model.api.BaseItemDto) = Track(
    id = item.id.toString(),
    name = item.name ?: "",
    albumId = item.albumId?.toString(),
    album = item.album,
    artists = item.artists,
    durationTicks = item.runTimeTicks,
    trackNumber = item.indexNumber,
    discNumber = item.parentIndexNumber,
    // Audio items inherit album art; fall back to the item's own image when there is no album.
    imageUrl = imageApi.getItemImageUrl(item.albumId ?: item.id, ImageType.PRIMARY),
)

private fun List<Track>.toQueueItems(): List<QueueItem> =
    map { QueueItem(id = JellyfinUUID.fromString(it.id)) }

private fun TrackSortOrder.toJellyfinSortBy(): List<ItemSortBy> = when (this) {
    TrackSortOrder.Alphabetical     -> listOf(ItemSortBy.NAME)
    TrackSortOrder.ReleaseDate      -> listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.NAME)
    TrackSortOrder.PlayCount        -> listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.NAME)
    TrackSortOrder.RecentlyAdded    -> listOf(ItemSortBy.DATE_LAST_CONTENT_ADDED)
    TrackSortOrder.RecentlyPlayed   -> listOf(ItemSortBy.DATE_PLAYED)
    TrackSortOrder.Random           -> listOf(ItemSortBy.RANDOM)
}

private fun TrackSortOrder.toSortOrder(): SortOrder = when (this) {
    TrackSortOrder.Alphabetical     -> SortOrder.ASCENDING
    TrackSortOrder.ReleaseDate      -> SortOrder.DESCENDING
    TrackSortOrder.PlayCount        -> SortOrder.DESCENDING
    TrackSortOrder.RecentlyAdded    -> SortOrder.DESCENDING
    TrackSortOrder.RecentlyPlayed   -> SortOrder.DESCENDING
    TrackSortOrder.Random           -> SortOrder.ASCENDING
}

// Expands a user-entered address into an ordered list of URLs to probe.
// Rules (applied in order of priority):
//   - Scheme present → only that scheme is tried.
//   - No scheme → https tried before http.
//   - Port present → only that port is tried for the matched scheme(s).
//   - No port → for https: 8096 then 443; for http: 8096 then 80.
fun expandCandidateUrls(input: String): List<String> {
    val trimmed = input.trim()

    val schemes: List<String>
    val hostAndPath: String

    when {
        trimmed.startsWith("https://") -> { schemes = listOf("https"); hostAndPath = trimmed.removePrefix("https://") }
        trimmed.startsWith("http://")  -> { schemes = listOf("http");  hostAndPath = trimmed.removePrefix("http://")  }
        else                           -> { schemes = listOf("https", "http"); hostAndPath = trimmed }
    }

    // Detect an explicit port, being careful with IPv6 brackets: [::1]:8096
    val hostOnly = hostAndPath.substringBefore("/")
    val hasPort = if (hostOnly.startsWith("[")) {
        hostOnly.substringAfter("]").startsWith(":")
    } else {
        hostOnly.contains(":")
    }

    return schemes.flatMap { scheme ->
        if (hasPort) {
            listOf("$scheme://$hostAndPath")
        } else {
            // Split host and path
            val slashIndex = hostAndPath.indexOf('/')
            val hostPart = if (slashIndex == -1) hostAndPath else hostAndPath.substring(0, slashIndex)
            val pathWithLeadingSlash = if (slashIndex == -1) "" else hostAndPath.substring(slashIndex)

            val ports = if (scheme == "https") listOf(8096, 443) else listOf(8096, 80)
            ports.map { port -> "$scheme://$hostPart:$port$pathWithLeadingSlash" }
        }
    }
}

expect fun createJellyfinInstance(clientInfo: ClientInfo, deviceInfo: DeviceInfo): Jellyfin

fun getDeviceId(): String {
    if (AppPreferences.deviceId.isEmpty()) {
        AppPreferences.deviceId = UUID.randomUUID().toString()
    }
    return AppPreferences.deviceId
}

fun getDeviceIdForUser(username: String): String {
    val combined = getDeviceId() + username
    return MessageDigest.getInstance("SHA-256")
        .digest(combined.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
