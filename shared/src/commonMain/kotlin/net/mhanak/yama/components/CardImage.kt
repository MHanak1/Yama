package net.mhanak.yama.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

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
    AsyncImage(
        modifier = Modifier.fillMaxSize(),
        model = imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
    )
}
