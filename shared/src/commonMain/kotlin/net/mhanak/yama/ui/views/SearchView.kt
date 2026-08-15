package net.mhanak.yama.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.ui.components.card.ItemCard
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.interaction.ContentFocusRegistry
import net.mhanak.yama.ui.components.interaction.LocalContentFocusRegistry
import net.mhanak.yama.ui.components.interaction.RegisterActiveContentFocus
import net.mhanak.yama.ui.components.interaction.contentFocusItem
import net.mhanak.yama.ui.components.library.TrackListCard
import net.mhanak.yama.ui.components.library.adaptiveCardWidth
import net.mhanak.yama.ui.screens.AlbumDetailRoute
import net.mhanak.yama.ui.screens.ArtistDetailRoute
import net.mhanak.yama.ui.screens.GenreDetailRoute
import net.mhanak.yama.ui.screens.PlaylistDetailRoute
import net.mhanak.yama.ui.theme.glassSource
import net.mhanak.yama.util.fuzzyFilterSort
import net.mhanak.yama.util.fuzzyScore
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import yama.shared.generated.resources.artist
import yama.shared.generated.resources.folder
import yama.shared.generated.resources.library_music

// How many results to surface per section (a compact overview, not the full list).
private const val TRACK_LIMIT = 6
private const val CARD_LIMIT = 12
// How many tracks to pull from the backend before client-side re-ranking.
private const val TRACK_FETCH = 30

/**
 * Global search *body*: one query, results across tracks, albums, artists, genres and playlists at
 * once, laid out as stacked sections (tracks as full rows, the rest as horizontal card shelves).
 *
 * The search field itself lives in the shared, hoisted [net.mhanak.yama.ui.components.navigation.HomeLibraryTopBar]
 * (rendered once in [net.mhanak.yama.ui.screens.MainScreen] and overlaid above this body), so the same
 * bar element persists across Home → Search and only this body animates in beneath it. This view is
 * therefore given the live [query] rather than owning it, and reserves [topContentPadding] for the bar.
 *
 * Tracks are fetched from the active source (server-backed, with [net.mhanak.yama.coordinators.CatalogReader]'s
 * offline fallback) on a debounced query, then re-ranked client-side for ordering consistency. The
 * other types filter the already-loaded browse flows in memory via [fuzzyFilterSort] — the same data
 * the library grid tabs read, so nothing extra is fetched.
 */
@Composable
fun SearchView(
    query: String,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

    // Debounced, source-backed track search. The effect is keyed on [query], so a fresh keystroke
    // cancels the previous run mid-delay and restarts it — the 250ms delay *is* the debounce. Also
    // keyed on [source] so switching backends re-runs the current query against the new one.
    LaunchedEffect(source, query) {
        val q = query.trim()
        if (q.isBlank()) {
            tracks = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val fetched = runCatching {
            appContainer.catalog.getAllTracks(
                limit = TRACK_FETCH, offset = 0,
                sortBy = TrackSortOrder.Alphabetical, searchTerm = q,
            )
        }.getOrDefault(emptyList())
        // Re-rank (don't filter) the backend's matches so ordering is consistent with the fuzzy
        // in-memory sections; the server decided recall, we only reorder.
        tracks = fetched.sortedByDescending { t ->
            (listOf(t.name, t.album ?: "") + (t.artists ?: emptyList()))
                .mapNotNull { fuzzyScore(q, it) }.maxOrNull() ?: -1
        }
    }

    val albums by source.albums.collectAsState()
    val artists by source.artists.collectAsState()
    val genres by source.genres.collectAsState()
    val playlists by source.playlists.collectAsState()

    // Each section carries its best match score so the whole screen can order shelves by relevance —
    // e.g. "Dune" surfaces the album shelf first, "Duke" the artist shelf. The best score is the
    // top-ranked item's, since fuzzyFilterSort already orders each section internally.
    val albumSection = remember(albums, query) {
        scoredSection(albums, query, key = { it.name }, extraKeys = { listOfNotNull(it.albumArtist) })
    }
    val artistSection = remember(artists, query) { scoredSection(artists, query, key = { it.name }) }
    val genreSection = remember(genres, query) { scoredSection(genres, query, key = { it.name }) }
    val playlistSection = remember(playlists, query) { scoredSection(playlists, query, key = { it.name }) }
    val trackMatches = remember(tracks) { tracks.take(TRACK_LIMIT) }
    val trackScore = remember(trackMatches, query) {
        trackMatches.firstOrNull()?.let { t ->
            (listOf(t.name, t.album ?: "") + (t.artists ?: emptyList()))
                .mapNotNull { fuzzyScore(query, it) }.maxOrNull()
        } ?: Int.MIN_VALUE
    }

    val allEmpty = trackMatches.isEmpty() && albumSection.items.isEmpty() && artistSection.items.isEmpty() &&
        genreSection.items.isEmpty() && playlistSection.items.isEmpty()

    // Padding that reserves the hoisted search bar's height at the top and the mini-player at the bottom.
    val edgePadding = PaddingValues(top = topContentPadding, bottom = bottomContentPadding)

    // TV D-pad: results register per-item FocusRequesters here (see [contentFocusItem]) so D-pad-down
    // from the hoisted search bar can land focus on the first result. The shared bar drives this via
    // MainScreen's onSearchFocusDown → registry.requestRestore(). Off TV this is inert.
    val savedFocusKey = rememberSaveable { mutableStateOf<String?>(null) }
    val focusRegistry = remember { ContentFocusRegistry(savedFocusKey) }
    RegisterActiveContentFocus(focusRegistry)

    CompositionLocalProvider(LocalContentFocusRegistry provides focusRegistry) {
    // Opaque screen background (matching a normal screen's base) so the results/hint stay readable
    // while the transition holds the Home/Library screen stationary behind this one — without it the
    // transparent body would render over that still backdrop. glassSource makes the hoisted bar frost
    // this content as it scrolls under it, the same as Home/Library (zIndex=1, above the app backdrop).
    Box(
        modifier.fillMaxSize()
            .glassSource(zIndex = 1f)
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            query.isBlank() -> HintBox(edgePadding, "Search tracks, albums, artists and more")
            allEmpty -> NoSearchResults(query = query, contentPadding = edgePadding)
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val cardWidth = adaptiveCardWidth(maxWidth)

                // Build each section as data (best score + an emitter), then order the shelves by that
                // score so the closest-matching type leads. `prefOrder` only breaks score ties, keeping
                // a stable Tracks→Artists→Albums→Genres→Playlists fallback.
                val sections = buildList {
                    if (trackMatches.isNotEmpty()) add(SearchSection(prefOrder = 0, score = trackScore) {
                        item(key = "h_tracks") { SectionHeader("Tracks") }
                        itemsIndexed(trackMatches, key = { _, t -> "track_${t.id}" }) { index, track ->
                            TrackListCard(
                                track = track,
                                tracks = trackMatches,
                                index = index,
                                player = appContainer.playback.viewed,
                                focusKey = "track_${track.id}",
                                modifier = Modifier.padding(horizontal = 16.dp),
                                image = { CardImage(imageUrl = track.imageUrl) },
                            )
                        }
                    })
                    if (artistSection.items.isNotEmpty()) add(SearchSection(1, artistSection.score) {
                        item(key = "h_artists") { SectionHeader("Artists") }
                        item(key = "r_artists") {
                            CardRow(artistSection.items, resetKey = query, key = { it.id }) { artist ->
                                SearchCard(
                                    focusKey = "artist_${artist.id}",
                                    title = artist.name,
                                    subtitle = null,
                                    imageUrl = artist.imageUrl,
                                    imageHash = artist.imageHash,
                                    fallback = painterResource(Res.drawable.artist),
                                    width = cardWidth,
                                    onClick = { onNavigate(ArtistDetailRoute(artist.id)) },
                                )
                            }
                        }
                    })
                    if (albumSection.items.isNotEmpty()) add(SearchSection(2, albumSection.score) {
                        item(key = "h_albums") { SectionHeader("Albums") }
                        item(key = "r_albums") {
                            CardRow(albumSection.items, resetKey = query, key = { it.id }) { album ->
                                SearchCard(
                                    focusKey = "album_${album.id}",
                                    title = album.name,
                                    subtitle = album.albumArtist,
                                    imageUrl = album.imageUrl,
                                    imageHash = album.imageHash,
                                    fallback = painterResource(Res.drawable.album),
                                    width = cardWidth,
                                    onClick = { onNavigate(AlbumDetailRoute(album.id)) },
                                )
                            }
                        }
                    })
                    if (genreSection.items.isNotEmpty()) add(SearchSection(3, genreSection.score) {
                        item(key = "h_genres") { SectionHeader("Genres") }
                        item(key = "r_genres") {
                            CardRow(genreSection.items, resetKey = query, key = { it.id }) { genre ->
                                SearchCard(
                                    focusKey = "genre_${genre.id}",
                                    title = genre.name,
                                    subtitle = null,
                                    imageUrl = genre.imageUrl,
                                    imageHash = genre.imageHash,
                                    fallback = painterResource(Res.drawable.folder),
                                    width = cardWidth,
                                    onClick = { onNavigate(GenreDetailRoute(genre.id)) },
                                )
                            }
                        }
                    })
                    if (playlistSection.items.isNotEmpty()) add(SearchSection(4, playlistSection.score) {
                        item(key = "h_playlists") { SectionHeader("Playlists") }
                        item(key = "r_playlists") {
                            CardRow(playlistSection.items, resetKey = query, key = { it.id }) { playlist ->
                                SearchCard(
                                    focusKey = "playlist_${playlist.id}",
                                    title = playlist.name,
                                    subtitle = null,
                                    imageUrl = playlist.imageUrl,
                                    imageHash = playlist.imageHash,
                                    fallback = painterResource(Res.drawable.library_music),
                                    width = cardWidth,
                                    onClick = { onNavigate(PlaylistDetailRoute(playlist.id)) },
                                )
                            }
                        }
                    })
                }
                val ordered = sections.sortedWith(
                    compareByDescending<SearchSection> { it.score }.thenBy { it.prefOrder },
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = topContentPadding + 8.dp,
                        bottom = bottomContentPadding + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ordered.forEach { it.content(this) }
                }
            }
        }
    }
    }
}

/** One search-result section: [items] to show, its best-match [score] (drives shelf ordering), and
 *  [prefOrder] as the tie-break for equal scores. */
private class ScoredSection<T>(val items: List<T>, val score: Int)

/** Filter+rank [list] and capture the top match's score, so the section can be ordered against others. */
private fun <T> scoredSection(
    list: List<T>,
    query: String,
    key: (T) -> String,
    extraKeys: (T) -> List<String> = { emptyList() },
): ScoredSection<T> {
    val sorted = list.fuzzyFilterSort(query, key, extraKeys).take(CARD_LIMIT)
    val best = sorted.firstOrNull()?.let {
        (listOf(key(it)) + extraKeys(it)).mapNotNull { k -> fuzzyScore(query, k) }.maxOrNull()
    } ?: Int.MIN_VALUE
    return ScoredSection(sorted, best)
}

/** A renderable section: its relevance [score], stable [prefOrder] tie-break, and lazy-list [content]. */
private class SearchSection(
    val prefOrder: Int,
    val score: Int,
    val content: LazyListScope.() -> Unit,
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** A horizontal shelf of result cards for one non-track section. A fresh [LazyListState] is created
 *  whenever [resetKey] (the query) changes, so switching searches scrolls the shelf back to the start. */
@Composable
private fun <T> CardRow(
    entries: List<T>,
    resetKey: Any?,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    val state = remember(resetKey) { LazyListState() }
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = key) { card(it) }
    }
}

/** A fixed-width result card — mirrors HomeShelf's ShelfCard so search matches the library grid look.
 *  [focusKey] registers the card with the screen's [ContentFocusRegistry] for TV D-pad navigation
 *  (contentFocusItem must precede the clickable so the focus target and click surface are one node). */
@Composable
private fun SearchCard(
    focusKey: String,
    title: String,
    subtitle: String?,
    imageUrl: String?,
    imageHash: String?,
    fallback: Painter,
    width: Dp,
    onClick: () -> Unit,
) {
    ItemCard(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.width(width),
        contentModifier = Modifier.contentFocusItem(focusKey).clickable(onClick = onClick),
        image = { CardImage(imageUrl = imageUrl, imageHash = imageHash, imageFallback = fallback) },
    )
}

@Composable
private fun HintBox(contentPadding: PaddingValues, text: String) {
    Box(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
