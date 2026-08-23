package net.mhanak.yama.ui.home

import net.mhanak.yama.AppContainer
import net.mhanak.yama.media.download.DownloadedAlbum
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.HomeBlockKind
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.util.AppPreferences

/** How many items a home shelf shows before deferring the rest to its "See more" page. */
const val HOME_SHELF_LIMIT = 20

/** The per-source-account key the home layout is persisted under (mirrors AppContainer's scrobbleKey). */
fun homeConfigKey(source: MusicSource): String =
    (source as? OfflineCapable)?.downloadSourceKey() ?: "local"

/** The default layout for a source: everything it supports, in [HomeBlockKind] declaration order. */
fun defaultHomeBlocks(source: MusicSource): List<HomeBlockKind> = source.supportedHomeBlocks.toList()

/**
 * The blocks to show for [source] right now: the user's saved layout if any, else the default — with
 * anything the source no longer supports filtered out (capabilities can change across logins/versions).
 */
fun resolveHomeBlocks(source: MusicSource): List<HomeBlockKind> {
    val supported = source.supportedHomeBlocks
    val configured = AppPreferences.homeBlocks(homeConfigKey(source)) ?: defaultHomeBlocks(source)
    return configured.filter { it in supported }
}

/**
 * The single source of truth for "what the home screen shows now", shared by HomeView's rendering and
 * HomeContentStore's loading so they never disagree. Deliberately independent of reachability: the block
 * *set* stays stable across going online/offline so the layout never reflows on a connection change.
 * Album-discovery shelves survive offline by serving their last-seen albums from [CatalogCache]
 * (see [load]); unreachable/unavailable items are grayed by the shelf cards, not dropped.
 */
fun activeHomeBlocks(source: MusicSource): List<HomeBlockKind> = resolveHomeBlocks(source)

/**
 * The loaded contents of a home block, tagged by the card type its shelf renders. Keeping the three
 * shapes explicit (rather than a bag of `Any`) lets [net.mhanak.yama.ui.components.home.HomeShelf]
 * `when` over them exhaustively.
 */
sealed interface HomeBlockData {
    val isEmpty: Boolean

    data class Albums(val albums: List<Album>) : HomeBlockData {
        override val isEmpty get() = albums.isEmpty()
    }

    data class Tracks(val tracks: List<Track>) : HomeBlockData {
        override val isEmpty get() = tracks.isEmpty()
    }

    data class Genres(val genres: List<Genre>) : HomeBlockData {
        override val isEmpty get() = genres.isEmpty()
    }
}

/**
 * Load a block's shelf contents for the active source. This is the single place the block-tier →
 * data-path mapping lives (see [net.mhanak.yama.media.sources.HomeBlockTier]):
 * - CatalogAlbums/Favourites/Genres — filter/shuffle the already-hydrated browse StateFlows (offline-safe).
 * - TrackDiscovery — through [AppContainer.catalog], so it degrades to the downloaded subset offline
 *   rather than hitting the source directly and throwing.
 * - AlbumDiscovery — a live [net.mhanak.yama.media.sources.MusicSource.getAlbums] query, read-through
 *   [net.mhanak.yama.media.download.CatalogCache] (see [loadAlbumDiscovery]): online it fetches and
 *   writes through; offline (or on cold start before the socket connects) it serves the last-seen list,
 *   so the shelf never vanishes and the layout never reflows on a connection change.
 * - Downloads — straight from the downloads index, always available offline.
 */
suspend fun HomeBlockKind.load(appContainer: AppContainer, limit: Int = HOME_SHELF_LIMIT): HomeBlockData {
    val source = appContainer.activeMusicSource
    return when (this) {
        HomeBlockKind.RecentlyAddedAlbums, HomeBlockKind.MostPlayedAlbums ->
            loadAlbumDiscovery(appContainer, source, this, limit)
        HomeBlockKind.RandomAlbums ->
            HomeBlockData.Albums(source.albums.value.shuffled().take(limit))
        HomeBlockKind.FavouriteAlbums ->
            HomeBlockData.Albums(source.albums.value.filter { it.favorite }.take(limit))
        HomeBlockKind.BrowseGenres ->
            HomeBlockData.Genres(source.genres.value.take(limit))
        HomeBlockKind.RecentlyPlayedTracks ->
            HomeBlockData.Tracks(appContainer.catalog.getAllTracks(limit, 0, TrackSortOrder.RecentlyPlayed))
        HomeBlockKind.MostPlayedTracks ->
            HomeBlockData.Tracks(appContainer.catalog.getAllTracks(limit, 0, TrackSortOrder.PlayCount))
        HomeBlockKind.DownloadedAlbums -> {
            val key = (source as? OfflineCapable)?.downloadSourceKey()
                ?: return HomeBlockData.Albums(emptyList())
            HomeBlockData.Albums(appContainer.downloads.downloadedAlbums(key).take(limit).map { it.toAlbum() })
        }
    }
}

/**
 * Load an album-discovery shelf read-through [net.mhanak.yama.media.download.CatalogCache], mirroring
 * [net.mhanak.yama.coordinators.CatalogReader.tracksFor]: reachable → fetch from the source and write
 * the result through to the cache (so a later offline / cold-start load works); otherwise (offline, or
 * the fetch failed/returned empty) → serve the cached last-seen list. Keyed per source partition by the
 * block's name. Returns empty only when there is neither a live result nor a cached one (a first-ever
 * launch of that shelf while offline).
 */
private suspend fun loadAlbumDiscovery(
    appContainer: AppContainer,
    source: MusicSource,
    kind: HomeBlockKind,
    limit: Int,
): HomeBlockData.Albums {
    val key = (source as? OfflineCapable)?.downloadSourceKey()
    if (source.isReachable.value) {
        val fresh = runCatching { source.getAlbums(kind.albumSort!!, limit) }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            if (key != null) appContainer.catalogCache.saveHomeShelf(key, kind.name, fresh)
            return HomeBlockData.Albums(fresh)
        }
    }
    val cached = key?.let { appContainer.catalogCache.loadHomeShelf(it, kind.name) }
    return HomeBlockData.Albums(cached?.take(limit) ?: emptyList())
}

/** Adapt a downloads-index album to the shared [Album] model so shelves render it like any other. */
private fun DownloadedAlbum.toAlbum(): Album = Album(
    id = id,
    name = name,
    albumArtist = artist,
    year = null,
    songCount = trackCount,
    imageUrl = artworkPath,
    imageHash = null,
)
