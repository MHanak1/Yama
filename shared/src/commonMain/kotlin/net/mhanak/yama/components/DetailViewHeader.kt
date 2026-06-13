package net.mhanak.yama.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

// The image is at least this size, growing to a fifth of the available width on very wide layouts.
private val HeaderImageSize = 200.dp
// Below this content width the header stacks into a centered column; above it the image sits to the left.
private val HeaderWideBreakpoint = 500.dp

@Composable
fun DetailViewHeader(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},

    imageUrl: String?,
    name: String,
    artist: String? = null,
    album: String? = null,
    genres: List<String>? = null,
    year: Int? = null,

    // The detail view's play/shuffle actions, rendered as the last element of the header.
    playActions: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wide = maxWidth >= HeaderWideBreakpoint
        val imageSize = maxOf(HeaderImageSize, maxWidth / 5)

        val image = @Composable {
            AsyncImage(
                model = imageUrl,
                contentDescription = name,
                modifier = Modifier
                    .size(imageSize)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }

        if (wide) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                image()
                DetailInfo(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    centered = false,
                    onNavigate = onNavigate,
                    name = name,
                    artist = artist,
                    album = album,
                    genres = genres,
                    year = year,
                    playActions = playActions,
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                image()
                DetailInfo(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    centered = true,
                    onNavigate = onNavigate,
                    name = name,
                    artist = artist,
                    album = album,
                    genres = genres,
                    year = year,
                    playActions = playActions,
                )
            }
        }
    }
}

/**
 * The textual half of the header: title, artist, year, genres and the play actions. Shared between the
 * stacked (centered) slim layout and the side-by-side (left-aligned) wide layout via [horizontalAlignment]
 * and [centered] (which only governs multi-line text alignment).
 */
@Composable
private fun DetailInfo(
    modifier: Modifier,
    horizontalAlignment: Alignment.Horizontal,
    centered: Boolean,
    onNavigate: (Any) -> Unit,
    name: String,
    artist: String?,
    album: String?,
    genres: List<String>?,
    year: Int?,
    playActions: (@Composable () -> Unit)?,
) {
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start

    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(name, style = MaterialTheme.typography.headlineLarge, textAlign = textAlign)

        if (artist != null) {
            LibraryReference(
                label = artist,
                type = LibraryReferenceType.Artist,
                onNavigate = onNavigate,
            )
        }

        if (album != null) {
            LibraryReference(
                label = album,
                type = LibraryReferenceType.Album,
                onNavigate = onNavigate,
            )
        }

        if (year != null) {
            Text(year.toString(), textAlign = textAlign)
        }

        if (genres != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (genre in genres) {
                    LibraryReference(
                        label = genre,
                        type = LibraryReferenceType.Genre,
                        onNavigate = onNavigate,
                    )
                }
            }
        }

        if (playActions != null) {
            Spacer(Modifier.height(8.dp))
            playActions()
        }
    }
}
