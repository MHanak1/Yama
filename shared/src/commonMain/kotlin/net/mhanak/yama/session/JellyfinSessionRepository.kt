package net.mhanak.yama.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mhanak.yama.util.SecureStorage

class JellyfinSessionRepository(private val storage: SecureStorage) {
    private val key = "jellyfin_sessions"

    fun loadAll(): List<JellyfinSession> {
        val json = storage.getString(key) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<JellyfinSession>>(json) }
            .getOrElse { emptyList() }
    }

    fun save(session: JellyfinSession) {
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
