package net.mhanak.yama.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun RequestLocalAudioPermission(onResult: (Boolean) -> Unit) {
    // The local source is now SAF folder-based (see DirectoryPicker/MediaFileScanner): access is granted
    // per-folder by the persisted tree Uri permission, so there is no longer any READ_MEDIA_AUDIO /
    // READ_EXTERNAL_STORAGE runtime permission to request. Report granted immediately so App.kt's
    // post-grant rescan still fires (harmless when no folders are picked yet — it just finds nothing).
    LaunchedEffect(Unit) { onResult(true) }
}
