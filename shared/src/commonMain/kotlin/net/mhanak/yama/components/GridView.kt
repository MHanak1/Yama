package net.mhanak.yama.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

data class GridItem (
    val title: String?,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val imageBlurHash: String? = null,
    val onClick: () -> Unit = {},
)

@Composable
fun GridView(
    items: List<GridItem>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    imageFallback: Painter? = null,
) {
    BoxWithConstraints(modifier.focusGroup().focusRestorer()) {
        LazyVerticalGrid(
            // silly way of making the grid size fit both mobile and desktop
            columns = GridCells.Adaptive(minSize = Dp(100.toFloat() + (maxWidth.value / 12F))),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = contentPadding.plus(PaddingValues(8.dp)),
        ) {
            items(items = items) { item ->
                ElevatedCard(onClick = item.onClick) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxSize()
                    ) {
                        Box (
                            modifier = Modifier
                                .fillMaxSize()
                                .aspectRatio(ratio = 1.0F)
                                .clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (imageFallback != null && item.imageUrl != null) {
                                Image(
                                    modifier = Modifier
                                        .fillMaxSize(0.5f),
                                    painter = imageFallback,
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.outline), //todo: find more suitable color
                                )
                            }
                            AsyncImage(
                                modifier = Modifier
                                    .fillMaxSize(),
                                model = item.imageUrl,
                                contentDescription = null,
                            )
                        }

                        Text(
                            item.title ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.subtitle!= null) {
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}