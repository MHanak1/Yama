package net.mhanak.yama

import android.os.Build
import java.nio.file.Path

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getDeviceName(): String = Build.MODEL

// androidMain
actual fun getAppDataDir(): Path = MyApplication.appContext.filesDir.toPath()