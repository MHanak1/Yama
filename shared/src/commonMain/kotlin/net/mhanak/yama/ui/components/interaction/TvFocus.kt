package net.mhanak.yama.ui.components.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import net.mhanak.yama.LocalIsTvMode

/**
 * Explicit focus save/restore for TV D-pad content navigation.
 *
 * The implicit Compose focus machinery (focusRestorer + onEnter redirects + ComposeUiFlags) cannot
 * reliably restore focus to a specific grid/list item on Android TV for two structural reasons:
 *
 * 1. focusRestorer requires stable item keys in LazyGrid/LazyList — without them it has no anchor
 *    to save and restore focus against.
 * 2. Programmatic requestFocus() on a focus *group* skips the group's onEnter redirect and lands
 *    on the first focusable in the tree (the search bar), not the content.
 *
 * This approach is fully explicit: each item card registers a per-item FocusRequester keyed by
 * its id, and writes that key back to a rememberSaveable when focused. On screen entry the shell
 * calls [ActiveContentFocus.registry]?.requestRestore(), which requestFocus()es the exact saved
 * leaf directly — no group redirect, no focusRestorer, no global flag.
 *
 * Call sites:
 *  - GridView / ListView: create a [ContentFocusRegistry], call [RegisterActiveContentFocus],
 *    provide [LocalContentFocusRegistry] to their item content.
 *  - Cards (GridCard, ListCard, TrackListCard): apply [Modifier.contentFocusItem] with the item key.
 *  - Shell (MainScreen): remember [ActiveContentFocus], provide via [LocalActiveContentFocus],
 *    call registry?.requestRestore() on nav entry, now-playing up-exit, and search-bar down.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Per-screen registry
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tracks the focused item for a single grid or list. One instance lives per GridView / ListView
 * and is wired into the composition via [LocalContentFocusRegistry].
 *
 * @param savedKey holds the key of the last focused item. Pass a
 *   [androidx.compose.runtime.saveable.rememberSaveable]-backed state so the key survives a
 *   navigate-to-detail → back round-trip and the same item is restored.
 */
@Stable
class ContentFocusRegistry(private val savedKey: MutableState<String?>) {
    // Maintains insertion order (composition order ≈ visual order) so firstOrNull() gives the top item.
    private val items = LinkedHashMap<String, FocusRequester>()

    fun register(key: String, fr: FocusRequester) {
        items[key] = fr
    }

    fun unregister(key: String) {
        items.remove(key)
    }

    /** Called from [Modifier.contentFocusItem]'s onFocusChanged — records which item the user landed on. */
    fun onFocused(key: String) {
        savedKey.value = key
    }

    /**
     * Focuses the previously focused item if it is still visible, or the first visible item on a
     * cold launch. No-ops silently when no items are registered yet (too early, empty list, etc.).
     */
    fun requestRestore() {
        val key = savedKey.value
        val fr = (if (key != null) items[key] else null) ?: items.values.firstOrNull() ?: return
        runCatching { fr.requestFocus() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shell-level holder (one per MainScreen)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Holds the active screen's [ContentFocusRegistry]. Provided at the MainScreen level via
 * [LocalActiveContentFocus]; screens register themselves via [RegisterActiveContentFocus].
 * Last-writer-wins across a navigation transition.
 */
@Stable
class ActiveContentFocus {
    var registry: ContentFocusRegistry? by mutableStateOf(null)
        private set

    fun register(target: ContentFocusRegistry) {
        registry = target
    }

    fun unregister(target: ContentFocusRegistry) {
        // Only clear if the caller still owns the slot — prevents the leaving screen from
        // wiping the entering screen's registry during an overlapping nav transition.
        if (registry === target) registry = null
    }
}

/** Provided by [net.mhanak.yama.ui.screens.MainScreen] around the NavHost content subtree. */
val LocalActiveContentFocus = compositionLocalOf<ActiveContentFocus?> { null }

/**
 * On TV, registers [registry] as the active screen's content focus tracker for as long as this
 * call is in composition. No-op off TV or when no [ActiveContentFocus] is provided (overlays
 * outside the NavHost won't steal the screen's entry focus).
 */
@Composable
fun RegisterActiveContentFocus(registry: ContentFocusRegistry) {
    if (!LocalIsTvMode.current) return
    val holder = LocalActiveContentFocus.current ?: return
    DisposableEffect(holder, registry) {
        holder.register(registry)
        onDispose { holder.unregister(registry) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-screen local (provided by GridView / ListView to their items)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Provided by [net.mhanak.yama.ui.components.library.GridView] and
 * [net.mhanak.yama.ui.components.library.ListView] so item cards can reach their screen's
 * [ContentFocusRegistry] without an explicit parameter thread.
 */
val LocalContentFocusRegistry = compositionLocalOf<ContentFocusRegistry?> { null }

// ─────────────────────────────────────────────────────────────────────────────
// Per-item modifier
// ─────────────────────────────────────────────────────────────────────────────

/**
 * On TV, attaches a stable [FocusRequester] keyed by [key] to this node and reports the focused
 * key back to the screen's [ContentFocusRegistry] via [onFocusChanged]. Off TV or when [key] is
 * null this is effectively a no-op — remember and DisposableEffect are still called so the slot
 * count is stable, but no modifier is applied and no registration occurs.
 *
 * Apply this before [androidx.compose.foundation.combinedClickable] so the focus target node and
 * the clickable surface are the same node and requestFocus() resolves to it reliably.
 */
@Composable
fun Modifier.contentFocusItem(key: String?): Modifier {
    val isTV = LocalIsTvMode.current
    val registry = LocalContentFocusRegistry.current
    // Always call remember/DisposableEffect regardless of conditions so slot count is stable.
    val fr = remember { FocusRequester() }
    DisposableEffect(registry, key, isTV) {
        if (isTV && key != null && registry != null) registry.register(key, fr)
        onDispose {
            if (key != null) registry?.unregister(key)
        }
    }
    return if (isTV && key != null && registry != null) {
        this
            .focusRequester(fr)
            .onFocusChanged { if (it.isFocused) registry.onFocused(key) }
    } else {
        this
    }
}
