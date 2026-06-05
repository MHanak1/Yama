package net.mhanak.yama.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.isTelevisionDevice

/** How the persistent sidebar is displayed on wide screens. */
enum class SidebarMode {
    /** Icon-only rail: non-TV 1000–1200dp, or TV unfocused. */
    Collapsed,
    /** Full sidebar: non-TV >1200dp, or TV focused. */
    Expanded,
}

private val NAV_RAIL_WIDTH = 80.dp
private val EXPANDED_SIDEBAR_WIDTH = 280.dp

// Show the permanent sidebar (instead of modal drawer) above this width.
private val WideLayoutBreakpoint = 1000.dp
// On non-TV wide screens, expand from rail to full sidebar above this width.
private val ExpandedLayoutBreakpoint = 1200.dp

/**
 * Adaptive two-pane layout: permanent sidebar on wide screens (≥1000dp or TV), modal drawer on narrow.
 *
 * - [wideDrawerContent] fills the permanent sidebar; receives the current [SidebarMode].
 *   On TV the sidebar starts collapsed and expands when any child gains focus.
 *   On non-TV wide screens the mode is always [SidebarMode.NavRail].
 * - [narrowDrawerContent] fills the modal drawer; receives an [onClose] lambda to dismiss it.
 * - [content] receives [isWide] and [onMenuClick] (null on wide — no hamburger menu needed).
 *
 * Manages the background hazeSource layer, drawer state, and glass styling internally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveNavigationLayout(
    wideDrawerContent: @Composable (mode: SidebarMode) -> Unit,
    narrowDrawerContent: @Composable (onClose: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    // Pass false (the recommended default when content uses HorizontalPager) to disable
    // swipe-to-open so the drawer doesn't compete with pager gestures. The drawer can still
    // be swiped closed when open because gesturesEnabled is also set when drawerState is Open.
    narrowGesturesEnabled: Boolean = false,
    content: @Composable (isWide: Boolean, onMenuClick: (() -> Unit)?) -> Unit,
) {
    val isTV = isTelevisionDevice()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= WideLayoutBreakpoint || isTV

        // Background hazeSource: glass cards inside content areas (zIndex=1) only blur this,
        // not each other — see Glass.kt for the full zIndex layering explanation.
        Box(Modifier.fillMaxSize().glassSource(zIndex = 0f))

        if (isWide) {
            // TV: starts collapsed, expands when sidebar gains D-pad focus, collapses on loss.
            // Non-TV: mode is determined purely by window width (no focus-driven expansion).
            var sidebarExpanded by remember { mutableStateOf(false) }
            val mode = when {
                isTV -> if (sidebarExpanded) SidebarMode.Expanded else SidebarMode.Collapsed
                maxWidth >= ExpandedLayoutBreakpoint -> SidebarMode.Expanded
                else -> SidebarMode.Collapsed
            }
            val targetWidth = if (mode == SidebarMode.Expanded) EXPANDED_SIDEBAR_WIDTH else NAV_RAIL_WIDTH
            val sidebarWidth by animateDpAsState(targetWidth, label = "sidebarWidth")
            // Only report Expanded once the animation reaches full width, so content never
            // renders expanded labels while the rail is still animating its width.
            val displayMode = if (sidebarWidth >= EXPANDED_SIDEBAR_WIDTH) SidebarMode.Expanded else SidebarMode.Collapsed

            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(sidebarWidth)
                        .glassEffect(MaterialTheme.colorScheme.surfaceContainerLow)
                        // Isolate the sidebar as its own D-pad focus group so that focus
                        // in the content area never accidentally steps into the sidebar.
                        .focusGroup()
                        .then(
                            // Track focus so the TV sidebar auto-expands/collapses.
                            if (isTV) Modifier.onFocusChanged { sidebarExpanded = it.hasFocus }
                            else Modifier
                        ),
                ) {
                    wideDrawerContent(displayMode)
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    content(true, null)
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // Enable when caller says it's safe (e.g. pager is on first page) or the drawer
                // is already open so a swipe-to-close still works.
                gesturesEnabled = narrowGesturesEnabled || drawerState.currentValue == DrawerValue.Open,
                drawerContent = {
                    GlassModalDrawerSheet {
                        narrowDrawerContent { scope.launch { drawerState.close() } }
                    }
                },
            ) {
                content(false) { scope.launch { drawerState.open() } }
            }
        }
    }
}
