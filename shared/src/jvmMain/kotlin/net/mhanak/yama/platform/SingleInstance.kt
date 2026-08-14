package net.mhanak.yama.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.mhanak.yama.getAppDataDir
import net.mhanak.yama.util.logger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Cross-platform single-instance guard.
 *
 * Uses two pieces in the app data dir:
 *  - **`instance.lock`** held via an exclusive [FileLock] — the mutex. Holding it means "I am the
 *    primary instance." The OS drops it if the process dies, so a crash never wedges future starts.
 *  - **`instance.port`** — the loopback port of a tiny control [ServerSocket] the primary listens on.
 *    A second launch reads this, connects, and asks the primary to show itself, then exits.
 *
 * Both are per-user (in the user's data dir) and the socket binds an *ephemeral* port, so there's no
 * fixed-port collision with other apps.
 */
object SingleInstance {
    private val log = logger("SingleInstance")

    /**
     * Attempt to become the primary instance. Returns an [InstanceHandle] if this process is primary
     * (the caller should go on to build the UI), or null if another instance already owns the lock —
     * in which case that instance has been asked to show itself and this process should just exit.
     */
    fun tryAcquire(): InstanceHandle? {
        val dir = getAppDataDir()
        val lockFile = dir.resolve("instance.lock")
        val portFile = dir.resolve("instance.port")

        val channel = FileChannel.open(lockFile, CREATE, WRITE)
        val lock: FileLock? = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) {
            runCatching { channel.close() }
            signalPrimary(portFile)
            return null
        }
        return InstanceHandle(channel, lock, portFile).also { it.startListener() }
    }

    /** Secondary path: tell the already-running instance to surface. Best-effort with a short retry. */
    private fun signalPrimary(portFile: Path) {
        repeat(10) { attempt ->
            val ok = runCatching {
                val port = Files.readString(portFile).trim().toInt()
                Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                    sock.getOutputStream().apply { write('\n'.code); flush() }
                }
                true
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(100)
            if (attempt == 9) log.warn("Could not signal the primary instance to show")
        }
    }
}

/** Held by the primary instance for the process lifetime. [close] releases the lock and stops the listener. */
class InstanceHandle internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val portFile: Path,
) {
    private val log = logger("SingleInstance")
    private val _showRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 4)
    /** Emits whenever another launch asked us to show/focus the window. */
    val showRequests: SharedFlow<Unit> = _showRequests

    private var server: ServerSocket? = null

    internal fun startListener() {
        val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        server = s
        Files.writeString(portFile, s.localPort.toString())
        Thread({
            while (!s.isClosed) {
                runCatching {
                    s.accept().use { it.getInputStream().read() }  // one byte = "please show"
                    _showRequests.tryEmit(Unit)
                }.onFailure { if (!s.isClosed) log.warn("single-instance listener error", it) }
            }
        }, "yama-single-instance").apply { isDaemon = true }.start()
    }

    fun close() {
        runCatching { server?.close() }
        runCatching { Files.deleteIfExists(portFile) }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }
}
