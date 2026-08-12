package net.mhanak.yama.ui.components.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.LocalIsTvMode
import net.mhanak.yama.ui.views.LibraryTab
import net.mhanak.yama.ui.components.input.isInFlight
import net.mhanak.yama.ui.components.input.segmentedItemShape
import net.mhanak.yama.ui.components.settings.SourceSwitcher
import net.mhanak.yama.ui.theme.glassEffect

private val NAV_RAIL_WIDTH = 96.dp
private val EXPANDED_RAIL_WIDTH = 260.dp

// Fixed item height. Deliberately not `heightIn(min=)`: a hard height keeps collapsed and expanded
// items provably identical so the width animation (and TV focus expand/collapse) never causes a
// vertical jump or reflow.
private val RAIL_ITEM_HEIGHT = 54.dp

// Spacing between items within a joined segmented group (mirrors SegmentedButtonRow's 4dp gaps).
private val GROUP_GAP = 4.dp

private val ICON_SIZE = 24.dp
// Leading inset for the icon. Collapsed value centers the icon within the collapsed pill
// ((96 - 2*8 outer - 24 icon)/2 = 28dp); it's interpolated toward the expanded value by the
// animated width, so the icon glides symmetrically on both expand and collapse rather than snapping.
private val COLLAPSED_ICON_LEADING = (NAV_RAIL_WIDTH - 16.dp - ICON_SIZE) / 2f
private val EXPANDED_ICON_LEADING = 20.dp

/**
 * Persistent navigation rail for the medium and wide layouts. Layout: SourceSwitcher (fixed header)
 * → a scrollable middle holding the Home pill and the library-tabs group → a fixed footer group
 * (Now playing / Downloads / Settings) pinned to the bottom.
 *
 * Buttons mirror [net.mhanak.yama.ui.components.input.SegmentedButtonRow]: a [glassEffect] fill with
 * spring-animated colors, grouped into vertical "segmented" stacks via [segmentedItemShape] (outer
 * ends rounded 50%, the corners where items meet tightened to 25%).
 *
 * [forceExpanded] expands the rail purely by window width (the wide ≥1200dp layout). On TV the
 * rail ignores [forceExpanded] and instead expands while it holds D-pad focus, collapsing on blur.
 *
 * When expanded, items are full-width and left-aligned; labels reveal by clipping as the rail
 * animates wider (set as `softWrap = false`) so nothing wraps or jumps mid-animation.
 *
 * @param selectedTab the highlighted library tab, or null when the active destination isn't the library.
 */
@Composable
fun AppNavRail(
    forceExpanded: Boolean,
    homeSelected: Boolean,
    selectedTab: LibraryTab?,
    settingsSelected: Boolean,
    onHomeClick: () -> Unit,
    onTabClick: (LibraryTab) -> Unit,
    onSettingsClick: () -> Unit,
    downloadsSelected: Boolean = false,
    onDownloadsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    // The "Now playing" entry is shown only while something is playing. On TV this is the entry point
    // to the full-screen player (the rail doesn't dock a panel there).
    nowPlayingVisible: Boolean = false,
    onNowPlayingClick: () -> Unit = {},
) {
    val isTV = LocalIsTvMode.current
    // TV: rail starts collapsed and expands while focused. Non-TV: width-driven only.
    var focused by remember { mutableStateOf(false) }
    val expanded = if (isTV) focused else forceExpanded

    val targetWidth = if (expanded) EXPANDED_RAIL_WIDTH else NAV_RAIL_WIDTH
    val railWidth by animateDpAsState(targetWidth, label = "railWidth")

    // Layout progress (0 = collapsed, 1 = expanded) derived from the *animated* width, not the
    // instantaneous `expanded` boolean. Items position their icon/label off this so collapse mirrors
    // expand — otherwise the icon snaps to center on the first collapse frame while the rail is still
    // wide, then slides back left (the asymmetric jump we're fixing).
    val expandProgress = ((railWidth.value - NAV_RAIL_WIDTH.value) /
        (EXPANDED_RAIL_WIDTH.value - NAV_RAIL_WIDTH.value)).coerceIn(0f, 1f)

    // Attached to whichever item matches the current screen; entering the rail from content (D-pad
    // left) lands here rather than on the spatially-closest item.
    val selectedItemFocus = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            //.glassEffect(MaterialTheme.colorScheme.surfaceContainerLow)
            // Entering the rail (D-pad left from content) lands on the item matching the current
            // screen. focusProperties must precede focusGroup so onEnter applies to the rail's own
            // focus target rather than its child items.
            .then(
                if (isTV) Modifier.focusProperties {
                    onEnter = { runCatching { selectedItemFocus.requestFocus() } }
                } else Modifier,
            )
            // Isolate the rail as its own D-pad focus group so content focus never steps into it.
            .focusGroup()
            .then(if (isTV) Modifier.onFocusChanged { focused = it.hasFocus } else Modifier)
            // Background BEFORE statusBarsPadding so the rail's surface fills the whole height and
            // extends up into the status bar / display cutout; the padding then insets only the
            // content below. (Reversed, the padding shrinks the node first and the background stops
            // abruptly below the notch.)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding(),
    ) {
        SourceSwitcher(collapsed = !expanded)

        // Scrollable middle: Home (own pill) + the library-tabs group. Wrapped in verticalScroll so
        // a short window scrolls here rather than squeezing/clipping items. weight(1f) works because
        // this Column's *parent* is fixed-height — only this child scrolls, leaving the footer pinned.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            RailItem(
                selected = homeSelected,
                onClick = onHomeClick,
                icon = Icons.Default.Home,
                label = "Home",
                expandProgress = expandProgress,
                // Home stands alone, so it's a full pill.
                shape = RoundedCornerShape(percent = 50),
                focusRequester = if (homeSelected) selectedItemFocus else null,
            )

            Spacer(Modifier.height(8.dp))

            // Library tabs as one joined segmented group.
            Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                val tabs = LibraryTab.entries
                tabs.forEachIndexed { index, tab ->
                    RailItem(
                        selected = selectedTab == tab,
                        onClick = { onTabClick(tab) },
                        icon = tab.icon,
                        label = tab.label,
                        expandProgress = expandProgress,
                        shape = segmentedItemShape(index, tabs.size, vertical = true),
                        focusRequester = if (selectedTab == tab) selectedItemFocus else null,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        // Fixed footer as a second joined group. Now playing is conditional, so the entries are
        // collected first and their count drives each item's segment shape.
        val activeDownloads by LocalAppContainer.current.downloadManager.downloads.collectAsState()
        val footerEntries = buildList {
            if (nowPlayingVisible) {
                add(
                    RailEntry(
                        selected = false,
                        onClick = onNowPlayingClick,
                        icon = Icons.Default.PlayCircle,
                        label = "Now playing",
                    ),
                )
            }
            add(
                RailEntry(
                    selected = downloadsSelected,
                    onClick = onDownloadsClick,
                    icon = Icons.Default.Download,
                    label = "Downloads",
                    badgeCount = activeDownloads.count { it.state.isInFlight },
                    focusRequester = if (downloadsSelected) selectedItemFocus else null,
                ),
            )
            add(
                RailEntry(
                    selected = settingsSelected,
                    onClick = onSettingsClick,
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    focusRequester = if (settingsSelected) selectedItemFocus else null,
                ),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
        ) {
            footerEntries.forEachIndexed { index, entry ->
                RailItem(
                    selected = entry.selected,
                    onClick = entry.onClick,
                    icon = entry.icon,
                    label = entry.label,
                    expandProgress = expandProgress,
                    shape = segmentedItemShape(index, footerEntries.size, vertical = true),
                    badgeCount = entry.badgeCount,
                    focusRequester = entry.focusRequester,
                )
            }
        }
    }
}

// Lightweight holder so the conditional footer can be built as a list and shaped by index/count.
private class RailEntry(
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: ImageVector,
    val label: String,
    val badgeCount: Int = 0,
    val focusRequester: FocusRequester? = null,
)

@Composable
private fun RailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    // 0 = fully collapsed, 1 = fully expanded. Drives the icon inset and label reveal continuously so
    // both directions animate symmetrically.
    expandProgress: Float,
    shape: Shape,
    badgeCount: Int = 0,
    focusRequester: FocusRequester? = null,
) {
    // Match SegmentedButtonRow: a non-bouncy color spring so the selection highlight eases in
    // without oscillating.
    val colorSpring = spring<Color>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
                      else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = colorSpring,
        label = "railItemContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                      else MaterialTheme.colorScheme.onSurface,
        animationSpec = colorSpring,
        label = "railItemContent",
    )
    // Icon inset glides from centered-in-collapsed-pill to the expanded left inset in lockstep with
    // the width, so the icon holds roughly its position and the motion is symmetric both ways.
    val iconLeading = lerp(COLLAPSED_ICON_LEADING, EXPANDED_ICON_LEADING, expandProgress)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .padding(horizontal = 8.dp)
            .height(RAIL_ITEM_HEIGHT)
            // glassEffect clips to [shape] and paints the container color (blurred haze fill when
            // enabled, solid fallback otherwise) — same treatment the segmented buttons use.
            .glassEffect(containerColor, shape)
            .clickable(onClick = onClick)
            // Always left-aligned; the leading inset (not the arrangement) positions the icon, so it
            // animates continuously instead of snapping between Start and Center.
            .padding(start = iconLeading, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (badgeCount > 0) {
                BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                    Icon(icon, contentDescription = label)
                }
            } else {
                Icon(icon, contentDescription = label)
            }
            // Keep the label composed for the whole animation (gated only at rest-collapsed) so it
            // reveals/hides by clipping as the width grows/shrinks — the reverse of each other.
            if (expandProgress > 0f) {
                Spacer(Modifier.width(16.dp))
                // softWrap = false keeps the label on one line so it reveals by clipping as the
                // rail widens, instead of wrapping (and stretching the row) while it's still narrow.
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
