package net.mhanak.yama.media.sources.local

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import net.mhanak.yama.MyApplication

// Android tag reading via the built-in MediaMetadataRetriever (no extra dependency). [path] is a SAF
// document content:// URI produced by the scanner (readable via the folder's persisted tree grant).
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

// Android has no dependency-free embedded-lyrics reader: MediaMetadataRetriever exposes no lyrics key.
// Left null pending an approach (jaudiotagger over a content-stream copy, or a hand-rolled ID3/Vorbis
// lyric-frame parser). Sidecar .lrc (below) covers most cases now that folders are SAF-addressable.
actual fun readEmbeddedLyrics(path: String): String? = null

// Android sidecar-lyrics read: the audio file is a SAF document Uri, so the sibling .lrc is the same
// document id with its extension swapped (the external-storage provider encodes the path in the id,
// e.g. "primary:Music/x.mp3" -> "primary:Music/x.lrc"). Building the sibling Uri under the same tree
// keeps it covered by the persisted grant; opening it fails cleanly (→ null) when no sidecar exists.
actual fun readSidecarLyrics(path: String): String? {
    val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
    // Only SAF tree-document Uris carry a resolvable sibling; a non-tree Uri has no getTreeDocumentId.
    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    if (!docId.contains('.')) return null
    val lrcDocId = docId.substringBeforeLast('.') + ".lrc"
    if (lrcDocId == docId) return null
    val authority = uri.authority ?: return null
    val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocId)
    val lrcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, lrcDocId)
    return runCatching {
        MyApplication.appContext.contentResolver.openInputStream(lrcUri)?.use { it.readBytes().decodeToString() }
    }.getOrNull()
}

// "3/12" -> 3, "07" -> 7, "" -> null
private fun String.parseIndex(): Int? = substringBefore('/').trim().toIntOrNull()
