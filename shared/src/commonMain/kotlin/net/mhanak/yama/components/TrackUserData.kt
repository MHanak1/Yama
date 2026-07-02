package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.model.TrackUserDataStore

/**
 * The app-wide [TrackUserDataStore], provided in `App.kt` from `AppContainer`. Defaults to an empty
 * store so a surface read outside a provider just falls back to the model seed.
 */
val LocalTrackUserData = staticCompositionLocalOf<TrackUserDataStore> {
    error("TrackUserDataStore not provided")
}

/**
 * Reconcile a model [track] with the shared [TrackUserDataStore]: where the store has an entry for this
 * id, its `favorite`/`playCount` override the (possibly stale) values frozen into the model copy. With
 * no entry the model is returned unchanged — its fields are the last-known-from-fetch seed. Reading
 * tracks through this is what keeps every surface (lists, the playback queue, the player) consistent
 * after a toggle made anywhere, with no manual patching.
 */
@Composable
fun rememberReconciled(track: Track): Track {
    val store = LocalTrackUserData.current
    val data by remember(store, track.id) { store.observe(track.id) }.collectAsState()
    val userData = data ?: return track
    return remember(track, userData) {
        track.copy(favorite = userData.favorite, playCount = userData.playCount)
    }
}

/**
 * The live favourite flag for a track id, overlaying the store on a [fallback] model seed. A lighter
 * [rememberReconciled] for surfaces that only need the heart (the player's [FavoriteButton]).
 */
@Composable
fun rememberTrackFavorite(id: String, fallback: Boolean): Boolean {
    val store = LocalTrackUserData.current
    val data by remember(store, id) { store.observe(id) }.collectAsState()
    return data?.favorite ?: fallback
}
