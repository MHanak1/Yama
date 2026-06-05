package net.mhanak.yama.util

import androidx.compose.runtime.Composable

@Composable
expect fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit)