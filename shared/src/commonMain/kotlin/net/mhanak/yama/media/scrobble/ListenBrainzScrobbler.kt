package net.mhanak.yama.media.scrobble

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.mhanak.yama.session.ListenBrainzConfig
import net.mhanak.yama.util.logger

/**
 * [Scrobbler] speaking the ListenBrainz `submit-listens` protocol
 * (https://listenbrainz.readthedocs.io). Also covers Maloja and self-hosted LB-compatible servers
 * when [ListenBrainzConfig.baseUrl] is changed.
 *
 * [config] is read on every call (not captured at construction) so a token/URL edited in settings
 * takes effect immediately without rebuilding the client. When it returns null (not set up yet), every
 * method is a no-op — `submitListen` returns false so the caller keeps the listen queued.
 */
class ListenBrainzScrobbler(private val config: () -> ListenBrainzConfig?) : Scrobbler {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val client: HttpClient = createListenBrainzHttpClient(json)
    private val log = logger("Scrobble")

    override suspend fun submitListen(metadata: ListenMetadata, listenedAtEpochSec: Long): Boolean {
        val cfg = config() ?: return false
        val request = LbSubmitRequest(
            listenType = "single",
            payload = listOf(metadata.toListen(listenedAtEpochSec)),
        )
        return runCatching { post(cfg, request).status.isSuccess() }
            .onFailure { log.warn("ListenBrainz submitListen failed for '${metadata.trackName}'", it) }
            .getOrDefault(false)
    }

    override suspend fun nowPlaying(metadata: ListenMetadata) {
        val cfg = config() ?: return
        val request = LbSubmitRequest(
            listenType = "playing_now",
            payload = listOf(metadata.toListen(listenedAtEpochSec = null)),
        )
        runCatching { post(cfg, request) }
            .onFailure { log.debug("ListenBrainz nowPlaying failed for '${metadata.trackName}'", it) }
    }

    override suspend fun validate(): ValidationResult {
        val cfg = config() ?: return ValidationResult(valid = false, userName = null)
        return runCatching {
            val resp: LbValidateResponse = client
                .get("${cfg.baseUrl.trimEnd('/')}/1/validate-token") {
                    header("Authorization", "Token ${cfg.userToken}")
                }.body()
            ValidationResult(resp.valid, resp.userName)
        }.onFailure { log.warn("ListenBrainz validate failed", it) }
            .getOrDefault(ValidationResult(valid = false, userName = null))
    }

    private suspend fun post(cfg: ListenBrainzConfig, request: LbSubmitRequest): HttpResponse =
        client.post("${cfg.baseUrl.trimEnd('/')}/1/submit-listens") {
            header("Authorization", "Token ${cfg.userToken}")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private fun ListenMetadata.toListen(listenedAtEpochSec: Long?) = LbListen(
        listenedAt = listenedAtEpochSec,
        trackMetadata = LbTrackMetadata(
            artistName = artistName,
            trackName = trackName,
            releaseName = releaseName,
            additionalInfo = LbAdditionalInfo(durationMs = durationMs),
        ),
    )
}

// --- Wire DTOs (ListenBrainz JSON; @SerialName maps snake_case) --------------------------------------

@Serializable
private data class LbSubmitRequest(
    @SerialName("listen_type") val listenType: String,
    val payload: List<LbListen>,
)

@Serializable
private data class LbListen(
    @SerialName("listened_at") val listenedAt: Long? = null,
    @SerialName("track_metadata") val trackMetadata: LbTrackMetadata,
)

@Serializable
private data class LbTrackMetadata(
    @SerialName("artist_name") val artistName: String,
    @SerialName("track_name") val trackName: String,
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("additional_info") val additionalInfo: LbAdditionalInfo? = null,
)

@Serializable
private data class LbAdditionalInfo(
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("media_player") val mediaPlayer: String = "Yama",
    @SerialName("submission_client") val submissionClient: String = "Yama",
)

@Serializable
private data class LbValidateResponse(
    val valid: Boolean = false,
    @SerialName("user_name") val userName: String? = null,
)

/** Platform-specific factory — implemented in androidMain and jvmMain (both use OkHttp), mirroring
 *  [net.mhanak.yama.media.sources.subsonic.createSubsonicHttpClient]. */
expect fun createListenBrainzHttpClient(json: Json): HttpClient
