package net.mhanak.yama.media.sources.local

import android.media.MediaMetadataRetriever
import android.net.Uri
import net.mhanak.yama.MyApplication

// Android tag reading via the built-in MediaMetadataRetriever (no extra dependency). [path] is a
// MediaStore content:// URI produced by the scanner.
actual fun readTrackTags(path: String): TrackTags? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(MyApplication.appContext, Uri.parse(path))
        fun meta(key: Int): String? =
            retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }

        val artists = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?.split(';', '/', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        TrackTags(
            title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE),
            album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            artists = artists,
            genre = meta(MediaMetadataRetriever.METADATA_KEY_GENRE),
            trackNumber = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.parseIndex(),
            discNumber = meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.parseIndex(),
            year = (meta(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?: meta(MediaMetadataRetriever.METADATA_KEY_DATE))?.take(4)?.toIntOrNull(),
            durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            // METADATA_KEY_COMPILATION is "1" for compilation albums.
            isCompilation = meta(MediaMetadataRetriever.METADATA_KEY_COMPILATION) == "1",
            artwork = retriever.embeddedPicture,
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

// "3/12" -> 3, "07" -> 7, "" -> null
private fun String.parseIndex(): Int? = substringBefore('/').trim().toIntOrNull()
