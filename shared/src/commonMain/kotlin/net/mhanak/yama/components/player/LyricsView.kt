package net.mhanak.yama.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.media.model.Lyrics
import net.mhanak.yama.media.model.LyricsLine
import kotlin.math.absoluteValue

@Composable
fun LyricsView(
    lyrics: Lyrics?,
    positionMs: Long,
    modifier: Modifier = Modifier,
    // Multiplies the lyric text sizes so they grow with the rest of the player on large windows.
    scale: Float = 1f,
) {
    if (lyrics == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    when (lyrics) {
        Lyrics.None -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No lyrics available",
                style = MaterialTheme.typography.bodyMedium.scaled(scale),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Lyrics.Unsynced -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(lyrics.lines) { _, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge.scaled(scale),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is Lyrics.Timed -> TimedLyricsView(lyrics, positionMs, scale, modifier)
    }
}

@Composable
private fun TimedLyricsView(
    lyrics: Lyrics.Timed,
    positionMs: Long,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val wordSynced = remember(lyrics) { lyrics.lines.any { it.cues.isNotEmpty() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val activeLineIndex = lyrics.lines.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    var userScrolled by remember { mutableStateOf(false) }
    // Mutable without triggering recomposition — set before/after animateScrollToItem so
    // the isScrollInProgress observer can tell programmatic scrolls from user gestures.
    val autoScrollActive = remember { booleanArrayOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !autoScrollActive[0]) {
            userScrolled = true
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableHeightPx = with(LocalDensity.current) { maxHeight.roundToPx() }

        LaunchedEffect(activeLineIndex) {
            if (!userScrolled) {
                autoScrollActive[0] = true
                listState.scrollLineToAnchor(activeLineIndex, availableHeightPx)
                autoScrollActive[0] = false
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 80.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(lyrics.lines, key = { index, _ -> index }) { index, line ->
                val isActive = index == activeLineIndex
                val alpha = when {
                    isActive -> 1f
                    (index - activeLineIndex).absoluteValue <= 2 -> 0.6f
                    else -> 0.3f
                }
                if (wordSynced && isActive && line.cues.isNotEmpty()) {
                    WordSyncedLine(line = line, positionMs = positionMs, alpha = alpha, scale = scale)
                } else {
                    Text(
                        text = line.text,
                        style = if (isActive) MaterialTheme.typography.headlineSmall.scaled(scale)
                                else MaterialTheme.typography.titleMedium.scaled(scale),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().alpha(alpha).padding(vertical = 8.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = userScrolled,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        userScrolled = false
                        autoScrollActive[0] = true
                        listState.scrollLineToAnchor(activeLineIndex, availableHeightPx)
                        autoScrollActive[0] = false
                    }
                }
            ) {
                Text("Follow lyrics")
            }
        }
    }
}

/**
 * Scroll [index] so the line's vertical *centre* sits one third down the viewport — i.e. 1/3 of the
 * space above it and 2/3 below. Centring on the line (rather than anchoring its top) is what keeps
 * the resting point at a true 1/3 across aspect ratios: as lines wrap to different heights the top
 * would otherwise drift, pushing the visual centre below the 1/3 mark.
 *
 * `animateScrollToItem(index, scrollOffset)` only *estimates* the sizes of the items it skips over
 * as it approaches the target, so with variable-height lyric lines its final offset drifts with the
 * starting position. When the target line is already laid out we instead animate by the *measured*
 * pixel delta, which lands it exactly; for far jumps / first show we snap it close with the precise
 * [LazyListState.scrollToItem], then settle on its measured centre.
 */
private suspend fun LazyListState.scrollLineToAnchor(index: Int, viewportHeightPx: Int) {
    val anchor = viewportHeightPx / 3
    // distance to scroll so the line's centre lands on the anchor (positive = content moves up)
    fun deltaFor(info: LazyListItemInfo) = (info.offset + info.size / 2 - anchor).toFloat()

    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible != null) {
        animateScrollBy(deltaFor(visible))
    } else {
        scrollToItem(index, -anchor)
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { scrollBy(deltaFor(it)) }
    }
}

@Composable
private fun WordSyncedLine(
    line: LyricsLine,
    positionMs: Long,
    alpha: Float,
    scale: Float,
) {
    val activeCueIndex = line.cues.indexOfLast { it.startMs <= positionMs }
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val future = onSurface.copy(alpha = 0.35f)

    val annotated = remember(activeCueIndex, line, primary, onSurface, future) {
        buildAnnotatedString {
            var cursor = 0
            for ((i, cue) in line.cues.withIndex()) {
                // gap between previous cue end and this cue start (spaces, punctuation)
                if (cue.lineStartIndex > cursor) {
                    val gap = line.text.substring(cursor, cue.lineStartIndex)
                    withStyle(SpanStyle(color = if (i <= activeCueIndex) onSurface else future)) { append(gap) }
                }
                val color = when {
                    i < activeCueIndex -> onSurface
                    i == activeCueIndex -> primary
                    else -> future
                }
                withStyle(SpanStyle(color = color)) {
                    append(line.text.substring(cue.lineStartIndex, cue.lineEndIndex))
                }
                cursor = cue.lineEndIndex
            }
            if (cursor < line.text.length) {
                withStyle(SpanStyle(color = future)) { append(line.text.substring(cursor)) }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.headlineSmall.scaled(scale),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().alpha(alpha).padding(vertical = 8.dp),
    )
}
