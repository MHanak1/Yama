package net.mhanak.yama.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.mhanak.yama.AppContainer
import net.mhanak.yama.media.sources.HomeBlockKind

/**
 * Session-lived cache of the home screen's loaded shelf data, held on [AppContainer] so it survives
 * [net.mhanak.yama.ui.views.HomeView] leaving composition (navigating to a detail screen and back).
 * Without this, HomeView's own `remember` would be discarded on navigation and every return would
 * re-fetch every shelf — the visible "content jumps for a second" on back-navigation.
 *
 * State is Compose snapshot-backed so HomeView recomposes as shelves land. [data] is keyed by block;
 * [isLoading] drives the first-load spinner and the pull-to-refresh indicator.
 */
class HomeContentStore {
    val data = mutableStateMapOf<HomeBlockKind, HomeBlockData>()

    var isLoading by mutableStateOf(false)
        private set

    // The source + block set the current [data] belongs to, so we can skip a redundant reload.
    private var loadedKey: String? = null
    private var loadedBlocks: List<HomeBlockKind> = emptyList()

    /**
     * Ensure [blocks] are loaded for source [key]. A no-op when the data already matches (the
     * navigate-back fast path) unless [force]. Existing data stays visible while a reload runs, so the
     * screen never blanks; only a source switch clears it first (to avoid showing another account's
     * shelves).
     */
    suspend fun load(appContainer: AppContainer, key: String, blocks: List<HomeBlockKind>, force: Boolean) {
        val upToDate = key == loadedKey && blocks == loadedBlocks && data.isNotEmpty()
        if (!force && upToDate) return
        if (key != loadedKey) data.clear()

        isLoading = true
        try {
            loadBlocks(appContainer, key, blocks)
        } finally {
            isLoading = false
        }
    }

    /** Pull-to-refresh: re-pull the source's catalog, then reload every shelf from scratch. */
    suspend fun refresh(appContainer: AppContainer, key: String, blocks: List<HomeBlockKind>) {
        // Flip the flag *before* the slow source.refresh() so the refresh indicator appears immediately
        // rather than only once the catalog re-pull finishes (the "wheel takes a second to show" bug).
        isLoading = true
        try {
            runCatching { appContainer.activeMusicSource.refresh() }
            loadBlocks(appContainer, key, blocks)
        } finally {
            isLoading = false
        }
    }

    /** Refresh using the active source's current key + blocks — the nav-button ("re-tap Home") path. */
    suspend fun refreshActive(appContainer: AppContainer) {
        val source = appContainer.activeMusicSource
        refresh(appContainer, homeConfigKey(source), activeHomeBlocks(source))
    }

    /** Load [blocks] into [data] without touching [isLoading] (the callers own that flag). */
    private suspend fun loadBlocks(appContainer: AppContainer, key: String, blocks: List<HomeBlockKind>) {
        val results = coroutineScope {
            blocks.map { kind -> async { kind to runCatching { kind.load(appContainer) }.getOrNull() } }.awaitAll()
        }
        val fresh = results.mapNotNull { (kind, value) -> value?.let { kind to it } }.toMap()
        // Replace wholesale so blocks that were removed or now resolve empty drop out.
        data.keys.retainAll(fresh.keys)
        fresh.forEach { (kind, value) -> data[kind] = value }
        loadedKey = key
        loadedBlocks = blocks
    }
}
