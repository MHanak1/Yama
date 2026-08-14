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

actual val defaultUseDeviceVolume: Boolean = false

// True if *any* tray backend works here: the modern StatusNotifierItem protocol (Wayland/KDE/GNOME
// via a host like Waybar) or AWT's X11/Windows/macOS tray. Computed once inside DesktopTray.
actual fun supportsSystemTray(): Boolean = net.mhanak.yama.platform.DesktopTray.isSupported

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