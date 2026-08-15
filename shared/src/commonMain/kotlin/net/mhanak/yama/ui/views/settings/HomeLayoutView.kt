package net.mhanak.yama.ui.views.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.HomeBlockKind
import net.mhanak.yama.ui.components.interaction.ContentFocusHost
import net.mhanak.yama.ui.home.homeConfigKey
import net.mhanak.yama.ui.home.resolveHomeBlocks
import net.mhanak.yama.util.AppPreferences
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The per-source home-screen layout editor: a draggable list of the source's chosen blocks (each
 * removable), plus a **+** action that offers every block the source supports but isn't showing yet.
 * The list *is* the layout — its order and membership are persisted to
 * [AppPreferences.setHomeBlocks] on every edit (keyed by [homeConfigKey]), so [net.mhanak.yama.ui.views.HomeView]
 * picks the change up when it re-enters composition.
 *
 * Reuses the `sh.calvin.reorderable` drag pattern from the playback QueueSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLayoutView(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val source = LocalAppContainer.current.activeMusicSource
    val key = remember(source) { homeConfigKey(source) }
    val supported = remember(source) { source.supportedHomeBlocks }

    // The working copy: seeded from the resolved layout, mutated in place, persisted on every change.
    val blocks = remember(source) { mutableStateListOf<HomeBlockKind>().apply { addAll(resolveHomeBlocks(source)) } }
    fun persist() = AppPreferences.setHomeBlocks(key, blocks.toList())

    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = blocks.indexOfFirst { it.name == from.key }
        val toIndex = blocks.indexOfFirst { it.name == to.key }
        if (fromIndex >= 0 && toIndex >= 0) blocks.add(toIndex, blocks.removeAt(fromIndex))
    }

    val available = supported.filter { it !in blocks }
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home layout") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (available.isNotEmpty()) {
                        IconButton(onClick = { showPicker = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add block")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        ContentFocusHost(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (blocks.isEmpty()) {
                item {
                    Text(
                        "No blocks yet — tap + to add some.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(blocks, key = { it.name }) { kind ->
                ReorderableItem(reorderState, key = kind.name) { _ ->
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    onDragStopped = { persist() },
                                ),
                            )
                            Text(
                                kind.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                            IconButton(onClick = { blocks.remove(kind); persist() }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove ${kind.title}")
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add a block") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(available, key = { it.name }) { kind ->
                        TextButton(
                            onClick = { blocks.add(kind); persist(); showPicker = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(kind.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("Done") }
            },
        )
    }
}
