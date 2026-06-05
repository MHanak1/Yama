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
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.session.JellyfinSession
import net.mhanak.yama.session.JellyfinSessionRepository
import net.mhanak.yama.util.AppPreferences
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.musicGenresApi
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
        scope.launch { runCatching { refresh() } }
    }

    suspend fun logout() {
        runCatching { api?.sessionApi?.reportSessionEnded() }
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
        scope.launch { runCatching { refresh() } }
    }

    suspend fun logoutSession(sessionId: String) {
        val isActive = sessionId == currentSessionId
        if (isActive) {
            runCatching { api?.sessionApi?.reportSessionEnded() }
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
        _isRefreshing.value = true
        try {
            coroutineScope {
                launch { _albums.value = currentApi.fetchAlbums() }
                launch { _artists.value = currentApi.fetchArtists() }
                launch { _playlists.value = currentApi.fetchPlaylists(userId) }
                launch { _genres.value = currentApi.fetchGenres() }
            }
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
        ).content.items?.map { it.toTrack() } ?: emptyList()
    }

    override suspend fun getTracksForArtist(artistId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            artistIds = listOf(JellyfinUUID.fromString(artistId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER),
            sortOrder = listOf(SortOrder.ASCENDING),
            limit = 1_000,
        ).content.items?.map { it.toTrack() } ?: emptyList()
    }

    override suspend fun getTracksForGenre(genreId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            genreIds = listOf(JellyfinUUID.fromString(genreId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER),
            sortOrder = listOf(SortOrder.ASCENDING),
            limit = 1_000,
        ).content.items?.map { it.toTrack() } ?: emptyList()
    }

    override suspend fun getTracksForPlaylist(playlistId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.playlistsApi.getPlaylistItems(
            playlistId = JellyfinUUID.fromString(playlistId),
            limit = 1_000,
        ).content.items?.map { it.toTrack() } ?: emptyList()
    }

    private fun clearLibrary() {
        _albums.value = emptyList()
        _artists.value = emptyList()
        _playlists.value = emptyList()
        _genres.value = emptyList()
    }

    private fun restoreSession(session: JellyfinSession) {
        api = jellyfin.createApi(
            baseUrl = session.serverUrl,
            deviceInfo = DeviceInfo(id = session.sessionDeviceId, name = getDeviceName()),
            accessToken = session.accessToken,
        )
        currentSessionId = session.id
        isAuthenticated = true
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

private fun org.jellyfin.sdk.model.api.BaseItemDto.toTrack() = Track(
    id = id.toString(),
    name = name ?: "",
    albumId = albumId?.toString(),
    album = album,
    artist = artists?.firstOrNull() ?: albumArtist,
    durationTicks = runTimeTicks,
    trackNumber = indexNumber,
    discNumber = parentIndexNumber,
    //imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY)
)

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
