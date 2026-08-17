package net.mhanak.yama.ui.components.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.size.Size

/**
 * The image stack shared by the grid and list cards: a placeholder underneath (a decoded
 * [imageHash] blur if available, otherwise the tinted [imageFallback] icon) with the real
 * [imageUrl] image fading in on top of it via Coil.
 *
 * Must be called inside a [BoxScope] (the card's image slot) so the layers stack.
 */
@Composable
fun BoxScope.CardImage(
    imageUrl: String?,
    imageHash: String? = null,
    imageFallback: Painter? = null,
    fallbackFraction: Float = 0.5f,
) {
    val blurPainter = rememberBlurHashPainter(imageHash)
    when {
        blurPainter != null -> Image(
            modifier = Modifier.fillMaxSize(),
            painter = blurPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
        imageFallback != null -> Image(
            modifier = Modifier.fillMaxSize(fallbackFraction),
            painter = imageFallback,
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.outline), //todo: find more suitable color
        )
    }
    // Coil resolves an AsyncImage's decode size once — from the first layout pass — and never
    // re-decodes when the slot later grows. On desktop, enlarging the window widens the card but leaves
    // the original (smaller) bitmap in place, upscaled and pixelated. Fix: let Coil resolve the initial
    // size itself (so the first load still matches ImagePrefetch's Size/Scale and hits the memory cache),
    // then, only once the slot has grown past that first size, hand Coil an explicit larger size so it
    // re-decodes sharper. `reqPx` is grow-only, so shrinking never downgrades a bitmap already on screen.
    // Both values track the *slot*, not the url, so a recycled card in an already-enlarged grid loads at
    // the larger size straight away (no flash of the small bitmap).
    val context = LocalPlatformContext.current
    var firstPx by remember { mutableStateOf(0) }
    var reqPx by remember { mutableStateOf(0) }
    val request = remember(imageUrl, reqPx) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .apply { if (reqPx > 0) size(Size(reqPx, reqPx)).scale(Scale.FILL) }
            .build()
    }
    AsyncImage(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val edge = maxOf(size.width, size.height)
                if (edge <= 0) return@onSizeChanged
                // Record the slot's initial size as the baseline (no explicit request → Coil's own
                // resolver handles the first load); only bump to an explicit size once it grows past it.
                if (firstPx == 0) { firstPx = edge; return@onSizeChanged }
                // Grow-only, with a 15% hysteresis band measured against the size we've already decoded:
                // re-decode only once the slot exceeds it by ≥15%. The nav-rail collapse only widens
                // cards ~10%, so it stays on the existing bitmap (imperceptible upscale) rather than
                // re-decoding every frame of that animation; a genuine window enlargement still crosses
                // the band and sharpens. Shrinking never downgrades a bitmap already on screen.
                val decoded = if (reqPx > 0) reqPx else firstPx
                if (edge >= decoded * 1.15f) reqPx = edge
            },
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
    )
}
