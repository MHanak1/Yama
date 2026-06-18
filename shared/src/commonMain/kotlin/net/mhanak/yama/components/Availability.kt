package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import net.mhanak.yama.AppContainer
import net.mhanak.yama.media.model.Track

/**
 * A frame-stable view of "what is playable right now" for the active source, used to gray + gate play
 * on items the UI can't currently reach. There is no offline-mode toggle: an item is playable iff it's
 * downloaded *or* the source is reachable. While [reachable] everything is playable, so graying only
 * kicks in once the live link drops.
 *
 * Artist/genre fan-out is populated from each downloaded track's artist/genre IDs (Jellyfin tracks
 * now carry them via ItemFields.GENRES + artistItems), so artist/genre grids stay lit offline when
 * their tracks are downloaded.
 */
data class AvailabilitySnapshot(
    val reachable: Boolean = true,
    val trackIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val artistIds: Set<String> = emptySet(),
    val genreIds: Set<String> = emptySet(),
    // Pinned-only subsets for the download UI (DownloadButton, track row badge). Cached entries are
    // excluded so the "downloaded" indicator only lights up for explicit downloads.
    val pinnedTrackIds: Set<String> = emptySet(),
    val pinnedAlbumIds: Set<String> = emptySet(),
    val pinnedArtistIds: Set<String> = emptySet(),
    val pinnedGenreIds: Set<String> = emptySet(),
) {
    fun track(id: String) = reachable || id in trackIds
    fun album(id: String) = reachable || id in albumIds
    fun artist(id: String) = reachable || id in artistIds
    fun genre(id: String) = reachable || id in genreIds
    // Playlist availability isn't fanned out yet — gate purely on reachability.
    fun playlist(@Suppress("UNUSED_PARAMETER") id: String) = reachable

    fun isPlayable(kind: SelectableKind, id: String) = when (kind) {
        SelectableKind.Album -> album(id)
        SelectableKind.Artist -> artist(id)
        SelectableKind.Genre -> genre(id)
    }
}

/** Defaults to "everything reachable" so any surface read outside a provider behaves as fully online. */
val LocalAvailability = staticCompositionLocalOf { AvailabilitySnapshot() }

/**
 * Filter [tracks] to those playable right now — downloaded, or the source reachable. A non-composable
 * gate read straight from [appContainer], so play/enqueue handlers can drop unplayable tracks before
 * handing them to the player (the UI already dims them; this stops them from ever being queued offline).
 */
fun playableTracks(appContainer: AppContainer, tracks: List<Track>): List<Track> {
    if (appContainer.activeMusicSource.isReachable.value) return tracks
    val available = appContainer.downloads.availableTrackIds.value
    return tracks.filter { it.id in available }
}

/** Collect the active source's reachability + the download availability sets into one snapshot. Read
 *  in [net.mhanak.yama.App] and provided via [LocalAvailability]. */
@Composable
fun rememberAvailability(appContainer: AppContainer): AvailabilitySnapshot {
    val reachable by appContainer.activeMusicSource.isReachable.collectAsState()
    val tracks by appContainer.downloads.availableTrackIds.collectAsState()
    val albums by appContainer.downloads.availableAlbumIds.collectAsState()
    val artists by appContainer.downloads.availableArtistIds.collectAsState()
    val genres by appContainer.downloads.availableGenreIds.collectAsState()
    val pinnedTracks by appContainer.downloads.pinnedTrackIds.collectAsState()
    val pinnedAlbums by appContainer.downloads.pinnedAlbumIds.collectAsState()
    val pinnedArtists by appContainer.downloads.pinnedArtistIds.collectAsState()
    val pinnedGenres by appContainer.downloads.pinnedGenreIds.collectAsState()
    return remember(reachable, tracks, albums, artists, genres, pinnedTracks, pinnedAlbums, pinnedArtists, pinnedGenres) {
        AvailabilitySnapshot(reachable, tracks, albums, artists, genres, pinnedTracks, pinnedAlbums, pinnedArtists, pinnedGenres)
    }
}
