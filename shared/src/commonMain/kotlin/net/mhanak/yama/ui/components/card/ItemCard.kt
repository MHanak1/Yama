package net.mhanak.yama.ui.components.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shared elevated media card used across the app: a square, rounded artwork slot on top of a
 * one-line title and optional subtitle. This owns the *visual* spec — padding, corner radii, typography,
 * spacing — so the library grid ([net.mhanak.yama.ui.components.library.GridCard]) and the home shelves
 * ([net.mhanak.yama.ui.components.home.HomeShelf]) render identically. The two surfaces differ only in how
 * they wire interaction, which they inject via the two modifier slots.
 *
 * [modifier] applies to the outer [ElevatedCard]: use it for sizing (a fixed shelf width) or card-level
 * state (dimming). [contentModifier] applies to the inner content column, *inside* the card's Surface —
 * so a clickable placed there has its hover/ripple indication clipped to the card's rounded shape. (A
 * clickable on the outer [modifier] instead wraps the Surface and its highlight bleeds into the rounded
 * corners.) The column fills the card, so an injected clickable covers the whole surface.
 *
 * [image] is the content of the rounded artwork box. It receives a [BoxScope] so callers can overlay
 * extras with `align` (e.g. a selection indicator), and should fill the box — typically a
 * [net.mhanak.yama.ui.components.image.CardImage].
 */
@Composable
fun ItemCard(
    title: String?,
    subtitle: String?,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    image: @Composable BoxScope.() -> Unit,
) {
    ElevatedCard(
        modifier = modifier,
        // Keep the ElevatedCard's tonal container fill but drop the drop-shadow, so these cards
        // sit flat like the rest of the UI. Every interaction state is zeroed so hover/press/focus
        // (desktop + TV) never re-introduce a shadow.
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 0.dp,
        ),
    ) {
        Column(
            modifier = contentModifier
                .padding(12.dp)
                .fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
                content = image,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                title ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
