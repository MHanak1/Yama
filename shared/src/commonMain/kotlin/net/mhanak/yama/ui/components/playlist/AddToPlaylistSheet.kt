package net.mhanak.yama.ui.components.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.ui.components.interaction.LocalTvZoneFocus
import net.mhanak.yama.ui.components.interaction.tvFocusContainer
import net.mhanak.yama.ui.theme.glassEffect
import net.mhanak.yama.ui.theme.glassSource

/**
 * Bottom sheet for adding one or more tracks to a playlist. Given the [trackIds] to add (a single
 * track from the track menu, or a whole album from the home/library action sheet), it lists the
 * active source's existing playlists plus a top **"New playlist…"** row that creates one seeded with
 * those tracks. Structurally modelled on [net.mhanak.yama.ui.player.PlaybackTargetSheet] — same glass,
 * drag handle, and TV D-pad focus trap.
 *
 * Every write routes through `appContainer.playlists` (the coordinator); the sheet dismisses only
 * after the write completes so the coroutine isn't cancelled mid-flight. Shown only where editing is
 * supported and the source is reachable — callers gate on `appContainer.playlists.canEdit()`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(trackIds: List<String>, onDismiss: () -> Unit) {
    val appContainer = LocalAppContainer.current
    val playlists by appContainer.activeMusicSource.playlists.collectAsState()
    val scope = rememberCoroutineScope()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Close with the normal slide-down, then remove (mirrors HomeItemActionSheet).
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    // Runs the edit, then dismisses — order matters so the animated removal doesn't cancel the write.
    fun runEditThenDismiss(edit: suspend () -> Unit) {
        scope.launch { edit(); dismiss() }
    }

    var showCreate by remember { mutableStateOf(false) }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow

    // TV: pull D-pad focus onto "New playlist…" and trap it; restore content focus on close.
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
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(
                    "Add to playlist",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )

                PlaylistRow(
                    icon = Icons.Filled.Add,
                    label = "New playlist…",
                    emphasized = true,
                    onClick = { showCreate = true },
                    modifier = Modifier.focusRequester(entryFocus),
                )

                if (playlists.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))

                playlists.forEach { playlist ->
                    PlaylistRow(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = playlist.name,
                        onClick = { runEditThenDismiss { appContainer.playlists.addTracksToPlaylist(playlist.id, trackIds) } },
                    )
                }

                Spacer(Modifier.height(8.dp).navigationBarsPadding())
            }
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            onDismiss = { showCreate = false },
            onConfirm = { name -> runEditThenDismiss { appContainer.playlists.createPlaylist(name, trackIds) } },
        )
    }
}

/** One tappable row in the sheet: leading icon + label. [emphasized] tints the "New playlist…" action. */
@Composable
private fun PlaylistRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = {
            Text(label, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal, color = tint, maxLines = 1)
        },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
