package net.mhanak.yama.ui.components.local

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.local.LocalSource

/**
 * Full-screen "scanning your music" overlay shown while the local library is being built for the first
 * time — i.e. the local source is active, a scan is in flight, folders are configured, yet nothing has
 * been indexed to browse yet. It self-gates on that condition (rendering nothing otherwise), so callers
 * can drop it unconditionally into the top-level overlay stack.
 *
 * Once the scan emits its first albums the library shows through beneath and this fades out; if a scan
 * finds no audio at all it also fades out (leaving the normal empty-library view) rather than trapping
 * the user here. Progress is determinate during the tag-reading phase ([LocalSource.scanProgress]) and
 * indeterminate during the preceding directory walk, when the file count isn't yet known.
 */
@Composable
fun LocalLibraryScanningOverlay(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    val local = appContainer.localSource

    // `activeMusicSource` is snapshot-backed, so this read recomposes when the active source changes.
    val isActive = appContainer.activeMusicSource === local
    val folders by local.folders.collectAsState()
    val isRefreshing by local.isRefreshing.collectAsState()
    // Emptiness proxy: every scanned track derives an album, so no albums ⇒ nothing browsable yet.
    val albums by local.albums.collectAsState()
    val progress by local.scanProgress.collectAsState()

    val visible = isActive && isRefreshing && folders.isNotEmpty() && albums.isEmpty()

    // Cross-fade in/out so the overlay doesn't snap away the instant the first albums land.
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "scanningAlpha")
    if (alpha == 0f) return

    Surface(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha)
            // Swallow taps so the half-built library beneath can't be interacted with mid-scan.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp),
            ) {
                val fraction = progress?.fraction
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(56.dp))
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(56.dp))
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    "Scanning your music",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    scanSubtitle(progress?.done, progress?.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// "123 of 456 tracks" once the file count is known; a generic line during the directory walk.
private fun scanSubtitle(done: Int?, total: Int?): String =
    if (done != null && total != null && total > 0) "$done of $total tracks"
    else "Reading your local folders…"
