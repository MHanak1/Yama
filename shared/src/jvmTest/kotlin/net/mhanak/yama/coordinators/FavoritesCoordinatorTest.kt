package net.mhanak.yama.coordinators

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.mhanak.yama.media.download.CatalogCache
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.InMemoryTrackUserDataStore
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.FavoriteOutbox
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.media.sources.local.LocalLibraryStore
import net.mhanak.yama.media.sources.local.StoredTrack
import net.mhanak.yama.util.StreamingQuality
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [FavoritesCoordinator] — the first logic in Yama testable without Compose, enabled
 * by the [InMemoryTrackUserDataStore] extraction in item 1. All collaborators are plain fakes (no
 * Compose runtime, no Android SDK, no SQLDelight) so these run fast on any JVM.
 */
class FavoritesCoordinatorTest {

    // --- helpers ----------------------------------------------------------------------------------

    private fun tempDir() = Files.createTempDirectory("yama_test").toFile().also { it.deleteOnExit() }
    private fun tempFile(suffix: String = ".json") = File.createTempFile("yama_test", suffix).also { it.deleteOnExit() }

    private fun makeCoordinator(
        source: FakeMusicSource,
        store: InMemoryTrackUserDataStore = InMemoryTrackUserDataStore(),
        cache: CatalogCache = CatalogCache(tempDir()),
        lib: FakeLibraryStore = FakeLibraryStore(),
        outbox: FavoriteOutbox = FavoriteOutbox(tempFile(), { source }),
    ) = FavoritesCoordinator(
        source = { source },
        userData = store,
        catalogCache = cache,
        libraryStore = lib,
        favoriteOutbox = outbox,
    )

    // --- tests ------------------------------------------------------------------------------------

    /**
     * The store write is synchronous and happens before any IO coroutine fires, so the heart reflects
     * the new state immediately after [FavoritesCoordinator.setFavorite] returns — no delay, no
     * coroutine yield needed.
     */
    @Test
    fun `setFavorite writes store synchronously for Track kind`() {
        val store = InMemoryTrackUserDataStore()
        val source = FakeMusicSource(reachable = false, sourceKey = "test-key")
        val coordinator = makeCoordinator(source = source, store = store)

        assertNull(store.current("t1"), "no data before toggle")

        coordinator.setFavorite(FavoritableKind.Track, "t1", true)

        val data = store.current("t1")
        assertNotNull(data)
        assertTrue(data.favorite, "store should reflect favorite=true immediately")
    }

    @Test
    fun `setFavorite preserves existing playCount when toggling favorite`() {
        val store = InMemoryTrackUserDataStore()
        store.set("t1", net.mhanak.yama.media.model.TrackUserData(favorite = false, playCount = 7))
        val source = FakeMusicSource(reachable = false, sourceKey = "test-key")
        val coordinator = makeCoordinator(source = source, store = store)

        coordinator.setFavorite(FavoritableKind.Track, "t1", true)

        assertEquals(7, store.current("t1")?.playCount, "playCount must survive a favourite toggle")
    }

    @Test
    fun `setFavorite for non-Track kind does NOT touch the userData store`() {
        val store = InMemoryTrackUserDataStore()
        val source = FakeMusicSource(reachable = true, sourceKey = "test-key")
        val coordinator = makeCoordinator(source = source, store = store)

        coordinator.setFavorite(FavoritableKind.Album, "album-1", true)

        assertNull(store.current("album-1"), "userData store is Track-only; album toggle must not touch it")
    }

    @Test
    fun setFavorite_writes_catalogCache_and_outbox_offline() {
        val store = InMemoryTrackUserDataStore()
        val cache = CatalogCache(tempDir())
        val source = FakeMusicSource(reachable = false, sourceKey = "test-key")
        val outbox = FavoriteOutbox(tempFile(), { source })
        val coordinator = makeCoordinator(source = source, store = store, cache = cache, outbox = outbox)

        coordinator.setFavorite(FavoritableKind.Track, "t1", true)

        Thread.sleep(200)

        assertTrue(cache.favoriteTrackIds("test-key").contains("t1"))
        assertEquals(true, outbox.pendingTrackFavorites("test-key")["t1"])
    }
}

// --- Fakes ----------------------------------------------------------------------------------------

class FakeMusicSource(
    private val reachable: Boolean,
    private val sourceKey: String?,
    private val supportsFav: Boolean = true,
) : MusicSource, FavoriteCapable, OfflineCapable {
    override val type = SourceType.Jellyfin
    override var isAuthenticated = true
    override val albums = MutableStateFlow<List<Album>>(emptyList())
    override val artists = MutableStateFlow<List<Artist>>(emptyList())
    override val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    override val genres = MutableStateFlow<List<Genre>>(emptyList())
    override val isRefreshing = MutableStateFlow(false)
    override val refreshError = MutableStateFlow<Throwable?>(null)
    override val isReachable: StateFlow<Boolean> = MutableStateFlow(reachable)

    var lastSetFavoriteCall: Triple<FavoritableKind, String, Boolean>? = null

    // OfflineCapable
    override fun downloadSourceKey() = sourceKey
    override suspend fun getContentVersion(trackId: String): String? = null
    override suspend fun fetchTrackSnapshots(ids: List<String>): Map<String, OfflineCapable.TrackSnapshot> = emptyMap()
    override fun hydrateCatalog(snapshot: net.mhanak.yama.media.download.CatalogSnapshot) {}
    override fun supportsFavorites(kind: FavoritableKind) = supportsFav
    override suspend fun isFavorite(kind: FavoritableKind, id: String): Boolean = false
    override suspend fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        lastSetFavoriteCall = Triple(kind, id, favorite)
    }

    override suspend fun refresh() {}
    override suspend fun getTracksForAlbum(albumId: String) = emptyList<Track>()
    override suspend fun getTracksForArtist(artistId: String, limit: Int, offset: Int, sortBy: TrackSortOrder) = emptyList<Track>()
    override suspend fun getTracksForGenre(genreId: String, limit: Int, offset: Int, sortBy: TrackSortOrder) = emptyList<Track>()
    override suspend fun getTracksForPlaylist(playlistId: String) = emptyList<Track>()
    override suspend fun getAlbumsForArtist(artistId: String) = emptyList<Album>()
    override suspend fun getAlbumsForGenre(genreId: String) = emptyList<Album>()
    override suspend fun getStreamUrl(trackId: String, quality: StreamingQuality?) = ""
    override suspend fun getArtworkUrl(trackId: String) = null
    override suspend fun getLyrics(trackId: String) = Lyrics.None
}

class FakeLibraryStore : LocalLibraryStore {
    private val rows = mutableMapOf<String, StoredTrack>()

    override fun all(sourceKey: String) = rows.values.filter { it.sourceKey == sourceKey }
    override fun replaceAll(sourceKey: String, tracks: List<StoredTrack>) {
        rows.entries.removeIf { it.value.sourceKey == sourceKey }
        tracks.forEach { rows[it.id] = it }
    }
    override fun get(id: String) = rows[id]
    override fun get(sourceKey: String, id: String) = rows[id]?.takeIf { it.sourceKey == sourceKey }
    override fun put(track: StoredTrack) { rows[track.id] = track }
    override fun remove(sourceKey: String, ids: Collection<String>) { ids.forEach { rows.remove(it) } }
}
