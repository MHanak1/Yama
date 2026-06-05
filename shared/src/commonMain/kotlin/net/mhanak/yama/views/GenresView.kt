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
import yama.shared.generated.resources.folder

@Composable
fun GenresView(
    onGenreClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    val genres by appContainer.activeMusicSource.genres.collectAsState()

    GridView(
        items = genres.map { genre ->
            GridItem(
                title = genre.name,
                imageUrl = genre.imageUrl,
                onClick = { onGenreClick(genre.id) },
            )
        },
        modifier = modifier,
        contentPadding = contentPadding,
        imageFallback = painterResource(Res.drawable.folder),
    )
}
