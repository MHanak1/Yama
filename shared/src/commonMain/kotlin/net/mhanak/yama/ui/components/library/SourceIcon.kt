package net.mhanak.yama.ui.components.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import net.mhanak.yama.media.sources.SourceType
import org.jetbrains.compose.resources.painterResource

/**
 * Small inline source logo for list items and buttons (e.g. the login screen source picker).
 * Uses [sourceLogo] for the drawable/tint mapping, so this composable stays in sync with
 * [SourceAvatar] automatically when new [SourceType]s are added.
 */
@Composable
fun SourceIcon(sourceType: SourceType) {
    val logo = sourceLogo(sourceType)
    Image(
        painter = painterResource(logo.drawable),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        colorFilter = if (logo.tinted) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
    )
}
