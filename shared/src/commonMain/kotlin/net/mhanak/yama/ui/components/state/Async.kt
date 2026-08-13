package net.mhanak.yama.ui.components.state

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

private sealed class AsyncState<out T> {
    data object Loading : AsyncState<Nothing>()
    data class Success<out T>(val value: T) : AsyncState<T>()
    data class Failure(val throwable: Throwable) : AsyncState<Nothing>()
}

@Composable
fun <T> Async(
    key: Any? = Unit,
    producer: suspend () -> T,
    loading: @Composable () -> Unit = { LoadingSlot() },
    error: @Composable (Throwable) -> Unit = { t -> ErrorCard(message = t.message ?: "Unknown error") },
    content: @Composable (T) -> Unit,
) {
    var state by remember(key) { mutableStateOf<AsyncState<T>>(AsyncState.Loading) }

    LaunchedEffect(key) {
        state = AsyncState.Loading
        state = try {
            AsyncState.Success(producer())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AsyncState.Failure(e)
        }
    }

    // A plain crossfade (no scaleIn) so states swap cleanly without the default AnimatedContent
    // transition scaling content up from 0.92x. The default SizeTransform still runs (we don't
    // override it), so the container height animates smoothly between loading and content.
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
        contentAlignment = Alignment.Center,
    ) { s ->
        when (s) {
            AsyncState.Loading -> loading()
            is AsyncState.Success -> content(s.value)
            is AsyncState.Failure -> error(s.throwable)
        }
    }
}

/**
 * Default loading placeholder: a normal, intrinsically-sized [CircularProgressIndicator] centred in
 * a full-width [Box].
 *
 * The Box is essential, not cosmetic. Callers like the login form put `Async` under a `fillMaxWidth`
 * parent, which propagates a *fixed* width constraint (min == max == parent width) down the tree.
 * `CircularProgressIndicator` sizes itself with `Modifier.size(40.dp)`, but `size()` can only pick a
 * value *within* the incoming constraints — under a fixed width it can't shrink, so the spinner
 * stretches to the full card width and draws as a giant thin arc. The Box absorbs that fixed width
 * and re-measures its child with loose constraints, letting the spinner keep its real 40dp size.
 */
@Composable
private fun LoadingSlot() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}
