package net.mhanak.yama.media.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.mhanak.yama.getDeviceName
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.LyricsCue
import net.mhanak.yama.media.model.LyricsLine
import net.mhanak.yama.media.download.CatalogSnapshot
import net.mhanak.yama.media.model.MusicLibrary
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.JellyfinRemotePlayer
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.playback.RemoteTarget
import net.mhanak.yama.session.JellyfinSession
import net.mhanak.yama.session.JellyfinSessionRepository
import net.mhanak.yama.util.AppPreferences
import net.mhanak.yama.util.StreamingQuality
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
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
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
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

class JellyfinSource(private val sessionRepository: JellyfinSessionRepository) : StaleWhileRevalidateSource(), RemotePlaybackProvider, FavoriteCapable, PlaybackReporting, OfflineCapable, AccountedSource {
    override val type: SourceType = SourceType.Jellyfin
    override val supportsStreamingQuality: Boolean = true
    // Rebuilt by [reconnect] on device wake so a fresh instance (and thus a fresh OkHttp connection
    // pool) replaces one whose pooled sockets died while the app was backgrounded.
    var jellyfin = newJellyfinInstance()
        private set

    private fun newJellyfinInstance() = createJellyfinInstance(
        clientInfo = ClientInfo(name = "Yama", version = "0.0.1"),
        deviceInfo = DeviceInfo(id = getDeviceId(), name = getDeviceName()),
    )
    var api: ApiClient? = null
    override var isAuthenticated: Boolean by mutableStateOf(false)

    var sessions: List<JellyfinSession> by mutableStateOf(emptyList())
        private set
    var currentSessionId: String? by mutableStateOf(null)
        private set

    // --- AccountedSource -------------------------------------------------------------------------
    // Presents the session list through the generic interface so the switcher UI never imports
    // JellyfinSession. `accounts` and `currentAccountId` are derived from the snapshot-state vars
    // above, so recomposition is automatic — no extra StateFlow required.

    override val accounts: List<SourceAccount>
        get() = sessions.map { it.toSourceAccount() }

    override val currentAccountId: String?
        get() = currentSessionId

    override fun selectAccount(id: String) {
        sessions.find { it.id == id }?.let { switchSession(it) }
    }

    override val supportsLogout: Boolean get() = true

    override suspend fun logout(id: String) = logoutSession(id)

    // Builds a source-agnostic account descriptor from a Jellyfin session. Owns the
    // Jellyfin-specific profile-image URL construction so the UI never needs to know it.
    // Jellyfin serves user profile images unauthenticated (no token appended); same approach
    // as album art, which loads fine without one.
    private fun JellyfinSession.toSourceAccount() = SourceAccount(
        id = id,
        sourceType = SourceType.Jellyfin,
        name = userName ?: serverUrl,
        subtitle = serverName ?: serverUrl,
        avatarUrl = userId?.let { uid ->
            "${serverUrl.trimEnd('/')}/Users/$uid/Images/Primary"
        },
        stableKey = sessionKey(this),
    )

    // -------------------------------------------------------------------------------------

    val socket = JellyfinSocket(this)
    override val libraryChanges get() = socket.libraryChanges
    override val remoteCommands get() = socket.remoteCommands

    /** Toggle whether other clients can "Play On" this device (re-advertises capabilities). */
    fun setRemoteControlEnabled(enabled: Boolean) = socket.setRemoteControlEnabled(enabled)

    // Cast targets: other sessions that accept audio remote control, excluding this device. Derived
    // from the socket's live session push; collapses to empty when there are no collectors / no socket.
    override val remoteTargets: StateFlow<List<RemoteTarget>> =
        socket.sessions
            .map { list ->
                val ourDeviceId = currentSessionDeviceId()
                list.filter {
                    it.id != null &&
                        it.supportsRemoteControl &&
                        MediaType.AUDIO in it.playableMediaTypes &&
                        it.deviceId != ourDeviceId
                }.map { RemoteTarget(id = it.id!!, name = it.deviceName ?: it.client ?: "Unknown", client = it.client) }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The live socket's connection state — false while it's down or reconnecting, during which a
    // controlled device's mirrored state is frozen.
    override val connected: StateFlow<Boolean> get() = socket.connected

    // "Can we stream from origin right now" — the live link's health, surfaced as a flow so the UI can
    // gray undownloaded items the moment it drops. Same signal as [connected]; named for the downloads
    // contract (see [MusicSource.isReachable]).
    override val isReachable: StateFlow<Boolean> get() = socket.connected

    /** Partition key for this session's downloads — stable per server+user so two accounts on the same
     * device (or the same account on two servers) never share download rows or files. */
    override fun downloadSourceKey(): String? =
        sessions.find { it.id == currentSessionId }?.let { sessionKey(it) }

    /** The account's stable partition key — server+user hashed, so it survives re-login and is shared
     *  by [downloadSourceKey] and [SourceAccount.stableKey]. */
    private fun sessionKey(s: JellyfinSession): String {
        val token = MessageDigest.getInstance("SHA-256")
            .digest("${s.serverUrl}|${s.userId}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "jellyfin:$token"
    }

    /**
     * Whether the live socket link is healthy *right now*, recomputed on the spot rather than read
     * from the (possibly stale) [connected] snapshot — see [JellyfinSocket.isLinkHealthy]. Used by the
     * device-wake handler to skip a pointless [reconnect] when the link is already up.
     */
    fun isLiveLinkHealthy(): Boolean = socket.isLinkHealthy()

    override fun createPlayer(target: RemoteTarget): Player {
        val currentApi = requireNotNull(api) { "Not connected to a server" }
        val userId = sessions.find { it.id == currentSessionId }?.userId?.let { JellyfinUUID.fromString(it) }
        return JellyfinRemotePlayer(currentApi, socket.sessions, target, userId, resync = socket::resyncSessions)
    }

    override fun refreshTargets() { scope.launch { socket.resyncSessions() } }

    private fun currentSessionDeviceId(): String? =
        sessions.find { it.id == currentSessionId }?.sessionDeviceId

    private fun currentUserId(): JellyfinUUID? =
        sessions.find { it.id == currentSessionId }?.userId?.let { JellyfinUUID.fromString(it) }

    private val _libraries = MutableStateFlow<List<MusicLibrary>>(emptyList())
    override val libraries: StateFlow<List<MusicLibrary>> = _libraries.asStateFlow()

    // Concrete set of currently-included library IDs (all libraries minus the persisted excluded set),
    // recomputed whenever the library list loads or the user toggles one.
    private val _enabledLibraryIds = MutableStateFlow<Set<String>>(emptySet())
    override val enabledLibraryIds: StateFlow<Set<String>> = _enabledLibraryIds.asStateFlow()

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
        runRefresh {
            // Pull the music libraries first so the picker is populated and we know what to scope to.
            val libs = runCatching { currentApi.fetchMusicLibraries(userId) }.getOrDefault(emptyList())
            _libraries.value = libs
            val excluded = currentExcludedLibraryIds()
            val enabledIds = libs.map { it.id }.filterNot { it in excluded }
            _enabledLibraryIds.value = enabledIds.toSet()
            // When every library is on (the common case), a single recursive query is cheaper than one
            // per library; only fan out by parentId when the user has actually narrowed the selection.
            val scopeAll = libs.isEmpty() || enabledIds.size == libs.size
            coroutineScope {
                launch { _albums.value = fetchScoped(enabledIds, scopeAll, { it.id }, { it.name }) { currentApi.fetchAlbums(it) } }
                launch { _artists.value = fetchScoped(enabledIds, scopeAll, { it.id }, { it.name }) { currentApi.fetchArtists(it) } }
                launch { _albumArtists.value = fetchScoped(enabledIds, scopeAll, { it.id }, { it.name }) { currentApi.fetchAlbumArtists(it) } }
                launch { _genres.value = fetchScoped(enabledIds, scopeAll, { it.id }, { it.name }) { currentApi.fetchGenres(it) } }
                // Playlists live in their own Jellyfin view, not a music library, so they stay global.
                launch { _playlists.value = currentApi.fetchPlaylists(userId) }
            }
        }
    }

    override fun setLibraryEnabled(id: String, enabled: Boolean) {
        val key = sessionKey() ?: return
        val excluded = AppPreferences.excludedLibraries(key).toMutableSet()
        if (enabled) excluded.remove(id) else excluded.add(id)
        AppPreferences.setExcludedLibraries(key, excluded)
        _enabledLibraryIds.value = _libraries.value.map { it.id }.filterNot { it in excluded }.toSet()
        scope.launch { runCatching { refresh() } }
    }

    // Persisted exclusions are scoped per server+user, so two accounts on the same device keep
    // independent selections.
    private fun sessionKey(): String? =
        sessions.find { it.id == currentSessionId }?.let { "${it.serverUrl}|${it.userId}" }

    private fun currentExcludedLibraryIds(): Set<String> =
        sessionKey()?.let { AppPreferences.excludedLibraries(it) } ?: emptySet()

    // Runs [fetch] either once (unscoped, when all libraries are on) or once per enabled library in
    // parallel, then merges — de-duping by [id] (an item can sit in more than one library) and
    // re-sorting by [name] since the per-library results arrive separately.
    private suspend fun <T> fetchScoped(
        enabledIds: List<String>,
        scopeAll: Boolean,
        id: (T) -> String,
        name: (T) -> String,
        fetch: suspend (JellyfinUUID?) -> List<T>,
    ): List<T> =
        if (scopeAll) fetch(null)
        else coroutineScope {
            enabledIds
                .map { async { fetch(JellyfinUUID.fromString(it)) } }
                .awaitAll()
                .flatten()
                .distinctBy(id)
                .sortedBy { name(it).lowercase() }
        }

    override suspend fun getTracksForAlbum(albumId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            parentId = JellyfinUUID.fromString(albumId),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = listOf(ItemFields.GENRES),
            limit = 1_000,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getTracksForArtist(artistId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            artistIds = listOf(JellyfinUUID.fromString(artistId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = sortBy.toJellyfinSortBy(),
            sortOrder = listOf(sortBy.toSortOrder()),
            fields = listOf(ItemFields.GENRES),
            limit = limit,
            startIndex = offset,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getTracksForGenre(genreId: String, limit: Int, offset: Int, sortBy: TrackSortOrder): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            genreIds = listOf(JellyfinUUID.fromString(genreId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = sortBy.toJellyfinSortBy(),
            sortOrder = listOf(sortBy.toSortOrder()),
            fields = listOf(ItemFields.GENRES),
            limit = limit,
            startIndex = offset,
        ).content.items?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getAllTracks(limit: Int, offset: Int, sortBy: TrackSortOrder, favoritesOnly: Boolean, searchTerm: String?): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.itemsApi.getItems(
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = sortBy.toJellyfinSortBy(),
            sortOrder = listOf(sortBy.toSortOrder()),
            // null leaves favourites unfiltered; true restricts to liked tracks.
            isFavorite = if (favoritesOnly) true else null,
            // null/blank searches everything; the backend matches the term against track names.
            searchTerm = searchTerm?.takeIf { it.isNotBlank() },
            fields = listOf(ItemFields.GENRES),
            limit = limit,
            startIndex = offset,
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
                imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)),
                genres = item.genres ?: emptyList(),
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
                imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)),
                genres = item.genres ?: emptyList(),
            )
        } ?: emptyList()
    }

    override suspend fun getTracksForPlaylist(playlistId: String): List<Track> {
        val currentApi = api ?: return emptyList()
        return currentApi.playlistsApi.getPlaylistItems(
            playlistId = JellyfinUUID.fromString(playlistId),
            limit = 1_000,
        ).content.items
            ?.filter { it.type == BaseItemKind.AUDIO }
            ?.map { currentApi.toTrack(it) } ?: emptyList()
    }

    override suspend fun getTracksByIds(ids: List<String>): List<Track> {
        val currentApi = api ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        val items = currentApi.itemsApi.getItems(
            ids = ids.map { JellyfinUUID.fromString(it) },
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            fields = listOf(ItemFields.GENRES),
            limit = ids.size,
        ).content.items ?: return emptyList()
        // getItems doesn't preserve the requested order, so re-order by the id list.
        val byId = items.associateBy { it.id.toString() }
        return ids.mapNotNull { id -> byId[id]?.let { currentApi.toTrack(it) } }
    }

    override suspend fun reportPlaybackStarted(
        track: Track, positionMs: Long, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat, shuffle: Boolean,
    ) {
        val currentApi = api ?: return
        runCatching {
            currentApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = JellyfinUUID.fromString(track.id),
                    positionTicks = positionMs * 10_000,
                    canSeek = true,
                    isPaused = false,
                    isMuted = volume == 0f,
                    volumeLevel = volume?.let { (it * 100).toInt() },
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = repeat.toJellyfin(),
                    playbackOrder = if (shuffle) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT,
                    nowPlayingQueue = queue.toQueueItems(),
                )
            )
        }
    }

    override suspend fun reportPlaybackProgress(
        track: Track, positionMs: Long, isPaused: Boolean, queue: List<Track>, volume: Float?,
        repeat: RemoteCommand.Repeat, shuffle: Boolean,
    ) {
        val currentApi = api ?: return
        runCatching {
            currentApi.playStateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = JellyfinUUID.fromString(track.id),
                    positionTicks = positionMs * 10_000,
                    canSeek = true,
                    isPaused = isPaused,
                    isMuted = volume == 0f,
                    volumeLevel = volume?.let { (it * 100).toInt() },
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = repeat.toJellyfin(),
                    playbackOrder = if (shuffle) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT,
                    nowPlayingQueue = queue.toQueueItems(),
                )
            )
        }
    }

    private fun RemoteCommand.Repeat.toJellyfin(): RepeatMode = when (this) {
        RemoteCommand.Repeat.Off -> RepeatMode.REPEAT_NONE
        RemoteCommand.Repeat.All -> RepeatMode.REPEAT_ALL
        RemoteCommand.Repeat.One -> RepeatMode.REPEAT_ONE
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

    override suspend fun reportPlayed(trackId: String, playedAtEpochMs: Long, positionMs: Long): Boolean {
        val currentApi = api ?: return false
        // Mark the item played with its (backdated) DatePlayed — the server increments the play count
        // and records the timestamp. Jellyfin's LocalDateTime is UTC-naive, so convert from epoch in UTC.
        val datePlayed = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(playedAtEpochMs), java.time.ZoneOffset.UTC,
        )
        return runCatching {
            currentApi.playStateApi.markPlayedItem(
                itemId = JellyfinUUID.fromString(trackId),
                userId = currentUserId(),
                datePlayed = datePlayed,
            )
            true
        }.getOrDefault(false)
    }

    override suspend fun getStreamUrl(trackId: String, quality: StreamingQuality?): String {
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
        // An explicit [quality] (a download's fixed quality) wins; otherwise the live global default.
        // Original carries a null cap, so passing it deliberately means "no cap" rather than falling
        // back to the global setting.
        val maxBitrate = (quality ?: AppPreferences.streamingQuality).maxBitrateBps
        return buildString {
            append(baseUrl).append("/Audio/").append(trackId).append("/universal")
            append("?DeviceId=").append(deviceId)
            if (userId != null) append("&UserId=").append(userId)
            append("&Container=").append(containers)
            append("&AudioCodec=aac,mp3,opus")
            append("&EnableRedirection=true")
            if (maxBitrate != null) append("&MaxStreamingBitrate=").append(maxBitrate)
            append("&api_key=").append(token)
        }
    }

    override suspend fun getArtworkUrl(trackId: String): String? {
        val currentApi = api ?: return null
        // For audio items Jellyfin serves the embedded/album primary art at this path.
        return currentApi.imageApi.getItemImageUrl(JellyfinUUID.fromString(trackId), ImageType.PRIMARY)
    }

    override suspend fun getContentVersion(trackId: String): String? {
        val currentApi = api ?: return null
        // The etag is the server's per-item change token (returned on item DTOs by default); a moved
        // etag means the file/metadata changed, so a downloaded copy is stale. Fall back to the
        // created-date when the server omits an etag (this SDK's BaseItemDto exposes no last-saved field).
        return runCatching {
            val item = currentApi.userLibraryApi.getItem(
                itemId = JellyfinUUID.fromString(trackId),
                userId = currentUserId(),
            ).content
            item.etag ?: item.dateCreated?.toString()
        }.getOrNull()
    }

    override suspend fun fetchTrackSnapshots(ids: List<String>): Map<String, OfflineCapable.TrackSnapshot> {
        val currentApi = api ?: return emptyMap()
        val userId = currentUserId()
        val result = mutableMapOf<String, OfflineCapable.TrackSnapshot>()
        // Jellyfin's getItems accepts a comma-separated ID list; 200 per call keeps URLs short and
        // response bodies manageable. With 400 downloaded tracks this is 2 requests instead of 400.
        // userId is required to get per-user data (isFavorite, playCount) in the response — without
        // it the /Items endpoint omits UserData. ETAG is an opt-in field, not returned by default.
        ids.chunked(200).forEach { chunk ->
            runCatching {
                currentApi.itemsApi.getItems(
                    userId = userId,
                    ids = chunk.map { JellyfinUUID.fromString(it) },
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    fields = listOf(ItemFields.ETAG),
                    limit = chunk.size,
                ).content.items?.forEach { item ->
                    result[item.id.toString()] = OfflineCapable.TrackSnapshot(
                        contentVersion = item.etag ?: item.dateCreated?.toString(),
                        // Null when the server omitted UserData; callers must not overwrite stored
                        // values in that case (a null isFavorite ≠ "not favourite").
                        favorite = item.userData?.isFavorite,
                        playCount = item.userData?.playCount,
                    )
                }
            }
        }
        return result
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

    // Jellyfin lets the user favourite every kind of item (tracks, albums, artists, genres, playlists).
    override fun supportsFavorites(kind: FavoritableKind): Boolean = true

    override suspend fun isFavorite(kind: FavoritableKind, id: String): Boolean {
        val currentApi = api ?: return false
        return runCatching {
            currentApi.userLibraryApi.getItem(
                itemId = JellyfinUUID.fromString(id),
                userId = currentUserId(),
            ).content.userData?.isFavorite == true
        }.getOrDefault(false)
    }

    override suspend fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        val currentApi = api ?: return
        // Reflect the change in the cached browse lists at once — the favourites filter and detail
        // headers read `favorite` straight off these (stale-while-revalidate), so without this they'd
        // keep showing the old state until the next full refresh.
        updateCachedFavorite(kind, id, favorite)
        val itemId = JellyfinUUID.fromString(id)
        val userId = currentUserId()
        runCatching {
            if (favorite) currentApi.userLibraryApi.markFavoriteItem(itemId, userId)
            else currentApi.userLibraryApi.unmarkFavoriteItem(itemId, userId)
        }
    }

    private fun updateCachedFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        when (kind) {
            FavoritableKind.Album ->
                _albums.value = _albums.value.map { if (it.id == id) it.copy(favorite = favorite) else it }
            FavoritableKind.Artist -> {
                _artists.value = _artists.value.map { if (it.id == id) it.copy(favorite = favorite) else it }
                _albumArtists.value = _albumArtists.value.map { if (it.id == id) it.copy(favorite = favorite) else it }
            }
            FavoritableKind.Genre ->
                _genres.value = _genres.value.map { if (it.id == id) it.copy(favorite = favorite) else it }
            FavoritableKind.Playlist ->
                _playlists.value = _playlists.value.map { if (it.id == id) it.copy(favorite = favorite) else it }
            FavoritableKind.Track -> Unit // tracks aren't held in a cached browse list
        }
    }

    /**
     * Seed the browse flows from the on-disk catalog snapshot. Only fills a flow that's still empty,
     * so a refresh that already landed (the SWR online case) is never clobbered by stale disk data —
     * the snapshot only fills the gap while offline / before the first successful refresh.
     */
    override fun hydrateCatalog(snapshot: CatalogSnapshot) {
        hydrateIfEmpty(
            albums = snapshot.albums,
            artists = snapshot.artists,
            albumArtists = snapshot.albumArtists,
            genres = snapshot.genres,
            playlists = snapshot.playlists,
        )
    }

    private fun clearLibrary() {
        _albums.value = emptyList()
        _artists.value = emptyList()
        _albumArtists.value = emptyList()
        _playlists.value = emptyList()
        _genres.value = emptyList()
        _libraries.value = emptyList()
        _enabledLibraryIds.value = emptySet()
        clearRefreshError()
    }

    /**
     * Rebuild the backend connection for the current session so a fresh WebSocket connects at once.
     *
     * The Jellyfin SDK exposes no way to force its WebSocket to reconnect, and after the app is
     * backgrounded the socket can sit silently half-open (the radio dropped without a FIN/RST): the
     * SDK only notices once OkHttp's ~30s write timeout fires, so a controlling/controlled device
     * stays out of sync for that whole window. Recreating the [ApiClient] — on a fresh [jellyfin]
     * instance so it also gets a fresh OkHttp connection pool and can't reuse the dead pooled socket —
     * brings up a new WebSocket immediately. Cheap and idempotent; called on device wake (Android).
     *
     * The library cache is left untouched (no refresh); [JellyfinSocket.resyncSessions] pulls a fresh
     * session snapshot so a mirrored remote device's state corrects right away.
     */
    fun reconnect() {
        val session = sessions.find { it.id == currentSessionId } ?: return
        jellyfin = newJellyfinInstance()
        api = jellyfin.createApi(
            baseUrl = session.serverUrl,
            deviceInfo = DeviceInfo(id = session.sessionDeviceId, name = getDeviceName()),
            accessToken = session.accessToken,
        )
        api?.let { socket.bind(it) }
        scope.launch { socket.resyncSessions() }
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

private suspend fun ApiClient.fetchMusicLibraries(userId: JellyfinUUID?): List<MusicLibrary> =
    userViewsApi.getUserViews(userId = userId).content.items
        ?.filter { it.collectionType == CollectionType.MUSIC }
        ?.map { MusicLibrary(id = it.id.toString(), name = it.name ?: "") }
        ?: emptyList()

// [parentId] scopes the query to a single library (Jellyfin view); null fetches across all of them.
private suspend fun ApiClient.fetchAlbums(parentId: JellyfinUUID? = null): List<Album> =
    itemsApi.getItems(
        parentId = parentId,
        includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
        recursive = true,
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        fields = listOf(ItemFields.CHILD_COUNT, ItemFields.GENRES),
        limit = 1_000,
    ).content.items?.map { item ->
        Album(
            id = item.id.toString(),
            name = item.name ?: "",
            albumArtist = item.albumArtist,
            year = item.productionYear,
            songCount = item.childCount,
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)), //WOAH! WHAT A CODEFUL!
            favorite = item.userData?.isFavorite == true,
            genres = item.genres ?: emptyList(),
        )
    } ?: emptyList()

private suspend fun ApiClient.fetchArtists(parentId: JellyfinUUID? = null): List<Artist> =
    artistsApi.getArtists(
        parentId = parentId,
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        fields = listOf(ItemFields.GENRES),
        limit = 5_000,
    ).content.items?.map { item ->
        Artist(
            id = item.id.toString(),
            name = item.name ?: "",
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)),
            favorite = item.userData?.isFavorite == true,
            genres = item.genres ?: emptyList(),
        )
    } ?: emptyList()

private suspend fun ApiClient.fetchAlbumArtists(parentId: JellyfinUUID? = null): List<Artist> =
    artistsApi.getAlbumArtists(
        parentId = parentId,
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        fields = listOf(ItemFields.GENRES),
        limit = 1_000,
    ).content.items?.map { item ->
        Artist(
            id = item.id.toString(),
            name = item.name ?: "",
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)),
            favorite = item.userData?.isFavorite == true,
            genres = item.genres ?: emptyList(),
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
    ).content.items
        ?.filter { item -> item.mediaType == null || item.mediaType == MediaType.AUDIO }
        ?.map { item ->
        Playlist(
            id = item.id.toString(),
            name = item.name ?: "",
            itemCount = item.childCount,
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)),
            favorite = item.userData?.isFavorite == true,
            genres = item.genres ?: emptyList(),
        )
    } ?: emptyList()

private suspend fun ApiClient.fetchGenres(parentId: JellyfinUUID? = null): List<Genre> =
    musicGenresApi.getMusicGenres(
        parentId = parentId,
        sortBy = listOf(ItemSortBy.NAME),
        sortOrder = listOf(SortOrder.ASCENDING),
        limit = 1_000,
    ).content.items?.map { item ->
        Genre(
            id = item.id.toString(),
            name = item.name ?: "",
            imageUrl = imageApi.getItemImageUrl(item.id, ImageType.PRIMARY),
            imageHash = item.imageBlurHashes?.get(ImageType.PRIMARY)?.get(item.imageTags?.get(ImageType.PRIMARY)),
            favorite = item.userData?.isFavorite == true,
        )
    } ?: emptyList()

internal fun ApiClient.toTrack(item: org.jellyfin.sdk.model.api.BaseItemDto) = Track(
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
    // ID-bearing variants (returned by default for audio; genres need ItemFields.GENRES). These let a
    // downloaded track's row fan out to artist/genre availability.
    artistIds = item.artistItems?.map { it.id.toString() } ?: emptyList(),
    albumArtistId = item.albumArtists?.firstOrNull()?.id?.toString(),
    genres = item.genres ?: emptyList(),
    genreIds = item.genreItems?.map { it.id.toString() } ?: emptyList(),
    // User data rides along on the item, so favourite/play-count come for free with the track query —
    // no separate per-track fetch (the old FavoriteButton/TrackListCard isFavorite call).
    favorite = item.userData?.isFavorite == true,
    playCount = item.userData?.playCount ?: 0,
)

private fun List<Track>.toQueueItems(): List<QueueItem> =
    map { QueueItem(id = JellyfinUUID.fromString(it.id)) }

private fun TrackSortOrder.toJellyfinSortBy(): List<ItemSortBy> = when (this) {
    TrackSortOrder.Alphabetical     -> listOf(ItemSortBy.NAME)
    TrackSortOrder.ReleaseDate      -> listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.NAME)
    TrackSortOrder.PlayCount        -> listOf(ItemSortBy.PLAY_COUNT, ItemSortBy.NAME)
    TrackSortOrder.RecentlyAdded    -> listOf(ItemSortBy.DATE_CREATED)
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
