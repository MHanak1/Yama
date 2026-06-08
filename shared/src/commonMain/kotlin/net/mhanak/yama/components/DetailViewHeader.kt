package net.mhanak.yama.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Artist
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.screens.AlbumDetailRoute
import net.mhanak.yama.screens.ArtistDetailRoute
import net.mhanak.yama.screens.GenreDetailRoute
import net.mhanak.yama.views.AlbumDetailView
import net.mhanak.yama.views.GenreDetailView

@Composable
fun DetailViewHeader(
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},

    imageUrl: String?,
    name: String,
    artist: Artist? = null,
    album: Album? = null,
    genres: List<Genre>? = null,
    year: Int? = null,

    ) {
    Row (
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = name,
            modifier = Modifier
                .fillMaxWidth(0.3f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
        )
        Column (
            modifier = Modifier
                .padding(16.dp),
        ) {
            Text(name, style = MaterialTheme.typography.headlineLarge)

            if (artist != null) {
                LibraryReference(
                    label = artist.name,
                    type = LibraryReferenceType.Artist,
                    onClick = { onNavigate(ArtistDetailRoute(artist.id)) },
                )
            }

            if (album != null) {
                LibraryReference(
                    label = album.name,
                    type = LibraryReferenceType.Album,
                    onClick = { onNavigate(AlbumDetailRoute(album.id)) },
                )
            }

            if (genres != null) {
                Row {
                    for (genre in genres) {
                        LibraryReference(
                            label = genre.name,
                            type = LibraryReferenceType.Genre,
                            onClick = { onNavigate(GenreDetailRoute(genre.id)) },
                        )
                    }
                }
            }

            if (year != null) {
                Text(year.toString())
            }
        }
    }
}