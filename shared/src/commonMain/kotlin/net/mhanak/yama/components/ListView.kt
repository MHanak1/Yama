package net.mhanak.yama.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun ListView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding.plus(PaddingValues(8.dp)),
        content = content,
        modifier = modifier
            .focusGroup()
            .focusRestorer()
    )
}

@Composable
fun ListCard(
    onClick: () -> Unit = {},
    image: (@Composable BoxScope.() -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    endContent: @Composable (RowScope.() -> Unit)? = null,
) {
    GlassElevatedCard(onClick = onClick) {
        Row (
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box (
                modifier = Modifier
                    .sizeIn(minWidth = 32.dp, maxWidth = 64.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                image?.invoke(this)
            }

            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.weight(1f))
            endContent?.invoke(this)
        }
    }
}

@Composable
fun AsyncImageListCard(
    onClick: () -> Unit = {},
    imageUrl: String? = null,
    imageFallback: Painter? = null,
    title: String? = null,
    subtitle: String? = null,
) {
    ListCard(
        onClick = onClick,
        image = {
            Box(
                modifier = Modifier
                    .size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                if (imageFallback != null && imageUrl != null) {
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
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                )
            }
        },
        title = title,
        subtitle = subtitle,
    )
}