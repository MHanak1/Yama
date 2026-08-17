package net.mhanak.yama.util

// Matches a leading URL scheme, capturing the scheme name (e.g. "https" from "https://host").
private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * Turn a server base URL into a compact label for the source switcher: drop the `http(s)://`
 * scheme and, when the port is the scheme's default (80 for http, 443 for https), drop it too.
 * A non-default port is kept, since it's meaningful for reaching the server. Trailing slashes go.
 *
 * Used only as the *fallback* subtitle when a backend advertises no friendly server name — so a
 * bare `https://music.example.com` reads as `music.example.com`, while `http://10.0.0.5:8096`
 * keeps its port and reads as `10.0.0.5:8096`.
 *
 * commonMain-safe: uses only multiplatform stdlib (no `java.net.URI` / `String.format`).
 */
fun prettyServerUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    val schemeMatch = SCHEME_REGEX.find(trimmed)
    val scheme = schemeMatch?.groupValues?.get(1)?.lowercase()
    val afterScheme = if (schemeMatch != null) trimmed.substring(schemeMatch.value.length) else trimmed

    val defaultPort = when (scheme) {
        "http" -> ":80"
        "https" -> ":443"
        else -> null
    } ?: return afterScheme

    // Only touch the authority (host[:port]) — a ":80" living inside a path must survive untouched.
    val pathStart = afterScheme.indexOf('/')
    val authority = if (pathStart >= 0) afterScheme.substring(0, pathStart) else afterScheme
    val path = if (pathStart >= 0) afterScheme.substring(pathStart) else ""
    val cleanAuthority = if (authority.endsWith(defaultPort)) authority.dropLast(defaultPort.length) else authority
    return cleanAuthority + path
}
