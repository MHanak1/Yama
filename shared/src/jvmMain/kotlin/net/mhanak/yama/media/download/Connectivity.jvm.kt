package net.mhanak.yama.media.download

/** Desktop has no metered-network concept, so the Wi-Fi-only gate is always satisfied. */
actual fun isNetworkUnmetered(): Boolean = true
