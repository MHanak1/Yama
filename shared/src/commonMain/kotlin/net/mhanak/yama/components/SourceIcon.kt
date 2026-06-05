package net.mhanak.yama.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.folder
import yama.shared.generated.resources.jellyfin_logo
import yama.shared.generated.resources.subsonic_logo

@Composable
fun SourceIcon(label: String) {
    when (label) {
        "Jellyfin" -> Image(
            painter = painterResource(Res.drawable.jellyfin_logo),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        "Subsonic" -> Image(
            painter = painterResource(Res.drawable.subsonic_logo),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        "Local Files" -> Image(
            painter = painterResource(Res.drawable.folder),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
    }
}
