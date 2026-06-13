package net.mhanak.yama.media.sources.local

import java.io.File

/** Audio container extensions we attempt to index. Lower-case, no leading dot. */
val AudioExtensions = setOf(
    "mp3", "flac", "m4a", "aac", "alac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "mp4",
)

/**
 * Platform default music folder(s) to seed the watch list on first run. Desktop → the user's home
 * "Music" folder. Android has no folder concept (it indexes via the system MediaStore, which already
 * spans all audio), so it returns empty.
 */
expect fun defaultMusicFolders(): List<String>

/**
 * Enumerate audio files for indexing. Genuinely platform-specific: desktop does a plain filesystem
 * walk of the watched [folders] (see [walkAudioFiles]); Android queries the MediaStore so it respects
 * scoped storage (the [folders] argument is unused there).
 */
expect fun scanAudioFiles(folders: List<String>): List<AudioFile>

/**
 * Recursively walk [folders] for audio files — the desktop scan, shared here because it's pure
 * `java.io`. [File.walkTopDown] works on every Android API level too (unlike `java.nio.file.Files`),
 * though only the desktop actual uses it. Symlinks aren't followed; missing/unreadable roots are
 * skipped so a stale watched folder can't fail a whole scan.
 */
fun walkAudioFiles(folders: List<String>): List<AudioFile> {
    val seen = HashSet<String>()
    val out = ArrayList<AudioFile>()
    for (folder in folders) {
        val root = File(folder)
        if (!root.exists() || !root.isDirectory) continue
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AudioExtensions }
            .forEach { file ->
                val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (seen.add(canonical)) {
                    out += AudioFile(
                        path = file.absolutePath,
                        lastModified = file.lastModified(),
                        sizeBytes = file.length(),
                    )
                }
            }
    }
    return out
}
