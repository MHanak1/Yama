package net.mhanak.yama.ui.components.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.card.ItemCard
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.interaction.contentFocusItem
import net.mhanak.yama.ui.components.state.LocalAvailability
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
    // Card width, measured once by the caller off the shelf area's width along the shared library
    // curve ([adaptiveCardWidth]) so cards scale up on larger screens exactly like the library grid —
    // but without the grid's integer column-clamp, so the shelf shows a fractional number of cards (a
    // peek of the next one). Hoisted out of a per-shelf BoxWithConstraints: all shelves are full-width
    // and so share one width, and a BoxWithConstraints per shelf re-ran its subcomposition for every
    // shelf on every frame while the rail's width animated — the source of Home's rail-animation jank.
    cardWidth: Dp,
    onSeeMore: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    // TV D-pad: namespace for this shelf's card focus keys within the Home-wide registry (empty = off).
    focusKeyPrefix: String = "",
) {
    val player = LocalAppContainer.current.playback.viewed
    // Dim items that aren't playable right now (not downloaded and the source unreachable), matching the
    // library grid's graying. This is what lets album-discovery shelves stay visible offline (served from
    // the CatalogCache) instead of disappearing: the stale cards are shown, just grayed.
    val availability = LocalAvailability.current
    // Hoisted so the shelf's horizontal scroll offset survives a navigate-to-detail → back round-trip
    // (Home is disposed while a detail screen is open); paired with per-card focus restore, the card the
    // user left on scrolls back into view and refocuses.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // The item a long-press / right-click has opened the action sheet for (null = no sheet). Kept per
    // shelf: only one card can be long-pressed at a time, so each shelf hosting its own sheet is enough.
    var actionTarget by remember { mutableStateOf<HomeItemAction?>(null) }
    // The shelf outlives the transient sheet, so its scope drives playback started from the sheet — the
    // sheet dismissing (and disposing) mid-fetch would otherwise cancel it. See HomeItemActionSheet.
    val shelfScope = rememberCoroutineScope()

    Column(modifier.fillMaxWidth()) {
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
                        dimmed = !availability.album(album.id),
                        onClick = { onAlbumClick(album.id) },
                        onLongPress = { actionTarget = HomeItemAction.AlbumAction(album) },
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
                        dimmed = !availability.genre(genre.id),
                        onClick = { onGenreClick(genre.id) },
                        onLongPress = { actionTarget = HomeItemAction.GenreAction(genre) },
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
                        dimmed = !availability.track(track.id),
                        // Play the whole shelf as a queue, starting at the tapped track.
                        onClick = { player.playNow(data.tracks, index) },
                        onLongPress = { actionTarget = HomeItemAction.TrackAction(track) },
                    )
                }
            }
        }
    }

    actionTarget?.let { target ->
        HomeItemActionSheet(
            target = target,
            onDismiss = { actionTarget = null },
            playbackScope = shelfScope,
        )
    }
}

/** Namespaced focus key for a shelf card, or null when the shelf isn't focus-tracked (empty prefix). */
private fun shelfFocusKey(prefix: String, id: String): String? =
    if (prefix.isEmpty()) null else "$prefix/$id"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    imageHash: String?,
    fallback: Painter,
    width: Dp,
    onClick: () -> Unit,
    // Long-press (touch/TV) or right-click (desktop) opens the item's action sheet. Null = tap only.
    onLongPress: (() -> Unit)? = null,
    // TV D-pad: the card's registry key (null = untracked). Applied before clickable so the focus
    // target node and the clickable surface are the same node.
    focusKey: String? = null,
    // Grays the card when its item isn't playable right now (offline + not downloaded), matching the grid.
    dimmed: Boolean = false,
) {
    // The tap rides in on contentModifier so it lands inside the Surface and its ripple is clipped to
    // the rounded corners, matching the library grid. When an action sheet is offered, use
    // combinedClickable for the long-press and add a separate secondary-press gesture for desktop
    // right-click (which combinedClickable doesn't cover), mirroring GridCard / TrackListCard.
    val clickModifier = if (onLongPress != null) {
        Modifier
            .contentFocusItem(focusKey)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        onLongPress()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    } else {
        Modifier.contentFocusItem(focusKey).clickable(onClick = onClick)
    }
    // Fixed width sizes the card (outer modifier).
    ItemCard(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.width(width).alpha(if (dimmed) 0.5f else 1f),
        contentModifier = clickModifier,
        image = { CardImage(imageUrl = imageUrl, imageHash = imageHash, imageFallback = fallback) },
    )
}
