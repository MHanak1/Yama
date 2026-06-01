package net.mhanak.yama.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

@Composable
fun Modifier.tabFocusTraversal(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { keyEvent ->
        if (keyEvent.key == Key.Tab && keyEvent.type == KeyEventType.KeyDown) {
            if (keyEvent.isShiftPressed) {
                focusManager.moveFocus(FocusDirection.Previous)
            } else {
                focusManager.moveFocus(FocusDirection.Next)
            }
            true
        } else false
    }
}