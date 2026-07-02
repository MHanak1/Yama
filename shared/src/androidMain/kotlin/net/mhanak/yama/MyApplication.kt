package net.mhanak.yama

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import java.util.concurrent.Executors

class MyApplication : Application(), Configuration.Provider {
    override fun onCreate() {
        // Must be set before SLF4J binds (first getLogger() call) — slf4j-simple reads these
        // system properties once at initialization and never again.
        System.setProperty("org.slf4j.simpleLogger.log.org.jellyfin.sdk.api.okhttp", "WARN")
        super.onCreate()
        appContext = this
    }

    // Cap how many downloads run at once: WorkManager runs each
    // [net.mhanak.yama.media.download.DownloadWorker] on this executor, and each blocks its thread for
    // the whole download, so a fixed pool of [MAX_CONCURRENT_DOWNLOADS] threads bounds concurrency
    // (surplus downloads queue). Requires the default WorkManager initializer to be removed in the
    // manifest (on-demand init via this provider).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS))
            .build()

    companion object {
        lateinit var appContext: Context
        const val MAX_CONCURRENT_DOWNLOADS = 3
    }
}