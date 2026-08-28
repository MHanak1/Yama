package net.mhanak.yama.ui.components.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Album
import net.mhanak.yama.media.model.Genre
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.PlaylistWritable
import net.mhanak.yama.ui.components.detail.DetailPlayActions
import net.mhanak.yama.ui.components.playlist.AddToPlaylistSheet
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.interaction.LocalTvZoneFocus
import net.mhanak.yama.ui.components.interaction.tvFocusContainer
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.theme.glassEffect
import net.mhanak.yama.ui.theme.glassSource
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import yama.shared.generated.resources.folder

/**
 * What a long-pressed home-shelf card acts on. Home shelves only surface albums, genres, and tracks
 * (there is no artist shelf), so — unlike the library's [net.mhanak.yama.ui.components.settings.SelectableKind]
 * — this covers exactly those three, and tracks are first-class (the library excludes tracks from its
 * multi-selection because they already play individually; here a single track still gets the play/queue
 * actions, favourite, and download, just no Shuffle — [shuffleable] is false).
 *
 * Each case exposes the presentation bits the sheet header needs plus the favourite [kind]/[favoriteId]/
 * [initialFavorite]. Track-gathering and downloading are resolved from the concrete subtype in
 * [HomeItemActionSheet].
 */
sealed interface HomeItemAction {
    val title: String
    val subtitle: String?
    val imageUrl: String?
    val imageHash: String?
    val kind: FavoritableKind
    val favoriteId: String
    val initialFavorite: Boolean
    /** Shuffle only makes sense over a collection — a single track has nothing to shuffle. */
    val shuffleable: Boolean

    data class AlbumAction(val album: Album) : HomeItemAction {
        override val title get() = album.name
        override val subtitle get() = album.albumArtist
        override val imageUrl get() = album.imageUrl
        override val imageHash get() = album.imageHash
        override val kind get() = FavoritableKind.Album
        override val favoriteId get() = album.id
        override val initialFavorite get() = album.favorite
        override val shuffleable get() = true
    }

    data class GenreAction(val genre: Genre) : HomeItemAction {
        override val title get() = genre.name
        override val subtitle: String? get() = null
        override val imageUrl get() = genre.imageUrl
        override val imageHash get() = genre.imageHash
        override val kind get() = FavoritableKind.Genre
        override val favoriteId get() = genre.id
        override val initialFavorite get() = genre.favorite
        override val shuffleable get() = true
    }

    data class TrackAction(val track: Track) : HomeItemAction {
        override val title get() = track.name
        override val subtitle get() = track.artists?.joinToString(", ") ?: track.album
        override val imageUrl get() = track.imageUrl
        override val imageHash: String? get() = null
        override val kind get() = FavoritableKind.Track
        override val favoriteId get() = track.id
        override val initialFavorite get() = track.favorite
        override val shuffleable get() = false
    }
}

/**
 * A bottom-sheet ("shelf") of per-item actions for a long-pressed / right-clicked home card. The
 * play/queue affordance reuses the detail views' [DetailPlayActions] — a Play / Shuffle button pair
 * whose long-press / right-click opens a "Play next" / "Add to queue" menu — so queueing behaves exactly
 * as it does on an album/genre screen. Below it, Favourite and Download are plain sheet rows, shown only
 * when the active source supports them for this [target]'s kind (the same capability gates
 * [net.mhanak.yama.ui.views.LibraryView] uses). Shuffle is dropped for a single track.
 *
 * Structurally modelled on [net.mhanak.yama.ui.player.PlaybackTargetSheet] so the glass sheet, drag
 * handle, list rows, and TV D-pad focus trap match the rest of the app's bottom sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeItemActionSheet(
    target: HomeItemAction,
    // Removes the sheet (typically clears the host's `actionTarget`). Called after the hide animation.
    onDismiss: () -> Unit,
    // A scope owned by a host that survives this sheet's removal (the shelf), used to run playback so a
    // dismiss-on-play doesn't cancel the track fetch mid-flight. See [DetailPlayActions.scope].
    playbackScope: CoroutineScope,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    val availability = LocalAvailability.current

    // Close with the normal slide-down: animate the sheet to hidden, then remove it. (Swipe / scrim
    // dismissal animates on its own and routes straight to onDismiss via onDismissRequest below.)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiScope = rememberCoroutineScope()
    fun dismiss() {
        uiScope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Playable if downloaded or the source is reachable; gates the Play/Shuffle cluster (same as detail).
    val playable = when (target) {
        is HomeItemAction.AlbumAction -> availability.album(target.album.id)
        is HomeItemAction.GenreAction -> availability.genre(target.genre.id)
        is HomeItemAction.TrackAction -> availability.track(target.track.id)
    }

    // Tracks handed to DetailPlayActions, which itself drops any that aren't playable offline. A
    // collection fetches its tracks from the source (capped by the source's own getTracksFor* limit);
    // a single track is already the list.
    suspend fun gatherTracks(shuffled: Boolean): List<Track> {
        val tracks = when (target) {
            is HomeItemAction.AlbumAction -> source.getTracksForAlbum(target.album.id)
            is HomeItemAction.GenreAction -> source.getTracksForGenre(target.genre.id)
            is HomeItemAction.TrackAction -> listOf(target.track)
        }
        return if (shuffled) tracks.shuffled() else tracks
    }

    // Favourite state: seeded from the model for an instant render, then refined by a fetch. The toggle
    // is optimistic and write-through (mirrors LibraryView / FavoriteButton).
    val favoritesSupported = remember(source, target.kind) {
        (source as? FavoriteCapable)?.supportsFavorites(target.kind) == true
    }
    var isFavorite by remember(target) { mutableStateOf(target.initialFavorite) }
    LaunchedEffect(target) {
        val fav = source as? FavoriteCapable
        if (favoritesSupported && fav != null) isFavorite = fav.isFavorite(target.kind, target.favoriteId)
    }
    fun toggleFavorite() {
        val next = !isFavorite
        isFavorite = next
        appContainer.favorites.setFavorite(target.kind, target.favoriteId, next)
    }

    // Non-null when the active source persists downloads (Jellyfin); enables the Download row.
    val downloadsSupported = (source as? OfflineCapable)?.downloadSourceKey() != null
    fun download() {
        val manager = appContainer.downloadManager
        when (target) {
            is HomeItemAction.AlbumAction -> manager.enqueueAlbum(target.album.id)
            is HomeItemAction.GenreAction -> manager.enqueueGenre(target.genre.id)
            is HomeItemAction.TrackAction -> manager.enqueueTracks(listOf(target.track))
        }
        dismiss()
    }

    // "Add to playlist" — online-only, offered for albums (whole album) and single tracks, not genres
    // (which could be thousands of tracks). Tapping resolves the track ids (an album fetches them) then
    // swaps this sheet for the playlist picker.
    val reachable by source.isReachable.collectAsState()
    val canAddToPlaylist = source is PlaylistWritable && reachable && target !is HomeItemAction.GenreAction
    var addToPlaylistIds by remember { mutableStateOf<List<String>?>(null) }
    fun addToPlaylist() {
        // Gather on the host-owned scope so swapping this sheet away doesn't cancel an album's fetch.
        playbackScope.launch {
            addToPlaylistIds = when (target) {
                is HomeItemAction.TrackAction -> listOf(target.track.id)
                else -> gatherTracks(shuffled = false).map { it.id }
            }
        }
    }

    // Once tracks are resolved, present the playlist picker in place of the action rows. Its dismiss
    // tears the whole thing down (onDismiss clears the host's actionTarget).
    addToPlaylistIds?.let { ids ->
        AddToPlaylistSheet(trackIds = ids, onDismiss = onDismiss)
        return
    }

    val fallback: Painter = when (target) {
        is HomeItemAction.GenreAction -> painterResource(Res.drawable.folder)
        else -> painterResource(Res.drawable.album)
    }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow

    // TV: pull D-pad focus into the sheet on open (onto the Play button) and trap it; restore content
    // focus on close. Mirrors PlaybackTargetSheet. See tvFocusContainer / TvFocus.kt.
    val zone = LocalTvZoneFocus.current
    val entryFocus = remember { FocusRequester() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassEffect(sheetContainerColor, sheetShape)
                .glassSource(zIndex = 3f)
                .tvFocusContainer(entry = entryFocus, onDismissRestore = { zone?.restoreContent() }),
        ) {
            // Drag handle.
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }

            // Header: artwork + title/subtitle for the item being acted on.
            ListItem(
                headlineContent = { Text(target.title, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                supportingContent = target.subtitle?.let { { Text(it, maxLines = 1) } },
                leadingContent = {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                        CardImage(imageUrl = target.imageUrl, imageHash = target.imageHash, imageFallback = fallback)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )

            // The detail views' Play / Shuffle buttons verbatim: tap plays, long-press / right-click opens
            // the Play-next / Add-to-queue menu. Shuffle is dropped for a single track.
            DetailPlayActions(
                player = appContainer.playback.viewed,
                fetchTracks = { shuffled -> gatherTracks(shuffled) },
                enabled = playable,
                showShuffle = target.shuffleable,
                // Dismiss on tap (animated); playback runs on the shelf's scope so removing this sheet
                // doesn't cancel the fetch before it enqueues.
                onAction = { dismiss() },
                scope = playbackScope,
                modifier = Modifier.focusRequester(entryFocus),
            )

            if (favoritesSupported) {
                ActionRow(
                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    label = if (isFavorite) "Remove from favourites" else "Add to favourites",
                    onClick = { toggleFavorite() },
                )
            }
            if (canAddToPlaylist) {
                ActionRow(icon = Icons.AutoMirrored.Filled.PlaylistAdd, label = "Add to playlist", onClick = { addToPlaylist() })
            }
            if (downloadsSupported) {
                ActionRow(icon = Icons.Outlined.Download, label = "Download", onClick = { download() })
            }

            Spacer(Modifier.height(8.dp).navigationBarsPadding())
        }
    }
}

/** One tappable action row in the sheet: a leading icon and a label. */
@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
