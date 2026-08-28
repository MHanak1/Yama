package net.mhanak.yama.ui.components.input

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.theme.GlassFilledIconButton
import net.mhanak.yama.ui.theme.glassEffect

/**
 * A floating action laid out as a glassy text [label] pill to the left of a [button] (typically a
 * [GlassFilledIconButton]). Used by the library's multi-selection controls
 * ([net.mhanak.yama.ui.components.settings.LibrarySelectionButtons]); the playlists "New playlist" FAB
 * shares the same [GlassPrimaryActionButton] but without a label pill.
 */
@Composable
fun LabeledGlassAction(
    label: String,
    modifier: Modifier = Modifier,
    button: @Composable () -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .glassEffect(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        button()
    }
}

/**
 * The large primary variant of [GlassFilledIconButton] used for a cluster's headline action (Shuffle a
 * selection, create a playlist). Expressive press feedback: while held it squishes (scale) and its shape
 * morphs from a circle (corner = half the 72.dp size) toward a rounded square, springing back on release.
 * The same [shape] drives the glass clip, so the blur silhouette morphs with the button.
 */
@Composable
fun GlassPrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.90f else 1f, label = "primaryActionScale")
    val corner by animateDpAsState(if (pressed) 20.dp else 36.dp, label = "primaryActionCorner")
    GlassFilledIconButton(
        onClick = onClick,
        modifier = modifier.size(72.dp).scale(scale),
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
        content = content,
    )
}
