package net.mhanak.yama.media.sources.local

/**
 * Read embedded tags (ID3 / Vorbis / FLAC / MP4) and embedded cover art from a single audio file.
 * Returns null when the file can't be parsed at all (so the caller can fall back to the filename).
 * Genuinely platform-specific: jaudiotagger on desktop, MediaMetadataRetriever on Android.
 */
expect fun readTrackTags(path: String): TrackTags?
