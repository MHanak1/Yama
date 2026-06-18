package net.mhanak.yama.util

import kotlinx.serialization.Serializable

@Serializable
enum class StreamingQuality(val label: String, val maxBitrateBps: Int?) {
    Original("Original", null),
    High("High (320 kbps)", 320_000),
    Medium("Medium (192 kbps)", 192_000),
    Low("Low (96 kbps)", 96_000),
}
