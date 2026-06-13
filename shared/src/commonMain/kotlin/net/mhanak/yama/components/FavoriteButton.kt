package net.mhanak.yama.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.FavoritableKind

/**
 * The reusable favourite control — drop it next to any [FavoritableKind] item (a track in the
 * player, an album/artist on a detail screen, …). It renders a heart that toggles on tap: outlined
 * when not favourited, filled and tinted with the primary colour when it is (matching the player's
 * shuffle/repeat toggles). It renders nothing when the active source can't favourite this kind, or
 * when [itemId] is null, so the control disappears on backends without a favourites concept.
 *
 * State is fetched once per [itemId] and updated optimistically on tap (the network write is fire-
 * and-forget, re-synced on the next fetch); pass [initial] from a caller that already knows the
 * value to skip the fetch.
 */
@Composable
fun FavoriteButton(
    kind: FavoritableKind,
    itemId: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    initial: Boolean? = null,
) {
    val source = LocalAppContainer.current.activeMusicSource
    val supported = remember(source, kind) { source.supportsFavorites(kind) }
    if (!supported || itemId == null) return

    val scope = rememberCoroutineScope()
    var favorite by remember(source, itemId) { mutableStateOf(initial ?: false) }

    // Pull the current state when the item (or source) changes, unless the caller seeded it.
    LaunchedEffect(source, kind, itemId) {
        if (initial == null) favorite = source.isFavorite(kind, itemId)
    }

    IconButton(
        onClick = {
            val next = !favorite
            favorite = next // optimistic — the write is best-effort and re-synced on the next fetch.
            scope.launch { source.setFavorite(kind, itemId, next) }
        },
        modifier = modifier,
    ) {
        Icon(
            if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (favorite) "Remove favourite" else "Add favourite",
            tint = if (favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
        )
    }
}
