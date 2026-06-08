package net.mhanak.yama.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.AsyncImageGridCard
import net.mhanak.yama.components.ErrorCard
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
    val source = appContainer.activeMusicSource
    val genres by source.genres.collectAsState()
    val isRefreshing by source.isRefreshing.collectAsState()
    val refreshError by source.refreshError.collectAsState()

    when {
        genres.isEmpty() && isRefreshing -> Box(
            modifier.fillMaxSize().padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        genres.isEmpty() && refreshError != null -> Box(
            modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            ErrorCard(message = refreshError!!.message ?: "Failed to load genres")
        }
        else -> GridView(modifier = modifier, contentPadding = contentPadding) {
            items(genres) { genre ->
                AsyncImageGridCard(
                    title = genre.name,
                    imageUrl = genre.imageUrl,
                    onClick = { onGenreClick(genre.id) },
                    imageFallback = painterResource(Res.drawable.folder),
                )
            }
        }
    }
}
