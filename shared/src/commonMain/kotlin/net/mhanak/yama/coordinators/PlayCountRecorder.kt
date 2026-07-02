package net.mhanak.yama.coordinators

import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.model.TrackUserData
import net.mhanak.yama.media.model.TrackUserDataStore
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.local.LocalLibraryStore
import net.mhanak.yama.media.sources.local.LocalSource

/**
 * Records a completed local play: bumps the [userData] store (so every surface showing this track's
 * play count updates immediately), then persists to the offline row for sort-by-plays. The server
 * count stays authoritative online; offline plays reach the server via [ScrobbleOutbox].
 *
 * Pure: no coroutine scope of its own — invoked from [AppContainer]'s PlaybackReporter callback which
 * already runs in a fire-and-forget context.
 */
class PlayCountRecorder(
    private val source: () -> MusicSource,
    private val userData: TrackUserDataStore,
    private val libraryStore: LocalLibraryStore,
) {
    fun recordLocalPlay(track: Track) {
        // Bump the live store first so any surface showing this track's play count updates at once, then
        // persist to the offline row below (the durable count used for offline sort-by-plays).
        val cur = userData.current(track.id) ?: TrackUserData(track.favorite, track.playCount)
        userData.set(track.id, cur.copy(playCount = cur.playCount + 1))
        when (val src = source()) {
            is LocalSource -> src.recordPlay(track.id)
            else -> {
                val key = (src as? OfflineCapable)?.downloadSourceKey() ?: return
                val row = libraryStore.get(key, track.id) ?: return
                libraryStore.put(row.copy(playCount = row.playCount + 1, lastPlayedAt = System.currentTimeMillis()))
            }
        }
    }
}
