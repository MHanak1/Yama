package net.mhanak.yama.ui.theme

// Desktop Compose renders Haze blur through Skia's RenderEffect, available on every supported JVM
// target, so blur is always offered here.
actual fun supportsBlurEffects(): Boolean = true
