package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer

@Composable
fun PlaybackSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier) {
        // These three form one group, so no dividers between them — a divider only precedes the next
        // section header (matching Downloads / Scrobbling).
        SettingToggle(
            title = "Allow remote control",
            subtitle = "Let other clients play to this device (\"Play On\")",
            checked = appContainer.allowRemoteControl,
            onCheckedChange = { appContainer.allowRemoteControl = it },
        )
        SettingToggle(
            title = "Use device volume",
            subtitle = "Use the system volume instead of an in-app slider",
            checked = appContainer.useDeviceVolume,
            onCheckedChange = { appContainer.useDeviceVolume = it },
        )
        SettingToggle(
            title = "Keep screen on while playing",
            subtitle = "Stop the screen from dimming while the full player is open",
            checked = appContainer.keepScreenOn,
            onCheckedChange = { appContainer.keepScreenOn = it },
        )
        if (appContainer.activeMusicSource.supportsStreamingQuality) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            SettingsSectionHeader("Streaming")
            QualityDropdown(
                label = "Streaming quality",
                selected = appContainer.streamingQuality,
                onSelect = { appContainer.streamingQuality = it },
                supportingText = "Applies from the next track",
            )
        }
        // The Controller / TV layout toggle moved to Settings → Behavior.
        Spacer(Modifier.height(8.dp))
    }
}

