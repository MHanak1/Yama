package net.mhanak.yama.session

import kotlinx.serialization.Serializable

@Serializable
data class JellyfinSession(
    val id: String,
    val serverUrl: String,
    val serverName: String?,
    val userId: String?,
    val userName: String?,
    val accessToken: String,
    val sessionDeviceId: String,
)
