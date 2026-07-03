package net.mhanak.yama.ui.components.library

import net.mhanak.yama.media.sources.SourceType
import org.jetbrains.compose.resources.DrawableResource
import yama.shared.generated.resources.Res
import yama.shared.generated.resources.folder
import yama.shared.generated.resources.jellyfin_logo
import yama.shared.generated.resources.subsonic_logo

/**
 * Source → logo drawable mapping, shared between [SourceAvatar] (fallback layer) and [SourceIcon]
 * (login screen icon). Centralised here so that adding a new source type only requires one change.
 *
 * @param tinted Whether the drawable should be tinted with the current content colour. Vector
 *   icons (folder, subsonic_logo) should be tinted; full-colour PNGs (jellyfin_logo) should not.
 */
data class SourceLogoSpec(val drawable: DrawableResource, val tinted: Boolean)

fun sourceLogo(sourceType: SourceType): SourceLogoSpec = when (sourceType) {
    SourceType.Jellyfin -> SourceLogoSpec(Res.drawable.jellyfin_logo, tinted = false)
    SourceType.Subsonic -> SourceLogoSpec(Res.drawable.subsonic_logo, tinted = false)
    SourceType.Local    -> SourceLogoSpec(Res.drawable.folder,        tinted = true)
}
