package net.mhanak.yama.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import com.materialkolor.PaletteStyle
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.album
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.LocalIsTvMode
import net.mhanak.yama.ui.components.image.BlurredBackgroundImage
import net.mhanak.yama.ui.components.interaction.LocalTvZoneFocus
import net.mhanak.yama.ui.components.interaction.tvFocusContainer
import net.mhanak.yama.ui.theme.glassSource
import net.mhanak.yama.ui.theme.DynamicColorTheme
import net.mhanak.yama.ui.platform.ImmersiveMode
import net.mhanak.yama.ui.components.library.LibraryReference
import net.mhanak.yama.ui.components.library.LibraryReferenceType
import net.mhanak.yama.ui.theme.LocalUiOpacity
import net.mhanak.yama.ui.platform.PlatformUserInteractionEffect
import net.mhanak.yama.ui.components.interaction.PlayerIdleTimeoutMs
import net.mhanak.yama.ui.components.interaction.isIdle
import net.mhanak.yama.ui.components.interaction.rememberIdleMonitor
import net.mhanak.yama.ui.components.interaction.resetIdleOn
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.Track
import net.mhanak.yama.media.playback.Player
import net.mhanak.yama.media.playback.PlayerStatus

/**
 * Full-screen player: artwork, track info, the shared [PlayerControls], plus shuffle/repeat toggles.
 * Shown as an overlay at the `MainScreen` level so it covers the rail and bottom bar.
 *
 * The artwork is sized relative to the available space so the whole thing fits without clipping on
 * any screen (notably TV). Dragging down continuously drives [playerExpansion] so the sheet follows
 * the finger and snaps back or collapses on release (asymmetric: >80% stays open, else collapses).
 * On TV, D-pad focus is moved into the controls when it opens.
 *
 * [peekHeight] is the height of the now-playing bar (rail) or bar + bottom bar (slim): the collapsed
 * sheet rests with its top at that line rather than fully off the bottom, so a swipe up from the bar
 * tracks the finger 1:1 instead of trailing it by a bar's height.
 *
 * The lyrics button in the top bar swaps the artwork area for a [LyricsView]. Lyrics are fetched
 * once per track via the active music source and cached for the lifetime of this composable.
 */
@Composable
fun FullPlayer(
    status: PlayerStatus,
    player: Player,
    playerExpansion: Animatable<Float, AnimationVector1D>,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    peekHeight: Dp = 0.dp,
    onNavigate: (Any) -> Unit = {},
) {
    val track = status.current
    val scope = rememberCoroutineScope()
    val playPauseFocus = remember { FocusRequester() }
    val zone = LocalTvZoneFocus.current
    val density = LocalDensity.current
    val peekHeightPx = with(density) { peekHeight.toPx() }
    // Minimum upward swipe (in px) that opens the queue when the player is fully expanded.
    val swipeUpThresholdPx = with(density) { 80.dp.toPx() }
    val expandSpec = tween<Float>(400, easing = FastOutSlowInEasing)
    val collapseSpec = tween<Float>(450, easing = FastOutSlowInEasing)
    val snapBack = spring<Float>(stiffness = Spring.StiffnessMediumLow)

    val appContainer = LocalAppContainer.current
    val isCasting = appContainer.playback.viewedTarget != null
    // Show the in-app volume slider only when the player isn't driving the system volume (where the OS
    // panel handles it). Reflects the *actual* capability, so it appears even if device-volume control
    // was requested but is unavailable.
    val controlsSystemVolume by player.controlsSystemVolume.collectAsState()
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lyrics: Lyrics? by remember { mutableStateOf(null) }
    val smoothPosition = rememberSmoothPosition(status)

    // After a minute with no interaction, fade the chrome (top bar + transport) away so only the
    // artwork/title/artist remain — a "zen" view. Any pointer/key event resets the timer and brings
    // the controls back. Only armed while the player is actually expanded, so it doesn't tick (and
    // hide the system bars) while the collapsed sheet sits off-screen behind the rest of the UI.
    val idleMonitor = rememberIdleMonitor()
    PlatformUserInteractionEffect { idleMonitor.reset() }
    val expanded by remember { derivedStateOf { playerExpansion.value > 0.99f } }
    val controlsHidden = idleMonitor.isIdle(PlayerIdleTimeoutMs, enabled = expanded)

    // In the zen view, also hide the device's system bars (status bar, gesture/navigation bar) so
    // the artwork owns the whole screen; they return with the controls.
    ImmersiveMode(enabled = controlsHidden)

    LaunchedEffect(track?.id) {
        lyrics = null
        val id = track?.id ?: return@LaunchedEffect
        lyrics = appContainer.activeMusicSource.getLyrics(id)
    }

    // Move D-pad focus into the controls when the player opens, and whenever the chrome returns from the
    // idle "zen" view — the AnimatedVisibility exit removes the focused control, so re-focus play/pause
    // so the D-pad has a target again (any key first resets the idle monitor, bringing the chrome back).
    LaunchedEffect(controlsHidden) { if (!controlsHidden) runCatching { playPauseFocus.requestFocus() } }
    // When the queue sheet (which traps its own D-pad focus) closes over the still-open player, pull
    // focus back into the transport so the remote keeps working.
    LaunchedEffect(showQueue) { if (!showQueue && !controlsHidden) runCatching { playPauseFocus.requestFocus() } }

    // Recolour the whole player to the current artwork (album uuid as the shared cache key), animating
    // to the new scheme when the track changes. Honours the user's "Tint UI with album colours" setting.
    DynamicColorTheme(
        imageUrl = track?.imageUrl,
        cacheKey = track?.albumId ?: track?.id,
        enabled = appContainer.albumTintMode.tintsPlayer,
    ) {
    Surface(
        // TV: trap D-pad focus inside the full player (it covers the rail/bars but sits outside the
        // four-zone NavHost subtree, so nothing else keeps focus here), and return focus to the
        // now-playing bar when it collapses. Initial/zen focus is handled by the LaunchedEffect above.
        modifier = modifier.fillMaxSize().glassSource(zIndex = 2f)
            .tvFocusContainer(onDismissRestore = { zone?.focusNowPlaying() })
            .resetIdleOn(idleMonitor).graphicsLayer {
            val f = playerExpansion.value
            // The sheet rests with its top at the bar line (peekHeight up from the bottom) and slides
            // up to fully cover the screen as f goes 0 → 1. It also fades in over just the first
            // [FadeInUntil] of the drag so it's solid almost immediately, then only moves.
            translationY = (1f - f) * (size.height - peekHeightPx)
            alpha = (f / FadeInUntil).coerceIn(0f, 1f)
        },
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Orientation and scale come from the player's *own* box, not the screen: the player may not own
        // the whole window (something could sit beside it), so it adapts to the space it is handed.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val isHorizontal = appContainer.playerLayoutMode.isHorizontal(maxWidth, maxHeight)
            // Travel distance of the sheet: from its resting top (at the bar line) up to the box top.
            val travelPx = (with(density) { maxHeight.toPx() } - peekHeightPx).coerceAtLeast(1f)
            // One scale curve for the whole non-artwork UI, driven by the box's smaller side so it grows
            // on desktop and shrinks on cramped windows (now down to [MinPlayerScale]). Horizontal gets a
            // boost: with the artwork beside — not above — the controls, the same box has more vertical
            // room to spend on a larger UI. TV keeps the 1f baseline.
            val minSide = minOf(maxWidth, maxHeight)
            val playerScale = if (LocalIsTvMode.current) 1f
                else playerScaleFor(if (isHorizontal) minSide * HorizontalScaleBoost else minSide)

            BlurredBackgroundImage(
                imageUrl = track?.imageUrl,
                modifier = Modifier.alpha(0.2f),
            )

            // The four content blocks are defined once and only *arranged* differently per orientation,
            // so the chrome/idle/gesture logic isn't duplicated across the two layouts.
            val topBar = @Composable {
                AnimatedVisibility(
                    visible = !controlsHidden,
                    enter = expandVertically(animationSpec = tween(durationMillis = 1000)),
                    exit = shrinkVertically(animationSpec = tween(durationMillis = 1000))
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onCollapse) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
                        }
                        Spacer(Modifier.weight(1f))
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Now playing", style = MaterialTheme.typography.titleSmall.scaled(playerScale))
                            if (isCasting) {
                                Text(
                                    player.displayName,
                                    style = MaterialTheme.typography.labelSmall.scaled(playerScale),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // When the live link drops, the device name above is the last-known target;
                                // flag that its reported state is no longer updating.
                                RemoteConnectionIndicator(scale = playerScale)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }

            val transport = @Composable {
                AnimatedVisibility(
                    visible = controlsHidden,
                    enter = expandVertically(animationSpec = tween(durationMillis = 1000)),
                    exit = shrinkVertically(animationSpec = tween(durationMillis = 1000))
                ) {
                    val duration = status.durationMs.coerceAtLeast(1)
                    val position = rememberSmoothPosition(status)

                    LinearProgressIndicator(
                        progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .padding(vertical = 32.dp)
                            .widthIn(max = 480.dp * playerScale)
                            .fillMaxWidth(),
                        trackColor = ProgressIndicatorDefaults.linearTrackColor.copy(alpha = LocalUiOpacity.current),
                    )
                }

                AnimatedVisibility(
                    visible = !controlsHidden,
                    enter = expandVertically(animationSpec = tween(durationMillis = 1000)),
                    exit = shrinkVertically(animationSpec = tween(durationMillis = 1000))
                ) {
                    Spacer(Modifier.size(16.dp * playerScale))
                    PlayerControls(
                        status = status,
                        player = player,
                        modifier = Modifier.widthIn(max = 480.dp * playerScale).fillMaxWidth(),
                        showSecondaryControls = true,
                        showTertiaryControls = true,
                        showVolumeBar = !controlsSystemVolume,
                        scale = playerScale,
                        playPauseFocusRequester = playPauseFocus,
                        showLyrics = showLyrics,
                        onToggleLyrics = { showLyrics = !showLyrics },
                        onOpenQueue = { showQueue = true },
                    )
                }
            }

            // Album/title/artist block, positioned per layout (centred below the cover when stacked,
            // left-aligned beside it when horizontal).
            val info = @Composable { alignment: Alignment.Horizontal, textAlign: TextAlign ->
                TrackInfo(
                    track = track,
                    scale = playerScale,
                    onNavigate = onNavigate,
                    horizontalAlignment = alignment,
                    textAlign = textAlign,
                )
            }

            // The sheet's vertical drag: 1:1 finger tracking that drives [playerExpansion], plus a
            // swipe-up-for-queue shortcut. Shared by both arrangements.
            val dragContainer = Modifier
                .fillMaxSize()
                // Dodge the status/nav bars *and* the display cutout (the camera bump), taking the max
                // per side (union, not sum). On desktop these are all zero, so the base edge padding
                // added per-branch below is symmetric there. The base padding is applied per layout, not
                // here, so each can distribute its own margins.
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .pointerInput(playerExpansion, travelPx) {
                    // totalDy accumulates raw vertical delta for the current gesture (positive = down).
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDy = 0f },
                        onDragEnd = {
                            // Upward swipe (totalDy < 0) past the threshold while fully expanded → queue.
                            if (totalDy < -swipeUpThresholdPx && playerExpansion.value > 0.99f) {
                                showQueue = true
                            }
                            val f = playerExpansion.value
                            scope.launch {
                                playerExpansion.animateTo(
                                    if (f > 0.95f) 1f else 0f,
                                    if (f > 0.95f) expandSpec else collapseSpec,
                                )
                            }
                        },
                        onDragCancel = { scope.launch { playerExpansion.animateTo(1f, snapBack) } },
                    ) { _, dy ->
                        totalDy += dy
                        // The sheet only travels (box - peek) px, so df = -dy / travelPx.
                        val newF = (playerExpansion.value - dy / travelPx).coerceIn(0f, 1f)
                        scope.launch { playerExpansion.snapTo(newF) }
                    }
                }

            if (isHorizontal) {
                // Base edge padding is on the Row (start/end); the cover carries its own equal top/bottom
                // padding, and the cover→controls gap matches, so every margin around the cover is equal.
                Row(dragContainer.padding(start = PlayerEdgePadding, end = PlayerEdgePadding)) {
                    // Artwork on the left, a square filling the (padded) height; lyrics cross-fade over it.
                    // Everything else is a column on the right. Because the text sits *beside* the cover,
                    // its line-count can never nudge the artwork.
                    Box(
                        // padding *before* aspectRatio: the square is sized from the height that's left
                        // after the 24dp inset, so the box width collapses to match and the cover doesn't
                        // float horizontally inside a taller box (which is what doubled its left gap). The
                        // result: equal top/bottom/left margins (left = the Row's start padding).
                        Modifier.fillMaxHeight().padding(vertical = PlayerEdgePadding).aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = showLyrics,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                            modifier = Modifier.fillMaxSize(),
                        ) { isShowingLyrics ->
                            if (isShowingLyrics) {
                                LyricsView(
                                    lyrics = lyrics,
                                    positionMs = smoothPosition,
                                    scale = playerScale,
                                    onSeekTo = { player.seekTo(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                PlayerArtwork(status = status, player = player, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                    Spacer(Modifier.size(PlayerEdgePadding))
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        topBar()
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            info(Alignment.Start, TextAlign.Start)
                        }
                        transport()
                    }
                }
            } else {
                Column(
                    dragContainer.padding(horizontal = PlayerEdgePadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    topBar()
                    // Center area between the top bar and the transport controls. Lyrics take the whole
                    // region; otherwise it's the original stacked layout — album name above the cover,
                    // title + artist(s) below.
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            targetState = showLyrics,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                            modifier = Modifier.fillMaxSize(),
                        ) { isShowingLyrics ->
                            if (isShowingLyrics) {
                                LyricsView(
                                    lyrics = lyrics,
                                    positionMs = smoothPosition,
                                    scale = playerScale,
                                    onSeekTo = { player.seekTo(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                // Cap the cover at a fraction of the region's height (and centre the
                                // whole stack), so on tall screens it stays a comfortable size instead of
                                // swelling to fill every leftover pixel. Only the stacked layout does this;
                                // the horizontal branch fills the full column height beside the controls.
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val artSize = minOf(maxWidth, maxHeight * VerticalArtworkHeightFraction)
                                    Column(
                                        Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        track?.album?.let { album ->
                                            LibraryReference(
                                                label = album,
                                                type = LibraryReferenceType.Album,
                                                onNavigate = onNavigate,
                                                textOnly = true,
                                                style = MaterialTheme.typography.titleMedium.scaled(playerScale),
                                            )
                                            Spacer(Modifier.size(12.dp * playerScale))
                                        }
                                        PlayerArtwork(
                                            status = status,
                                            player = player,
                                            modifier = Modifier.size(artSize),
                                        )
                                        Spacer(Modifier.size(24.dp * playerScale))
                                        Text(
                                            track?.name ?: "",
                                            style = MaterialTheme.typography.headlineSmall.scaled(playerScale),
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!track?.artists.isNullOrEmpty()) {
                                            Row {
                                                track.artists.forEachIndexed { index, artist ->
                                                    if (index > 0) {
                                                        Text(
                                                            text = ", ",
                                                            style = MaterialTheme.typography.titleMedium.scaled(playerScale)
                                                                .copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                                        )
                                                    }
                                                    LibraryReference(
                                                        label = artist,
                                                        type = LibraryReferenceType.Artist,
                                                        onNavigate = onNavigate,
                                                        textOnly = true,
                                                        style = MaterialTheme.typography.titleMedium.scaled(playerScale)
                                                            .copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    transport()
                }
            }
        }
    }
    }

    if (showQueue) {
        QueueSheet(status = status, player = player, onDismiss = { showQueue = false })
    }
}

/**
 * The swipeable album cover, sized to a square that fits the space it is given ([alignment] places that
 * square within a larger box — it fills a weighted region in the stacked layout and the full height in
 * the horizontal one). Owns the whole track-transition state machine.
 *
 * Dragging horizontally reveals the previous/next track's artwork through a moving seam (see
 * [SwipeableArtwork]); releasing past [ArtCommitFraction] commits the skip. Track changes from any other
 * source (transport buttons, auto-advance) play the same seam briefly — unless the new track shares the
 * old one's artwork, in which case the cover swaps silently.
 */
@Composable
private fun PlayerArtwork(
    status: PlayerStatus,
    player: Player,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
) {
    val track = status.current
    // Neighbours drive the peeking covers. Index-based, so under shuffle the previewed cover can
    // differ from what next()/previous() actually plays — the commit still calls the player, which is
    // authoritative; only the in-flight preview art may momentarily be off.
    val prev = status.queue.getOrNull(status.queueIndex - 1)
    val next = status.queue.getOrNull(status.queueIndex + 1)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalPlatformContext.current

    // -1 = fully skipped to next, +1 = fully skipped to previous, 0 = current centred. Shared by the
    // finger drag and the programmatic (button / auto-advance) sweep.
    val offset = remember { Animatable(0f) }
    // The settled centre cover. It lags [track] by the transition: a change is shown by *animating*
    // toward it, so the resting layer keeps painting the old cover until the seam finishes — otherwise
    // the new art would pop in for a frame before the sweep starts.
    var shown by remember { mutableStateOf(track) }
    // Non-null while a seam animates; pins the exact (from → to) pair so the render stays stable even
    // as [track] flips underneath mid-animation.
    var sweep by remember { mutableStateOf<Sweep?>(null) }
    // Set when *we* caused the imminent [track] change (a gesture release), so the effect below doesn't
    // also fire a second, programmatic sweep for it. (If a release somehow doesn't advance the player,
    // the flag lingers and the next external change swaps silently — a negligible one-off.)
    var gestureCommit by remember { mutableStateOf(false) }
    // Previous queue index, to tell forward skips from backward ones for the sweep direction.
    var lastIndex by remember { mutableStateOf(status.queueIndex) }

    // Runs one seam from [from] to [to]. [engaged] = the finger already moved the seam (gesture
    // release), so don't reset to 0 first. [commit] (gesture only) is the transport call issued at full
    // extension, after which we wait for the player to actually advance before recentring.
    val playSeam: suspend (Track?, Track?, Boolean, Boolean, (() -> Unit)?) -> Unit =
        { from, to, forward, engaged, commit ->
            try {
                sweep = Sweep(from, to, forward)
                if (!engaged) offset.snapTo(0f)
                offset.animateTo(if (forward) -1f else 1f, tween(if (commit != null) CommitSweepMs else AutoSweepMs))
                if (commit != null) {
                    val fromId = from?.id
                    commit()
                    withTimeoutOrNull(1000) { player.status.first { it.current?.id != fromId } }
                }
                shown = to
                offset.snapTo(0f)
            } finally {
                // Even if a rapid track change cancels this mid-animation, drop the pin so the drag
                // re-attaches; the relaunched effect then re-establishes a clean state.
                sweep = null
            }
        }

    // React to track changes that *didn't* come from a finger release: transport buttons and
    // auto-advance. Same-artwork changes swap silently (a seam between identical covers is just a
    // flicker); different-artwork changes preload the incoming cover, then play a short seam.
    LaunchedEffect(track?.id) {
        val to = track
        val from = shown
        val fromIdx = lastIndex
        lastIndex = status.queueIndex
        when {
            to?.id == from?.id -> {}                                   // no real change (incl. first frame)
            to == null -> { shown = null; sweep = null; offset.snapTo(0f) }   // playback stopped
            gestureCommit -> { gestureCommit = false; shown = to }     // our own gesture already animated it
            // Same artwork → swap silently. Also normalise offset/sweep in case this interrupted an
            // in-flight different-art sweep (which wouldn't otherwise get cleaned up here).
            from?.imageUrl == to.imageUrl -> { shown = to; sweep = null; offset.snapTo(0f) }
            else -> {
                awaitImage(context, to.imageUrl)                       // load first so the sweep shows no placeholder
                playSeam(from, to, status.queueIndex >= fromIdx, false, null)
            }
        }
    }

    // Warm the neighbours so a *gesture* reveal doesn't flash a placeholder before the art loads.
    LaunchedEffect(prev?.imageUrl, next?.imageUrl) {
        awaitImage(context, prev?.imageUrl)
        awaitImage(context, next?.imageUrl)
    }

    // What the seam paints: the pinned pair while a sweep runs, otherwise the settled centre ([shown])
    // flanked by the live neighbours for the drag.
    val sweepState = sweep
    val effCurrent: Track?
    val effPrev: Track?
    val effNext: Track?
    if (sweepState != null) {
        effCurrent = sweepState.from
        effPrev = if (sweepState.forward) null else sweepState.to
        effNext = if (sweepState.forward) sweepState.to else null
    } else {
        effCurrent = shown
        effPrev = prev
        effNext = next
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = alignment) {
        // Square cover that fits the box on either axis, so it fills the space without clipping whether
        // the box is the (short) stacked region or the full-height horizontal column.
        val artSize = minOf(maxWidth, maxHeight)
        val artSizePx = with(density) { artSize.toPx() }
        // Finger drag is inert while a programmatic sweep plays (a change of identity to `Modifier`
        // detaches the pointer input); it re-attaches once the sweep clears.
        val dragModifier = if (sweepState == null) {
            Modifier.pointerInput(shown?.id, prev?.id, next?.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val f = offset.value
                        scope.launch {
                            when {
                                f <= -ArtCommitFraction && next != null -> {
                                    gestureCommit = true
                                    playSeam(shown, next, true, true) { player.next() }
                                }
                                f >= ArtCommitFraction && prev != null -> {
                                    gestureCommit = true
                                    playSeam(shown, prev, false, true) { player.previous() }
                                }
                                else -> offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    },
                ) { _, dragX ->
                    // Dragging left (dragX < 0) heads toward next; right toward previous. Clamp to
                    // whichever neighbours exist so you can't rubber-band past the ends of the queue.
                    val min = if (next != null) -1f else 0f
                    val max = if (prev != null) 1f else 0f
                    scope.launch { offset.snapTo((offset.value + dragX / artSizePx).coerceIn(min, max)) }
                }
            }
        } else Modifier
        SwipeableArtwork(
            current = effCurrent,
            prev = effPrev,
            next = effNext,
            offset = offset.value,
            artSize = artSize,
            modifier = dragModifier,
        )
    }
}

/**
 * The album name, track title and artist(s) as a vertical block, shared by both layouts. All three are
 * [LibraryReference]s: clickable links to their detail screens when they resolve against the active
 * library, plain text otherwise (following a link navigates via [onNavigate], which also collapses the
 * player). No reserved slots and no `minLines`, so the block is exactly as tall as its content — the
 * layout around it (anchored region, or beside the cover) is what keeps the artwork from jumping.
 */
@Composable
private fun TrackInfo(
    track: Track?,
    scale: Float,
    onNavigate: (Any) -> Unit,
    horizontalAlignment: Alignment.Horizontal,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    val labelStyle = MaterialTheme.typography.titleMedium.scaled(scale)
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        track?.album?.let { album ->
            LibraryReference(
                label = album,
                type = LibraryReferenceType.Album,
                onNavigate = onNavigate,
                textOnly = true,
                style = labelStyle,
            )
            Spacer(Modifier.size(6.dp * scale))
        }
        // Up to three lines so long titles stay fully visible; a pathological one ellipsizes.
        Text(
            track?.name ?: "",
            style = MaterialTheme.typography.headlineSmall.scaled(scale),
            textAlign = textAlign,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!track?.artists.isNullOrEmpty()) {
            Spacer(Modifier.size(6.dp * scale))
            Row {
                track.artists.forEachIndexed { index, artist ->
                    if (index > 0) {
                        Text(
                            text = ", ",
                            style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    LibraryReference(
                        label = artist,
                        type = LibraryReferenceType.Artist,
                        onNavigate = onNavigate,
                        textOnly = true,
                        style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

/** Releasing a cover drag past this fraction of the cover's width skips tracks. */
private const val ArtCommitFraction = 0.4f
/** Seam duration for a finger release (gesture commit) — a touch longer so the flick reads. */
private const val CommitSweepMs = 220
/** Seam duration for a button / auto-advance change — short, per the "100–200 ms" feel. */
private const val AutoSweepMs = 160

/** A single in-flight cover transition: which two covers, and whether it's a forward (next) skip. */
private data class Sweep(val from: Track?, val to: Track?, val forward: Boolean)

/**
 * Load [url] into Coil's cache and suspend until it's ready (bounded by a timeout), so the seam that
 * follows reveals a decoded cover rather than the placeholder.
 */
private suspend fun awaitImage(context: PlatformContext, url: String?) {
    if (url == null) return
    withTimeoutOrNull(1500) {
        // Size.ORIGINAL + an explicit memoryCacheKey so the warmed bitmap lands under the same key the
        // on-screen covers look up via placeholderMemoryCacheKey — that's what lets them paint it on the
        // first frame with no reload.
        SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context).data(url).size(Size.ORIGINAL).memoryCacheKey(url).build()
        )
    }
}

/**
 * The cover with its two neighbours, rendered as a moving-seam reveal. At [offset] 0 only [current]
 * shows. As |offset| grows a vertical seam sweeps across: the outgoing cover stays pinned to one edge
 * and is clipped back, while the incoming cover is revealed pinned to the opposite edge — a small gap
 * between them, the seam-facing corners rounded ("bevelled"). [offset] < 0 reveals [next] from the
 * right; > 0 reveals [prev] from the left.
 */
@Composable
private fun SwipeableArtwork(
    current: Track?,
    prev: Track?,
    next: Track?,
    offset: Float,
    artSize: Dp,
    modifier: Modifier = Modifier,
) {
    val outer = 20.dp   // outer (screen-facing) corner radius
    val bevel = 12.dp   // seam-facing corner radius — the "bevel" on the gap edge
    val gap = 8.dp      // gutter between the two covers at full sweep
    // Always render the same two edge-pinned regions rather than branching to a separate single resting
    // cover. That preserves Compose node identity across the seam→rest handoff: on a forward sweep the
    // incoming cover occupies the right region for the whole animation and simply *stays* there at rest,
    // instead of being torn down and rebuilt as a fresh AsyncImage node — the rebuild (and its one-frame
    // placeholder draw) was the end-of-transition flash/pixelation.
    val p = abs(offset)
    val leftIsCurrent = offset < 0f          // next-reveal: current on the left, next entering on the right
    val leftTrack = if (leftIsCurrent) current else prev
    val rightTrack = if (leftIsCurrent) next else current
    val leftFrac = if (leftIsCurrent) (1f - p) else p
    // Gutter and seam rounding are widest when the two covers are evenly split and taper to zero as
    // either one takes over. So at *both* ends — rest (p = 0) and fully swept (p = 1) — the single
    // visible cover is already full-width with round corners, meaning the post-animation snapTo(0)
    // changes nothing. (Scaling by `p` instead left an 8dp gutter + bevel at full sweep that popped away
    // on the snap: the cover's inner edge jumped out by the gutter and the corners squared-then-rounded.)
    val split = 2f * minOf(leftFrac, 1f - leftFrac)   // 0 when one cover fills, 1 at an even split
    val g = gap * split
    val seam = lerp(outer, bevel, split)
    val leftWidth = (artSize * leftFrac - g / 2).coerceAtLeast(0.dp)
    val rightWidth = (artSize * (1f - leftFrac) - g / 2).coerceAtLeast(0.dp)
    Box(modifier.size(artSize)) {
        if (leftWidth > 0.dp) {
            // Left cover: pinned to the start, clipped on the seam (end) side.
            Box(
                Modifier.align(Alignment.CenterStart).width(leftWidth).height(artSize)
                    .clip(RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = seam, bottomEnd = seam)),
                contentAlignment = Alignment.CenterStart,
            ) {
                CoverArt(leftTrack, Modifier.requiredSize(artSize))
            }
        }
        if (rightWidth > 0.dp) {
            // Right cover: pinned to the end, clipped on the seam (start) side.
            Box(
                Modifier.align(Alignment.CenterEnd).width(rightWidth).height(artSize)
                    .clip(RoundedCornerShape(topStart = seam, bottomStart = seam, topEnd = outer, bottomEnd = outer)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                CoverArt(rightTrack, Modifier.requiredSize(artSize))
            }
        }
    }
}

/**
 * One square cover: the album-icon placeholder with the artwork drawn on top (the [AsyncImage] simply
 * draws nothing when the track has no cover or it fails to load, letting the placeholder show through).
 * Shaping/clipping is the caller's job via [modifier].
 */
@Composable
private fun CoverArt(track: Track?, modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(Res.drawable.album),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxSize(0.5f),
        )
        AsyncImage(
            // Size.ORIGINAL so this shares one cache entry with the warm/preload and the blurred
            // background. memoryCacheKey pins that entry to a known key (the URL); placeholderMemoryCacheKey
            // then paints the cached bitmap *synchronously on frame 0*, so a freshly composed node — e.g.
            // the resting cover created at the seam→rest handoff — never shows the empty/placeholder frame
            // an AsyncImage otherwise renders while its (even cache-hit) request resolves on a coroutine.
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(track?.imageUrl)
                .size(Size.ORIGINAL)
                .memoryCacheKey(track?.imageUrl)
                .placeholderMemoryCacheKey(track?.imageUrl)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// The continuous size curve for the full player, driven by the player box's smaller side. It reads the
// phone-tuned 1f at [PlayerScaleBaseline] (≈ a phone's narrow dimension), ramps *up* to [MaxPlayerScale]
// by [PlayerScaleFull] (desktop/large windows), and *down* to [MinPlayerScale] by [PlayerScaleMin] so a
// cramped window (e.g. a short landscape one) shrinks the whole UI to fit rather than overflowing.
// Base margin between the player content and its edges (equal on all sides of the cover); the display
// cutout / system bars are added on top per-side (see the drag container).
private val PlayerEdgePadding = 24.dp

// In the stacked (vertical) layout the cover is capped at this fraction of the centre region's height,
// so it stays a comfortable size on tall screens rather than swelling to fill all the leftover space.
// The horizontal layout ignores this — there the cover fills the full column height beside the controls.
private const val VerticalArtworkHeightFraction = 0.6f

private val PlayerScaleMin = 220.dp
// Where the UI reaches its phone-tuned 1.0. Raised above a phone's ~412dp narrow side so a cramped
// window starts shrinking a touch *before* it gets that small, rather than only once it's already tiny.
private val PlayerScaleBaseline = 420.dp
private val PlayerScaleFull = 1200.dp
private const val MinPlayerScale = 0.5f
private const val MaxPlayerScale = 1.75f

// The horizontal layout puts the artwork *beside* the controls instead of above them, so the same box
// has a little more vertical room; feed the scale curve a modestly boosted dimension there. Kept small
// because a *short* landscape window still lands here (width ≥ 2·height), and over-boosting would push
// the right-column text into the controls.
private const val HorizontalScaleBoost = 1.1f

// Fraction of the open drag (0 = collapsed, 1 = open) over which the sheet fades from transparent to
// opaque; past this it's fully solid and only translates. Small so it solidifies near the start.
private const val FadeInUntil = 0.15f

private fun playerScaleFor(minDimension: Dp): Float = when {
    minDimension <= PlayerScaleMin -> MinPlayerScale
    minDimension < PlayerScaleBaseline -> {
        val t = (minDimension - PlayerScaleMin) / (PlayerScaleBaseline - PlayerScaleMin)
        MinPlayerScale + t * (1f - MinPlayerScale)
    }
    else -> {
        val t = ((minDimension - PlayerScaleBaseline) / (PlayerScaleFull - PlayerScaleBaseline)).coerceIn(0f, 1f)
        1f + t * (MaxPlayerScale - 1f)
    }
}

