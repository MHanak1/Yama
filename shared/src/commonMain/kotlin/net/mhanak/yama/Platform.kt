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