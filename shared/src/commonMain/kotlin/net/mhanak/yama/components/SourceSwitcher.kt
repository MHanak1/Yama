package net.mhanak.yama.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer

@Composable
fun SourceSwitcher(modifier: Modifier = Modifier, collapsed: Boolean = false, onRequestClose: () -> Unit = {}) {
    val appContainer = LocalAppContainer.current
    val jellyfinSource = appContainer.jellyfinSource
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    val sessions = jellyfinSource.sessions
    val currentSessionId = jellyfinSource.currentSessionId
    val currentSession = sessions.firstOrNull { it.id == currentSessionId }
    val sortedSessions = remember(sessions, currentSessionId) {
        sessions.sortedByDescending { it.id == currentSessionId }
    }
    val menuFocusRequester = remember { FocusRequester() }

    // On TV the DropdownMenu popup can fail to claim D-pad focus, letting events fall through
    // to NavigationDrawerItems behind it. Pull focus explicitly into the menu when it opens.
    LaunchedEffect(expanded) {
        if (expanded) runCatching { menuFocusRequester.requestFocus() }
    }

    Box(
        modifier = modifier
            .padding(horizontal = if (collapsed) 8.dp else 16.dp, vertical = 8.dp),
    ) {
        // Fixed-height interactive area so the header doesn't change height between the collapsed
        // (icon button) and expanded (outlined button) states — otherwise the rail jumps when it
        // collapses.
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            if (collapsed) {
                IconButton(onClick = { expanded = true }) {
                    SourceIcon("Jellyfin")
                }
            } else {
                GlassOutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceIcon("Jellyfin")
                        Spacer(Modifier.width(8.dp))
                        // Single line so the button doesn't wrap and grow vertically while the rail
                        // is mid-expansion (it's narrower than the text for a few frames).
                        Text(
                            currentSession?.userName ?: "Jellyfin",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .focusRequester(menuFocusRequester)
                .glassEffect(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraSmall),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            // One item per saved Jellyfin session; active one sorted first and bolded.
            // Logout button shown only for the first (active) session.
            sortedSessions.forEachIndexed { index, session ->
                val isActive = session.id == currentSessionId
                DropdownMenuItem(
                    text = {
                        Text(
                            session.userName ?: session.serverUrl,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = { SourceIcon("Jellyfin") },
                    trailingIcon = if (index == 0) {
                        {
                            IconButton(onClick = {
                                scope.launch {
                                    expanded = false
                                    onRequestClose()
                                    jellyfinSource.logoutSession(session.id)
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Log out")
                            }
                        }
                    } else null,
                    onClick = {
                        jellyfinSource.switchSession(session)
                        expanded = false
                    },
                )
            }

            // Future source types go here.

            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("Add Source") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRequestClose()
                    appContainer.showLoginScreen = true
                },
            )
        }
    }
}
