package net.mhanak.yama.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    loading: @Composable () -> Unit = { CircularProgressIndicator() },
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

    AnimatedContent(state) { s ->
        when (s) {
            AsyncState.Loading -> loading()
            is AsyncState.Success -> content(s.value)
            is AsyncState.Failure -> error(s.throwable)
        }
    }
}
