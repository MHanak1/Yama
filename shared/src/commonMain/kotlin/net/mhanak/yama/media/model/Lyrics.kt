package net.mhanak.yama.media.model

/**
 * A timed word-level cue within a [LyricsLine]. [lineStartIndex]/[lineEndIndex] are character
 * offsets into [LyricsLine.text] so renderers can build annotated strings without re-parsing.
 * Sources that provide word timing as a flat word list should reconstruct these offsets by
 * building [LyricsLine.text] as the words joined by spaces and tracking cumulative positions.
 */
data class LyricsCue(
    val startMs: Long,
    val endMs: Long?,
    val lineStartIndex: Int,
    val lineEndIndex: Int,
)

/**
 * One lyric line. [cues] is empty for line-synced sources; populated for word-synced ones.
 * Renderers distinguish the two cases by checking [cues].
 */
data class LyricsLine(
    val text: String,
    val startMs: Long,
    val cues: List<LyricsCue> = emptyList(),
)

sealed class Lyrics {
    /** Lyrics with at least line-level timestamps. May also carry word-level [LyricsCue]s. */
    data class Timed(val lines: List<LyricsLine>) : Lyrics()
    /** Lyrics with no timing information — display only. */
    data class Unsynced(val lines: List<String>) : Lyrics()
    /** The source has no lyrics for this track. */
    object None : Lyrics()
}
