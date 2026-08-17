package net.mhanak.yama.ui.platform

import androidx.compose.runtime.Composable

/**
 * Ensure any platform permission needed to read on-device audio is granted, requesting it once if
 * not, and report the outcome to [onResult]. Both targets now report granted immediately: desktop
 * needs no permission, and Android's local source is SAF folder-based (access comes from the picked
 * folder's persisted tree-Uri grant, not a runtime permission). Kept as a seam in case a future
 * platform needs an actual request. Safe to host wherever the local source becomes active.
 */
@Composable
expect fun RequestLocalAudioPermission(onResult: (Boolean) -> Unit = {})
