package net.mhanak.yama.media.sources.local

/**
 * Metadata read from a single audio file's embedded tags. Platform-agnostic — produced by the
 * platform [readTrackTags] actuals (jaudiotagger on desktop, MediaMetadataRetriever on Android) and
 * consumed by the shared ingester in [LocalSource].
 */
data class TrackTags(
    val title: String?,
    val album: String?,
    val albumArtist: String?,
    val artists: List<String>,
    val genre: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long?,
    /** The compilation flag (ID3 TCMP / iTunes cpil / Vorbis COMPILATION). When set, the ingester
     * groups the album under "Various Artists" even though its tracks have differing artists. */
    val isCompilation: Boolean,
    /** Raw embedded cover-art bytes. The ingester extracts these to a cache file once per album. */
    val artwork: ByteArray?,
) {
    // ByteArray uses identity equality, which would break data-class equality for two equal reads.
    // Override so [artwork] compares by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackTags) return false
        return title == other.title &&
            album == other.album &&
            albumArtist == other.albumArtist &&
            artists == other.artists &&
            genre == other.genre &&
            trackNumber == other.trackNumber &&
            discNumber == other.discNumber &&
            year == other.year &&
            durationMs == other.durationMs &&
            isCompilation == other.isCompilation &&
            artwork.contentEquals(other.artwork)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + artists.hashCode()
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (discNumber ?: 0)
        result = 31 * result + (year ?: 0)
        result = 31 * result + (durationMs?.hashCode() ?: 0)
        result = 31 * result + isCompilation.hashCode()
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }
}

/** A discovered audio file on disk, before its tags are read. */
data class AudioFile(
    val path: String,
    val lastModified: Long,
    val sizeBytes: Long,
)
