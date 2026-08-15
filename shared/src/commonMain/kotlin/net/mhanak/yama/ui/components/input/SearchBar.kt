package net.mhanak.yama.ui.components.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.theme.LocalUiOpacity

/**
 * Compact pill-shaped search field. Built on [BasicTextField] (rather than M3's `OutlinedTextField`,
 * which forces a 56.dp min height and won't vertically center when shrunk) so the content stays
 * centered at an arbitrary [height] and the corners are fully rounded.
 *
 * When [onClick] is non-null the bar renders as a *read-only shortcut* instead: a focusable, clickable
 * pill that looks identical but is not a text field — so it opens no keyboard and, crucially, TV D-pad
 * *select* fires [onClick] (used by Home's field, which just opens the dedicated search screen). A real
 * text field would trap D-pad focus and open the IME with no way to trigger navigation.
 */
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    // TV only: called when the user presses D-pad down while the search bar is focused. Return true
    // to consume the event (focus was redirected), false to fall through to the default moveFocus.
    onFocusDown: (() -> Boolean)? = null,
    // TV only: called on D-pad left. A focused text field otherwise consumes left for the caret and
    // never exits, so hosts pass this to redirect left to the sidebar. Return true to consume.
    onFocusLeft: (() -> Boolean)? = null,
    // When set, render a read-only shortcut (no text field) that invokes this on tap / D-pad select.
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    if (onClick != null) {
        Row(
            modifier = modifier
                .height(height)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.secondaryContainer.copy(alpha = LocalUiOpacity.current))
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge).copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        // A focused text field swallows D-pad up/down (the field is single-line, so they'd otherwise
        // do nothing), trapping TV focus on the search bar. Translate them into focus moves so the
        // user can step down into the content and back up. Left/right still pass through for the caret.
        modifier = modifier.height(height).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                // If a custom focus-down handler is provided (e.g., LibraryView sending focus to the
                // content grid via ContentFocusRegistry.requestRestore), prefer it; otherwise fall
                // back to the standard moveFocus so non-TV / non-library hosts still work.
                Key.DirectionDown -> {
                    val handled = onFocusDown?.invoke() == true
                    if (handled) true else focusManager.moveFocus(FocusDirection.Down)
                }
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                // Left/right normally move the caret; when a host provides onFocusLeft (TV), redirect
                // left out to the sidebar instead — a single-line field has no useful caret travel there.
                Key.DirectionLeft -> {
                    val handled = onFocusLeft?.invoke() == true
                    if (handled) true else false
                }
                else -> false
            }
        },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.secondaryContainer.copy(alpha = LocalUiOpacity.current))
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = colors.onSurfaceVariant)
                    }
                }
            }
        },
    )
}
