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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.FavoritableKind

/**
 * The reusable favourite control — drop it next to any [FavoritableKind] item (a track in the
 * player, an album/artist on a detail screen, …). It renders a heart that toggles on tap: outlined
 * when not favourited, filled and tinted with the primary colour when it is (matching the player's
 * shuffle/repeat toggles). It renders nothing when the active source can't favourite this kind, or
 * when [itemId] is null, so the control disappears on backends without a favourites concept.
 *
 * State is seeded from [initial] — which callers now read straight off the item model (`favorite`) —
 * and updated optimistically on tap; the write goes through [net.mhanak.yama.AppContainer.setFavorite],
 * which persists it locally (so it shows offline) and flushes it to the backend. Only when no [initial]
 * is supplied does it fall back to fetching the state for [itemId].
 */
@Composable
fun FavoriteButton(
    kind: FavoritableKind,
    itemId: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    initial: Boolean? = null,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val supported = remember(source, kind) { source.supportsFavorites(kind) }
    if (!supported || itemId == null) return

    var favorite by remember(source, itemId) { mutableStateOf(initial ?: false) }

    // Seed from the caller-supplied value (now carried on the model), only falling back to a fetch when
    // none was passed.
    LaunchedEffect(source, kind, itemId, initial) {
        favorite = initial ?: source.isFavorite(kind, itemId)
    }

    IconButton(
        onClick = {
            val next = !favorite
            favorite = next // optimistic — the write persists locally and is flushed to the backend.
            appContainer.setFavorite(kind, itemId, next)
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
