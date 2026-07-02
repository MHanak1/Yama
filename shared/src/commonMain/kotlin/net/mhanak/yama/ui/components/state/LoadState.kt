package net.mhanak.yama.ui.components.state

/**
 * A three-state loading model for async data fetched in a [LaunchedEffect]: the fetch is in
 * progress ([Loading]), succeeded ([Success]), or failed ([Failure]).
 *
 * Used by detail views that hold async track/album lists inside a [ListView] (LazyColumn), where
 * the [Async] composable can't be used directly because the header and track rows are interleaved
 * as lazy items rather than rendered as a single block.
 */
sealed class LoadState<out T> {
    data object Loading : LoadState<Nothing>()
    data class Success<out T>(val value: T) : LoadState<T>()
    data class Failure(val throwable: Throwable) : LoadState<Nothing>()
}
