package net.mhanak.yama.ui.components.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import net.mhanak.yama.ui.components.state.LogError
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Broad failure category, used to pick the error icon and (for the network case) friendly copy. */
enum class LoginErrorKind { Network, Auth, Security, Generic }

// Matches HTTP-401 signals that arrive only as text (Ktor / non-typed layers).
private val UNAUTHORIZED_REGEX = Regex("""(?i)\b401\b|unauthori[sz]ed""")

/**
 * Best-effort HTTP status behind [t], read reflectively so this UI component stays decoupled from the
 * Jellyfin SDK — its [org.jellyfin.sdk.api.client.exception.InvalidStatusException] exposes an int
 * `getStatus()`. Returns null when the throwable has no such accessor.
 */
private fun httpStatusOf(t: Throwable): Int? = runCatching {
    t::class.java.methods
        .firstOrNull { it.name == "getStatus" && it.parameterCount == 0 }
        ?.invoke(t) as? Int
}.getOrNull()

/** Humanized error presentation: a category, an optional title, and a one-line message. */
data class LoginErrorInfo(val kind: LoginErrorKind, val title: String?, val message: String)

/**
 * Map a login failure to human copy.
 *
 * Low-level transport exceptions (which surface as opaque stack-trace-ish messages) are rewritten
 * into actionable guidance. Everything else **falls through to the exception's own message**, so the
 * already-humanized messages from [net.mhanak.yama.media.sources.subsonic.SubsonicException],
 * MusicAssistantException, and Jellyfin are preserved verbatim.
 *
 * @param fallbackTitle title to show on the generic (fall-through) branch, letting a call site add
 *   context (e.g. "Could not connect to server") without overriding the specific network titles.
 */
fun friendlyLoginError(t: Throwable, fallbackTitle: String? = null): LoginErrorInfo {
    // Ktor / the Jellyfin SDK wrap the real cause several layers deep; inspect the whole chain.
    val chain = generateSequence(t) { it.cause }.take(8).toList()

    // Wrong credentials — the most common login failure — surface as HTTP 401 (typed status on the
    // Jellyfin SDK exception, or just "401"/"Unauthorized" text elsewhere). Checked first so it wins
    // over the generic fall-through. (Subsonic maps its own code 40 to a message already.)
    if (chain.any { httpStatusOf(it) == 401 || it.message?.let(UNAUTHORIZED_REGEX::containsMatchIn) == true }) {
        return LoginErrorInfo(
            LoginErrorKind.Auth,
            "Invalid username or password",
            "Double-check your credentials and try again.",
        )
    }

    chain.firstNotNullOfOrNull { cause ->
        when (cause) {
            is UnknownHostException -> LoginErrorInfo(
                LoginErrorKind.Network,
                "Can't reach that server",
                "Check the address and your internet connection.",
            )
            is ConnectException -> LoginErrorInfo(
                LoginErrorKind.Network,
                "Server isn't responding",
                "The connection was refused. Make sure the server is running and reachable.",
            )
            is SocketTimeoutException -> LoginErrorInfo(
                LoginErrorKind.Network,
                "Server isn't responding",
                "The connection timed out — the server may be offline or unreachable.",
            )
            is SSLException -> LoginErrorInfo(
                LoginErrorKind.Security,
                "Secure connection failed",
                "The server's security certificate couldn't be verified.",
            )
            else -> null
        }
    }?.let { return it }

    val message = t.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Please try again."
    return LoginErrorInfo(LoginErrorKind.Generic, fallbackTitle, message)
}

private fun LoginErrorKind.icon(): ImageVector = when (this) {
    LoginErrorKind.Network -> Icons.Default.CloudOff
    LoginErrorKind.Auth -> Icons.Default.Lock
    LoginErrorKind.Security -> Icons.Default.Lock
    LoginErrorKind.Generic -> Icons.Default.ErrorOutline
}

/**
 * The login error surface: a leading category icon beside a title/message, on the app's standard
 * `errorContainer` colours (matching [net.mhanak.yama.ui.components.state.ErrorBox]).
 */
@Composable
fun LoginErrorCard(error: Throwable, modifier: Modifier = Modifier, fallbackTitle: String? = null) {
    LogError(error, context = "Login error")
    val info = friendlyLoginError(error, fallbackTitle)
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = info.kind.icon(),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                if (info.title != null) {
                    Text(info.title, style = MaterialTheme.typography.titleSmall)
                }
                Text(info.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Animated error slot: shows [LoginErrorCard] when [error] is non-null and slides it in/out. Keeps
 * hosting simple at the call site — pass a nullable error and this handles the enter/exit animation,
 * retaining the last error so its content stays put through the exit.
 */
@Composable
fun LoginErrorSlot(error: Throwable?, modifier: Modifier = Modifier, fallbackTitle: String? = null) {
    var lastError by remember { mutableStateOf<Throwable?>(null) }
    if (error != null) lastError = error
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        // Small top gap so the card doesn't butt against the button above it.
        Column(modifier = Modifier.padding(top = 12.dp)) {
            lastError?.let { LoginErrorCard(error = it, fallbackTitle = fallbackTitle) }
        }
    }
}
