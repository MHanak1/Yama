package net.mhanak.yama.components.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.glassEffect
import net.mhanak.yama.components.glassSource
import net.mhanak.yama.media.playback.RemotePlaybackProvider
import net.mhanak.yama.media.playback.RemoteTarget

/**
 * Bottom sheet for choosing where playback happens: "This device" or one of the source's discovered
 * cast targets ([RemotePlaybackProvider.remoteTargets]). Selecting one routes through
 * `PlaybackController.selectTarget`, swapping the active player. Shown only while the active source
 * supports casting.
 *
 * It also hosts a [VolumeSlider] for the currently active player — the local engine volume when
 * playing here, or the controlled device's volume when casting (whichever the active player exposes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackTargetSheet(onDismiss: () -> Unit) {
    val appContainer = LocalAppContainer.current
    val provider = appContainer.activeMusicSource as? RemotePlaybackProvider
    val targets by (provider?.remoteTargets ?: EMPTY_TARGETS).collectAsState()
    val activeTarget = appContainer.playback.activeTarget

    // The live push can be stale right after backgrounding; pull a fresh snapshot when the sheet opens
    // so the device list (and the controlled device's reported volume) is current.
    LaunchedEffect(Unit) { provider?.refreshTargets() }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassEffect(sheetContainerColor, sheetShape)
                .glassSource(zIndex = 3f),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Play on",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )

                // While the live link is down the discovered device list (and any controlled device's
                // reported state) is stale; surface that here regardless of whether we're already casting.
                RemoteConnectionIndicator(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    requireCasting = false,
                )

                TargetRow(
                    icon = Icons.Filled.Smartphone,
                    label = "This device",
                    selected = activeTarget == null,
                    onClick = {
                        appContainer.playback.selectTarget(null)
                        onDismiss()
                    },
                )

                targets.forEach { target ->
                    TargetRow(
                        icon = Icons.Filled.Speaker,
                        label = target.name,
                        subtitle = target.client,
                        selected = activeTarget?.id == target.id,
                        onClick = {
                            appContainer.playback.selectTarget(target)
                            onDismiss()
                        },
                    )
                }

                if (targets.isEmpty()) {
                    Text(
                        "No other devices found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }

                VolumeSlider(
                    player = appContainer.playback.active,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )

                Spacer(Modifier.height(8.dp).navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun TargetRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = {
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = tint)
        },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        trailingContent = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = tint) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

// Stand-in for sources that can't cast, so the sheet still renders "This device".
private val EMPTY_TARGETS = MutableStateFlow<List<RemoteTarget>>(emptyList())
