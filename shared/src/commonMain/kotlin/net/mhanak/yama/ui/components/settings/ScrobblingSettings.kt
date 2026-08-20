package net.mhanak.yama.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.mhanak.yama.LocalAppContainer

@Composable
fun ScrobblingSettings(modifier: Modifier = Modifier) {
    val appContainer = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        SettingToggle(
            title = "Enable scrobbling",
            subtitle = "Submit your listens to ListenBrainz",
            checked = appContainer.scrobblingEnabled,
            onCheckedChange = { appContainer.scrobblingEnabled = it },
        )

        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SettingsSectionHeader("ListenBrainz account")

        var token by remember { mutableStateOf(appContainer.listenBrainzToken) }
        var baseUrl by remember { mutableStateOf(appContainer.listenBrainzBaseUrl) }
        var revealToken by remember { mutableStateOf(false) }
        var validating by remember { mutableStateOf(false) }
        // null = nothing tried yet; message + isError otherwise.
        var status by remember {
            mutableStateOf(appContainer.listenBrainzUserName?.let { "Signed in as $it" to false })
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("User token") },
                supportingText = { Text("Find it at listenbrainz.org/settings") },
                singleLine = true,
                visualTransformation =
                    if (revealToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { revealToken = !revealToken }) {
                        Text(if (revealToken) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Server URL") },
                supportingText = { Text("Change for a self-hosted / LB-compatible server (e.g. Maloja)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = token.isNotBlank() && !validating,
                    onClick = {
                        validating = true
                        scope.launch {
                            val result = appContainer.validateAndSaveListenBrainz(token, baseUrl)
                            validating = false
                            status = if (result.valid) {
                                appContainer.scrobblingEnabled = true
                                "Signed in as ${result.userName}" to false
                            } else {
                                "Couldn't validate that token" to true
                            }
                        }
                    },
                ) { Text(if (validating) "Checking…" else "Validate & save") }

                if (appContainer.listenBrainzUserName != null) {
                    TextButton(onClick = {
                        appContainer.clearListenBrainz()
                        token = ""
                        status = null
                    }) { Text("Sign out") }
                }
            }
            status?.let { (message, isError) ->
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        SettingsSectionHeader("Per-source")
        Text(
            "Scrobbling is on for every source. Turn it off for a server that already scrobbles to " +
                "ListenBrainz itself, to avoid duplicate listens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        val enabled = appContainer.scrobblingEnabled
        appContainer.scrobbleTargets().forEach { target ->
            ScrobbleTargetRow(
                name = target.name,
                subtitle = target.subtitle,
                checked = appContainer.scrobbleEnabled(target.key),
                enabled = enabled,
                onCheckedChange = { appContainer.setScrobbleEnabled(target.key, it) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ScrobbleTargetRow(
    name: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

