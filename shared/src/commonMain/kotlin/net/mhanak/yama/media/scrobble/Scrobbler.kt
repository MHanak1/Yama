package net.mhanak.yama.media.scrobble

import net.mhanak.yama.media.model.Track

/**
 * The metadata one listen carries. Deliberately service-agnostic (artist/title/release + optional
 * duration) — enough for ListenBrainz's server-side MessyBrainz mapping, and the same shape a future
 * AudioScrobbler (Last.fm / Libre.fm) implementation would need. No MBIDs: the [Track] model doesn't
 * carry them, and ListenBrainz resolves recordings from the text metadata.
 */
data class ListenMetadata(
    val trackName: String,
    val artistName: String,
    val releaseName: String?,
    val durationMs: Long?,
)

/** Result of validating a token against the service — [valid] plus the resolved account name. */
data class ValidationResult(val valid: Boolean, val userName: String?)

/**
 * A scrobbling backend. One implementation per wire protocol: [ListenBrainzScrobbler] speaks the
 * ListenBrainz `submit-listens` protocol (which also covers Maloja / self-hosted LB via a custom base
 * URL). A future `LastFmScrobbler` would speak AudioScrobbler 2.0 behind this same interface, so the
 * call sites in `AppContainer` never change.
 *
 * All methods are best-effort and must not throw — network/credential failures are folded into the
 * boolean / [ValidationResult] returns so callers can queue-and-retry.
 */
interface Scrobbler {
    /** Submit a single completed listen timestamped at [listenedAtEpochSec]. Returns true when the
     *  service accepted it (so a queued copy can be dropped), false to keep it for retry. */
    suspend fun submitListen(metadata: ListenMetadata, listenedAtEpochSec: Long): Boolean

    /** Push an ephemeral "now playing" update (no timestamp, never persisted). Best-effort. */
    suspend fun nowPlaying(metadata: ListenMetadata)

    /** Check that the configured token is valid and resolve the account name. */
    suspend fun validate(): ValidationResult
}

/** A track's scrobble metadata. `durationTicks` is treated as 100 ns ticks app-wide (`/10_000` = ms),
 *  matching the download/look-ahead code; omitted when unknown so it's simply absent from the listen. */
fun Track.toListenMetadata(): ListenMetadata = ListenMetadata(
    trackName = name,
    artistName = artists?.joinToString(", ").orEmpty(),
    releaseName = album,
    durationMs = durationTicks?.let { it / 10_000 },
)
