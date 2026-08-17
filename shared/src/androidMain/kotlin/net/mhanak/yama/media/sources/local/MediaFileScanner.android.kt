package net.mhanak.yama.media.sources.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import net.mhanak.yama.MyApplication

// Android has no auto-seeded folder: the user picks watched folders via the SAF tree picker
// (DirectoryPicker.android), exactly like desktop. Empty means "nothing indexed until you add one".
actual fun defaultMusicFolders(): List<String> = emptyList()

/**
 * Android scan = a recursive walk of each watched SAF **tree Uri** (produced by the folder picker),
 * the scoped-storage-correct way to enumerate a user-chosen folder. Each [folders] entry is a tree
 * Uri string; each discovered audio file is referenced by its SAF **document Uri** — which
 * MediaMetadataRetriever, ExoPlayer and Coil all consume directly (with the persisted read grant), so
 * we never touch raw file paths. Mirrors the desktop [walkAudioFiles] behaviour over `content://`.
 */
actual fun scanAudioFiles(folders: List<String>): List<AudioFile> {
    val resolver = MyApplication.appContext.contentResolver
    val out = ArrayList<AudioFile>()
    for (folder in folders) {
        val treeUri = runCatching { Uri.parse(folder) }.getOrNull() ?: continue
        // The tree's own document id is the traversal root. Guarded: a revoked/invalid grant (e.g. the
        // folder was deleted, or a stale MediaStore path from before the SAF switch) just skips.
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
        walkTree(resolver, treeUri, rootDocId, out)
    }
    return out
}

// Breadth-first walk of a SAF tree using the fast cursor API (bulk column query per directory), rather
// than DocumentFile — which issues one IPC per node and is far too slow for a music library.
private fun walkTree(resolver: ContentResolver, treeUri: Uri, rootDocId: String, out: MutableList<AudioFile>) {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val dirs = ArrayDeque<String>().apply { add(rootDocId) }
    while (dirs.isNotEmpty()) {
        val dirId = dirs.removeFirst()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        runCatching {
            resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val docId = c.getString(idCol) ?: continue
                    if (c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        dirs.add(docId) // recurse into subfolders
                        continue
                    }
                    val name = c.getString(nameCol) ?: continue
                    if (name.substringAfterLast('.', "").lowercase() !in AudioExtensions) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    out += AudioFile(
                        path = docUri.toString(),
                        // COLUMN_LAST_MODIFIED is epoch millis (like desktop File.lastModified()), so the
                        // incremental-rescan comparison in LocalSource stays consistent across platforms.
                        lastModified = if (c.isNull(modCol)) 0L else c.getLong(modCol),
                        sizeBytes = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
                    )
                }
            }
        }
    }
}
