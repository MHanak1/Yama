package net.mhanak.yama.components

import coil3.request.ImageRequest
import coil3.request.allowHardware

// Coil decodes to hardware bitmaps by default on Android; their pixels can't be read back, so the
// palette extractor needs a software decode.
actual fun ImageRequest.Builder.readableBitmap(): ImageRequest.Builder = allowHardware(false)
