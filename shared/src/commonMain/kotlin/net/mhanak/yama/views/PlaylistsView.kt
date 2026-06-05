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
import yama.shared.generated.resources.library_music

@Composable
fun PlaylistsView(
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val playlists by appContainer.activeMusicSource.playlists.collectAsState()

    GridView(
        items = playlists.map { playlist ->
            GridItem(
                title = playlist.name,
                subtitle = playlist.itemCount?.let { "$it tracks" },
                imageUrl = playlist.imageUrl,
                onClick = { onPlaylistClick(playlist.id) },
            )
        },
        modifier = modifier,
        contentPadding = contentPadding,
        imageFallback = painterResource(Res.drawable.library_music),
    )
}
