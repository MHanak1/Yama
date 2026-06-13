package net.mhanak.yama.components

import androidx.compose.runtime.Composable

/**
 * Ensure the platform permission needed to read on-device audio is granted, requesting it once if
 * not, and report the outcome to [onResult]. Android requests READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE;
 * desktop needs no permission and reports granted immediately. Safe to host wherever the local source
 * becomes active — it only launches a request when actually needed.
 */
@Composable
expect fun RequestLocalAudioPermission(onResult: (Boolean) -> Unit = {})
