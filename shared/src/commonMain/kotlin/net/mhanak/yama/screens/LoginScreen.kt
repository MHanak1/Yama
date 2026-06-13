package net.mhanak.yama.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.Async
import net.mhanak.yama.components.ErrorCard
import net.mhanak.yama.components.LocalLibrarySettings
import net.mhanak.yama.components.SourceIcon
import net.mhanak.yama.components.VerticalScrollbarIfNeeded
import net.mhanak.yama.components.supportsDirectoryPicker
import net.mhanak.yama.util.tabFocusTraversal
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import kotlin.collections.emptyList

// Subcomponents call this to show/hide the back button and set its action.
// Pass null to hide the button.
val LocalSetBackAction = compositionLocalOf<((() -> Unit)?) -> Unit> { {} }


@Composable
@Preview
fun LoginScreen(onDismiss: (() -> Unit)? = null) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var backAction: (() -> Unit)? by remember { mutableStateOf(null) }
    val options = listOf("Jellyfin", "Subsonic", "Local Files")

    CompositionLocalProvider(LocalSetBackAction provides { backAction = it }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth()
                .widthIn(max = 512.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = { backAction?.invoke() },
                    enabled = backAction != null,
                ) {
                    if (backAction != null) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                if (onDismiss != null) {
                    IconButton(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onClick = onDismiss,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                var sourceMenuExpanded by remember { mutableStateOf(false) }
                Box(modifier = Modifier.align(Alignment.Center)) {
                    OutlinedButton(onClick = { sourceMenuExpanded = true }) {
                        AnimatedContent(
                            targetState = selectedIndex,
                            contentAlignment = Alignment.CenterStart,
                            transitionSpec = {
                                fadeIn(tween(200)) togetherWith
                                    fadeOut(tween(200)) using
                                    SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(200) })
                            },
                        ) { idx ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SourceIcon(options[idx])
                                Spacer(Modifier.width(8.dp))
                                Text(options[idx])
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sourceMenuExpanded,
                        onDismissRequest = { sourceMenuExpanded = false },
                    ) {
                        options.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { SourceIcon(label) },
                                onClick = { selectedIndex = index; sourceMenuExpanded = false },
                                enabled = label == "Jellyfin" || label == "Local Files",
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedContent(selectedIndex) { targetState ->
                when (targetState) {
                    0 -> JellyfinMain()
                    1 -> {}
                    2 -> LocalFilesMain(onDismiss)
                }
            }
        }
    }
}

/**
 * The "Local Files" tab of the login screen. There's nothing to authenticate — the local source is
 * always usable — so this just makes it the active source (which flips [App] to [MainScreen], since
 * [net.mhanak.yama.media.sources.local.LocalSource.isAuthenticated] is always true). Folders are
 * managed afterwards in Settings.
 */
@Composable
fun LocalFilesMain(onDismiss: (() -> Unit)?) {
    val appContainer = LocalAppContainer.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            if (supportsDirectoryPicker) {
                "Play music stored on this device. Add the folders you want to scan — you can change " +
                    "these any time in Settings."
            } else {
                "Play music stored on this device. Your media library is indexed automatically."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        // Reuse the Settings folder manager (folder list + add/rescan, or the auto-index note where
        // there's no picker) so onboarding and Settings stay in sync.
        LocalLibrarySettings()
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            appContainer.selectSource(appContainer.localSource)
            onDismiss?.invoke()
        }) {
            Text("Use Local Library")
        }
    }
}

@Composable
fun JellyfinMain() {
    val appContainer = LocalAppContainer.current
    val setBackAction = LocalSetBackAction.current
    var selectedServer by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { setBackAction(null) }
    }

    LaunchedEffect(selectedServer) {
        setBackAction(if (selectedServer.isNotEmpty()) ({ selectedServer = "" }) else null)
    }

    if (selectedServer.isEmpty()) {
        JellyfinServerPicker(onServerSelected = { selectedServer = it })
    } else {
        Async(
            key = selectedServer,
            producer = { appContainer.jellyfinSource.connectToAddress(selectedServer) },
            error = { t -> ErrorCard(title = "Could not connect to server", message = t.message ?: "Unknown error") },
        ) {
            JellyfinLogin(selectedServer, appContainer.jellyfinSource.api!!)
        }
    }
}

@Composable
fun JellyfinServerPicker(onServerSelected: (String) -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JellyfinServers(onServerSelected)
        Spacer(modifier = Modifier.height(8.dp))
        val hostState = rememberTextFieldState()
        OutlinedTextField(state = hostState, label = { Text("Server Address") })
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { onServerSelected(hostState.text as String) }) { Text("Connect") }
    }
}

@Composable
fun JellyfinServers(onServerSelected: (String) -> Unit) {
    val appContainer = LocalAppContainer.current

    val servers by remember {
        appContainer.jellyfinSource.jellyfin.discovery.discoverLocalServers()
            .runningFold(emptyList<ServerDiscoveryInfo>()) { list, server -> list + server }
    }.collectAsState(initial = emptyList())

    val listState = rememberLazyListState()

    Box(modifier = Modifier.heightIn(max = 400.dp)) {
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(servers, key = { it.id }) { server ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(
                    visible = visible,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                ) {
                    JellyfinServer(server = server, onClick = { onServerSelected(server.address) })
                }
            }
        }
        VerticalScrollbarIfNeeded(
            listState,
            modifier = Modifier.matchParentSize().wrapContentWidth(Alignment.End).offset(x = 16.dp),
        )
    }
}

@Composable
fun JellyfinServer(server: ServerDiscoveryInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(server.name, style = MaterialTheme.typography.headlineSmall)
            Text(server.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

private sealed class QcUiState {
    data object Initiating : QcUiState()
    data class Active(val code: String) : QcUiState()
    data class Error(val message: String) : QcUiState()
}

@Composable
fun JellyfinLogin(address: String, api: ApiClient) {
    val jellyfinSource = LocalAppContainer.current.jellyfinSource
    val scope = rememberCoroutineScope()

    val usernameState = rememberTextFieldState()
    val passwordState = rememberTextFieldState()
    val autofillManager = LocalAutofillManager.current

    var loginState by remember { mutableStateOf<LoginUiState>(LoginUiState.Idle) }
    var qcState by remember { mutableStateOf<QcUiState>(QcUiState.Initiating) }
    var qcInitKey by remember { mutableIntStateOf(0) }

    // Poll the server every 5 s while a QC session is active.
    LaunchedEffect(qcState) {
        val active = qcState as? QcUiState.Active ?: return@LaunchedEffect
        try {
            while (true) {
                delay(5_000)
                if (jellyfinSource.pollQuickConnect()) {
                    jellyfinSource.completeQuickConnect()
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            qcState = QcUiState.Error(e.message ?: "Quick Connect failed")
        }
    }

    Box {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Quick Connect — shown at the top, initiated automatically.
            Async(producer = { api.quickConnectApi.getQuickConnectEnabled().content }, loading = {}) { enabled ->
                if (enabled) {
                    Column (
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Kick off (or re-kick off on retry) the QC session.
                        LaunchedEffect(qcInitKey) {
                            qcState = QcUiState.Initiating
                            try {
                                qcState = QcUiState.Active(jellyfinSource.initiateQuickConnect())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                qcState = QcUiState.Error(e.message ?: "Failed to start Quick Connect")
                            }
                        }

                        AnimatedContent(
                            targetState = qcState,
                            contentAlignment = Alignment.TopCenter,
                            transitionSpec = {
                                fadeIn(tween(220, delayMillis = 90)) togetherWith
                                    fadeOut(tween(90)) using
                                    SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(220) })
                            },
                            contentKey = { it::class },
                        ) { qc ->
                            when (qc) {
                                QcUiState.Initiating -> CircularProgressIndicator()

                                is QcUiState.Active -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Quick Connect", style = MaterialTheme.typography.titleMedium)
                                    Text(qc.code, style = MaterialTheme.typography.displayMedium)
                                    Text(
                                        "Enter this code in another Jellyfin app",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                is QcUiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    ErrorCard(message = qc.message)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(onClick = { qcInitKey++ }) { Text("Retry") }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("— or —", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // Password auth
            OutlinedTextField(
                state = usernameState,
                modifier = Modifier.semantics { contentType = ContentType.Username }.tabFocusTraversal(),
                label = { Text("Username") },
            )
            OutlinedSecureTextField(
                state = passwordState,
                modifier = Modifier.semantics { contentType = ContentType.Password }.tabFocusTraversal(),
                label = { Text("Password") },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                enabled = loginState !is LoginUiState.Loading,
                onClick = {
                    scope.launch {
                        loginState = LoginUiState.Loading
                        loginState = try {
                            jellyfinSource.login(
                                username = usernameState.text.toString(),
                                password = passwordState.text.toString(),
                            )
                            LoginUiState.Idle
                        } catch (e: Exception) {
                            LoginUiState.Error(e.message ?: "Login failed")
                        }
                    }
                },
            ) {
                if (loginState is LoginUiState.Loading) CircularProgressIndicator() else Text("Log In")
            }
            if (loginState is LoginUiState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                ErrorCard(message = (loginState as LoginUiState.Error).message)
            }
        }
    }
}
