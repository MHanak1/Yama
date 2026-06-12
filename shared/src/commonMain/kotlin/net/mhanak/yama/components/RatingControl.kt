package net.mhanak.yama.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.RateableKind
import net.mhanak.yama.media.sources.Rating
import net.mhanak.yama.media.sources.RatingStyle

/**
 * The reusable rating / favourite control — drop it next to any [RateableKind] item (a track in the
 * player, an album/artist on a detail screen, …) and it adapts to whatever the active source supports:
 *
 * - [RatingStyle.Favorite] → a heart that toggles on tap (outlined when not favourited, filled when it
 *   is), e.g. Jellyfin.
 * - [RatingStyle.Stars] → a star that opens a bottom sheet to pick a 1..max rating, e.g. Subsonic.
 * - [RatingStyle.None] (or a null [itemId]) → renders nothing, so the control disappears on backends
 *   that can't rate this kind of item.
 *
 * State is fetched once per [itemId] and updated optimistically on change (the network write is fire-
 * and-forget); pass [initial] from a caller that already knows the value to skip the fetch. The active
 * state is tinted with the primary colour to match the player's shuffle/repeat toggles.
 */
@Composable
fun RatingControl(
    kind: RateableKind,
    itemId: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    initial: Rating? = null,
) {
    val source = LocalAppContainer.current.activeMusicSource
    val style = remember(source, kind) { source.ratingStyle(kind) }
    if (style is RatingStyle.None || itemId == null) return

    val scope = rememberCoroutineScope()
    var rating by remember(source, itemId) { mutableStateOf(initial ?: Rating.Unrated) }
    var showStarPicker by remember { mutableStateOf(false) }

    // Pull the current state when the item (or source) changes, unless the caller seeded it.
    LaunchedEffect(source, kind, itemId) {
        if (initial == null) rating = source.getRating(kind, itemId)
    }

    fun persist(next: Rating) {
        rating = next // optimistic — the write is best-effort and re-synced on the next fetch.
        scope.launch { source.setRating(kind, itemId, next) }
    }

    val active = when (style) {
        RatingStyle.Favorite -> rating.favorite
        is RatingStyle.Stars -> (rating.stars ?: 0) > 0
        RatingStyle.None -> false
    }
    val tint = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    val icon: ImageVector
    val description: String
    when (style) {
        RatingStyle.Favorite -> {
            icon = if (active) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
            description = if (active) "Remove favourite" else "Add favourite"
        }
        is RatingStyle.Stars -> {
            icon = if (active) Icons.Filled.Star else Icons.Outlined.StarBorder
            description = "Rate"
        }
        RatingStyle.None -> return
    }

    IconButton(
        onClick = {
            when (style) {
                RatingStyle.Favorite -> persist(rating.copy(favorite = !rating.favorite))
                is RatingStyle.Stars -> showStarPicker = true
                RatingStyle.None -> Unit
            }
        },
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }

    if (style is RatingStyle.Stars && showStarPicker) {
        StarRatingSheet(
            max = style.max,
            current = rating.stars ?: 0,
            onSelect = { stars ->
                persist(rating.copy(stars = stars.takeIf { it > 0 }))
                showStarPicker = false
            },
            onDismiss = { showStarPicker = false },
        )
    }
}

/**
 * A plain Material bottom sheet of tappable stars. Tapping star *n* sets the rating to *n*; tapping
 * the current rating again clears it. (Deliberately the stock [ModalBottomSheet], not the glass one,
 * which is currently broken.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StarRatingSheet(
    max: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Rate", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (i in 1..max) {
                    val filled = i <= current
                    IconButton(onClick = { onSelect(if (current == i) 0 else i) }) {
                        Icon(
                            if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "$i ${if (i == 1) "star" else "stars"}",
                            tint = if (filled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}
