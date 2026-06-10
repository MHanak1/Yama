package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Implementation detail of [GridView] and [ListView] — not meant to be called from anywhere else.
 *
 * Warms Coil's cache for images just past the visible window so a card's image is ready the moment
 * it scrolls on screen. Whenever [lastVisibleIndex] advances we enqueue the next [aheadCount] URLs
 * at [targetSizePx] — a square edge in px the host derives from its own measured layout — with
 * [Scale.FILL] to mirror the cards' `ContentScale.Crop`. Decoding at the same size/scale the card
 * will request means the bitmap lands in the *memory* cache under the matching key, so the visible
 * card needs neither a network round-trip nor a decode. A non-positive [targetSizePx] (nothing
 * measured yet) falls back to an original-size request, which still warms the disk cache.
 */
@Composable
internal fun ImagePrefetch(
    urls: List<String?>,
    lastVisibleIndex: () -> Int,
    targetSizePx: () -> Int,
    aheadCount: Int = 12,
) {
    val context = LocalPlatformContext.current
    val loader = SingletonImageLoader.get(context)
    LaunchedEffect(urls, loader, aheadCount) {
        snapshotFlow(lastVisibleIndex)
            .distinctUntilChanged()
            .collectLatest { last ->
                val end = (last + aheadCount).coerceAtMost(urls.lastIndex)
                for (i in (last + 1)..end) {
                    val url = urls.getOrNull(i) ?: continue
                    val px = targetSizePx()
                    loader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .apply { if (px > 0) size(Size(px, px)).scale(Scale.FILL) }
                            .build()
                    )
                }
            }
    }
}
