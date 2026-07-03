package net.mhanak.yama.media.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.mhanak.yama.util.logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Minimal streaming file fetcher backed by `java.net.HttpURLConnection` (always present; no extra
 * dependency, and this module's commonMain already targets the JVM/Android so `java.*` is available).
 *
 * The plan suggested Ktor, but its client artifacts aren't a guaranteed-visible transitive of the
 * Jellyfin SDK on the compile classpath; HttpURLConnection sidesteps that with no `expect`/`actual`.
 * It streams to a `.part` sibling and atomically renames on success — this *is* the download-then-swap
 * rule — and follows cross-scheme redirects manually (the Jellyfin universal endpoint 302-redirects to
 * the real container when it can direct-play).
 */
internal object HttpDownloader {

    private val log = logger("Downloads")

    data class Result(val contentType: String?, val bytes: Long)

    /**
     * Stream [url] into [dest], reporting progress. Writes `<dest>.part` then renames it onto [dest] on
     * success, so a partial/cancelled download never leaves a usable-looking file. Cooperatively
     * cancellable (checks the coroutine on each chunk).
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + ".part")
        val conn = openFollowingRedirects(url)
        try {
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            // Atomic swap: replace any prior copy with the freshly completed one.
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true); part.delete()
            }
            Result(contentType = conn.contentType, bytes = downloaded)
        } catch (t: Throwable) {
            log.error("download failed url=$url dest=${dest.name}", t)
            part.delete()
            throw t
        } finally {
            conn.disconnect()
        }
    }

    /** Probe an item's content type without downloading it (used to pick an extension up front if
     *  needed); returns null on any failure. */
    private fun openFollowingRedirects(url: String, maxHops: Int = 5): HttpURLConnection {
        var current = url
        repeat(maxHops) {
            val conn = (URI.create(current).toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 30_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    log.error("Redirect with no Location header from $current (HTTP $code)")
                    error("Redirect with no Location from $current")
                }
                // Resolve relative redirects against the current URL.
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                log.error("HTTP $code fetching $current")
                error("HTTP $code fetching $current")
            }
            return conn
        }
        log.error("Too many redirects fetching $url")
        error("Too many redirects fetching $url")
    }
}

/** Map an HTTP `Content-Type` to a file extension. The universal endpoint transcodes/redirects to a
 *  container we don't know up front, so the extension is resolved from the response, not ahead. */
internal fun contentTypeToExtension(contentType: String?): String = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/aac" -> "aac"
    "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
    "audio/flac", "audio/x-flac" -> "flac"
    "audio/ogg", "application/ogg" -> "ogg"
    "audio/opus" -> "opus"
    "audio/webm", "video/webm" -> "webm"
    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "bin"
}
