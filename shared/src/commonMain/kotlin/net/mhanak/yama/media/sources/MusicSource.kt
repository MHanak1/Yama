package net.mhanak.yama.media.sources

enum class SourceType {
    Jellyfin,
    Subsonic,
    Local,
}
interface MusicSource {
    val type: SourceType
}