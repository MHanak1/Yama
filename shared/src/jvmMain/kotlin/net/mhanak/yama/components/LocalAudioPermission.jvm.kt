package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// Desktop reads the filesystem directly — no runtime permission gate.
@Composable
actual fun RequestLocalAudioPermission(onResult: (Boolean) -> Unit) {
    LaunchedEffect(Unit) { onResult(true) }
}
