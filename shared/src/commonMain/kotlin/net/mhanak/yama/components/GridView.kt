package net.mhanak.yama.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GridView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    state: LazyGridState = rememberLazyGridState(),
    prefetchUrls: List<String?>? = null,
    content: LazyGridScope.() -> Unit
) {
    // On TV, register this grid as the screen's primary focus target so the shell routes screen-entry
    // focus straight into the focusRestorer here (restoring the previously focused item) instead of
    // onto the top search bar. Attaching the requester off TV is harmless — it's only ever requested
    // there. See TvFocus.kt.
    val contentFocus = remember { FocusRequester() }
    RegisterPrimaryContentFocus(contentFocus)
    // focusRestorer/focusRequester must precede focusGroup: a focus target reads focus properties
    // from itself and its ancestors (the modifiers before it), so the restorer only applies to the
    // group when it sits ahead of focusGroup in the chain.
    BoxWithConstraints(modifier.focusRequester(contentFocus).focusRestorer().focusGroup()) {
        LazyVerticalGrid(
            state = state,
            // silly way of making the grid size fit both mobile and desktop
            columns = GridCells.Adaptive(minSize = Dp(100.toFloat() + (maxWidth.value / 12F))),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = contentPadding.plus(PaddingValues(8.dp)),
            content = content,
        )
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
 * A selected card is outlined in the primary colour and, while selection mode is active, shows a
 * check/empty-circle indicator over its artwork.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridCard(
    onClick: () -> Unit = {},
    image: (@Composable BoxScope.() -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    selectable: GridSelection? = null,
) {
    val selected = selectable?.selected == true
    // The grid recycles card slots, so keep the shift-click gesture (keyed on Unit, never relaunched)
    // pointing at the *current* item's toggle rather than the one captured when the slot was first laid
    // out — otherwise shift-clicking a recycled card toggles whichever album used to occupy it.
    val onToggle = rememberUpdatedState(selectable?.onToggle)
    val clickModifier = if (selectable != null) {
        Modifier
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
        Modifier.combinedClickable(onClick = onClick)
    }
    ElevatedCard(
        modifier = clickModifier,
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize()
        ) {
            Box (
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(ratio = 1.0F)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
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
            }

            Spacer(Modifier.height(8.dp))

            Text(
                title ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle!= null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
) {
    val selection = LocalLibrarySelection.current
    val gridSelection = if (selection != null && selectableKind != null && selectionId != null) {
        GridSelection(
            selected = selection.isSelected(selectionId),
            active = selection.isActive,
            onToggle = { selection.toggle(selectableKind, selectionId) },
        )
    } else null

    GridCard(
        onClick = onClick,
        image = {
            CardImage(imageUrl = imageUrl, imageHash = imageHash, imageFallback = imageFallback)
        },
        title = title,
        subtitle = subtitle,
        selectable = gridSelection,
    )
}