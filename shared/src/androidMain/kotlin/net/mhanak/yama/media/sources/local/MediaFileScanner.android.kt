package net.mhanak.yama.media.sources.local

import android.content.ContentUris
import android.provider.MediaStore
import net.mhanak.yama.MyApplication

// Android indexes via the system MediaStore (which already spans every audio folder), so there's no
// user-facing watched-folder concept to seed.
actual fun defaultMusicFolders(): List<String> = emptyList()

/**
 * Android scan = a MediaStore query for music items, the scoped-storage-correct way to enumerate
 * audio (needs the READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE permission). Each item is referenced by
 * its `content://` URI — which MediaMetadataRetriever, ExoPlayer and Coil all consume directly — so
 * we never touch raw file paths. The [folders] argument is unused here.
 */
actual fun scanAudioFiles(folders: List<String>): List<AudioFile> {
    val resolver = MyApplication.appContext.contentResolver
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.SIZE,
    )
    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val out = ArrayList<AudioFile>()
    resolver.query(collection, projection, selection, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        while (cursor.moveToNext()) {
            val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
            out += AudioFile(
                path = uri.toString(),
                lastModified = cursor.getLong(modCol) * 1000, // DATE_MODIFIED is epoch seconds
                sizeBytes = cursor.getLong(sizeCol),
            )
        }
    }
    return out
}
