package net.mhanak.yama.media.sources

import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

actual fun createJellyfinInstance(clientInfo: ClientInfo, deviceInfo: DeviceInfo): Jellyfin {
    return createJellyfin{
        clientInfo
        deviceInfo
    }
}
