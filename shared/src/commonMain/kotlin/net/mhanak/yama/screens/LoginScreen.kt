package net.mhanak.yama.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.runningFold
import net.mhanak.yama.LocalAppContainer
import net.mhanak.yama.components.ErrorCard
import net.mhanak.yama.components.VerticalScrollbarIfNeeded
import net.mhanak.yama.util.tabFocusTraversal
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jetbrains.compose.resources.painterResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.folder
import yama.shared.generated.resources.jellyfin_logo
import yama.shared.generated.resources.subsonic_logo
import kotlin.collections.emptyList

@Composable
@Preview
fun LoginScreen() {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val options = listOf("Jellyfin", "Subsonic", "Local Files")

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
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    onClick = { selectedIndex = index },
                    selected = index == selectedIndex,
                    label = { Text(label) },
                    enabled = label == "Jellyfin" || label == "Local Files",

                    icon = {
                        when (label) {
                            "Jellyfin" -> Image(
                                painter = painterResource(Res.drawable.jellyfin_logo), null,
                            )

                            "Subsonic" -> Image(
                                painter = painterResource(Res.drawable.subsonic_logo), null,
                            )

                            "Local Files" -> Image(
                                painter = painterResource(Res.drawable.folder), null,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer)
                            )
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))


        AnimatedContent(
            selectedIndex,
        ) { targetState ->
            when (targetState) {
                0 -> {
                    JellyfinMain()
                }
                1 -> {

                }
                2 -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {},
                    ) {
                        Text("Select Folder")
                    }
                }
            }
        }
    }

}

private sealed class ConnectState {
    object Idle : ConnectState()
    object Loading : ConnectState()
    object Success : ConnectState()
    data class Error(val message: String) : ConnectState()
}

@Composable
fun JellyfinMain() {
    val appContainer = LocalAppContainer.current
    var selectedServer by remember { mutableStateOf("") }
    var connectState by remember { mutableStateOf<ConnectState>(ConnectState.Idle) }


    LaunchedEffect(selectedServer) {
        if (selectedServer.isEmpty()) {
            connectState = ConnectState.Idle
            return@LaunchedEffect
        }
        connectState = ConnectState.Loading
        connectState = try {
            appContainer.jellyfinSource.connect(selectedServer)
            ConnectState.Success
        } catch (e: Exception) {
            ConnectState.Error(e.message ?: "Unknown error")
        }
    }

    AnimatedContent(connectState) { state ->
        when (state) {
            ConnectState.Idle -> JellyfinServerPicker(
                onServerSelected = { selectedServer = it }
            )
            ConnectState.Loading -> CircularProgressIndicator()
            is ConnectState.Success -> JellyfinLogin(
                selectedServer,
            )
            is ConnectState.Error -> {
                ErrorCard(title = "Could not connect to server", message = state.message)
            }
        }
    }
}


@Composable
fun JellyfinServerPicker(
    onServerSelected: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JellyfinServers(onServerSelected)

        Spacer(modifier = Modifier.height(8.dp))

        val hostState = rememberTextFieldState()
        OutlinedTextField(
            state = hostState,
            label = { Text("Server Address") },
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            onServerSelected(hostState.text as String)
        }) {
            Text("Connect")
        }
    }

}

@Composable
fun JellyfinServers(
    onServerSelected: (String) -> Unit
) {
    val appContainer = LocalAppContainer.current

    val servers by remember {
        appContainer.jellyfinSource.jellyfin.discovery.discoverLocalServers()
            .runningFold(emptyList<ServerDiscoveryInfo>()) { list, server -> list + server }
    }.collectAsState(initial = emptyList())


    val listState = rememberLazyListState()

    Box(
        modifier = Modifier
            .heightIn(max = 400.dp)
    ) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(servers) { server ->
                JellyfinServer(
                    server,
                    onClick = { onServerSelected(server.address) }
                )
            }
        }
        VerticalScrollbarIfNeeded(
            listState,
            modifier = Modifier
                .matchParentSize()
                .wrapContentWidth(Alignment.End)
                .offset(x = 16.dp)
        )
    }
}

@Composable
fun JellyfinServer(
    server: ServerDiscoveryInfo,
    onClick: () -> Unit
) {
    ElevatedCard (
        onClick = onClick,
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column (
            modifier = Modifier
                .padding(16.dp)
        ) {
            Text(server.name, style = MaterialTheme.typography.headlineSmall)
            Text(server.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun JellyfinLogin(
    address: String,
) {
    Box {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val usernameState = rememberTextFieldState()
            val passwordState = rememberTextFieldState()

            val appContainer = LocalAppContainer.current

            if appContainer.jellyfinSource.api.quickConnectApi.getQuickConnectEnabled() //This is async,

            val autofillManager = LocalAutofillManager.current
            OutlinedTextField(
                state = usernameState,
                modifier = Modifier
                    .semantics { contentType = ContentType.Username }
                    .tabFocusTraversal(),
                label = { Text("Username") },
            )
            OutlinedSecureTextField(
                state = passwordState,
                modifier = Modifier
                    .semantics { contentType = ContentType.Password }
                    .tabFocusTraversal(),
                label = { Text("Password") },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(onClick = {}) {
                Text("Log In")
            }
        }
    }
}