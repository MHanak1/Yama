package net.mhanak.yama.coordinators

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.mhanak.yama.media.download.CatalogCache
import net.mhanak.yama.media.model.TrackUserData
import net.mhanak.yama.media.model.TrackUserDataStore
import net.mhanak.yama.media.playback.FavoriteOutbox
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.TrackSortOrder
import net.mhanak.yama.media.sources.local.LocalLibraryStore

/**
 * Single seam for all favourite-toggle writes and the per-session server-favourites refresh.
 *
 * For tracks: writes [userData] (the in-memory overlay) immediately on the calling thread so the UI
 * update is instantaneous, then fans out to the offline durable stores ([CatalogCache], [libraryStore])
 * and the backend ([MusicSource.setFavorite]) on a background IO coroutine. Non-track kinds skip the
 * offline stores and go straight to the source + outbox.
 *
 * [refreshFavorites] rebuilds the complete offline favourite-track set from the server once per
 * partition per session, merging pending [FavoriteOutbox] entries on top so a toggle not yet replayed
 * isn't lost by server data that doesn't reflect it yet.
 */
class FavoritesCoordinator(
    private val source: () -> MusicSource,
    private val userData: TrackUserDataStore,
    private val catalogCache: CatalogCache,
    private val libraryStore: LocalLibraryStore,
    private val favoriteOutbox: FavoriteOutbox,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Partitions whose favourite set has been refreshed from the server this session, so
    // [refreshFavorites] runs its full pass at most once per source per launch (it pages the backend).
    private val favoritesRefreshed = mutableSetOf<String>()

    /**
     * Toggle the favourite state for a library item — the single seam the UI calls instead of the source
     * directly. It (1) persists the new state offline for tracks — in the catalog cache's favourite-track
     * set (the offline source of truth, [CatalogCache.favoriteTrackIds]) and on the downloaded row if any
     * — so the heart survives a restart and shows offline; (2) writes through the active source (which
     * updates its cached browse lists and, when online, the backend); and (3) queues the change in
     * [favoriteOutbox], which only persists it while offline and replays it on reconnect.
     */
    fun setFavorite(kind: FavoritableKind, id: String, favorite: Boolean) {
        val src = source()
        if (kind == FavoritableKind.Track) {
            val cur = userData.current(id) ?: TrackUserData(favorite, playCount = 0)
            userData.set(id, cur.copy(favorite = favorite))
        }
        scope.launch {
            if (kind == FavoritableKind.Track) {
                (src as? OfflineCapable)?.downloadSourceKey()?.let { key ->
                    // The durable offline source of truth for track hearts (shown wherever a track appears
                    // offline, even in containers whose track lists were never cached).
                    catalogCache.setTrackFavorite(key, id, favorite)
                    libraryStore.get(key, id)?.let { libraryStore.put(it.copy(favorite = favorite)) }
                }
            }
            runCatching { (src as? FavoriteCapable)?.setFavorite(kind, id, favorite) }
            favoriteOutbox.record(kind, id, favorite)
        }
    }

    /**
     * Seed the live [userData] store from the durable offline favourite set for [key] so hearts are
     * correct offline even before any list is opened. Called by [OfflineSyncOrchestrator] on every
     * partition switch.
     */
    fun seedOfflineFavorites(key: String) {
        userData.applyFavoriteSet(catalogCache.favoriteTrackIds(key))
    }

    /**
     * Rebuild the offline favourite-track set ([CatalogCache.favoriteTrackIds]) for a reachable source
     * from the backend's complete list of favourite tracks, so hearts are correct offline even for
     * containers never opened online (the second half of making track favourites work offline; the
     * first is persisting each toggle in [setFavorite]). Runs at most once per partition per session.
     *
     * Pending offline toggles ([FavoriteOutbox]) are read first and merged on top of the server set, so
     * a change not yet replayed isn't dropped by server data that doesn't reflect it yet (the outbox's
     * concurrent [FavoriteOutbox.flush] may ack/clear entries while this runs).
     */
    fun refreshFavorites(src: MusicSource) {
        val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
        if (!src.isReachable.value || (src as? FavoriteCapable)?.supportsFavorites(FavoritableKind.Track) != true) return
        synchronized(favoritesRefreshed) { if (!favoritesRefreshed.add(key)) return }
        scope.launch {
            val pending = favoriteOutbox.pendingTrackFavorites(key)
            val serverFavs = runCatching { fetchAllFavoriteTrackIds(src) }.getOrNull()
            if (serverFavs == null) {
                synchronized(favoritesRefreshed) { favoritesRefreshed.remove(key) } // let a later reconnect retry
                return@launch
            }
            val merged = serverFavs.toMutableSet()
            for ((id, fav) in pending) if (fav) merged.add(id) else merged.remove(id)
            catalogCache.replaceFavoriteTrackIds(key, merged)
            // Reflect the server's full favourite truth in the live store too, so hearts already on
            // screen update on reconnect (and changes made on another device land without a re-fetch).
            userData.applyFavoriteSet(merged)
        }
    }

    /** Page the source's favourite tracks into a flat id set (favourites can be large; one page at a time). */
    private suspend fun fetchAllFavoriteTrackIds(src: MusicSource): Set<String> {
        val ids = HashSet<String>()
        val pageSize = 500
        var offset = 0
        while (true) {
            val page = src.getAllTracks(pageSize, offset, TrackSortOrder.Alphabetical, favoritesOnly = true)
            if (page.isEmpty()) break
            page.mapTo(ids) { it.id }
            if (page.size < pageSize) break
            offset += pageSize
        }
        return ids
    }
}
