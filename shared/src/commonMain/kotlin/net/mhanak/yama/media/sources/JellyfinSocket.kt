package net.mhanak.yama.media.sources

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.mhanak.yama.util.AppPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.jellyfin.sdk.model.api.SessionsMessage

/**
 * The live WebSocket layer for one Jellyfin session. The Kotlin SDK's [ApiClient.webSocket] manages
 * the actual connection (keep-alive + auto-reconnect) and connects lazily once we collect a
 * subscription, so this class is mostly subscription wiring + translation.
 *
 * Three responsibilities:
 * - **Library push:** debounced `LibraryChangedMessage` → [JellyfinSource.refresh], surfaced as
 *   [libraryChanges] (stale-while-revalidate without polling).
 * - **Controlled device ("Play On"):** when remote control is enabled, advertise media-control
 *   capability and translate incoming `PlayMessage`/`PlaystateMessage` into [RemoteCommand]s on
 *   [remoteCommands], which `PlaybackController` plays on the local player.
 * - **Session discovery (controlling other devices):** mirror the server's live `SessionsMessage`
 *   into [sessions], the source of truth for "cast" targets and for a remote player's status.
 *
 * Lifecycle is owned by [JellyfinSource]: [bind] on every auth event, [unbind] on logout.
 */
class JellyfinSocket(private val source: JellyfinSource) {
    private var scope: CoroutineScope? = null
    private var boundApi: ApiClient? = null
    private var refreshJob: Job? = null

    // Whether other clients may control this device. Gates capability advertisement and whether
    // inbound play/playstate messages are acted on.
    @Volatile
    var remoteControlEnabled: Boolean = AppPreferences.allowRemoteControl
        private set

    private val _libraryChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val libraryChanges: SharedFlow<Unit> = _libraryChanges

    private val _remoteCommands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 16)
    val remoteCommands: SharedFlow<RemoteCommand> = _remoteCommands

    // Live list of the server's sessions (every client, including this one). The server pushes this
    // constantly while subscribed; it feeds the cast-target list and each remote player's status.
    private val _sessions = MutableStateFlow<List<SessionInfoDto>>(emptyList())
    val sessions: StateFlow<List<SessionInfoDto>> = _sessions.asStateFlow()

    fun bind(api: ApiClient) {
        unbind()
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        boundApi = api
        val ws = api.webSocket

        // Library changes arrive in bursts during a scan; coalesce them into a single refresh.
        s.launch {
            ws.subscribe<LibraryChangedMessage>().collect {
                refreshJob?.cancel()
                refreshJob = s.launch {
                    delay(LIBRARY_DEBOUNCE_MS)
                    runCatching { source.refresh() }
                    _libraryChanges.tryEmit(Unit)
                }
            }
        }

        s.launch { ws.subscribe<PlayMessage>().collect { handlePlay(it) } }
        s.launch { ws.subscribe<PlaystateMessage>().collect { handlePlaystate(it) } }
        s.launch { ws.subscribe<GeneralCommandMessage>().collect { handleGeneralCommand(it) } }

        // Subscribing auto-sends SessionsStartMessage; the server then pushes the full session list
        // on every change. Drives the cast-target list and remote players' status.
        s.launch { ws.subscribe<SessionsMessage>().collect { _sessions.value = it.data.orEmpty() } }

        // The server only treats a session as remote-controllable while its socket is connected, so
        // (re)advertise capabilities every time the socket reaches Connected — this also covers
        // reconnects and avoids racing the connection with a one-shot post at bind time.
        s.launch {
            ws.state.collect { state ->
                println("[Yama] socket state = $state")
                if (state is SocketApiState.Connected) {
                    runCatching { postCapabilities(api) }
                        .onFailure { println("[Yama] postCapabilities failed: $it") }
                }
            }
        }
    }

    fun unbind() {
        scope?.cancel()
        scope = null
        boundApi = null
        refreshJob = null
        _sessions.value = emptyList()
    }

    /**
     * Pull a fresh session snapshot over REST and push it into [sessions], bypassing the live socket.
     *
     * The server only streams `SessionsMessage` to a *connected* socket, and after the app has been
     * backgrounded (especially on Android, where the process can be frozen with no foreground service
     * while we're only controlling a remote device) that socket may be silently half-open for a while
     * before the SDK notices and reconnects. During that window [sessions] is stale, so a controlled
     * device's now-playing/queue/volume freezes at whatever it was when we left. Calling this on
     * resume (and when opening the cast sheet) corrects the state immediately; the socket then resumes
     * its 1-second push once it reconnects.
     */
    suspend fun resyncSessions() {
        val api = boundApi ?: return
        runCatching { api.sessionApi.getSessions().content }
            .onSuccess { _sessions.value = it }
            .onFailure { println("[Yama] resyncSessions failed: $it") }
    }

    /** Toggle whether this device responds to remote control; re-advertises capabilities. */
    fun setRemoteControlEnabled(enabled: Boolean) {
        remoteControlEnabled = enabled
        val api = boundApi ?: return
        scope?.launch { runCatching { postCapabilities(api) } }
    }

    private suspend fun postCapabilities(api: ApiClient) {
        api.sessionApi.postCapabilities(
            playableMediaTypes = listOf(MediaType.AUDIO),
            supportedCommands = if (remoteControlEnabled) SUPPORTED_COMMANDS else emptyList(),
            supportsMediaControl = remoteControlEnabled,
            supportsPersistentIdentifier = true,
        )
    }

    private suspend fun handlePlay(message: PlayMessage) {
        if (!remoteControlEnabled) return
        val request = message.data ?: return
        println(
            "[Yama] PlayMessage cmd=${request.playCommand} ids=${request.itemIds.orEmpty().size} " +
                "startIndex=${request.startIndex} startPositionTicks=${request.startPositionTicks}",
        )
        val tracks = source.getTracksByIds(request.itemIds.orEmpty().map { it.toString() })
        if (tracks.isEmpty()) return
        val command = when (request.playCommand) {
            PlayCommand.PLAY_NEXT -> RemoteCommand.PlayNext(tracks)
            PlayCommand.PLAY_LAST -> RemoteCommand.AddToQueue(tracks)
            else -> RemoteCommand.Play(tracks, request.startIndex ?: 0)
        }
        _remoteCommands.emit(command)
    }

    private suspend fun handlePlaystate(message: PlaystateMessage) {
        if (!remoteControlEnabled) return
        val request = message.data ?: return
        val command = when (request.command) {
            PlaystateCommand.UNPAUSE -> RemoteCommand.Resume
            PlaystateCommand.PAUSE -> RemoteCommand.Pause
            PlaystateCommand.PLAY_PAUSE -> RemoteCommand.PlayPause
            PlaystateCommand.STOP -> RemoteCommand.Stop
            PlaystateCommand.NEXT_TRACK -> RemoteCommand.Next
            PlaystateCommand.PREVIOUS_TRACK -> RemoteCommand.Previous
            // Jellyfin ticks are 100-nanosecond units → milliseconds.
            PlaystateCommand.SEEK -> RemoteCommand.Seek((request.seekPositionTicks ?: 0) / 10_000)
            // FastForward / Rewind are not handled yet.
            else -> return
        }
        _remoteCommands.emit(command)
    }

    private suspend fun handleGeneralCommand(message: GeneralCommandMessage) {
        if (!remoteControlEnabled) return
        val data = message.data ?: return
        val command = when (data.name) {
            // Jellyfin sends the level as a 0–100 string in the "Volume" argument.
            GeneralCommandType.SET_VOLUME ->
                data.arguments["Volume"]?.toIntOrNull()?.let { RemoteCommand.SetVolume(it / 100f) } ?: return
            GeneralCommandType.VOLUME_UP -> RemoteCommand.VolumeUp
            GeneralCommandType.VOLUME_DOWN -> RemoteCommand.VolumeDown
            // Repeat/shuffle/mute and the rest are not handled yet.
            else -> return
        }
        _remoteCommands.emit(command)
    }

    private companion object {
        const val LIBRARY_DEBOUNCE_MS = 1_500L

        // The remote-control commands this device understands, advertised so controllers (e.g. the
        // Jellyfin web UI) show the matching buttons. Transport (play/pause/seek/next) rides on the
        // PlaystateCommand channel and doesn't need to be listed here; these are the general commands.
        val SUPPORTED_COMMANDS = listOf(
            GeneralCommandType.PLAY_STATE,
            GeneralCommandType.PLAY,
            GeneralCommandType.SET_REPEAT_MODE,
            GeneralCommandType.SET_SHUFFLE_QUEUE,
            GeneralCommandType.SET_VOLUME,
            GeneralCommandType.VOLUME_UP,
            GeneralCommandType.VOLUME_DOWN,
            GeneralCommandType.MUTE,
            GeneralCommandType.UNMUTE,
            GeneralCommandType.TOGGLE_MUTE,
            GeneralCommandType.DISPLAY_MESSAGE,
        )
    }
}
