package net.mhanak.yama.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.screens.AlbumDetailRoute
import net.mhanak.yama.screens.ArtistDetailRoute
import net.mhanak.yama.screens.GenreDetailRoute
import net.mhanak.yama.screens.PlaylistDetailRoute
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import yama.shared.generated.resources.artist
import yama.shared.generated.resources.folder
import yama.shared.generated.resources.library_music

enum class LibraryReferenceType { Album, Artist, Genre, Playlist }

/**
 * A reference to a library item shown by name (e.g. an album's artist, a track's genres). The component
 * resolves the name against the active source itself: if it matches a browsable item it becomes clickable
 * and navigates there via [onNavigate]; if there's no match (the library hasn't loaded, or the name isn't
 * a browsable item) it simply renders the label, non-clickable.
 *
 * By default it renders as a [SuggestionChip] with the type's icon. Set [textOnly] to render just the
 * (clickable) label with no chip or icon. [style], when non-null, overrides the label's text style
 * (otherwise the chip keeps its default styling and [textOnly] inherits the ambient style).
 */
@Composable
fun LibraryReference(
    label: String,
    type: LibraryReferenceType,
    modifier: Modifier = Modifier,
    onNavigate: (Any) -> Unit = {},
    textOnly: Boolean = false,
    style: TextStyle? = null,
) {
    val source = LocalAppContainer.current.activeMusicSource
    val albums by source.albums.collectAsState()
    val artists by source.artists.collectAsState()
    val genres by source.genres.collectAsState()
    val playlists by source.playlists.collectAsState()

    // Match the label to a library item to build its route; null when nothing matches.
    val route: Any? = when (type) {
        LibraryReferenceType.Album -> albums.find { it.name == label }?.let { AlbumDetailRoute(it.id) }
        LibraryReferenceType.Artist -> artists.find { it.name == label }?.let { ArtistDetailRoute(it.id) }
        LibraryReferenceType.Genre -> genres.find { it.name == label }?.let { GenreDetailRoute(it.id) }
        LibraryReferenceType.Playlist -> playlists.find { it.name == label }?.let { PlaylistDetailRoute(it.id) }
    }
    val onClick: (() -> Unit)? = route?.let { r -> { onNavigate(r) } }

    if (textOnly) {
        Text(
            text = label,
            style = style ?: LocalTextStyle.current,
            modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        )
        return
    }

    val icon = when (type) {
        LibraryReferenceType.Album -> painterResource(Res.drawable.album)
        LibraryReferenceType.Artist -> painterResource(Res.drawable.artist)
        LibraryReferenceType.Genre -> painterResource(Res.drawable.folder)
        LibraryReferenceType.Playlist -> painterResource(Res.drawable.library_music)
    }
    SuggestionChip(
        onClick = onClick ?: {},
        enabled = onClick != null,
        label = { if (style != null) Text(label, style = style) else Text(label) },
        icon = { Icon(painter = icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier,
    )
}
