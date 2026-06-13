package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * Whether this platform offers a folder picker for the local-files source. Desktop → true (native
 * directory chooser); Android → false in a first pass (it indexes the whole MediaStore, so there's
 * no per-folder selection yet — adding SAF tree-folder support is a later seam).
 */
expect val supportsDirectoryPicker: Boolean

/**
 * Returns a launcher that opens a native directory chooser and reports the chosen absolute path
 * (or null if cancelled) to [onResult]. A no-op where [supportsDirectoryPicker] is false.
 */
@Composable
expect fun rememberDirectoryPicker(onResult: (String?) -> Unit): () -> Unit
