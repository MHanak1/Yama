package net.mhanak.yama.ui.components.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LowPriority
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.sources.FavoritableKind
import net.mhanak.yama.media.sources.FavoriteCapable
import net.mhanak.yama.media.sources.OfflineCapable
import kotlin.math.abs
import kotlin.math.roundToInt
import net.mhanak.yama.ui.components.state.LocalAvailability
import net.mhanak.yama.ui.components.state.rememberTrackFavorite
import net.mhanak.yama.ui.theme.GlassElevatedCard
import net.mhanak.yama.ui.components.input.QualityPickerDialog

/** Drag distance past which releasing fires the swipe action. */
private val SwipeTriggerDistance = 64.dp

/**
 * A track row that knows how to enqueue itself. Reusable across every track list (album, artist,
 * genre, playlist detail) so the play/queue affordances stay identical everywhere.
 *
 * - **Tap** plays from this track onward (`playNow(tracks, index)`) — the default list behaviour.
 * - **Long-press** (or **right-click**) opens a menu: Play / Play from here / Play next / Add to queue.
 * - **Swipe right** adds the track to the end of the queue; **swipe left** plays it next. The row
 *   follows the finger and snaps back on release. Crossing [SwipeTriggerDistance] emphasises the
 *   action icon and fires a haptic pulse to signal "let go now".
 *
 * @param tracks the full list this row belongs to, so "play from here" can hand the player the tail.
 * @param index this track's position within [tracks].
 */
@Composable
fun TrackListCard(
    track: Track,
    tracks: List<Track>,
    index: Int,
    player: Player,
    modifier: Modifier = Modifier,
    subtitle: String? = track.artists?.joinToString(", "),
    // When true, the horizontal swipe-to-queue gestures are disabled (the tap/long-press/right-click
    // menu stays). Used where the row sits inside a horizontally swipeable container (the library
    // pager) so the row's drag doesn't preempt the container's.
    disableGestures: Boolean = false,
    image: (@Composable BoxScope.() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Where to anchor the menu: the cursor for right-clicks, top-start for long-presses.
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var showQualityDialog by remember { mutableStateOf(false) }

    val appContainer = LocalAppContainer.current
    val source = appContainer.activeMusicSource
    // Gray + gate playback when the track is neither downloaded nor reachable (offline, no copy). The
    // row stays visible/openable; only its play affordances are disabled.
    val availability = LocalAvailability.current
    val playable = availability.track(track.id)
    // Non-null when the active source persists downloads (Jellyfin); enables the Download menu action.
    val downloadKey = (source as? OfflineCapable)?.downloadSourceKey()
    // Pinned = explicitly downloaded; shows the OfflinePin badge. `pinnedTrackIds` excludes auto-cached.
    val isPinned = downloadKey != null && track.id in availability.pinnedTrackIds
    // Cached = auto-played recently but not explicitly downloaded; subtle indicator only.
    val isCached = downloadKey != null && !isPinned && track.id in availability.trackIds
    val downloadStale = remember(isPinned, downloadKey, track.id) {
        if (!isPinned || downloadKey == null) false
        else appContainer.downloads.row(downloadKey, track.id)?.stale == true
    }

    val playThis = { if (playable) player.playNow(listOf(track)); Unit }
    // "Play from here" starts the whole list at this row — but when offline only the downloaded tracks
    // are playable, so drop the rest and adjust the start index to this track's position among them.
    val playFromHere = {
        if (playable) {
            val filtered = tracks.filter { availability.track(it.id) }
            val newIndex = tracks.take(index).count { availability.track(it.id) }
            player.playNow(filtered, newIndex)
        }
        Unit
    }
    val playNext = { if (playable) player.playNext(listOf(track)); Unit }
    val addToQueue = { if (playable) player.addToQueue(listOf(track)); Unit }

    val favoritesSupported = remember(source) { (source as? FavoriteCapable)?.supportsFavorites(FavoritableKind.Track) == true }
    // Favourite state reads through the shared TrackUserDataStore (overlaying the model seed), so a
    // toggle anywhere recomposes this row — no per-row mirror, no resync LaunchedEffect, no manual queue
    // patching. The tap writes the store synchronously in setFavorite, so it stays optimistic.
    val isFavorite = rememberTrackFavorite(track.id, fallback = track.favorite)

    val density = LocalDensity.current
    val triggerPx = with(density) { SwipeTriggerDistance.toPx() }
    val scope = rememberCoroutineScope()

    val toggleFavorite = {
        // Write-through only: setFavorite flips the store, which recomposes isFavorite above.
        appContainer.favorites.setFavorite(FavoritableKind.Track, track.id, !isFavorite)
        Unit
    }
    // Plain state updated synchronously while dragging, so onDragEnd reads the true offset (a launched
    // Animatable.snapTo would lag behind the release). An Animatable only drives the snap-back.
    var offsetX by remember { mutableStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }

    // The swipe has dragged past the point where releasing would fire the action.
    val triggerReached by remember { derivedStateOf { abs(offsetX) >= triggerPx } }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(triggerReached) {
        if (triggerReached) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(modifier = modifier) {
        // Coloured action backing, revealed as the card slides off it.
        TrackSwipeBackground(
            offset = offsetX,
            triggerReached = triggerReached,
            modifier = Modifier.matchParentSize(),
        )

        GlassElevatedCard(
            onClick = playFromHere,
            onLongClick = {
                menuOffset = DpOffset.Zero
                menuExpanded = true
            },
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .alpha(if (playable) 1f else 0.5f)
                .then(
                    if (disableGestures || !playable) Modifier
                    else Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onDragEnd = {
                                when {
                                    offsetX >= triggerPx ->  playNext()
                                    offsetX <= -triggerPx -> addToQueue()
                                }
                                settleJob = scope.launch {
                                    Animatable(offsetX).animateTo(0f) { offsetX = value }
                                }
                            },
                            onDragCancel = {
                                settleJob = scope.launch {
                                    Animatable(offsetX).animateTo(0f) { offsetX = value }
                                }
                            },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                offsetX += delta
                            },
                        )
                    },
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val position = event.changes.first().position
                            menuOffset = DpOffset(position.x.toDp(), position.y.toDp())
                            menuExpanded = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            ListCardRow(
                image = image,
                title = track.name,
                subtitle = subtitle,
                endContent = if (favoritesSupported || isPinned || isCached) {{
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPinned) {
                            Icon(
                                if (downloadStale) Icons.Filled.DownloadForOffline else Icons.Filled.OfflinePin,
                                contentDescription = if (downloadStale) "Downloaded (update available)" else "Downloaded",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        } else if (isCached) {
                            // Subtle indicator: same icon but small and muted so it doesn't imply an explicit download.
                            Icon(
                                Icons.Outlined.OfflinePin,
                                contentDescription = "Cached",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (favoritesSupported) {
                            IconButton(onClick = toggleFavorite, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) "Remove favourite" else "Add favourite",
                                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }} else null,
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            offset = menuOffset,
        ) {
            TrackMenuItem("Play", Icons.Filled.PlayArrow) { menuExpanded = false; playThis() }
            TrackMenuItem("Play from here", Icons.Filled.PlayCircle) { menuExpanded = false; playFromHere() }
            TrackMenuItem("Play next", Icons.Filled.Queue) { menuExpanded = false; playNext() }
            TrackMenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { menuExpanded = false; addToQueue() }
            if (favoritesSupported) {
                TrackMenuItem(
                    if (isFavorite) "Remove from favourites" else "Add to favourites",
                    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                ) { menuExpanded = false; toggleFavorite() }
            }
            if (downloadKey != null) {
                if (isCached) {
                    // Cached entry: offer to explicitly download (pin) it; no "Change quality" offered.
                    TrackMenuItem("Download", Icons.Filled.Download) {
                        menuExpanded = false
                        appContainer.downloadManager.enqueueTracks(listOf(track))
                    }
                } else {
                    TrackMenuItem(
                        if (isPinned) "Remove download" else "Download",
                        if (isPinned) Icons.Filled.Delete else Icons.Filled.Download,
                    ) {
                        menuExpanded = false
                        if (isPinned) appContainer.downloadManager.removeDownload(downloadKey, track.id)
                        else appContainer.downloadManager.enqueueTracks(listOf(track))
                    }
                    if (isPinned) {
                        TrackMenuItem("Change quality…", Icons.Filled.HighQuality) {
                            menuExpanded = false
                            showQualityDialog = true
                        }
                    }
                }
            }
        }
    }

    if (showQualityDialog && downloadKey != null) {
        QualityPickerDialog(
            title = "Change quality…",
            current = appContainer.downloads.row(downloadKey, track.id)?.quality,
            onDismiss = { showQualityDialog = false },
            onPick = { quality -> appContainer.downloadManager.redownload(listOf(track), quality) },
        )
    }
}

@Composable
private fun TrackMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/**
 * Coloured backing revealed while swiping, with the action's icon pinned to the leading/trailing edge.
 * When [triggerReached] the icon scales up and the backing reaches full opacity — the visual half of
 * the "you can let go now" cue that the haptic pulse completes. Renders nothing at rest ([offset] == 0).
 */
@Composable
private fun TrackSwipeBackground(offset: Float, triggerReached: Boolean, modifier: Modifier = Modifier) {
    if (offset == 0f) return

    val startToEnd = offset > 0f
    val alignment = if (startToEnd) Alignment.CenterStart else Alignment.CenterEnd
    val icon = if (startToEnd) Icons.AutoMirrored.Filled.QueueMusic else Icons.Filled.LowPriority
    val color =
        if (startToEnd) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.secondaryContainer

    val iconScale by animateFloatAsState(if (triggerReached) 1.25f else 0.85f, label = "swipeIconScale")
    val backgroundAlpha by animateFloatAsState(if (triggerReached) 1f else 0.5f, label = "swipeBgAlpha")

    Box(
        modifier = modifier
            .clip(CardDefaults.shape)
            .background(color.copy(alpha = color.alpha * backgroundAlpha))
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Row (
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ){
            if (!startToEnd) {
                Text("Add to Queue")
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.scale(iconScale),
            )
            if (startToEnd) {
                Text("Play Next")
            }
        }
    }
}
