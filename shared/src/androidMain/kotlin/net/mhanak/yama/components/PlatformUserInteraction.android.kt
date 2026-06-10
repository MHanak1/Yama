package net.mhanak.yama.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Process-wide fan-out for `Activity.onUserInteraction()`. `MainActivity` forwards every user
 * interaction (touch, key, D-pad, trackball) here; [PlatformUserInteractionEffect] subscribes while
 * composed. Lives in the shared module so the activity (which depends on it) can call in.
 */
object UserInteractionBus {
    private val listeners = mutableSetOf<() -> Unit>()

    fun register(listener: () -> Unit) { listeners += listener }
    fun unregister(listener: () -> Unit) { listeners -= listener }
    fun notifyInteraction() { listeners.toList().forEach { it() } }
}

@Composable
actual fun PlatformUserInteractionEffect(onInteraction: () -> Unit) {
    val current by rememberUpdatedState(onInteraction)
    DisposableEffect(Unit) {
        val listener = { current() }
        UserInteractionBus.register(listener)
        onDispose { UserInteractionBus.unregister(listener) }
    }
}
