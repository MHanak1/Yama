package net.mhanak.yama.views

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.PaginatedTrackList

@Composable
fun TracksView(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val appContainer = LocalAppContainer.current
    PaginatedTrackList(
        loadPage = { offset, limit, sortBy ->
            appContainer.activeMusicSource.getAllTracks(limit, offset, sortBy)
        },
        modifier = modifier,
        contentPadding = contentPadding,
        addSingleToQueue = true,
    )
}
