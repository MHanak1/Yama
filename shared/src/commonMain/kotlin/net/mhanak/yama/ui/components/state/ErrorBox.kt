package net.mhanak.yama.ui.components.state

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.mhanak.yama.util.logger

private val errorLog = logger("ErrorUI")

/**
 * Side-effect that records [throwable] (message *and* stack trace) to the platform console exactly
 * once per distinct instance. Drop this inside any error-box composable so an error shown to the user
 * is never silently swallowed — the boxes themselves only render [Throwable.message], which loses the
 * exception type and stack trace needed to debug it.
 *
 * Keyed on the throwable instance, so it logs when a new error appears and does not re-log on
 * recomposition (or while an enter/exit animation keeps the box composed).
 */
@Composable
fun LogError(throwable: Throwable, context: String = "Error surfaced to user") {
    LaunchedEffect(throwable) { errorLog.error(context, throwable) }
}

@Composable
fun ErrorBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier,
    ) {
        Column (
            modifier = Modifier.padding(8.dp).fillMaxSize(),
            content = content
        )
    }
}
@Composable
fun ErrorCard(modifier: Modifier = Modifier, title: String = "", message: String = "") {
    ErrorBox {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
