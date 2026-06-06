package net.mhanak.yama.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.mhanak.yama.isTelevisionDevice
import net.mhanak.yama.views.LibraryTab

private val NAV_RAIL_WIDTH = 96.dp
private val EXPANDED_RAIL_WIDTH = 260.dp

/**
 * Persistent navigation rail for the medium and wide layouts. Items: SourceSwitcher (header) →
 * Home → library tabs → Settings pinned to the bottom.
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
    modifier: Modifier = Modifier,
) {
    val isTV = isTelevisionDevice()
    // TV: rail starts collapsed and expands while focused. Non-TV: width-driven only.
    var focused by remember { mutableStateOf(false) }
    val expanded = if (isTV) focused else forceExpanded

    val targetWidth = if (expanded) EXPANDED_RAIL_WIDTH else NAV_RAIL_WIDTH
    val railWidth by animateDpAsState(targetWidth, label = "railWidth")

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            .glassEffect(MaterialTheme.colorScheme.surfaceContainerLow)
            // Isolate the rail as its own D-pad focus group so content focus never steps into it.
            .focusGroup()
            .then(if (isTV) Modifier.onFocusChanged { focused = it.hasFocus } else Modifier)
            .statusBarsPadding(),
    ) {
        SourceSwitcher(collapsed = !expanded)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

        RailItem(
            selected = homeSelected,
            onClick = onHomeClick,
            icon = Icons.Default.Home,
            label = "Home",
            expanded = expanded,
        )

        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

        LibraryTab.entries.forEach { tab ->
            RailItem(
                selected = selectedTab == tab,
                onClick = { onTabClick(tab) },
                icon = tab.icon,
                label = tab.label,
                expanded = expanded,
            )
        }

        Spacer(Modifier.weight(1f))

        RailItem(
            selected = settingsSelected,
            onClick = onSettingsClick,
            icon = Icons.Default.Settings,
            label = "Settings",
            expanded = expanded,
        )
    }
}

@Composable
private fun RailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    expanded: Boolean,
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(horizontal = if (expanded) 20.dp else 8.dp),
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Icon(icon, contentDescription = label)
            if (expanded) {
                Spacer(Modifier.width(16.dp))
                // softWrap = false keeps the label on one line so it reveals by clipping as the
                // rail widens, instead of wrapping (and stretching the row) while it's still narrow.
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
