package net.mhanak.yama.ui.components.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.yama_logo

/**
 * Login-screen hero: the gradient waveform mark beside the "Yama" wordmark, side by side.
 *
 * The mark is [Res.drawable.yama_logo] — the app icon with its background layer stripped
 * (regenerated from `resources/branding/logo.svg` by `tools/generate_icons.sh`), so it sits on transparency and reads on any
 * surface. The wordmark is a real [Text] (not baked into the image) so it follows the theme's
 * `onSurface` colour in both light and dark.
 */
@Composable
fun LoginHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.yama_logo),
            contentDescription = null, // the wordmark beside it carries the name for a11y
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Yama",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
