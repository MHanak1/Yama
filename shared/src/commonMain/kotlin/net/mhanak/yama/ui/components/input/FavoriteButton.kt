package net.mhanak.yama.ui.components.input

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.ui.components.state.rememberTrackFavorite

/**
 * The reusable favourite control — drop it next to any [FavoritableKind] item (a track in the
 * player, an album/artist on a detail screen, …). It renders a heart that toggles on tap: outlined
 * when not favourited, filled and tinted with the primary colour when it is (matching the player's
 * shuffle/repeat toggles). It renders nothing when the active source can't favourite this kind, or
 * when [itemId] is null, so the control disappears on backends without a favourites concept.
 *
 * State is seeded from [initial] — which callers now read straight off the item model (`favorite`) —
 * and updated optimistically on tap; the write goes through [net.mhanak.yama.coordinators.FavoritesCoordinator.setFavorite],
 * which persists it locally (so it shows offline) and flushes it to the backend. Only when no [initial]
 * is supplied does it fall back to fetching the state for [itemId].
 *
 * [emphasized] switches the rendering from a plain icon (the default — light enough for dense list rows
 * and detail headers) to an expressive [ToggleButton] whose shape morphs round → squarish when
 * favourited. Only the prominent player control opts in; leaving it off keeps every other call site
 * visually unchanged.
 */
@Composable
fun FavoriteButton(
    kind: FavoritableKind,
    itemId: String?,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    initial: Boolean? = null,
    emphasized: Boolean = false,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val supported = remember(source, kind) { (source as? FavoriteCapable)?.supportsFavorites(kind) == true }
    if (!supported || itemId == null) return

    // Track hearts read through the shared TrackUserDataStore — the single live truth across the player,
    // lists and the queue — so the tap is optimistic (setFavorite flips the store, which recomposes
    // this) without any local mirror or per-button fetch. Other kinds (album/artist/genre/playlist) keep
    // their own optimistic local state seeded from the model, falling back to a fetch when no seed.
    if (kind == FavoritableKind.Track) {
        val favorite = rememberTrackFavorite(itemId, fallback = initial ?: false)
        FavoriteIcon(favorite, iconSize, modifier, emphasized) { appContainer.favorites.setFavorite(kind, itemId, !favorite) }
        return
    }

    var favorite by remember(source, itemId) { mutableStateOf(initial ?: false) }
    LaunchedEffect(source, kind, itemId, initial) {
        favorite = initial ?: (source as? FavoriteCapable)?.isFavorite(kind, itemId) ?: false
    }
    FavoriteIcon(favorite, iconSize, modifier, emphasized) {
        val next = !favorite
        favorite = next // optimistic — the write persists locally and is flushed to the backend.
        appContainer.favorites.setFavorite(kind, itemId, next)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavoriteIcon(favorite: Boolean, iconSize: Dp, modifier: Modifier, emphasized: Boolean, onClick: () -> Unit) {
    val icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder
    val description = if (favorite) "Remove favourite" else "Add favourite"
    if (emphasized) {
        // Expressive toggle: the container fills + the shape morphs round → squarish when favourited,
        // so the state reads from the silhouette, not just the heart's fill. contentPadding is zeroed
        // so the icon centres in the caller-sized (48.dp) button rather than adding button padding.
        ToggleButton(
            checked = favorite,
            onCheckedChange = { onClick() },
            modifier = modifier,
            shapes = ToggleButtonDefaults.shapes(),
            // Transparent until favourited so the idle heart matches the surrounding icon buttons;
            // only the active state fills. The checked container keeps the theme default (primary).
            colors = ToggleButtonDefaults.toggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(iconSize))
        }
    } else {
        IconButton(onClick = onClick, modifier = modifier) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
