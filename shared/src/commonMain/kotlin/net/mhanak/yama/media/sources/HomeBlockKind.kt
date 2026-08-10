package net.mhanak.yama.media.sources

/**
 * The data tier a [HomeBlockKind] draws from. Tier drives two things at once: which capability a
 * source needs to back the block, and how the block behaves offline.
 *
 * - [CatalogAlbums]/[Favourites]/[Genres] — derived client-side from the hydrated browse StateFlows
 *   (`albums`/`genres`), which persist to disk via CatalogCache, so these survive offline for free.
 * - [TrackDiscovery] — served through `CatalogReader.getAllTracks`, which degrades to the downloaded
 *   subset when offline (every sort order supported there).
 * - [AlbumDiscovery] — a live `MusicSource.getAlbums` query with no result cache; **hidden offline**.
 * - [Downloads] — read straight from the downloads index; inherently offline.
 */
enum class HomeBlockTier { CatalogAlbums, Favourites, Genres, TrackDiscovery, AlbumDiscovery, Downloads }

/**
 * One kind of home-screen content block. The enum is the shared vocabulary between the source layer
 * ([MusicSource.supportedHomeBlocks]), persistence (the per-source ordered layout stored in
 * AppPreferences), and the UI loaders — so it deliberately lives in the low `media.sources` layer and
 * carries no UI or coroutine dependencies. The concrete data-fetching lives in the `ui/home` loaders.
 *
 * Declaration order doubles as the default layout order for a fresh source.
 *
 * Optional [trackSort]/[albumSort] link a block to the discovery ordering it maps to, shared by both
 * the loaders and the "See more" pages.
 */
enum class HomeBlockKind(
    val title: String,
    val tier: HomeBlockTier,
    val trackSort: TrackSortOrder? = null,
    val albumSort: AlbumSortOrder? = null,
) {
    RecentlyAddedAlbums("Recently added", HomeBlockTier.AlbumDiscovery, albumSort = AlbumSortOrder.RecentlyAdded),
    RecentlyPlayedTracks("Recently played", HomeBlockTier.TrackDiscovery, trackSort = TrackSortOrder.RecentlyPlayed),
    MostPlayedAlbums("Most played albums", HomeBlockTier.AlbumDiscovery, albumSort = AlbumSortOrder.MostPlayed),
    MostPlayedTracks("Most played tracks", HomeBlockTier.TrackDiscovery, trackSort = TrackSortOrder.PlayCount),
    FavouriteAlbums("Favourite albums", HomeBlockTier.Favourites),
    RandomAlbums("Random albums", HomeBlockTier.CatalogAlbums),
    BrowseGenres("Browse by genre", HomeBlockTier.Genres),
    DownloadedAlbums("Downloaded", HomeBlockTier.Downloads);

    /** Tier-C album-discovery blocks issue an uncached live query, so they disappear when offline. */
    val hiddenWhenOffline: Boolean get() = tier == HomeBlockTier.AlbumDiscovery
}
