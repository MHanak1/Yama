package net.mhanak.yama.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
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

/**
 * The kinds of library item that can be multi-selected for batch playback. Playlists and tracks are
 * deliberately excluded — playlists are already a play unit and tracks play individually.
 */
enum class SelectableKind { Album, Artist, Genre }

/**
 * Holds the current multi-selection in [net.mhanak.yama.views.LibraryView]. Selection is single-kind:
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
 * The floating play/shuffle controls shown over the library while a multi-selection is active. A large
 * primary [Shuffle] button with a smaller [PlayArrow] button stacked above it, both glassy — shuffle
 * plays the selected items' tracks in random order, play keeps them in the order the items were picked.
 */
@Composable
fun LibrarySelectionButtons(
    visible: Boolean,
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassFilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play selected")
            }
            GlassFilledIconButton(onClick = onShuffle, modifier = Modifier.size(72.dp)) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = "Shuffle selected",
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
