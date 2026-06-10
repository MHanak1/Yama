package net.mhanak.yama.util

/**
 * How far album-derived colours spread through the UI. Ordered from least to most pervasive so the
 * `tints*` helpers can be plain ordinal comparisons (each level includes the ones before it).
 */
enum class AlbumTintMode {
    /** No album-based tinting anywhere — every surface uses the default theme. */
    Never,

    /** Only the full player recolours to the playing track (the original on/off behaviour). */
    Player,

    /** Player plus the album/artist/genre/playlist detail screens recolour to their own item. */
    PlayerAndLibrary,

    /** The whole app recolours to the currently playing album, falling back to default when idle. */
    AllUi;

    /** Whether the full player recolours to its track. */
    val tintsPlayer: Boolean get() = this >= Player

    /** Whether the detail screens recolour to their item. */
    val tintsDetails: Boolean get() = this >= PlayerAndLibrary

    /** Whether the entire app recolours to the currently playing album. */
    val tintsEverything: Boolean get() = this == AllUi
}
