package net.mhanak.yama.ui.components.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.components.interaction.ContentFocusRegistry
import net.mhanak.yama.ui.components.interaction.LocalContentFocusRegistry
import net.mhanak.yama.ui.components.interaction.RegisterActiveContentFocus
import net.mhanak.yama.ui.components.interaction.contentFocusItem
import net.mhanak.yama.ui.components.image.ImagePrefetch
import net.mhanak.yama.ui.components.settings.SelectableKind
import net.mhanak.yama.ui.components.settings.LocalLibrarySelection
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.card.ItemCard

/**
 * The un-clamped target width for one library card at a given container [maxWidth]. Starts at 100dp
 * and grows gently (1/12 of the width) so cards get larger on bigger screens rather than just adding
 * columns.
 *
 * [GridView] feeds this to [GridCells.Adaptive], which then rounds *up* to a whole number of columns
 * (so an integer count of cards exactly fills the row). [net.mhanak.yama.ui.components.home.HomeShelf]
 * uses the same value directly for its fixed-width cards, deliberately skipping that integer clamp —
 * a horizontal shelf reads better showing a fractional number of cards (a peek of the next one) than
 * snapping to a whole count, and it keeps both surfaces growing along one shared curve.
 */
fun adaptiveCardWidth(maxWidth: Dp): Dp = 100.dp + maxWidth / 12f

@Composable
fun GridView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    state: LazyGridState = rememberLazyGridState(),
    prefetchUrls: List<String?>? = null,
    content: LazyGridScope.() -> Unit
) {
    // On TV, each item card registers its own FocusRequester via contentFocusItem, keyed by item id.
    // The registry records which item was last focused (savedKey, rememberSaveable so it survives
    // the back-stack round-trip) and restores focus to that leaf directly on screen entry — avoiding
    // the group-requestFocus → skip-onEnter → wrong-target problem of the implicit focusRestorer
    // approach. See TvFocus.kt.
    val savedKey = rememberSaveable { mutableStateOf<String?>(null) }
    val registry = remember { ContentFocusRegistry(savedKey) }
    RegisterActiveContentFocus(registry)
    // Keep focusGroup so the rail and search bar remain D-pad-separated from content — but drop
    // focusRestorer and focusRequester since restoration is now handled explicitly via requestRestore().
    BoxWithConstraints(modifier.focusGroup()) {
        CompositionLocalProvider(LocalContentFocusRegistry provides registry) {
            LazyVerticalGrid(
                state = state,
                // Adaptive fits a whole number of columns of at least this width, so cards scale up
                // on larger screens (see [adaptiveCardWidth]) instead of just adding narrow columns.
                columns = GridCells.Adaptive(minSize = adaptiveCardWidth(maxWidth)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = contentPadding.plus(PaddingValues(8.dp)),
                content = content,
            )
        }
    }
    if (prefetchUrls != null) {
        // The image box is the cell width minus the card's 12.dp padding on each side
        // (see GridCard); decode prefetched art at that size so it matches what the card requests.
        val imageInset = with(LocalDensity.current) { 24.dp.roundToPx() }
        ImagePrefetch(
            urls = prefetchUrls,
            lastVisibleIndex = { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
            targetSizePx = {
                val cellWidth = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.width ?: 0
                if (cellWidth > 0) cellWidth - imageInset else 0
            },
        )
    }
}

/**
 * When [selectable] is non-null the card joins the library multi-selection: a long-press or
 * shift+left-click toggles it, and once any item is selected a plain tap toggles instead of opening it.
 * While selection mode is active the card shows a check/empty-circle indicator over its artwork, filled
 * in the primary colour when selected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridCard(
    onClick: () -> Unit = {},
    image: (@Composable BoxScope.() -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    selectable: GridSelection? = null,
    // Dimmed when the item is neither downloaded nor reachable. Navigation stays enabled (you can open
    // a grayed album to download from it) — only the visual is dimmed.
    dimmed: Boolean = false,
    // TV D-pad: the item's id, used to register a per-item FocusRequester for explicit restoration.
    // Null means no focus tracking (e.g. non-TV or items without a stable id).
    focusKey: String? = null,
) {
    val selected = selectable?.selected == true
    // The grid recycles card slots, so keep the shift-click gesture (keyed on Unit, never relaunched)
    // pointing at the *current* item's toggle rather than the one captured when the slot was first laid
    // out — otherwise shift-clicking a recycled card toggles whichever album used to occupy it.
    val onToggle = rememberUpdatedState(selectable?.onToggle)
    // contentFocusItem comes first: focusRequester must precede combinedClickable so the focus target
    // node (created by combinedClickable) is downstream and the requester resolves to it.
    val focusMod = Modifier.contentFocusItem(focusKey)
    val clickModifier = if (selectable != null) {
        focusMod
            .combinedClickable(
                onClick = { if (selectable.active) selectable.onToggle() else onClick() },
                onLongClick = selectable.onToggle,
            )
            // Shift+left-click toggles selection on desktop (where there's no long-press). Handled in a
            // separate gesture so combinedClickable's tap still fires for an unmodified click.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val event = awaitPointerEvent()
                    if (event.type == PointerEventType.Press &&
                        event.buttons.isPrimaryPressed &&
                        event.keyboardModifiers.isShiftPressed
                    ) {
                        onToggle.value?.invoke()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    } else {
        focusMod.combinedClickable(onClick = onClick)
    }
    // Presentation comes from the shared [ItemCard]; this only injects library behaviour. The click /
    // long-press / shift-select / TV-focus chain rides in on contentModifier, so it lands inside the
    // card's Surface and its highlight is clipped to the rounded shape (rather than bleeding into the
    // corners). The selection indicator overlays the artwork slot, and dimming sits on the outer card.
    ItemCard(
        title = title,
        subtitle = subtitle,
        modifier = Modifier.alpha(if (dimmed) 0.5f else 1f),
        contentModifier = clickModifier,
        image = {
            image?.invoke(this)
            if (selectable?.active == true) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(2.dp),
                )
            }
        },
    )
}

/** Selection wiring for one [GridCard]: whether it's [selected], whether selection mode is [active]
 * (so a plain tap toggles rather than opens), and the [onToggle] action. */
data class GridSelection(
    val selected: Boolean,
    val active: Boolean,
    val onToggle: () -> Unit,
)

@Composable
fun AsyncImageGridCard(
    onClick: () -> Unit = {},
    imageUrl: String? = null,
    imageHash: String? = null,
    imageFallback: Painter? = null,
    title: String? = null,
    subtitle: String? = null,
    // When both are provided and a LocalLibrarySelection is present, the card becomes multi-selectable.
    selectableKind: SelectableKind? = null,
    selectionId: String? = null,
    // TV D-pad: the item's stable id for focus registration. Defaults to selectionId so callers that
    // already pass selectionId get focus tracking for free — only pass explicitly when selectionId is absent.
    focusKey: String? = selectionId,
) {
    val selection = LocalLibrarySelection.current
    val gridSelection = if (selection != null && selectableKind != null && selectionId != null) {
        GridSelection(
            selected = selection.isSelected(selectionId),
            active = selection.isActive,
            onToggle = { selection.toggle(selectableKind, selectionId) },
        )
    } else null

    // Dim items that aren't downloaded and aren't currently reachable (per kind). Only known for the
    // kinds the availability snapshot fans out (Album/Artist/Genre); playlists never dim here.
    val dimmed = if (selectableKind != null && selectionId != null)
        !LocalAvailability.current.isPlayable(selectableKind, selectionId) else false

    GridCard(
        onClick = onClick,
        image = {
            CardImage(imageUrl = imageUrl, imageHash = imageHash, imageFallback = imageFallback)
        },
        title = title,
        subtitle = subtitle,
        selectable = gridSelection,
        dimmed = dimmed,
        focusKey = focusKey,
    )
}