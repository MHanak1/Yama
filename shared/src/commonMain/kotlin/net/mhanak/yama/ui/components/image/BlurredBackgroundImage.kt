package net.mhanak.yama.ui.components.image

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import net.mhanak.yama.ui.theme.DynamicColorAnimationMs
import net.mhanak.yama.ui.theme.LocalHazeState

enum class GradientDirection { None, Up, Down, Left, Right }

@Composable
fun BlurredBackgroundImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 500.dp,
) {
    LocalHazeState.current ?: return
    val context = LocalPlatformContext.current
    // Two-layer cross-dissolve. The previous cover stays painted underneath while the incoming one fades
    // in over it: the old image (never the dark surface) is what shows through during the swap, so there
    // is no black flash, and the fade gives the backdrop a smooth transition in step with the colours.
    // Both layers request one shared Size.ORIGINAL cache entry (see the player's cover/preload), and
    // placeholderMemoryCacheKey lets the incoming layer paint that bitmap on frame 0 rather than loading.
    var previous by remember { mutableStateOf<String?>(null) }
    Box(modifier.fillMaxSize()) {
        if (previous != null && previous != imageUrl) {
            AsyncImage(
                model = backgroundRequest(context, previous),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(blurRadius),
            )
        }
        // Fresh fade per incoming url; opaque immediately on the very first image (nothing to fade over).
        val fade = remember(imageUrl) { Animatable(if (previous == null) 1f else 0f) }
        LaunchedEffect(imageUrl) {
            fade.animateTo(1f, tween(DynamicColorAnimationMs))   // match the colour cross-fade
            previous = imageUrl
        }
        AsyncImage(
            model = backgroundRequest(context, imageUrl),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = fade.value }
                .blur(blurRadius),
        )
    }
}

private fun backgroundRequest(context: PlatformContext, url: String?) =
    ImageRequest.Builder(context)
        .data(url)
        .size(Size.ORIGINAL)
        .memoryCacheKey(url)
        .placeholderMemoryCacheKey(url)
        .build()
