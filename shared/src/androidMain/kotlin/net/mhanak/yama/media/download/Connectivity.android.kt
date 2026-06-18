package net.mhanak.yama.media.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import net.mhanak.yama.MyApplication

/**
 * True when the active network is unmetered. Reaches the app context the same way the other Android
 * actuals do ([net.mhanak.yama.MyApplication.appContext]). Falls back to `true` if connectivity can't
 * be queried, so a permission/lookup hiccup never silently blocks all downloads.
 */
actual fun isNetworkUnmetered(): Boolean {
    val cm = MyApplication.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    return runCatching {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(true)
}
