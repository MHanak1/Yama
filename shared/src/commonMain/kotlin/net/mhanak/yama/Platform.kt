package net.mhanak.yama

import java.nio.file.Path

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun getDeviceName(): String

// commonMain
expect fun getAppDataDir(): Path

expect fun isTelevisionDevice(): Boolean

/** Default for the "Use device volume" preference. True on Android; false on desktop (opt-in). */
expect val defaultUseDeviceVolume: Boolean

/** Whether this platform can hide the app to a system tray. False on Android; on desktop it reflects
 *  whether the running environment actually provides a working tray (some Wayland sessions don't). */
expect fun supportsSystemTray(): Boolean