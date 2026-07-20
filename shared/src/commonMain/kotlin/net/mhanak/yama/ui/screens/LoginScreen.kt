package net.mhanak.yama.ui.screens

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.media.sources.SourceType
import net.mhanak.yama.ui.components.login.LoginErrorCard
import net.mhanak.yama.ui.components.login.LoginErrorSlot
import net.mhanak.yama.ui.components.login.LoginHeader
import net.mhanak.yama.ui.components.login.SourcePicker
import net.mhanak.yama.ui.components.state.Async
import net.mhanak.yama.ui.components.settings.LocalLibrarySettings
import net.mhanak.yama.ui.platform.VerticalScrollbarIfNeeded
import net.mhanak.yama.ui.platform.supportsDirectoryPicker
import net.mhanak.yama.util.tabFocusTraversal
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import kotlin.collections.emptyList

// Subcomponents call this to show/hide the back button and set its action.
// Pass null to hide the button.
val LocalSetBackAction = compositionLocalOf<((() -> Unit)?) -> Unit> { {} }

// The sources offered on the login screen, in display order.
private val loginSources = listOf(SourceType.Jellyfin, SourceType.Subsonic, SourceType.Local)

/**
 * The standard [CircularProgressIndicator], sized to fit a Button's content slot (the default 40dp
 * would overflow it) and coloured to the button's content so it's visible on the filled background.
 */
@Composable
private fun ButtonSpinner() {
    CircularProgressIndicator(
        modifier = Modifier.size(20.dp),
        color = LocalContentColor.current,
    )
}

@Composable
@Preview
fun LoginScreen(onDismiss: (() -> Unit)? = null) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var backAction: (() -> Unit)? by remember { mutableStateOf(null) }

    CompositionLocalProvider(LocalSetBackAction provides { backAction = it }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Top actions: back (set by a subflow, e.g. Jellyfin login → server picker) and close
            // (only in the "Add Source" modal). Sits above the hero so both corners stay reachable.
            Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
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
            }

            LoginHeader()

            Spacer(modifier = Modifier.height(24.dp))

            SourcePicker(
                options = loginSources,
                selectedIndex = selectedIndex,
                onSelect = { selectedIndex = it },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Form container — a structured panel matching the app's SourceSwitcher surfaces. Its
            // height animates as the active form (and its content) changes.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                AnimatedContent(
                    targetState = selectedIndex,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    label = "sourceForm",
                ) { targetState ->
                    when (targetState) {
                        0 -> JellyfinMain()
                        1 -> SubsonicMain()
                        2 -> LocalFilesMain(onDismiss)
                    }
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
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                appContainer.selectSource(appContainer.localSource)
                onDismiss?.invoke()
            },
        ) {
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
            error = { t -> LoginErrorCard(error = t, fallbackTitle = "Could not connect to server") },
        ) {
            JellyfinLogin(selectedServer, appContainer.jellyfinSource.api!!)
        }
    }
}

@Composable
fun JellyfinServerPicker(onServerSelected: (String) -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JellyfinServers(onServerSelected)
        Spacer(modifier = Modifier.height(8.dp))
        val hostState = rememberTextFieldState()
        val connect = { onServerSelected(hostState.text.toString()) }
        OutlinedTextField(
            state = hostState,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server Address") },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            onKeyboardAction = { connect() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = connect,
        ) { Text("Connect") }
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
    data class Error(val throwable: Throwable) : LoginUiState()
}

private sealed class QcUiState {
    data object Initiating : QcUiState()
    data class Active(val code: String) : QcUiState()
    data class Error(val throwable: Throwable) : QcUiState()
}

@Composable
fun JellyfinLogin(address: String, api: ApiClient) {
    val appContainer = LocalAppContainer.current
    val jellyfinSource = appContainer.jellyfinSource
    val scope = rememberCoroutineScope()

    val usernameState = rememberTextFieldState()
    val passwordState = rememberTextFieldState()
    val autofillManager = LocalAutofillManager.current
    val focusManager = LocalFocusManager.current

    var loginState by remember { mutableStateOf<LoginUiState>(LoginUiState.Idle) }
    var qcState by remember { mutableStateOf<QcUiState>(QcUiState.Initiating) }
    var qcInitKey by remember { mutableIntStateOf(0) }

    // Shared by the Log In button and the password field's Enter/IME "Go" action.
    val submit: () -> Unit = submit@{
        if (loginState is LoginUiState.Loading) return@submit
        scope.launch {
            loginState = LoginUiState.Loading
            loginState = try {
                jellyfinSource.login(
                    username = usernameState.text.toString(),
                    password = passwordState.text.toString(),
                )
                appContainer.selectSource(jellyfinSource)
                LoginUiState.Idle
            } catch (e: Exception) {
                LoginUiState.Error(e)
            }
        }
    }

    // Poll the server every 5 s while a QC session is active.
    LaunchedEffect(qcState) {
        val active = qcState as? QcUiState.Active ?: return@LaunchedEffect
        try {
            while (true) {
                delay(5_000)
                if (jellyfinSource.pollQuickConnect()) {
                    jellyfinSource.completeQuickConnect()
                    appContainer.selectSource(jellyfinSource)
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            qcState = QcUiState.Error(e)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
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
                            qcState = QcUiState.Error(e)
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
                                LoginErrorCard(error = qc.throwable)
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
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username }.tabFocusTraversal(),
            label = { Text("Username") },
            // Single line so Enter is the IME action (advance to password) rather than a newline.
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            onKeyboardAction = { focusManager.moveFocus(FocusDirection.Next) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedSecureTextField(
            state = passwordState,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password }.tabFocusTraversal(),
            label = { Text("Password") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            onKeyboardAction = { submit() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = loginState !is LoginUiState.Loading,
            onClick = submit,
        ) {
            if (loginState is LoginUiState.Loading) ButtonSpinner() else Text("Log In")
        }
        LoginErrorSlot(error = (loginState as? LoginUiState.Error)?.throwable)
    }
}

// ---------------------------------------------------------------------------
// Subsonic login flow
// ---------------------------------------------------------------------------

/**
 * Subsonic tab in the login screen. Two-step: server URL probe → credentials.
 * Mirrors the structure of [JellyfinMain] (server picker → login form) but without
 * LAN discovery (Subsonic has no discovery protocol).
 */
@Composable
fun SubsonicMain() {
    val appContainer = LocalAppContainer.current
    val setBackAction = LocalSetBackAction.current
    var selectedServer by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { setBackAction(null) } }

    LaunchedEffect(selectedServer) {
        setBackAction(if (selectedServer.isNotEmpty()) ({ selectedServer = "" }) else null)
    }

    if (selectedServer.isEmpty()) {
        SubsonicServerPicker(onServerSelected = { selectedServer = it })
    } else {
        Async(
            key = selectedServer,
            producer = { appContainer.subsonicSource.connect(selectedServer) },
            // friendlyLoginError falls through to the (already humanized) SubsonicException message.
            error = { t -> LoginErrorCard(error = t, fallbackTitle = "Could not reach server") },
        ) { normalizedUrl ->
            SubsonicLogin(serverUrl = normalizedUrl)
        }
    }
}

@Composable
private fun SubsonicServerPicker(onServerSelected: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Connect to a Subsonic-compatible server (Navidrome, Airsonic, Gonic, …)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        val hostState = rememberTextFieldState()
        val connect = { if (hostState.text.isNotBlank()) onServerSelected(hostState.text.toString().trim()) }
        OutlinedTextField(
            state = hostState,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server Address") },
            placeholder = { Text("https://music.example.com") },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            onKeyboardAction = { connect() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = hostState.text.isNotBlank(),
            onClick = connect,
        ) {
            Text("Connect")
        }
    }
}

@Composable
private fun SubsonicLogin(serverUrl: String) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val usernameState = rememberTextFieldState()
    val passwordState = rememberTextFieldState()
    val autofillManager = LocalAutofillManager.current
    val focusManager = LocalFocusManager.current

    var loginState by remember { mutableStateOf<LoginUiState>(LoginUiState.Idle) }

    // Shared by the Log In button and the password field's Enter/IME "Go" action.
    val submit: () -> Unit = submit@{
        if (loginState is LoginUiState.Loading) return@submit
        scope.launch {
            loginState = LoginUiState.Loading
            loginState = try {
                autofillManager?.commit()
                appContainer.subsonicSource.login(
                    serverUrl = serverUrl,
                    username = usernameState.text.toString(),
                    password = passwordState.text.toString(),
                )
                // Switch the active source to Subsonic so App.kt observes
                // isAuthenticated = true and transitions to MainScreen.
                // Also clears showLoginScreen for the "Add Source" modal flow.
                appContainer.selectSource(appContainer.subsonicSource)
                appContainer.showLoginScreen = false
                LoginUiState.Idle
            } catch (e: Exception) {
                LoginUiState.Error(e)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(serverUrl, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            state = usernameState,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username }.tabFocusTraversal(),
            label = { Text("Username") },
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            onKeyboardAction = { focusManager.moveFocus(FocusDirection.Next) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedSecureTextField(
            state = passwordState,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password }.tabFocusTraversal(),
            label = { Text("Password") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            onKeyboardAction = { submit() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = loginState !is LoginUiState.Loading,
            onClick = submit,
        ) {
            if (loginState is LoginUiState.Loading) ButtonSpinner() else Text("Log In")
        }
        LoginErrorSlot(error = (loginState as? LoginUiState.Error)?.throwable)
    }
}
