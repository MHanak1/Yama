package net.mhanak.yama.ui.components.playlist

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Playlist
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.OfflineCapable
import net.mhanak.yama.media.sources.PlaylistWritable
import net.mhanak.yama.ui.components.detail.DetailPlayActions
import net.mhanak.yama.ui.components.image.CardImage
import net.mhanak.yama.ui.components.interaction.LocalTvZoneFocus
import net.mhanak.yama.ui.components.interaction.tvFocusContainer
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.theme.glassEffect
import net.mhanak.yama.ui.theme.glassSource
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.library_music

/**
 * A bottom-sheet ("shelf") of per-item actions for a long-pressed / right-clicked playlist card, the
 * playlist counterpart to [net.mhanak.yama.ui.components.home.HomeItemActionSheet]. Play / Shuffle reuse
 * the detail views' [DetailPlayActions]; Favourite and Download are plain rows gated on source support.
 *
 * Unlike the home sheet, this also carries the playlist's **edit** actions — Rename and Delete — which
 * used to live in [net.mhanak.yama.ui.views.detail.PlaylistDetailView]'s top bar. They route through
 * `appContainer.playlists` (the coordinator) and are shown only when the source is
 * [PlaylistWritable] and reachable (edits are online-only for now). The source's `_playlists` StateFlow
 * is updated by the coordinator, so the grid reflects a rename/delete without an explicit refresh here.
 *
 * Structurally modelled on [net.mhanak.yama.ui.player.PlaybackTargetSheet] / HomeItemActionSheet — same
 * glass sheet, drag handle, list rows, and TV D-pad focus trap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistActionSheet(
    playlist: Playlist,
    // Removes the sheet (typically clears the host's `actionTarget`). Called after the hide animation.
    onDismiss: () -> Unit,
    // A scope owned by a host that survives this sheet's removal (the grid), used to run playback /
    // edits so a dismiss-on-action doesn't cancel the coroutine mid-flight. See HomeItemActionSheet.
    playbackScope: CoroutineScope,
) {
    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource

    // Close with the normal slide-down: animate to hidden, then remove (mirrors HomeItemActionSheet).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiScope = rememberCoroutineScope()
    fun dismiss() {
        uiScope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Playable if downloaded or the source is reachable; gates the Play/Shuffle cluster (same as detail).
    val playable = LocalAvailability.current.playlist(playlist.id)

    // Editing (rename/delete) is online-only and only where the source supports it (mirrors the gate
    // PlaylistDetailView used before these actions moved here).
    val reachable by source.isReachable.collectAsState()
    val editable = source is PlaylistWritable && reachable

    // Favourite state: seeded from the model for an instant render, then refined by a fetch. The toggle
    // is optimistic and write-through (mirrors HomeItemActionSheet / FavoriteButton).
    val favoritesSupported = remember(source) {
        (source as? FavoriteCapable)?.supportsFavorites(FavoritableKind.Playlist) == true
    }
    var isFavorite by remember(playlist.id) { mutableStateOf(playlist.favorite) }
    LaunchedEffect(playlist.id) {
        val fav = source as? FavoriteCapable
        if (favoritesSupported && fav != null) isFavorite = fav.isFavorite(FavoritableKind.Playlist, playlist.id)
    }
    fun toggleFavorite() {
        val next = !isFavorite
        isFavorite = next
        appContainer.favorites.setFavorite(FavoritableKind.Playlist, playlist.id, next)
    }

    // Non-null when the active source persists downloads (Jellyfin); enables the Download row.
    val downloadsSupported = (source as? OfflineCapable)?.downloadSourceKey() != null

    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow

    // TV: pull D-pad focus into the sheet on open (onto the Play button) and trap it; restore content
    // focus on close. Mirrors HomeItemActionSheet. See tvFocusContainer / TvFocus.kt.
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

            // Header: artwork + name / track count for the playlist being acted on.
            ListItem(
                headlineContent = { Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                supportingContent = playlist.itemCount?.let { { Text("$it tracks", maxLines = 1) } },
                leadingContent = {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                        CardImage(
                            imageUrl = playlist.imageUrl,
                            imageHash = playlist.imageHash,
                            imageFallback = painterResource(Res.drawable.library_music),
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )

            // The detail views' Play / Shuffle buttons verbatim: tap plays, long-press / right-click opens
            // the Play-next / Add-to-queue menu. Capped at 100 tracks (as PlaylistDetailView does).
            DetailPlayActions(
                player = appContainer.playback.viewed,
                fetchTracks = { shuffled ->
                    val tracks = source.getTracksForPlaylist(playlist.id)
                    (if (shuffled) tracks.shuffled() else tracks).take(100)
                },
                enabled = playable,
                // Dismiss on tap (animated); playback runs on the grid's scope so removing this sheet
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
            if (downloadsSupported) {
                ActionRow(icon = Icons.Outlined.Download, label = "Download") {
                    appContainer.downloadManager.enqueuePlaylist(playlist.id)
                    dismiss()
                }
            }
            if (editable) {
                ActionRow(icon = Icons.Filled.Edit, label = "Rename") { showRename = true }
                ActionRow(icon = Icons.Filled.Delete, label = "Delete") { showDeleteConfirm = true }
            }

            Spacer(Modifier.height(8.dp).navigationBarsPadding())
        }
    }

    if (showRename) {
        PlaylistNameDialog(
            title = "Rename playlist",
            confirmLabel = "Rename",
            initialName = playlist.name,
            onDismiss = { showRename = false },
            // Run on the host scope, then tear the sheet down; the rename lands in the grid via the
            // source's _playlists flow.
            onConfirm = { name ->
                playbackScope.launch { appContainer.playlists.renamePlaylist(playlist.id, name) }
                dismiss()
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete playlist?") },
            text = { Text("This removes the playlist from the server. The tracks themselves aren't deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    playbackScope.launch { appContainer.playlists.deletePlaylist(playlist.id) }
                    dismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
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
