package net.mhanak.yama.coordinators

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.mhanak.yama.media.download.CatalogCache
import net.mhanak.yama.media.download.CatalogSnapshot
import net.mhanak.yama.media.download.DownloadRepository
import net.mhanak.yama.media.model.TrackUserDataStore
import net.mhanak.yama.media.playback.FavoriteOutbox
import net.mhanak.yama.media.playback.PlaybackController
import net.mhanak.yama.media.playback.ScrobbleOutbox
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable

/**
 * Wires the offline catalog + downloads to the active source:
 * - routes the local player's stream/artwork resolution through the downloads ladder,
 * - points availability at the active partition and hydrates its browse flows from the cached
 *   snapshot (so the catalog shows instantly and survives going offline),
 * - persists the source's browse flows on every emit (the disk tier of its SWR),
 * - once the active source is reachable, triggers staleness checks, outbox flushes, and the
 *   full favourite-set refresh.
 *
 * Keyed on `(source, downloadSourceKey)` so a source switch *or* a Jellyfin session change (which
 * leaves the source instance the same but changes the partition) both re-target everything.
 *
 * The [source] lambda is read inside [snapshotFlow] lambdas, which means Compose's snapshot system
 * tracks the underlying [activeMusicSource] mutableStateOf through the indirection transparently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSyncOrchestrator(
    private val source: () -> MusicSource,
    private val playback: PlaybackController,
    private val downloads: DownloadRepository,
    private val catalogCache: CatalogCache,
    private val userData: TrackUserDataStore,
    private val scrobbleOutbox: ScrobbleOutbox,
    private val favoriteOutbox: FavoriteOutbox,
    private val favorites: FavoritesCoordinator,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        wire()
    }

    private fun wire() {
        playback.local.resolver = downloads

        // Partition switch: re-target the download ladder, clear the user-data store (so hearts don't
        // leak across accounts), load the cached snapshot into the source's browse flows, and seed
        // offline favourites from the durable set.
        scope.launch(Dispatchers.Main) {
            snapshotFlow { source() to (source() as? OfflineCapable)?.downloadSourceKey() }
                .distinctUntilChanged()
                .collect { (src, key) ->
                    downloads.setActiveSourceKey(key)
                    // The store is partition-scoped: drop the previous partition's user-data (or hearts
                    // leak across accounts), then seed this one's favourites from the durable offline set
                    // so the overlay shows correct hearts offline even before any list is opened.
                    userData.clear()
                    if (key != null) {
                        catalogCache.loadSnapshot(key)?.let { (src as? OfflineCapable)?.hydrateCatalog(it) }
                        favorites.seedOfflineFavorites(key)
                    }
                }
        }

        // Snapshot persistence: write the source's browse flows through to the catalog cache on every
        // emit — the disk tier of the source's stale-while-revalidate.
        scope.launch {
            snapshotFlow { source() to (source() as? OfflineCapable)?.downloadSourceKey() }
                .distinctUntilChanged()
                .flatMapLatest { (src, key) ->
                    if (key == null) flowOf<Pair<String, CatalogSnapshot>?>(null)
                    else combine(src.albums, src.artists, src.albumArtists, src.genres, src.playlists) { a, ar, aa, g, p ->
                        key to CatalogSnapshot(a, ar, aa, g, p)
                    }
                }
                .collect { it?.let { (key, snap) -> if (!snap.isEmpty) catalogCache.saveSnapshot(key, snap) } }
        }

        // Reachable edge: once the active source comes online, run the staleness version-check pass
        // for its partition (at most once per source per session) so downloaded copies that changed
        // upstream re-fetch (pinned) or evict (cached), flush any offline scrobbles and favourite
        // changes that accumulated while it was unreachable, and rebuild the offline favourite-track
        // set from the backend so hearts are correct offline even for unopened containers.
        scope.launch(Dispatchers.Main) {
            snapshotFlow { source() }
                .flatMapLatest { src -> src.isReachable.map { reachable -> src to reachable } }
                .collect { (src, reachable) ->
                    if (reachable) {
                        downloads.refreshStaleness(src)
                        scrobbleOutbox.flush()
                        favoriteOutbox.flush()
                        favorites.refreshFavorites(src)
                    }
                }
        }
    }
}
