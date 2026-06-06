package net.mhanak.yama.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.isTelevisionDevice

// Persistent rail (instead of modal drawer) at/above this width. Below it: slim layout.
private val WideLayoutBreakpoint = 800.dp
// Expand the persistent rail from icon-only to icon+label at/above this width (non-TV).
private val ExpandedLayoutBreakpoint = 1200.dp

/**
 * Adaptive shell with three states driven by available width (see Layout.md):
 *
 * - **Slim** (`<800dp`, non-TV): modal nav drawer ([modalContent]) opened via the menu button,
 *   plus a [bottomBar] overlaid at the bottom (so its glass blurs the content scrolling under it).
 *   Content gets `hasRail = false`, a non-null `onMenuClick`, and a bottom inset equal to the bar.
 * - **Medium** (`800–1200dp`): persistent [rail] in its collapsed (icon-only) state.
 * - **Wide** (`≥1200dp` or TV): persistent [rail] expanded with labels. On TV the rail manages
 *   its own focus-driven expansion, so `forceExpanded` is ignored there.
 *
 * Medium and wide both pass `hasRail = true`, `onMenuClick = null`, and a zero bottom inset.
 * Manages the background hazeSource layer and the slim drawer state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveNavigationLayout(
    rail: @Composable (forceExpanded: Boolean) -> Unit,
    bottomBar: @Composable () -> Unit,
    modalContent: @Composable ColumnScope.(onClose: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (hasRail: Boolean, onMenuClick: (() -> Unit)?, bottomInset: Dp) -> Unit,
) {
    val isTV = isTelevisionDevice()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val hasRail = maxWidth >= WideLayoutBreakpoint || isTV
        val forceExpanded = maxWidth >= ExpandedLayoutBreakpoint

        // Background hazeSource: glass surfaces inside content (zIndex=1) only blur this, not each
        // other — see Glass.kt for the full zIndex layering explanation.
        Box(Modifier.fillMaxSize().glassSource(zIndex = 0f))

        if (hasRail) {
            Row(Modifier.fillMaxSize()) {
                rail(forceExpanded)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    content(true, null, 0.dp)
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // Don't open via swipe (it competes with the library pager); only allow the
                // swipe-to-close gesture once the drawer is already open.
                gesturesEnabled = drawerState.currentValue == DrawerValue.Open,
                drawerContent = {
                    GlassModalDrawerSheet {
                        modalContent { scope.launch { drawerState.close() } }
                    }
                },
            ) {
                Box(Modifier.fillMaxSize()) {
                    // Content fills the whole area and draws behind the bar so the bar's glass has
                    // something to blur; it gets a bottom inset so list ends clear the bar.
                    content(false, { scope.launch { drawerState.open() } }, BottomBarHeight)
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        bottomBar()
                    }
                }
            }
        }
    }
}
