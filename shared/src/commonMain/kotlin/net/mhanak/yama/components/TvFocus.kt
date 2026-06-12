package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import net.mhanak.yama.isTelevisionDevice

/**
 * Shared seam for routing TV D-pad focus into a screen's primary scrollable.
 *
 * On TV, when a screen becomes active — initial launch, a top-level switch, or returning from a
 * detail screen — the shell ([net.mhanak.yama.screens.MainScreen]) requests focus on the NavHost
 * focus group. Left to its own devices the focus search lands on the first focusable in the tree,
 * which is usually the top search bar. That's wrong: we want focus to land in the content grid,
 * which then restores its previously focused item via `focusRestorer` (so returning from a detail
 * screen lands back on the item you opened, and a fresh screen lands on its first item).
 *
 * The active screen's primary grid registers its focus target here (via [RegisterPrimaryContentFocus]);
 * the shell reads [PrimaryContentFocus.requester] and requests focus on it directly when a screen
 * becomes active. (Requesting focus on the enclosing NavHost group instead would skip its `onEnter`
 * redirect — a programmatic `requestFocus()` doesn't run custom-enter — and land on the search bar.)
 * Last writer wins, so across a navigation transition the entering screen's grid takes over and the
 * leaving one clears itself on dispose.
 */
@Stable
class PrimaryContentFocus {
    var requester: FocusRequester? by mutableStateOf(null)
        private set

    fun register(target: FocusRequester) {
        requester = target
    }

    fun unregister(target: FocusRequester) {
        if (requester === target) requester = null
    }
}

val LocalPrimaryContentFocus = compositionLocalOf<PrimaryContentFocus?> { null }

/**
 * On TV, registers [requester] as the active screen's primary content focus target for as long as
 * this call is in composition, so the shell routes screen-entry focus to it. Attach the same
 * [requester] to the container's focus group with [androidx.compose.ui.focus.focusRequester]. No-op
 * off TV, or when no [PrimaryContentFocus] is provided (e.g. inside an overlay drawn outside the
 * NavHost), so overlays never steal the screen's entry focus.
 */
@Composable
fun RegisterPrimaryContentFocus(requester: FocusRequester) {
    if (!isTelevisionDevice()) return
    val holder = LocalPrimaryContentFocus.current ?: return
    DisposableEffect(holder, requester) {
        holder.register(requester)
        onDispose { holder.unregister(requester) }
    }
}
