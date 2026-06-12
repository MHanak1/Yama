package net.mhanak.yama.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.playback.RemotePlaybackProvider

/**
 * A compact "reconnecting" badge for when the active source's live link to the server is down. While
 * disconnected, a remote ("Play On") session's now-playing / queue / seekbar can't update (see
 * [RemotePlaybackProvider.connected]), so this tells the user the state may be stale rather than
 * presenting it as live. A gentle alpha pulse marks it as an in-progress state, not a hard error.
 *
 * Renders nothing while connected. By default it only appears while actually casting; the cast sheet
 * passes [requireCasting] = false so a stale device list is flagged there too.
 */
@Composable
fun RemoteConnectionIndicator(
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    requireCasting: Boolean = true,
) {
    val appContainer = LocalAppContainer.current
    val provider = appContainer.activeMusicSource as? RemotePlaybackProvider
    val connected by (provider?.connected ?: ALWAYS_CONNECTED).collectAsState()
    val casting = appContainer.playback.activeTarget != null
    val visible = provider != null && !connected && (casting || !requireCasting)

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val pulse = rememberInfiniteTransition(label = "reconnecting")
        val pulseAlpha by pulse.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "reconnecting-alpha",
        )
        Row(
            modifier = modifier.alpha(pulseAlpha),
            horizontalArrangement = Arrangement.spacedBy(4.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = "Reconnecting",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp * scale),
            )
            Text(
                "Reconnecting…",
                style = MaterialTheme.typography.labelSmall.scaled(scale),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private val ALWAYS_CONNECTED = MutableStateFlow(true)
