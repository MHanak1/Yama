package net.mhanak.yama.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.util.SecureStorage

/** Default ListenBrainz ingest host. Overridable so a self-hosted LB / Maloja (LB-compatible) server
 *  can be pointed at the same client. */
const val DEFAULT_LISTENBRAINZ_URL = "https://api.listenbrainz.org"

/**
 * The user's ListenBrainz credentials. The [userToken] is a secret (kept in [SecureStorage], never in
 * plain [net.mhanak.yama.util.AppPreferences]); [userName] is cached from a successful token
 * validation purely for display.
 */
@Serializable
data class ListenBrainzConfig(
    val userToken: String,
    val baseUrl: String = DEFAULT_LISTENBRAINZ_URL,
    val userName: String? = null,
)

/**
 * Persists the single [ListenBrainzConfig] in [SecureStorage] as a JSON blob under one key. Mirrors
 * [SubsonicSessionRepository]'s shape, but there is only ever one ListenBrainz account, so no list /
 * id handling — just [load] / [save] / [clear].
 */
class ListenBrainzConfigRepository(private val storage: SecureStorage) {
    private val key = "listenbrainz_config"

    fun load(): ListenBrainzConfig? {
        val json = storage.getString(key) ?: return null
        return runCatching { Json.decodeFromString<ListenBrainzConfig>(json) }.getOrNull()
    }

    fun save(config: ListenBrainzConfig) {
        storage.putString(key, Json.encodeToString(config))
    }

    fun clear() {
        storage.remove(key)
    }
}
