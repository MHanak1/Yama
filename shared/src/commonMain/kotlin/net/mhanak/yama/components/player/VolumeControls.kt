package net.mhanak.yama.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import net.mhanak.yama.media.playback.Player

/**
 * Horizontal volume slider bound to [player]. Renders nothing when the player reports no volume
 * (null) — e.g. a controlled device that doesn't advertise a level. The thumb tracks the reported
 * level except mid-drag, where a local value is held; the change is committed on release (one command,
 * not a stream of them), and the committed value is held until the player's report catches up so a
 * slow (network) report doesn't snap the thumb back.
 */
@Composable
fun VolumeSlider(player: Player, modifier: Modifier = Modifier) {
    val volume by player.volume.collectAsState()
    val level = volume ?: return

    var dragValue by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(level) { pending?.let { if (kotlin.math.abs(level - it) < 0.02f) pending = null } }
    val shown = dragValue ?: pending ?: level

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (shown <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = "Volume",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = shown,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { player.setVolume(it); pending = it }
                dragValue = null
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A transient vertical volume bar pinned to the right edge, shown briefly when [remoteVolumeChange]
 * emits — i.e. only for volume changes triggered by a remote command, not local UI interaction.
 *
 * Place inside the root [Box] of the screen; it aligns itself to the center-right.
 */
@Composable
fun BoxScope.VolumeIndicator(volume: Float?, remoteVolumeChange: Flow<Unit>) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(remoteVolumeChange) {
        remoteVolumeChange.collect {
            visible = true
            delay(1_500)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && volume != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
    ) {
        VolumeBar(level = volume ?: 0f)
    }
}

@Composable
private fun VolumeBar(level: Float) {
    val clamped = level.coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 10.dp).height(180.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.weight(1f).width(6.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(clamped).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Icon(
                if (clamped <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
