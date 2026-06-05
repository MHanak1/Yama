package net.mhanak.yama

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.nio.file.Path

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getDeviceName(): String = Build.MODEL

// androidMain
actual fun getAppDataDir(): Path = MyApplication.appContext.filesDir.toPath()

actual fun isTelevisionDevice(): Boolean {
    val uiModeManager = MyApplication.appContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}