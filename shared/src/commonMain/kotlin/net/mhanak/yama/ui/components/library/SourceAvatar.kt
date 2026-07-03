package net.mhanak.yama.ui.components.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.times
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import coil3.compose.AsyncImage
import net.mhanak.yama.media.sources.SourceType
import org.jetbrains.compose.resources.painterResource

/**
 * Circular identity avatar for a source switcher entry.
 *
 * Layered rendering: the source logo is drawn first (layer 0) so it always shows. The real
 * profile image is then painted on top (layer 1) via Coil3 — if the request fails or the
 * user has no profile photo, the layer renders nothing and the logo beneath is visible.
 * No explicit error-state handling is needed; absent image == transparent layer.
 *
 * [iconSize] controls the fallback-logo glyph independently of [size] so the circle can grow
 * without ballooning the icon (e.g. in the collapsed rail the circle is 44dp but the glyph
 * stays at its natural size).
 *
 * [avatarUrl] is the remote profile image URL; null means no photo exists and only the source
 * logo is shown. For Jellyfin, the URL is built by [JellyfinSource.toSourceAccount] and passed
 * through [SourceAccount.avatarUrl] — this composable has no knowledge of where it comes from.
 *
 * Logo drawables and tinting are driven by [sourceLogo] so adding a new [SourceType] only
 * requires updating that mapping.
 */
@Composable
fun SourceAvatar(
    sourceType: SourceType,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = size * 0.55f,  // fallback glyph — decoupled from circle size
    showBackground: Boolean = true,
) {
    val logo = sourceLogo(sourceType)

    val boxModifier = if (showBackground) {
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
    } else {
        modifier.size(size)
    }

    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center,
    ) {
        // Layer 0: source logo — always visible; shows through if the profile photo fails to load.
        if (logo.tinted) {
            // Material icons have ~2dp internal padding on a 24dp grid (~83% fill), so scale up by
            // 24/20 to visually match full-bleed PNGs that fill their bounding box.
            Image(
                painter = painterResource(logo.drawable),
                contentDescription = null,
                modifier = Modifier.size(iconSize * 1.2f),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer),
            )
        } else {
            // The jellyfin-logo.png visual centroid is ~10% below the geometric centre (bottom-heavy
            // triangle shape), so nudge it up to make it look centred in the circle.
            Image(
                painter = painterResource(logo.drawable),
                contentDescription = null,
                modifier = Modifier.size(iconSize).offset(y = -(0.07f * iconSize)),
            )
        }

        // Layer 1: profile photo — covers the logo when Coil loads it successfully.
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}
