package net.mhanak.yama.media.download

/**
 * Whether the device's active network is currently **unmetered** (Wi-Fi / Ethernet rather than
 * cellular). Used by [DownloadScheduler] to gate "Download over Wi-Fi only". Platforms with no metered
 * concept (desktop) report `true` so the gate is a no-op there.
 */
expect fun isNetworkUnmetered(): Boolean
