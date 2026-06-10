package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a BlurHash string into a small [ImageBitmap]. Implemented per-platform via
 * vanniktech/blurhash (Android Bitmap / JVM BufferedImage), bridged to Compose's ImageBitmap.
 * Returns null when the hash is invalid.
 */
expect fun decodeBlurHash(blurHash: String, width: Int, height: Int): ImageBitmap?

/**
 * Decodes [blurHash] off the main thread and returns it as a [Painter] suitable as an image
 * placeholder. Decoding happens at a tiny resolution (the blur is then scaled up), so it is cheap;
 * the result is keyed on the hash so it isn't recomputed on every recomposition. Returns null while
 * decoding, when [blurHash] is null, or when the hash is invalid.
 */
@Composable
fun rememberBlurHashPainter(blurHash: String?, width: Int = 32, height: Int = 32): Painter? {
    val bitmap by produceState<ImageBitmap?>(null, blurHash, width, height) {
        value = blurHash?.let { hash ->
            withContext(Dispatchers.Default) { decodeBlurHash(hash, width, height) }
        }
    }
    return remember(bitmap) { bitmap?.let { BitmapPainter(it) } }
}
