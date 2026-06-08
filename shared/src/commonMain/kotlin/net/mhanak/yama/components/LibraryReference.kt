package net.mhanak.yama.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import yama.shared.generated.resources.artist
import yama.shared.generated.resources.folder
import yama.shared.generated.resources.library_music

enum class LibraryReferenceType { Album, Artist, Genre, Playlist }

@Composable
fun LibraryReference(
    label: String,
    type: LibraryReferenceType,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val icon = when (type) {
        LibraryReferenceType.Album -> painterResource(Res.drawable.album)
        LibraryReferenceType.Artist -> painterResource(Res.drawable.artist)
        LibraryReferenceType.Genre -> painterResource(Res.drawable.folder)
        LibraryReferenceType.Playlist -> painterResource(Res.drawable.library_music)
    }
    SuggestionChip(
        onClick = onClick,
        label = { Text(label) },
        icon = { Icon(painter = icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier,
    )
}
