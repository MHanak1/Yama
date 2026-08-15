package net.mhanak.yama.ui.views.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.BuildInfo
import net.mhanak.yama.ui.components.interaction.ContentFocusHost

private const val REPO_URL = "https://github.com/MHanak1/Yama"
private const val LICENSE_URL = "https://github.com/MHanak1/Yama/blob/main/LICENSE"

/** One third-party dependency and the license it ships under. Ordered copyleft-first, since those
 *  are the ones that carry real obligations; the remainder are permissive (Apache-2.0). */
private data class Attribution(val name: String, val license: String, val url: String)

private val ATTRIBUTIONS = listOf(
    Attribution("vlcj", "GPL-3.0 (desktop audio playback)", "https://github.com/caprica/vlcj"),
    Attribution("libVLC", "LGPL-2.1+ (bundled/system, desktop)", "https://www.videolan.org/vlc/libvlc.html"),
    Attribution("Jellyfin Kotlin SDK", "LGPL-3.0", "https://github.com/jellyfin/jellyfin-sdk-kotlin"),
    Attribution("AndroidX Media3 (ExoPlayer)", "Apache-2.0 (Android playback)", "https://github.com/androidx/media"),
    Attribution("Kotlin, Coroutines & Serialization", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    Attribution("Compose Multiplatform", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
    Attribution("Ktor", "Apache-2.0", "https://github.com/ktorio/ktor"),
    Attribution("SQLDelight", "Apache-2.0", "https://github.com/sqldelight/sqldelight"),
    Attribution("OkHttp & Moshi", "Apache-2.0", "https://github.com/square"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutView(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 0.dp,
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        ContentFocusHost(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App identity.
            Text("Yama", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Version ${BuildInfo.VERSION}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "A Kotlin Multiplatform music client for Jellyfin, Subsonic, local files and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            // License card.
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("License", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Yama is free software licensed under the GNU General Public License, " +
                            "version 3 (GPLv3).\n\n" +
                            "This program is distributed in the hope that it will be useful, but " +
                            "WITHOUT ANY WARRANTY; without even the implied warranty of " +
                            "MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. You may " +
                            "redistribute and/or modify it under the terms of the GPL.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinkRow("View full license text", onClick = { uriHandler.openUri(LICENSE_URL) })
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    LinkRow("Source code on GitHub", onClick = { uriHandler.openUri(REPO_URL) })
                }
            }

            Spacer(Modifier.height(16.dp))

            // Open-source attributions.
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Open-source licenses", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Yama is built on these projects, and includes them under their respective " +
                            "licenses. GPLv3 is required because it links vlcj.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ATTRIBUTIONS.forEachIndexed { index, attr ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        AttributionRow(attr, onClick = { uriHandler.openUri(attr.url) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "…and other",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Copyright © 2026 Michał Hanak",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(bottomContentPadding))
        }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AttributionRow(attr: Attribution, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(attr.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                attr.license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
