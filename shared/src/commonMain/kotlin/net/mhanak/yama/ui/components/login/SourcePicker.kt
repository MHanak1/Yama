package net.mhanak.yama.ui.components.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.ui.components.library.SourceAvatar

private const val ANIM_MS = 200

/**
 * Source selector for the login screen — a vertical accordion, structured like the wide-rail variant
 * of the app's [SourceSwitcher] (`ui/components/settings/SourceSwitcher.kt`): a fixed
 * `secondaryContainer` identity pill, and a `surfaceContainerHigh` panel that expands *in place*
 * beneath it (rather than a floating popup) listing the other sources.
 *
 * The panel is offset up by the pill's corner radius (28dp) and the pill draws on top (`zIndex(1f)`)
 * so the two merge into one rounded shape — the exact trick [SourceSwitcher] uses.
 *
 * @param enabled predicate gating which sources are selectable (disabled ones render dimmed and
 *   ignore taps). Defaults to all-enabled.
 */
@Composable
fun SourcePicker(
    options: List<SourceType>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (SourceType) -> Boolean = { true },
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Identity pill — fixed 28dp shape, never mutates; draws on top of the panel's overlap.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .zIndex(1f),
        ) {
            IdentityRow(
                sourceType = options[selectedIndex],
                expanded = expanded,
                onClick = { expanded = !expanded },
            )
        }

        // Panel — offset up by the pill's corner radius so its background fills the pill's rounded
        // bottom-corner gap; the pill (zIndex 1f) hides the centre overlap. A non-scrolling Spacer
        // keeps the rows below the pill's visual bottom edge.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(ANIM_MS)),
            exit = shrinkVertically(animationSpec = tween(ANIM_MS)),
            modifier = Modifier.offset(y = (-28).dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Spacer(Modifier.height(28.dp))
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    options.forEachIndexed { index, sourceType ->
                        // The selected source lives in the pill above — don't relist it.
                        if (index == selectedIndex) return@forEachIndexed
                        SourceRow(
                            sourceType = sourceType,
                            enabled = enabled(sourceType),
                            onClick = { onSelect(index); expanded = false },
                        )
                    }
                }
            }
        }
    }
}

// Pill header — the selected source + a chevron that rotates on expand. Mirrors SourceSwitcher.IdentityRow.
@Composable
private fun IdentityRow(sourceType: SourceType, expanded: Boolean, onClick: () -> Unit) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ANIM_MS),
        label = "chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceAvatar(sourceType = sourceType, avatarUrl = null, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = sourceType.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse source menu" else "Expand source menu",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.rotate(chevronRotation),
        )
    }
}

// Panel entry — an unselected source. Mirrors SourceSwitcher.SourceRow (transparent; active is excluded).
@Composable
private fun SourceRow(sourceType: SourceType, enabled: Boolean, onClick: () -> Unit) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceAvatar(
            sourceType = sourceType,
            avatarUrl = null,
            size = 32.dp,
            iconSize = 20.dp,
            showBackground = false,
            modifier = Modifier.alpha(contentAlpha),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = sourceType.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
        )
    }
}
