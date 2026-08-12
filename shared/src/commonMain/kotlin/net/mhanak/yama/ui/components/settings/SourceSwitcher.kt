package net.mhanak.yama.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.model.MusicLibrary
import net.mhanak.yama.media.sources.AccountedSource
import net.mhanak.yama.media.sources.MusicSource
import net.mhanak.yama.media.sources.SourceAccount
import net.mhanak.yama.ui.components.library.SourceAvatar

private const val ANIM_MS = 200

// Both rail widths must render at the same total height so toggling wide <-> slim never shifts
// the nav items below. Total = content height + top + bottom outer padding, tuned to 72dp:
//   collapsed: 44 (chip) + 10 top + 18 bottom = 72   (10dp top keeps the chip search-bar-aligned)
//   expanded:  56 (pill) +  8 top +  8 bottom = 72   (8dp top clears the window edge; 8dp bottom matches the Home->tabs spacer)
// The taller pill (56 vs 44) forces the 12dp difference into the collapsed bottom gap, since the
// chip's top is pinned for search-bar alignment — that's why collapsed carries the larger bottom.
// The pill height is fixed (not padding-driven) so an account subtitle can't grow it past 56dp.
private val CHIP_HEIGHT = 44.dp   // slim rail avatar chip; also matches the search bar height
private val PILL_HEIGHT = 56.dp   // wide rail identity pill

@Composable
fun SourceSwitcher(modifier: Modifier = Modifier, collapsed: Boolean = false, onRequestClose: () -> Unit = {}) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val activeSource = appContainer.activeMusicSource
    // Build the flat account list from all sources in registration order. Each entry pairs an
    // account with its owning source so a click can call selectAccount on the right source.
    val allEntries: List<Pair<MusicSource, SourceAccount>> = remember(appContainer.sources) {
        appContainer.sources.flatMap { source ->
            (source as? AccountedSource)?.accounts.orEmpty().map { source to it }
        }
    }
    val activeAccountedSource = activeSource as? AccountedSource
    val activeAccount = activeAccountedSource?.accounts?.firstOrNull { it.id == activeAccountedSource.currentAccountId }

    // The library picker reflects the *active* source (it's empty for sources without the concept,
    // e.g. Local), not any specific backend.
    val libraries by activeSource.libraries.collectAsState()
    val enabledLibraryIds by activeSource.enabledLibraryIds.collectAsState()
    val menuFocusRequester = remember { FocusRequester() }

    // On TV the DropdownMenu popup can fail to claim D-pad focus, letting events fall through
    // to NavigationDrawerItems behind it. Pull focus explicitly into the menu when it opens.
    LaunchedEffect(expanded) {
        if (expanded) runCatching { menuFocusRequester.requestFocus() }
    }

    val onSelectAccount: (MusicSource, SourceAccount) -> Unit = { source, account ->
        appContainer.selectAccount(source, account.id)
        expanded = false
        onRequestClose()
    }
    val onLogout: (MusicSource, SourceAccount) -> Unit = { source, account ->
        scope.launch {
            expanded = false
            onRequestClose()
            (source as? AccountedSource)?.logout(account.id)
        }
    }
    val onAddSource: () -> Unit = {
        expanded = false
        onRequestClose()
        appContainer.showLoginScreen = true
    }

    // Horizontal inset matches the rail's RailItem pills (8.dp each side) so the identity pill lines up
    // flush with the Home / library / footer buttons below it in both collapsed and expanded widths.
    // Vertical padding is asymmetric so both widths stay 72dp tall (see CHIP/PILL comment above): the
    // expanded pill gets 8.dp top (so it doesn't touch the window edge) and 8.dp bottom (matching the
    // Home->tabs spacer, seating it the same distance from Home as the nav items sit from each other),
    // while collapsed keeps its 10.dp top for search-bar alignment and absorbs the height difference in
    // its bottom gap.
    Box(
        modifier = modifier.padding(
            start = 8.dp,
            end = 8.dp,
            top = if (collapsed) 10.dp else 8.dp,
            bottom = if (collapsed) 18.dp else 8.dp,
        ),
    ) {
        if (collapsed) {
            // 96dp narrow rail: avatar-only chip + popover panel.
            // 44dp chip height matches the search bar (SearchBar default 44dp, centred in 64dp TopAppBar,
            // top edge at statusBar+10dp; we use vertical=10dp above to align). The avatar circle fills
            // the chip so the secondaryContainer background extends to the tap target — no IconButton
            // wrapper, which would impose a 48dp ripple that overflows the 44dp box.
            Box(Modifier.fillMaxWidth().height(CHIP_HEIGHT), contentAlignment = Alignment.Center) {
                if (activeAccount != null) {
                    SourceAvatar(
                        sourceType = activeAccount.sourceType,
                        avatarUrl = activeAccount.avatarUrl,
                        size = 44.dp,
                        iconSize = 24.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { expanded = !expanded },
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .focusRequester(menuFocusRequester)
                    .width(300.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            ) {
                SourceSwitcherPanel(
                    excludeActive = false,
                    allEntries = allEntries,
                    activeSource = activeSource,
                    activeAccount = activeAccount,
                    libraries = libraries,
                    enabledLibraryIds = enabledLibraryIds,
                    onSelectAccount = onSelectAccount,
                    onLogout = onLogout,
                    onToggleLibrary = { id, checked -> activeSource.setLibraryEnabled(id, !checked) },
                    onAddSource = onAddSource,
                )
            }
        } else {
            // Wide rail / slim drawer: fixed identity pill on top; a brighter second container
            // slides out from under it on expand. The pill shape never changes — 28dp all corners,
            // secondaryContainer — matching the bevel sketch: /-\ | | \-/ on top, | | | | \-/ below.
            Column(modifier = Modifier.fillMaxWidth()) {
                // Identity pill — fixed shape, never mutates. zIndex(1f) ensures it draws on top
                // of the drawer's top overlap that fills the pill's rounded-corner gap region.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .zIndex(1f)
                ) {
                    IdentityRow(
                        account = activeAccount,
                        showChevron = true,
                        expanded = expanded,
                        onClick = { expanded = !expanded },
                    )
                }

                // Drawer — offset upward by the pill's corner radius (28dp) so its background fills
                // the transparent corner gap region at the pill's rounded bottom. The pill (zIndex 1f)
                // draws on top, hiding the overlap in the centre while the corners are filled.
                // A non-scrollable Spacer at the top of the drawer content keeps list items below
                // the pill's visual bottom edge.
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(animationSpec = tween(ANIM_MS)),
                    exit = shrinkVertically(animationSpec = tween(ANIM_MS)),
                    modifier = Modifier.offset(y = (-28).dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Spacer(Modifier.height(28.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SourceSwitcherPanel(
                                excludeActive = true,
                                allEntries = allEntries,
                                activeSource = activeSource,
                                activeAccount = activeAccount,
                                libraries = libraries,
                                enabledLibraryIds = enabledLibraryIds,
                                onSelectAccount = onSelectAccount,
                                onLogout = onLogout,
                                onToggleLibrary = { id, checked -> activeSource.setLibraryEnabled(id, !checked) },
                                onAddSource = onAddSource,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Pill header row: the identity trigger in the accordion.
@Composable
private fun IdentityRow(
    account: SourceAccount?,
    showChevron: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(ANIM_MS),
        label = "chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PILL_HEIGHT)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account != null) {
            SourceAvatar(sourceType = account.sourceType, avatarUrl = account.avatarUrl, size = 36.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account?.name ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = account?.subtitle
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
    }
}

@Composable
private fun SourceSwitcherPanel(
    modifier: Modifier = Modifier,
    excludeActive: Boolean,
    allEntries: List<Pair<MusicSource, SourceAccount>>,
    activeSource: MusicSource,
    activeAccount: SourceAccount?,
    libraries: List<MusicLibrary>,
    enabledLibraryIds: Set<String>,
    onSelectAccount: (MusicSource, SourceAccount) -> Unit,
    onLogout: (MusicSource, SourceAccount) -> Unit,
    onToggleLibrary: (String, Boolean) -> Unit,
    onAddSource: () -> Unit,
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        allEntries.forEach { (source, account) ->
            val isActive = source === activeSource && account.id == activeAccount?.id
            // When excludeActive, the active account is shown in the identity pill — don't relist it.
            if (excludeActive && isActive) return@forEach
            SourceRow(
                account = account,
                isActive = isActive,
                onClick = { onSelectAccount(source, account) },
            )
        }

        // Library picker for the active source: tick which libraries feed the albums/artists/genres
        // views. Toggling leaves the panel open so several can be changed in one pass.
        if (libraries.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
            libraries.forEach { library ->
                val checked = library.id in enabledLibraryIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleLibrary(library.id, checked) }
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = { onToggleLibrary(library.id, checked) },
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAddSource)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Add Source",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Log out the active account. Shown only when the active source supports logout (i.e.
        // Jellyfin); disappears automatically when switching to Local or any other non-logout source.
        val activeAccountedSource = activeSource as? AccountedSource
        if (activeAccountedSource?.supportsLogout == true && activeAccount != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLogout(activeSource, activeAccount) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Log out",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    account: SourceAccount,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val textColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceAvatar(sourceType = account.sourceType, avatarUrl = account.avatarUrl, size = 32.dp, iconSize = 20.dp, showBackground = false)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.subtitle != null) {
                Text(
                    text = account.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
