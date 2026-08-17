package net.mhanak.yama.media.sources.local

/**
 * Read embedded tags (ID3 / Vorbis / FLAC / MP4) and embedded cover art from a single audio file.
 * Returns null when the file can't be parsed at all (so the caller can fall back to the filename).
 * Genuinely platform-specific: jaudiotagger on desktop, MediaMetadataRetriever on Android.
 */
expect fun readTrackTags(path: String): TrackTags?

/**
 * Read the embedded lyric tag (ID3 USLT / Vorbis LYRICS / MP4 ©lyr) from a single audio file, or null
 * when absent or unreadable. The raw text may itself be LRC-formatted (line timestamps) — the caller
 * runs it through [parseLrc], which yields synced [net.mhanak.yama.media.model.Lyrics.Timed] when
 * timestamps are present and plain [net.mhanak.yama.media.model.Lyrics.Unsynced] otherwise.
 *
 * Called lazily by [LocalSource.getLyrics] (once, on demand), not during the scan — so a per-file read
 * here is cheap. Desktop reads via jaudiotagger; Android has no dependency-free embedded-lyric reader.
 */
expect fun readEmbeddedLyrics(path: String): String?

/**
 * Read a `.lrc` sidecar sitting next to the audio file at [path] — same basename, `.lrc` extension —
 * or null when there is none (or it can't be read). Desktop reads the sibling [java.io.File] directly;
 * Android resolves the sibling document within the audio file's SAF tree (addressable now that the
 * local source is folder-based). The raw text goes through [parseLrc], so a synced `.lrc` yields
 * [net.mhanak.yama.media.model.Lyrics.Timed]. Called lazily by [LocalSource.getLyrics].
 */
expect fun readSidecarLyrics(path: String): String?
