package net.mhanak.yama.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.theme.GlassFilledIconButton
import net.mhanak.yama.ui.theme.glassEffect

/**
 * The kinds of library item that can be multi-selected for batch playback. Playlists and tracks are
 * deliberately excluded — playlists are already a play unit and tracks play individually.
 */
enum class SelectableKind { Album, Artist, Genre }

/**
 * Holds the current multi-selection in [net.mhanak.yama.ui.views.LibraryView]. Selection is single-kind:
 * selecting an item of a different [kind] than the current one clears the previous selection (you
 * can't mix albums and artists in one batch). [selectedIds] preserves selection order, so "Play"
 * can honour the order items were picked while "Shuffle" randomises.
 *
 * Provided to the grids via [LocalLibrarySelection] so item cards can toggle themselves without the
 * views threading callbacks all the way down.
 */
@Stable
class LibrarySelectionState {
    var kind by mutableStateOf<SelectableKind?>(null)
        private set

    private val _selectedIds = mutableStateListOf<String>()
    val selectedIds: List<String> get() = _selectedIds

    /** Selection mode is "on" exactly while something is selected; an empty selection exits it. */
    val isActive: Boolean get() = _selectedIds.isNotEmpty()
    val count: Int get() = _selectedIds.size

    fun isSelected(id: String): Boolean = _selectedIds.contains(id)

    fun toggle(kind: SelectableKind, id: String) {
        if (this.kind != kind) {
            _selectedIds.clear()
            this.kind = kind
        }
        if (!_selectedIds.remove(id)) _selectedIds.add(id)
        if (_selectedIds.isEmpty()) this.kind = null
    }

    fun clear() {
        _selectedIds.clear()
        kind = null
    }
}

/** Null when no library grid is hosting a selection (e.g. a grid card used outside the library). */
val LocalLibrarySelection = compositionLocalOf<LibrarySelectionState?> { null }

/**
 * The floating controls shown over the library while a multi-selection is active. A large primary
 * [Shuffle] button with smaller [PlayArrow], favourite, and download buttons stacked above it, all
 * glassy and each captioned with a label to its left — shuffle plays the selected items' tracks in
 * random order, play keeps them in the order the items were picked, the heart toggles all selected
 * items' favourite state, and download enqueues every selected container for offline use. The heart is
 * [Icons.Filled.Favorite] when every selected item is already favourited and
 * [Icons.Outlined.FavoriteBorder] otherwise; it's hidden when the source can't favourite this kind, and
 * the download button is hidden when the source doesn't persist downloads.
 */
@Composable
fun LibrarySelectionButtons(
    visible: Boolean,
    allFavorite: Boolean,
    favoritesSupported: Boolean,
    downloadsSupported: Boolean,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (downloadsSupported) {
                LabeledAction(label = "Download") {
                    GlassFilledIconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "Download selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            if (favoritesSupported) {
                LabeledAction(label = if (allFavorite) "Unfavourite" else "Favourite") {
                    GlassFilledIconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (allFavorite) "Remove selected from favourites" else "Add selected to favourites",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            LabeledAction(label = "Play") {
                GlassFilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play selected", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            LabeledAction(label = "Shuffle") {
                GlassFilledIconButton(onClick = onShuffle, modifier = Modifier.size(72.dp)) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Shuffle selected",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/** A selection action button with a glassy text [label] pill to its left. */
@Composable
private fun LabeledAction(
    label: String,
    button: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .glassEffect(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        button()
    }
}
