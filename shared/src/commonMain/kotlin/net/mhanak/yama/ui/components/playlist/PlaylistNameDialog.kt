package net.mhanak.yama.ui.components.playlist

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction

/**
 * A single-field dialog for naming a playlist — reused for both **create** (empty [initialName]) and
 * **rename** (seeded). It is a dumb input: it validates non-blank and hands the trimmed name back via
 * [onConfirm], leaving the caller to decide whether that means create or rename (and to call the
 * [net.mhanak.yama.coordinators.PlaylistsCoordinator]).
 *
 * Uses the newer `TextFieldState` API (as [net.mhanak.yama.ui.screens.LoginScreen] does), single-line
 * so Enter submits.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialName: String = "",
) {
    val state = rememberTextFieldState(initialName)
    val name = state.text.toString().trim()
    fun submit() {
        if (name.isNotEmpty()) { onConfirm(name); onDismiss() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                state = state,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Playlist name") },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { submit() },
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = name.isNotEmpty()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
