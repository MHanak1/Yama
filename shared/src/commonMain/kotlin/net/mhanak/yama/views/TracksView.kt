package net.mhanak.yama.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TracksView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(
        modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Tracks coming soon",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
