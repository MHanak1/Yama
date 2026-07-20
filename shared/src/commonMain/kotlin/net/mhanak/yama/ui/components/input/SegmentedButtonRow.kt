package net.mhanak.yama.ui.components.input

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.theme.glassEffect

private val SELECTED_WIDTH_BONUS = 12.dp
private val SELECTED_PADDING_BONUS = 6.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> SegmentedButtonRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    selectedContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    label: @Composable (T) -> Unit,
) {
    val count = options.size
    // Shrink applied to each unselected button so their total loss equals the selected button's
    // gain — keeping the combined minimum width equal to the available width at all times.
    val shrinkPerUnselected = if (count > 1) SELECTED_WIDTH_BONUS / (count - 1) else 0.dp
    // Color spring without bounce — oscillating colors look wrong.
    val colorSpring = spring<Color>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    val sizeSpring = spring<Dp>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        BoxWithConstraints(modifier = modifier) {
            val gapTotal = 4.dp * (count - 1)
            val baseWidth = (maxWidth - gapTotal) / count

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                options.forEachIndexed { index, option ->
                    key(index) {
                        val selected = option == selectedOption
                        val shape = segmentedItemShape(index, count, vertical = false)
                        val bringIntoViewRequester = remember { BringIntoViewRequester() }

                        LaunchedEffect(selected) {
                            if (selected) bringIntoViewRequester.bringIntoView()
                        }

                        val animatedContainerColor by animateColorAsState(
                            targetValue = if (selected) selectedContainerColor else containerColor,
                            animationSpec = colorSpring,
                            label = "buttonContainerColor",
                        )
                        val animatedContentColor by animateColorAsState(
                            targetValue = if (selected) selectedContentColor else contentColor,
                            animationSpec = colorSpring,
                            label = "buttonContentColor",
                        )
                        val widthBonus by animateDpAsState(
                            targetValue = if (selected) SELECTED_WIDTH_BONUS else -shrinkPerUnselected,
                            animationSpec = sizeSpring,
                            label = "buttonWidth",
                        )
                        val paddingBonus by animateDpAsState(
                            targetValue = if (selected) SELECTED_PADDING_BONUS else 0.dp,
                            animationSpec = sizeSpring,
                            label = "buttonPadding",
                        )

                        Surface(
                            onClick = { onOptionSelected(option) },
                            shape = shape,
                            color = Color.Transparent,
                            contentColor = animatedContentColor,
                            modifier = Modifier
                                .bringIntoViewRequester(bringIntoViewRequester)
                                .widthIn(min = maxOf(0.dp, baseWidth + widthBonus))
                                .glassEffect(animatedContainerColor, shape),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp + paddingBonus, vertical = 8.dp),
                            ) {
                                label(option)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Corner shape for one item in a joined segmented group. The outer ends of the group stay fully
 * rounded (50%) while the corners where items meet tighten to 25%, so a run of items reads as a
 * single connected control. [vertical] maps that same logic onto top/bottom (for a stacked rail)
 * instead of start/end (for a horizontal row). Shared with the navigation rail.
 */
internal fun segmentedItemShape(index: Int, count: Int, vertical: Boolean = false): Shape = when {
    count == 1 -> RoundedCornerShape(percent = 50)
    // First item: outer corners (top for vertical, start for horizontal) fully rounded; the pair
    // facing the next item tightened to 25%.
    index == 0 -> if (vertical) RoundedCornerShape(
        topStartPercent = 50, topEndPercent = 50,
        bottomStartPercent = 25, bottomEndPercent = 25,
    ) else RoundedCornerShape(
        topStartPercent = 50, bottomStartPercent = 50,
        topEndPercent = 25, bottomEndPercent = 25,
    )
    // Last item: mirror of the first.
    index == count - 1 -> if (vertical) RoundedCornerShape(
        topStartPercent = 25, topEndPercent = 25,
        bottomStartPercent = 50, bottomEndPercent = 50,
    ) else RoundedCornerShape(
        topStartPercent = 25, bottomStartPercent = 25,
        topEndPercent = 50, bottomEndPercent = 50,
    )
    else -> RoundedCornerShape(percent = 25)
}
