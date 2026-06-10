package net.mhanak.yama.components

import coil3.request.ImageRequest

// Desktop never decodes to hardware bitmaps, so the decode is already CPU-readable — nothing to do.
actual fun ImageRequest.Builder.readableBitmap(): ImageRequest.Builder = this
