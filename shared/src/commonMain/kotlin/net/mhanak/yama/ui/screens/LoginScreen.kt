package net.mhanak.yama.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalDensity
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
import net.mhanak.yama.LocalIsTvMode
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

// ---------------------------------------------------------------------------
// Composition locals & constants
// ---------------------------------------------------------------------------

// Subcomponents call this to show/hide the back button and set its action.
// Pass null to hide the button.
val LocalSetBackAction = compositionLocalOf<((() -> Unit)?) -> Unit> { {} }

// The scroll viewport height while the (TV) keyboard is open over the form (0.dp = closed / no field
// focused). Read by keepVisibleWhenKeyboardOpen to compute where to scroll the focused field.
private val LocalKeyboardViewport = compositionLocalOf { 0.dp }

// Each text field reports focus gained/lost here so LoginScreen can count focused fields. On TV a
// focused field means the keyboard is open, which is what drives the scroll. Provided by LoginScreen.
private val LocalFieldFocus = compositionLocalOf<(focused: Boolean) -> Unit> { {} }

// Where the focused field comes to rest while the keyboard is open: this fraction of the viewport
// height down from the top (0.25 = a quarter down, i.e. centred in the top half, clear of a
// bottom-docked TV keyboard).
private const val FOCUSED_FIELD_TOP_FRACTION = 0.25f

// The sources offered on the login screen, in display order.
private val loginSources = listOf(SourceType.Jellyfin, SourceType.Subsonic, SourceType.Local)

// ---------------------------------------------------------------------------
// Text fields & small building blocks
// ---------------------------------------------------------------------------

/**
 * Reports this field's focus up to [LoginScreen] (via [LocalFieldFocus]) and, while the keyboard is
 * open, scrolls the field to a fixed resting spot ([FOCUSED_FIELD_TOP_FRACTION] down the viewport)
 * so it clears the keyboard.
 *
 * The form itself isn't resized — [LoginScreen] just adds trailing scroll slack while editing, and
 * we scroll the focused field into place with a [BringIntoViewRequester] and a computed target rect
 * (the field's own built-in bring-into-view only reaches the viewport edge, i.e. still behind the
 * keyboard). Re-issued one frame after the keyboard opens so the added slack has laid out first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.keepVisibleWhenKeyboardOpen(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val reportFocus = LocalFieldFocus.current
    val viewport = LocalKeyboardViewport.current
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0f) }
    LaunchedEffect(focused, viewport) {
        if (focused && viewport > 0.dp) {
            withFrameNanos {} // let the trailing slack (which grew the scroll range) lay out first
            val v = with(density) { viewport.toPx() }
            val top = -(FOCUSED_FIELD_TOP_FRACTION * v)
            // Ask for a viewport-tall region whose top sits FOCUSED_FIELD_TOP_FRACTION *above* this
            // field. A region exactly the viewport's height has one visible position, so the field
            // lands at that fraction down the screen — clear of the keyboard, same spot every time —
            // regardless of which field or how tall the form is.
            requester.bringIntoView(Rect(0f, top, width, top + v))
        }
    }
    // A field can be disposed while still focused (e.g. switching source tabs), so release its
    // contribution to the focused-field count on dispose or the scroll trigger would stick.
    DisposableEffect(Unit) {
        onDispose { if (focused) reportFocus(false) }
    }
    return onSizeChanged { width = it.width.toFloat() }
        .onFocusChanged { state ->
            if (state.isFocused != focused) {
                focused = state.isFocused
                reportFocus(state.isFocused)
            }
        }
        .bringIntoViewRequester(requester)
}

/**
 * A login-form text field: full width, single line, autocorrect off, and — crucially — the only
 * place [keepVisibleWhenKeyboardOpen] is applied, so the scroll-into-view behaviour is bound to text
 * fields by construction (never to buttons or other focusables). [contentType] wires autofill.
 */
@Composable
private fun LoginTextField(
    state: TextFieldState,
    label: String,
    imeAction: ImeAction,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    contentType: ContentType? = null,
) {
    OutlinedTextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .loginFieldContentType(contentType)
            .tabFocusTraversal()
            .keepVisibleWhenKeyboardOpen(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        // Single line so Enter fires the IME action (submit / advance) instead of inserting a newline.
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
        onKeyboardAction = { onAction() },
    )
}

/** The [LoginTextField] counterpart for secret input (masked, autofilled as a password). */
@Composable
private fun LoginSecureTextField(
    state: TextFieldState,
    label: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedSecureTextField(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .loginFieldContentType(ContentType.Password)
            .tabFocusTraversal()
            .keepVisibleWhenKeyboardOpen(),
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
        onKeyboardAction = { onAction() },
    )
}

// Applies the autofill contentType semantics when one is given; a no-op otherwise.
private fun Modifier.loginFieldContentType(type: ContentType?): Modifier =
    if (type != null) semantics { contentType = type } else this

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

// ---------------------------------------------------------------------------
// Login screen entry point
// ---------------------------------------------------------------------------

@Composable
@Preview
fun LoginScreen(onDismiss: (() -> Unit)? = null) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    var backAction: (() -> Unit)? by remember { mutableStateOf(null) }

    // On TV the on-screen keyboard docks over the lower part of the screen but (unlike phones)
    // reports no usable WindowInsets.ime, so imePadding() below stays 0 and a focused field can end
    // up hidden behind the keyboard. Fix: while editing, scroll the focused field to a fixed spot in
    // the top half (see keepVisibleWhenKeyboardOpen), with trailingSlack below giving the scroll the
    // room to do it. The form itself is never resized.
    //
    // The trigger is just "a text field is focused": on TV, focusing a field is what opens the
    // keyboard, so this tracks it closely without the OS insets (which this TV reports late, only
    // after the first keystroke). Only text fields carry keepVisibleWhenKeyboardOpen, so navigating
    // buttons or the source picker doesn't move anything. Phones are unaffected (isTv is false).
    val isTv = LocalIsTvMode.current
    var focusedFieldCount by remember { mutableIntStateOf(0) }
    val keyboardOpen = isTv && focusedFieldCount > 0
    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // While editing, the focused field is scrolled to FOCUSED_FIELD_TOP_FRACTION down the
        // screen (see keepVisibleWhenKeyboardOpen). keyboardViewport passes the viewport height the
        // field modifier needs to compute that scroll; trailingSlack is empty scroll room below the
        // form so even the bottom field can reach that position (it overflows under the keyboard).
        // Both are zero when no field is focused, so the form looks exactly as it did before.
        val keyboardViewport = if (keyboardOpen) maxHeight else 0.dp
        val trailingSlack = if (keyboardOpen) maxHeight * (1 - FOCUSED_FIELD_TOP_FRACTION) else 0.dp
        CompositionLocalProvider(
            LocalSetBackAction provides { backAction = it },
            LocalKeyboardViewport provides keyboardViewport,
            LocalFieldFocus provides { focused -> focusedFieldCount += if (focused) 1 else -1 },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(scrollState)
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

                // Empty scroll room so a focused field near the bottom of the form can still scroll up
                // to FOCUSED_FIELD_TOP_FRACTION while the keyboard is open; zero (a no-op) otherwise.
                Spacer(modifier = Modifier.height(trailingSlack))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared login plumbing
// ---------------------------------------------------------------------------

private sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Error(val throwable: Throwable) : LoginUiState()
}

/** The loading [state] of a login form plus a guarded [submit] that runs it. See [rememberLoginAction]. */
private class LoginAction(val state: LoginUiState, val submit: () -> Unit)

/**
 * State machine shared by every credential form: a [LoginUiState] and a [submit] that runs [perform]
 * exactly once at a time. Taps are ignored while a submit is in flight; success returns to [Idle]
 * (the caller's [perform] is responsible for navigating away), failures surface as [Error].
 */
@Composable
private fun rememberLoginAction(perform: suspend () -> Unit): LoginAction {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoginUiState>(LoginUiState.Idle) }
    val submit: () -> Unit = submit@{
        if (state is LoginUiState.Loading) return@submit
        scope.launch {
            state = LoginUiState.Loading
            state = try {
                perform()
                LoginUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoginUiState.Error(e)
            }
        }
    }
    return LoginAction(state, submit)
}

/**
 * Shared "pick a server, then authenticate" scaffold for the Jellyfin and Subsonic tabs. Owns the
 * selected-server state and wires the back button (via [LocalSetBackAction]) so backing out of the
 * credential step returns to the picker. [connect] probes the chosen server; its result is passed to
 * [authenticated] alongside the address once the probe succeeds.
 */
@Composable
private fun <T> ServerAuthFlow(
    connect: suspend (address: String) -> T,
    connectErrorTitle: String,
    picker: @Composable (onServerSelected: (String) -> Unit) -> Unit,
    authenticated: @Composable (address: String, result: T) -> Unit,
) {
    val setBackAction = LocalSetBackAction.current
    var selectedServer by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { setBackAction(null) } }

    LaunchedEffect(selectedServer) {
        setBackAction(if (selectedServer.isNotEmpty()) ({ selectedServer = "" }) else null)
    }

    if (selectedServer.isEmpty()) {
        picker { selectedServer = it }
    } else {
        Async(
            key = selectedServer,
            producer = { connect(selectedServer) },
            error = { t -> LoginErrorCard(error = t, fallbackTitle = connectErrorTitle) },
        ) { result ->
            authenticated(selectedServer, result)
        }
    }
}

/**
 * The username + password + "Log In" step, shared by the Jellyfin and Subsonic flows. [header] fills
 * the space above the username field — the server URL for Subsonic, Quick Connect for Jellyfin. The
 * button shows a [ButtonSpinner] while [isSubmitting]; [error] (if any) renders below via [LoginErrorSlot].
 */
@Composable
private fun CredentialsForm(
    usernameState: TextFieldState,
    passwordState: TextFieldState,
    isSubmitting: Boolean,
    error: Throwable?,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header()
        LoginTextField(
            state = usernameState,
            label = "Username",
            imeAction = ImeAction.Next,
            onAction = { focusManager.moveFocus(FocusDirection.Next) },
            contentType = ContentType.Username,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LoginSecureTextField(
            state = passwordState,
            label = "Password",
            onAction = onSubmit,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting,
            onClick = onSubmit,
        ) {
            if (isSubmitting) ButtonSpinner() else Text("Log In")
        }
        LoginErrorSlot(error = error)
    }
}

// ---------------------------------------------------------------------------
// Local Files flow
// ---------------------------------------------------------------------------

/**
 * The "Local Files" tab of the login screen. There's nothing to authenticate — the local source is
 * always usable — so this just makes it the active source (which flips [App] to [MainScreen], since
 * [net.mhanak.yama.media.sources.local.LocalSource.isAuthenticated] is always true). Folders are
 * managed afterwards in Settings.
 */
@Composable
private fun LocalFilesMain(onDismiss: (() -> Unit)?) {
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

// ---------------------------------------------------------------------------
// Jellyfin flow
// ---------------------------------------------------------------------------

@Composable
private fun JellyfinMain() {
    val appContainer = LocalAppContainer.current
    ServerAuthFlow(
        connect = { appContainer.jellyfinSource.connectToAddress(it) },
        connectErrorTitle = "Could not connect to server",
        picker = { onServerSelected -> JellyfinServerPicker(onServerSelected) },
        // connectToAddress has already primed jellyfinSource.api, so the login step only needs it.
        authenticated = { _, _ -> JellyfinLogin(appContainer.jellyfinSource.api!!) },
    )
}

@Composable
private fun JellyfinServerPicker(onServerSelected: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JellyfinServers(onServerSelected)
        Spacer(modifier = Modifier.height(8.dp))
        val hostState = rememberTextFieldState()
        val connect = { onServerSelected(hostState.text.toString()) }
        LoginTextField(
            state = hostState,
            label = "Server Address",
            imeAction = ImeAction.Go,
            onAction = connect,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = connect,
        ) { Text("Connect") }
    }
}

@Composable
private fun JellyfinServers(onServerSelected: (String) -> Unit) {
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
private fun JellyfinServer(server: ServerDiscoveryInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
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

private sealed class QcUiState {
    data object Initiating : QcUiState()
    data class Active(val code: String) : QcUiState()
    data class Error(val throwable: Throwable) : QcUiState()
}

/**
 * Quick Connect header for the Jellyfin login form: initiates a QC session automatically, shows the
 * pairing code to enter in another Jellyfin app, and polls until it's approved. Renders nothing when
 * the server has Quick Connect disabled.
 */
@Composable
private fun QuickConnectSection(api: ApiClient) {
    val appContainer = LocalAppContainer.current
    val jellyfinSource = appContainer.jellyfinSource
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

    // Quick Connect — shown at the top, initiated automatically. Only rendered if the server enables it.
    Async(producer = { api.quickConnectApi.getQuickConnectEnabled().content }, loading = {}) { enabled ->
        if (enabled) {
            Column(
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
}

@Composable
private fun JellyfinLogin(api: ApiClient) {
    val appContainer = LocalAppContainer.current
    val jellyfinSource = appContainer.jellyfinSource

    val usernameState = rememberTextFieldState()
    val passwordState = rememberTextFieldState()

    val action = rememberLoginAction {
        jellyfinSource.login(
            username = usernameState.text.toString(),
            password = passwordState.text.toString(),
        )
        appContainer.selectSource(jellyfinSource)
    }

    CredentialsForm(
        usernameState = usernameState,
        passwordState = passwordState,
        isSubmitting = action.state is LoginUiState.Loading,
        error = (action.state as? LoginUiState.Error)?.throwable,
        onSubmit = action.submit,
        header = { QuickConnectSection(api) },
    )
}

// ---------------------------------------------------------------------------
// Subsonic flow
// ---------------------------------------------------------------------------

/**
 * Subsonic tab in the login screen. Two-step: server URL probe → credentials.
 * Mirrors the structure of [JellyfinMain] (server picker → login form) but without
 * LAN discovery (Subsonic has no discovery protocol).
 */
@Composable
private fun SubsonicMain() {
    val appContainer = LocalAppContainer.current
    ServerAuthFlow(
        connect = { appContainer.subsonicSource.connect(it) },
        // friendlyLoginError falls through to the (already humanized) SubsonicException message.
        connectErrorTitle = "Could not reach server",
        picker = { onServerSelected -> SubsonicServerPicker(onServerSelected) },
        authenticated = { _, normalizedUrl -> SubsonicLogin(serverUrl = normalizedUrl) },
    )
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
        LoginTextField(
            state = hostState,
            label = "Server Address",
            imeAction = ImeAction.Go,
            onAction = connect,
            placeholder = "https://music.example.com",
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
    val autofillManager = LocalAutofillManager.current

    val usernameState = rememberTextFieldState()
    val passwordState = rememberTextFieldState()

    val action = rememberLoginAction {
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
    }

    CredentialsForm(
        usernameState = usernameState,
        passwordState = passwordState,
        isSubmitting = action.state is LoginUiState.Loading,
        error = (action.state as? LoginUiState.Error)?.throwable,
        onSubmit = action.submit,
        header = {
            Text(serverUrl, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
        },
    )
}
