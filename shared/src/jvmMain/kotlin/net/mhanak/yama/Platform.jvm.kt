package net.mhanak.yama

import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun isTelevisionDevice(): Boolean = false

// jvmMain
actual fun getDeviceName(): String =
    InetAddress.getLocalHost().hostName ?: System.getProperty("os.name") ?: "Desktop"

// jvmMain
actual fun getAppDataDir(): Path {
    val appName = "yama"
    return when {
        // Windows
        System.getProperty("os.name").startsWith("Windows") ->
            Path(System.getenv("LOCALAPPDATA"), appName)
        // macOS
        System.getProperty("os.name") == "Mac OS X" ->
            Path(System.getProperty("user.home"), "Library", "Application Support", appName)
        // Linux and everything else — respect XDG
        else ->
            Path(
                System.getenv("XDG_DATA_HOME")
                    ?: "${System.getProperty("user.home")}/.local/share",
                appName
            )
    }.also { it.createDirectories() }
}