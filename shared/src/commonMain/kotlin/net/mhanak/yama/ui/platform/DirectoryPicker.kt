package net.mhanak.yama.ui.platform

import androidx.compose.runtime.Composable

/**
 * Whether this platform offers a folder picker for the local-files source. Desktop → true (native
 * directory chooser); Android → true (the SAF tree picker). Left as a seam so a future platform
 * without folder selection can return false.
 */
expect val supportsDirectoryPicker: Boolean

/**
 * Returns a launcher that opens the platform folder chooser and reports the chosen folder to
 * [onResult] (or null if cancelled). The reported string is whatever the scanner walks: an absolute
 * path on desktop, a persisted SAF tree Uri on Android. A no-op where [supportsDirectoryPicker] is false.
 */
@Composable
expect fun rememberDirectoryPicker(onResult: (String?) -> Unit): () -> Unit

/**
 * A human-readable label for a watched-folder entry, for the settings list. Desktop returns the
 * absolute path as-is (already readable); Android decodes the opaque SAF tree Uri to a folder path
 * like "Music/Rock". The raw stored string is kept for the scanner — this is presentation only.
 */
expect fun folderDisplayName(path: String): String
