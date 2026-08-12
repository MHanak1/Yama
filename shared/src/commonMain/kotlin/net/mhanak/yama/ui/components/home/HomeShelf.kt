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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.card.ItemCard
import net.mhanak.yama.ui.components.image.CardImage
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
) {
    val player = LocalAppContainer.current.playback.viewed

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
                            // Play the whole shelf as a queue, starting at the tapped track.
                            onClick = { player.playNow(data.tracks, index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    imageHash: String?,
    fallback: Painter,
    width: Dp,
    onClick: () -> Unit,
) {
    // Fixed width sizes the card (outer modifier); the tap rides in on contentModifier so it lands
    // inside the Surface and its ripple is clipped to the rounded corners, matching the library grid.
    ItemCard(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.width(width),
        contentModifier = Modifier.clickable(onClick = onClick),
        image = { CardImage(imageUrl = imageUrl, imageHash = imageHash, imageFallback = fallback) },
    )
}
