package net.mhanak.yama.components

import androidx.compose.runtime.Composable

// Android indexes the whole MediaStore, so there's no per-folder picker in this first pass.
actual val supportsDirectoryPicker: Boolean = false

@Composable
actual fun rememberDirectoryPicker(onResult: (String?) -> Unit): () -> Unit = {}
