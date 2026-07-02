package net.mhanak.yama.util

import android.util.Log

actual fun logger(name: String): Logger = object : Logger {
    override fun debug(message: String, throwable: Throwable?) {
        if (throwable != null) Log.d(name, message, throwable) else Log.d(name, message)
    }
    override fun info(message: String, throwable: Throwable?) {
        if (throwable != null) Log.i(name, message, throwable) else Log.i(name, message)
    }
    override fun warn(message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(name, message, throwable) else Log.w(name, message)
    }
    override fun error(message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(name, message, throwable) else Log.e(name, message)
    }
}
