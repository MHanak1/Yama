package net.mhanak.yama.media.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * A track's *mutable* user-data, split off from its immutable identity/metadata ([Track]). A track
 * exists as many copies across the app (detail lists, the frozen playback queue, downloaded SQL rows,
 * the offline favourite set); each carried its own `favorite`/`playCount`, so one logical toggle had
 * to be fanned out by hand and every surface defensively re-seeded. This is the single shared value
 * those surfaces read through instead.
 */
data class TrackUserData(
    val favorite: Boolean = false,
    val playCount: Int = 0,
)

/**
 * The one in-memory owner of every track's [TrackUserData], keyed by track id. Surfaces *observe*
 * through it (so a toggle anywhere recomposes everywhere) and mutations *write* it exactly once; the
 * [Track] model keeps `favorite`/`playCount` only as a last-known-from-fetch seed, which the store
 * overrides wherever it has an entry. Persistence / outbox / backend become subscribers of the writes
 * rather than parallel writers.
 *
 * Scoped to the active source + partition: [clear] on a source/partition switch is mandatory or hearts
 * leak across accounts. Plain Kotlin (no Compose), so the favourites logic is unit-testable.
 */
interface TrackUserDataStore {
    /** Observe one track's user-data; emits null while unknown, so callers fall back to the model seed. */
    fun observe(id: String): StateFlow<TrackUserData?>

    /** The current user-data for [id], or null if the store has never been told about it. */
    fun current(id: String): TrackUserData?

    /** Set the full user-data for [id] (a toggle, a play-count bump). */
    fun set(id: String, data: TrackUserData)

    /** Bulk-seed from a freshly loaded list (online fetch, reconnect refresh). Overwrites per id. */
    fun merge(updates: Map<String, TrackUserData>)

    /**
     * Reconcile the favourite flag against a *complete* favourite set (the offline durable truth on
     * partition load, or the server's full list on reconnect): every id in [ids] becomes favourite,
     * and any entry currently favourite but absent from [ids] is un-favourited. Play counts are kept.
     */
    fun applyFavoriteSet(ids: Set<String>)

    /** Drop everything — called on a source/partition switch so the next partition starts clean. */
    fun clear()
}

/**
 * Default [TrackUserDataStore]: one [MutableStateFlow] per touched track id. The map stays bounded to
 * tracks the user has actually touched (browsed, played, favourited), not the whole library; a tiny
 * id→(Boolean,Int) map. Thread-safe (read/written from both the IO refresh paths and the Main UI taps).
 */
class InMemoryTrackUserDataStore : TrackUserDataStore {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<TrackUserData?>>()

    private fun flowFor(id: String): MutableStateFlow<TrackUserData?> =
        flows.computeIfAbsent(id) { MutableStateFlow(null) }

    override fun observe(id: String): StateFlow<TrackUserData?> = flowFor(id).asStateFlow()

    override fun current(id: String): TrackUserData? = flows[id]?.value

    override fun set(id: String, data: TrackUserData) {
        flowFor(id).value = data
    }

    override fun merge(updates: Map<String, TrackUserData>) {
        for ((id, data) in updates) flowFor(id).value = data
    }

    override fun applyFavoriteSet(ids: Set<String>) {
        for (id in ids) {
            val flow = flowFor(id)
            val cur = flow.value
            if (cur == null) flow.value = TrackUserData(favorite = true)
            else if (!cur.favorite) flow.value = cur.copy(favorite = true)
        }
        // Un-favourite anything we know about that the authoritative set no longer contains.
        for ((id, flow) in flows) {
            val cur = flow.value ?: continue
            if (cur.favorite && id !in ids) flow.value = cur.copy(favorite = false)
        }
    }

    override fun clear() {
        // Emit null first so any live observer falls back to its model seed, then drop the entries.
        for (flow in flows.values) flow.value = null
        flows.clear()
    }
}
