package net.mhanak.yama.media.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Playlist

/**
 * Abstract base for [MusicSource] implementations that follow a stale-while-revalidate pattern.
 *
 * Owns the five browse [StateFlow]s, the [isRefreshing]/[refreshError] bookkeeping pair, a
 * [runRefresh] helper that enforces the null → true → try/catch/finally pattern in one place,
 * and a [hydrateIfEmpty] guard that fills a flow only if it is still empty — so a refresh that
 * already landed is never clobbered by a stale disk snapshot.
 *
 * Subclasses keep their source-specific state (extra flows, authentication, fetching logic) and
 * call [runRefresh] in their [refresh] override instead of hand-rolling the boilerplate.
 */
abstract class StaleWhileRevalidateSource : MusicSource {
    /** Shared coroutine scope; use for background work launched from non-suspend contexts. */
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Browse flows — owned here so every subclass gets them for free. Subclasses write via the
    // protected backing fields; the public `override val` exposes them read-only via asStateFlow().
    protected val _albums = MutableStateFlow<List<Album>>(emptyList())
    override val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    protected val _artists = MutableStateFlow<List<Artist>>(emptyList())
    override val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    protected val _albumArtists = MutableStateFlow<List<Artist>>(emptyList())
    override val albumArtists: StateFlow<List<Artist>> = _albumArtists.asStateFlow()

    protected val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    protected val _genres = MutableStateFlow<List<Genre>>(emptyList())
    override val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    override val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshError = MutableStateFlow<Throwable?>(null)
    override val refreshError: StateFlow<Throwable?> = _refreshError.asStateFlow()

    /**
     * Run a refresh body with shared bookkeeping: clears [refreshError], sets [isRefreshing] true,
     * and restores it false in a finally block. Exceptions are caught and stored in [refreshError]
     * except [CancellationException], which propagates — a cancelled refresh is not a failure.
     */
    protected suspend fun runRefresh(block: suspend () -> Unit) {
        _refreshError.value = null
        _isRefreshing.value = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _refreshError.value = e
        } finally {
            _isRefreshing.value = false
        }
    }

    /**
     * SWR guard: fill only the browse flows that are still empty, so a refresh that already landed
     * is never clobbered by a stale disk snapshot. Only non-null arguments are considered; omit a
     * flow to leave it untouched. Used by [OfflineCapable.hydrateCatalog] implementations.
     */
    protected fun hydrateIfEmpty(
        albums: List<Album>? = null,
        artists: List<Artist>? = null,
        albumArtists: List<Artist>? = null,
        genres: List<Genre>? = null,
        playlists: List<Playlist>? = null,
    ) {
        if (albums != null && _albums.value.isEmpty()) _albums.value = albums
        if (artists != null && _artists.value.isEmpty()) _artists.value = artists
        if (albumArtists != null && _albumArtists.value.isEmpty()) _albumArtists.value = albumArtists
        if (genres != null && _genres.value.isEmpty()) _genres.value = genres
        if (playlists != null && _playlists.value.isEmpty()) _playlists.value = playlists
    }

    /** Clear [refreshError] — call when resetting source state on logout or partition switch. */
    protected fun clearRefreshError() {
        _refreshError.value = null
    }
}
