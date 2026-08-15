package net.mhanak.yama.ui.components.interaction

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
class ContentFocusRegistry(
    private val savedKey: MutableState<String?>,
    // Optional focus target used by [requestRestore] when no keyed items are registered — lets a screen
    // with no per-item focus keys (e.g. a settings form) still land entry inside its content group
    // instead of on the app-bar back button. Typically the content group's own FocusRequester, whose
    // first focusable is the first control. Null for the grid/list case, which always has items.
    private val emptyFallback: FocusRequester? = null,
) {
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
        val fr = (if (key != null) items[key] else null)
            ?: items.values.firstOrNull()
            ?: emptyFallback
            ?: return
        runCatching { fr.requestFocus() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen-level content-focus host (for non-grid content screens)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Wraps a content screen's scroll container so it participates in the TV entry/restore model with a
 * single line, without needing a hand-rolled [ContentFocusRegistry]. On TV it:
 *  - creates a per-screen registry (savedKey [rememberSaveable] so a keyed row is restored on back),
 *  - registers it as the active screen's content focus target,
 *  - provides [LocalContentFocusRegistry] so rows can opt into restore via [Modifier.contentFocusItem],
 *  - marks its Box a focus group and makes that group the registry's empty-list fallback, so even a
 *    screen with no keyed rows lands entry on its first control (not the app-bar back button).
 *
 * Off TV this is a plain [Box] with [modifier] and adds nothing.
 */
@Composable
fun ContentFocusHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!LocalIsTvMode.current) {
        Box(modifier) { content() }
        return
    }
    val savedKey = rememberSaveable { mutableStateOf<String?>(null) }
    val groupFocus = remember { FocusRequester() }
    val registry = remember { ContentFocusRegistry(savedKey, emptyFallback = groupFocus) }
    RegisterActiveContentFocus(registry)
    CompositionLocalProvider(LocalContentFocusRegistry provides registry) {
        Box(modifier.focusRequester(groupFocus).focusGroup()) { content() }
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
// Zone coordinator (one per MainScreen) — deterministic 4-zone D-pad transitions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Holds the concrete leaf entry actions for the four TV focus zones so any zone's [onExit] handler
 * can hand focus to a neighbour deterministically, instead of relying on Compose's spatial focus
 * search (which picks the nearest focusable and so lands non-deterministically between the top bar,
 * content and player bar).
 *
 * Transitions call **leaf** requesters directly rather than a neighbour's focus *group*: a documented
 * CMP gotcha (see this file's header) is that a programmatic requestFocus() on a group skips its
 * onEnter and lands on the first focusable, so we never bounce through a group's onEnter.
 *
 *  - [sidebar] is attached to whichever rail item matches the active screen (AppNavRail).
 *  - [search] is attached to the shared top bar's search field (HomeLibraryTopBar), in every mode.
 *  - [nowPlaying] is attached to the now-playing bar's whole-bar row (NowPlayingBar).
 *  - [restoreContent] restores the active grid's last-focused item (or first item cold), falling back
 *    to the content group when a screen has no registry — the single rule the nav-entry effect, the
 *    player-bar up-exit and the search-bar down-exit all share.
 */
@Stable
class TvZoneFocus(
    val sidebar: FocusRequester,
    val search: FocusRequester,
    val nowPlaying: FocusRequester,
    private val activeContent: ActiveContentFocus,
    private val contentFallback: FocusRequester,
) {
    fun focusSidebar() { runCatching { sidebar.requestFocus() } }
    fun focusSearch() { runCatching { search.requestFocus() } }
    fun focusNowPlaying() { runCatching { nowPlaying.requestFocus() } }

    /** Restore the active screen's last-focused content item, or focus the content group if none. */
    fun restoreContent() {
        val registry = activeContent.registry
        if (registry != null) registry.requestRestore()
        else runCatching { contentFallback.requestFocus() }
    }
}

/** Provided by [net.mhanak.yama.ui.screens.MainScreen] so the four zones can reach each other. */
val LocalTvZoneFocus = compositionLocalOf<TvZoneFocus?> { null }

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
// Overlay focus containment (full player, sheets, dialogs, popups)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * TV focus containment for an overlay (full player, bottom sheet, dialog, dropdown) — the pattern
 * generalised from the hand-rolled version in
 * [net.mhanak.yama.ui.components.settings.SourceSwitcher]. Overlays render *outside* the four-zone
 * NavHost subtree, so nothing otherwise pulls D-pad focus into them or keeps it there. Apply this to
 * the overlay's root/content node. On TV it:
 *  - focuses [entry] once when the overlay appears (so the popup claims D-pad focus instead of letting
 *    events fall through to the content behind — the failure the SourceSwitcher workaround describes),
 *  - traps focus inside via `onExit = { cancelFocus() }` so D-pad can't wander onto the covered content,
 *  - calls [onDismissRestore] when the overlay leaves composition, so focus returns to its opener.
 *
 * Off TV it is a no-op. [entry] must be attached to a focusable inside the overlay via
 * [Modifier.focusRequester]; pass null to only trap/restore without moving focus on open.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.tvFocusContainer(
    entry: FocusRequester? = null,
    onDismissRestore: (() -> Unit)? = null,
): Modifier {
    if (!LocalIsTvMode.current) return this
    if (entry != null) {
        LaunchedEffect(Unit) { runCatching { entry.requestFocus() } }
    }
    if (onDismissRestore != null) {
        DisposableEffect(Unit) { onDispose { onDismissRestore() } }
    }
    // focusProperties must precede focusGroup so onExit applies to the container's own focus target.
    return this
        .focusProperties { onExit = { cancelFocus() } }
        .focusGroup()
}

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
