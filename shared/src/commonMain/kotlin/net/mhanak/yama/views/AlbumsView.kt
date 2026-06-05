package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.GridItem
import net.mhanak.yama.components.GridView
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album

@Composable
fun AlbumsView(
    onAlbumClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val albums by appContainer.activeMusicSource.albums.collectAsState()

    GridView(
        items = albums.map { album ->
            GridItem(
                title = album.name,
                subtitle = album.albumArtist,
                imageUrl = album.imageUrl,
                onClick = { onAlbumClick(album.id) },
            )
        },
        modifier = modifier,
        contentPadding = contentPadding,
        imageFallback = painterResource(Res.drawable.album),
    )
}
