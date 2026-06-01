package net.mhanak.yama

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.mhanak.yama.media.sources.JellyfinSource
import net.mhanak.yama.media.sources.MusicSource

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

class AppContainer {
    val jellyfinSource = JellyfinSource()

    var activeMusicSource: MusicSource by mutableStateOf(jellyfinSource)
}