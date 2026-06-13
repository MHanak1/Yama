package net.mhanak.yama.media.sources.local

import java.io.File

// Desktop: the conventional ~/Music directory if it exists.
actual fun defaultMusicFolders(): List<String> {
    val home = System.getProperty("user.home") ?: return emptyList()
    val music = File(home, "Music")
    return if (music.isDirectory) listOf(music.absolutePath) else emptyList()
}

// Desktop scan = a plain recursive filesystem walk of the watched folders.
actual fun scanAudioFiles(folders: List<String>): List<AudioFile> = walkAudioFiles(folders)
