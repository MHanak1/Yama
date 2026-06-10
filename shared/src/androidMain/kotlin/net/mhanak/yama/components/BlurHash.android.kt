package net.mhanak.yama.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.vanniktech.blurhash.BlurHash

actual fun decodeBlurHash(blurHash: String, width: Int, height: Int): ImageBitmap? =
    BlurHash.decode(blurHash, width, height)?.asImageBitmap()
