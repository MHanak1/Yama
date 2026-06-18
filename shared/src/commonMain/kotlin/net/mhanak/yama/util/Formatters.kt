package net.mhanak.yama.util

/**
 * Human-readable file size (e.g. "0 B", "812 KB", "4.3 GB"). Uses binary units (1024) and rounds to one
 * decimal by hand — [String.format] is JVM-only and this is commonMain. Shared by the downloads UI.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    // One decimal, dropping a trailing ".0".
    val tenths = (value * 10).toLong()
    val whole = tenths / 10
    val frac = tenths % 10
    val number = if (frac == 0L) "$whole" else "$whole.$frac"
    return "$number ${units[unit]}"
}
