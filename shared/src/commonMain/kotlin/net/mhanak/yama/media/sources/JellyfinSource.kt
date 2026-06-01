package net.mhanak.yama.media.sources

import net.mhanak.yama.getDeviceName
import net.mhanak.yama.util.AppPreferences
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.security.MessageDigest
import java.util.UUID

class JellyfinSource : MusicSource {
    override val type: SourceType = SourceType.Jellyfin
    val jellyfin = createJellyfin {
        clientInfo = ClientInfo(name = "Yama - Yet another music app", version = "0.0.1")
        deviceInfo = DeviceInfo(id = getDeviceId(), name = getDeviceName())
    }
    var api: ApiClient? = null

    suspend fun connect(baseUrl: String) {
        val api = jellyfin.createApi(baseUrl)

        if (api.systemApi.postPingSystem().status == 200) {
            this.api = api
        } else {
            throw Error("Could not connect to Jellyfin.")
        }
    }
}

expect fun createJellyfinInstance(clientInfo: ClientInfo, deviceInfo: DeviceInfo) : Jellyfin

fun getDeviceId(): String {
    if (AppPreferences.deviceId.isEmpty()) {
        AppPreferences.deviceId = UUID.randomUUID().toString()
    }
    return AppPreferences.deviceId
}

fun getDeviceIdForUser(username: String): String {
    val combined = getDeviceId() + username
    return MessageDigest.getInstance("SHA-256")
        .digest(combined.toByteArray())
        .joinToString("") { "%02x".format(it) }
}