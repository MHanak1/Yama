package net.mhanak.yama.session

import kotlinx.serialization.Serializable

/**
 * Persisted credentials for one Subsonic account. The password is stored (via the surrounding
 * [SubsonicSessionRepository] → [net.mhanak.yama.util.SecureStorage] chain) because Subsonic's
 * token-auth scheme (`t = md5(password+salt)`) requires the plaintext password at request time.
 * SecureStorage uses EncryptedSharedPreferences on Android and libsecret/DPAPI on desktop, so
 * the password never lands in plain app storage.
 */
@Serializable
data class SubsonicSession(
    /** Local UUID — the stable identity used as [AccountedSource.currentAccountId]. */
    val id: String,
    /** Normalised server base URL, e.g. `https://music.example.com:4533`. No trailing slash. */
    val serverUrl: String,
    val serverName: String?,
    val username: String,
    /** Plaintext password required for per-request salt+md5 token generation. */
    val password: String,
    /** Subsonic API version negotiated during login, e.g. `"1.16.1"`. */
    val apiVersion: String,
    /** Whether the server supports OpenSubsonic extensions (detected via getOpenSubsonicExtensions). */
    val openSubsonic: Boolean,
)
