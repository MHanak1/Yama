package net.mhanak.yama.media.sources

import android.content.Context
import android.health.connect.datatypes.Device
import android.provider.Settings
import kotlinx.coroutines.MainScope
import net.mhanak.yama.MyApplication
import net.mhanak.yama.getPlatform
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

actual fun createJellyfinInstance(clientInfo: ClientInfo, deviceInfo: DeviceInfo): Jellyfin {
    return createJellyfin {
        this.clientInfo = clientInfo
        this.deviceInfo = deviceInfo
        context = MyApplication.appContext
    }
}