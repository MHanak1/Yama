package net.mhanak.yama.ui.components.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.card.ItemCard
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.interaction.contentFocusItem
import net.mhanak.yama.ui.components.library.adaptiveCardWidth
import net.mhanak.yama.ui.home.HomeBlockData
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import yama.shared.generated.resources.folder

/**
 * One horizontal home-screen shelf: a [title] with a "See more" affordance, then a [LazyRow] of the
 * block's contents. The rendered card and its tap action are chosen by the [HomeBlockData] subtype —
 * albums/genres navigate, tracks play in place within the shelf's own list (so tapping the 3rd track
 * starts a queue of the shelf, at index 2).
 *
 * Cards render through the shared [ItemCard], so the shelf matches the library grid's padding, corner
 * radii, and typography exactly. It deliberately stops short of [net.mhanak.yama.ui.components.library.AsyncImageGridCard],
 * which additionally pulls in grid-cell sizing and the multi-select / TV-focus registries that only
 * exist inside a `GridView` — the shelf only needs a plain tap.
 */
@Composable
fun HomeShelf(
    title: String,
    data: HomeBlockData,
    onSeeMore: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    // TV D-pad: namespace for this shelf's card focus keys within the Home-wide registry (empty = off).
    focusKeyPrefix: String = "",
) {
    val player = LocalAppContainer.current.playback.viewed
    // Hoisted so the shelf's horizontal scroll offset survives a navigate-to-detail → back round-trip
    // (Home is disposed while a detail screen is open); paired with per-card focus restore, the card the
    // user left on scrolls back into view and refocuses.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Size the cards off the shelf's own width along the shared library curve ([adaptiveCardWidth]),
    // so they scale up on larger screens exactly like the library grid — but without the grid's
    // integer column-clamp, so the shelf shows a fractional number of cards (a peek of the next one).
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardWidth = adaptiveCardWidth(maxWidth)
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onSeeMore) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "See more")
                }
            }

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (data) {
                    is HomeBlockData.Albums -> items(data.albums, key = { it.id }) { album ->
                        ShelfCard(
                            title = album.name,
                            subtitle = album.albumArtist,
                            imageUrl = album.imageUrl,
                            imageHash = album.imageHash,
                            fallback = painterResource(Res.drawable.album),
                            width = cardWidth,
                            focusKey = shelfFocusKey(focusKeyPrefix, album.id),
                            onClick = { onAlbumClick(album.id) },
                        )
                    }
                    is HomeBlockData.Genres -> items(data.genres, key = { it.id }) { genre ->
                        ShelfCard(
                            title = genre.name,
                            subtitle = null,
                            imageUrl = genre.imageUrl,
                            imageHash = genre.imageHash,
                            fallback = painterResource(Res.drawable.folder),
                            width = cardWidth,
                            focusKey = shelfFocusKey(focusKeyPrefix, genre.id),
                            onClick = { onGenreClick(genre.id) },
                        )
                    }
                    is HomeBlockData.Tracks -> itemsIndexed(data.tracks, key = { _, t -> t.id }) { index, track ->
                        ShelfCard(
                            title = track.name,
                            subtitle = track.artists?.joinToString(", ") ?: track.album,
                            imageUrl = track.imageUrl,
                            imageHash = null,
                            fallback = painterResource(Res.drawable.album),
                            width = cardWidth,
                            focusKey = shelfFocusKey(focusKeyPrefix, track.id),
                            // Play the whole shelf as a queue, starting at the tapped track.
                            onClick = { player.playNow(data.tracks, index) },
                        )
                    }
                }
            }
        }
    }
}

/** Namespaced focus key for a shelf card, or null when the shelf isn't focus-tracked (empty prefix). */
private fun shelfFocusKey(prefix: String, id: String): String? =
    if (prefix.isEmpty()) null else "$prefix/$id"

@Composable
private fun ShelfCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    imageHash: String?,
    fallback: Painter,
    width: Dp,
    onClick: () -> Unit,
    // TV D-pad: the card's registry key (null = untracked). Applied before clickable so the focus
    // target node and the clickable surface are the same node.
    focusKey: String? = null,
) {
    // Fixed width sizes the card (outer modifier); the tap rides in on contentModifier so it lands
    // inside the Surface and its ripple is clipped to the rounded corners, matching the library grid.
    ItemCard(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.width(width),
        contentModifier = Modifier.contentFocusItem(focusKey).clickable(onClick = onClick),
        image = { CardImage(imageUrl = imageUrl, imageHash = imageHash, imageFallback = fallback) },
    )
}
