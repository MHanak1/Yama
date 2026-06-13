package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
actual fun PlatformDeviceWakeEffect(onWake: () -> Unit) {
    val callback by rememberUpdatedState(onWake)
    // Skip the first ON_START (initial launch — the connection is already fresh) and fire on every
    // later one: that's the app returning to the foreground after the screen turned off, when the
    // backgrounded socket may be half-open. A screen-off/on cycle keeps the composition (and this
    // flag) alive, so the flag reliably distinguishes the launch from a wake.
    var started by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (started) callback() else started = true
    }
}
