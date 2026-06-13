package net.mhanak.yama.media.sources.local

import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.LyricsLine

// [mm:ss.xx] or [mm:ss.xxx] or [mm:ss] timestamps; a line may carry several (repeated lyrics).
private val LrcTimestamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

/**
 * Parse a `.lrc` sidecar. Timestamped lines become [Lyrics.Timed]; a file with no timestamps at all
 * (or only `[xx:yy]` metadata tags) falls back to [Lyrics.Unsynced]. Word-level cues aren't produced
 * — `.lrc` is a line-synced format.
 */
fun parseLrc(text: String): Lyrics {
    val timed = ArrayList<LyricsLine>()
    val plain = ArrayList<String>()

    for (raw in text.lineSequence()) {
        val matches = LrcTimestamp.findAll(raw).toList()
        val lyric = raw.replace(LrcTimestamp, "").trim()
        if (matches.isEmpty()) {
            if (lyric.isNotEmpty()) plain += lyric
            continue
        }
        if (lyric.isEmpty()) continue // pure timestamp/metadata line
        for (m in matches) {
            val (min, sec, frac) = m.destructured
            val fracMs = when (frac.length) {
                0 -> 0
                1 -> frac.toInt() * 100
                2 -> frac.toInt() * 10
                else -> frac.take(3).toInt()
            }
            val startMs = (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
            timed += LyricsLine(text = lyric, startMs = startMs)
        }
    }

    return when {
        timed.isNotEmpty() -> Lyrics.Timed(timed.sortedBy { it.startMs })
        plain.isNotEmpty() -> Lyrics.Unsynced(plain)
        else -> Lyrics.None
    }
}
