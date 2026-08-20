package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.isTelevisionDevice
import net.mhanak.yama.supportsSystemTray
import net.mhanak.yama.ui.components.input.SegmentedButtonRow
import net.mhanak.yama.ui.screens.LaunchDestination
import net.mhanak.yama.util.AppPreferences

/**
 * "Behavior" settings: how the app acts rather than how it looks. Cross-platform, so — unlike the old
 * desktop-only "System" category it grew out of — it's shown everywhere; the individual rows gate
 * themselves (the tray row only on desktops with a working tray, the TV toggle only off real TVs).
 */
@Composable
fun BehaviorSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    Column(modifier = modifier) {

        // ── Startup ───────────────────────────────────────────────────
        // Which top-level screen opens after login. Not routed through AppContainer's reactive state
        // since it's only read once at NavHost start — a local mirror keeps the toggle in sync here.
        SettingsSectionHeader("Startup")
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Open on launch",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            var launch by remember { mutableStateOf(AppPreferences.launchDestination) }
            SegmentedButtonRow(
                options = LaunchDestination.entries,
                selectedOption = launch,
                onOptionSelected = { launch = it; AppPreferences.launchDestination = it },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(it.label) }
        }

        // ── Input ─────────────────────────────────────────────────────
        // A real TV reports itself via isTelevisionDevice() and always uses the TV layout, so the
        // manual opt-in is only offered on non-TV hardware (desktop / a phone with a controller).
        if (!isTelevisionDevice()) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            SettingsSectionHeader("Input")
            SettingToggle(
                title = "Controller / TV layout",
                subtitle = "Use D-pad and controller navigation instead of pointer",
                checked = appContainer.forceTvMode,
                onCheckedChange = { appContainer.forceTvMode = it },
            )
        }

        // ── Window ────────────────────────────────────────────────────
        // Desktop-only: read once here and mirror locally, since it's only consulted at window-close
        // time (in the desktop entrypoint) and doesn't need an AppContainer reactive field. Gated on a
        // working tray — with nothing to fall back to, closing the window would just quit.
        if (supportsSystemTray()) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            SettingsSectionHeader("Window")
            var hideToTray by remember { mutableStateOf(AppPreferences.hideToTrayOnClose) }
            SettingToggle(
                title = "Hide to tray on close",
                subtitle = "Keep Yama running in the system tray when you close the window",
                checked = hideToTray,
                onCheckedChange = { hideToTray = it; AppPreferences.hideToTrayOnClose = it },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
