package net.mhanak.yama.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.util.SecureStorage

/** Persists a list of [SubsonicSession]s in [SecureStorage] as a JSON array. Mirrors
 *  [JellyfinSessionRepository] exactly — single key, upsert-by-id, delete-by-id. */
class SubsonicSessionRepository(private val storage: SecureStorage) {
    private val key = "subsonic_sessions"

    fun loadAll(): List<SubsonicSession> {
        val json = storage.getString(key) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<SubsonicSession>>(json) }
            .getOrElse { emptyList() }
    }

    fun save(session: SubsonicSession) {
        val sessions = loadAll().toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions.add(session)
        storage.putString(key, Json.encodeToString(sessions))
    }

    fun delete(id: String) {
        val sessions = loadAll().filter { it.id != id }
        storage.putString(key, Json.encodeToString(sessions))
    }
}
