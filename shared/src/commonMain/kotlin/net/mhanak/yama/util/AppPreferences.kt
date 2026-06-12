package net.mhanak.yama.util

import com.russhwolf.settings.Settings

// commonMain
object AppPreferences {
    private val settings: Settings = Settings()

    // M3 baseline primary — a sensible default seed when the system palette isn't used.
    private val defaultSeedColor: Int = 0xFF6750A4.toInt()

    var serverAddress: String
        get() = settings.getString("server_address", "")
        set(value) { settings.putString("server_address", value) }

    var authToken: String
        get() = settings.getString("auth_token", "")
        set(value) { settings.putString("auth_token", value) }

    var deviceId: String
        get() = settings.getString("device_id", "")
        set(value) { settings.putString("device_id", value) }

    var blurEnabled: Boolean
        get() = settings.getBoolean("blur_enabled", true)
        set(value) { settings.putBoolean("blur_enabled", value) }

    var uiOpacity: Float
        get() = if (blurEnabled) settings.getFloat("ui_opacity", 0.7f) else 1.0f
        set(value) { settings.putFloat("ui_opacity", value) }

    // Global UI scaling factor applied via LocalDensity (1.0 = system default).
    var uiScale: Float
        get() = settings.getFloat("ui_scale", 1.0f)
        set(value) { settings.putFloat("ui_scale", value) }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(settings.getInt("theme_mode", ThemeMode.System.ordinal)) { ThemeMode.System }
        set(value) { settings.putInt("theme_mode", value.ordinal) }

    // Use the OS dynamic palette (Android 12+ "Material You"). Defaults on; it's gated by
    // supportsDynamicColor() at the use site, so it's effectively off where the platform can't provide one.
    var useMaterialYou: Boolean
        get() = settings.getBoolean("use_material_you", true)
        set(value) { settings.putBoolean("use_material_you", value) }

    // Seed colour (packed ARGB) for the generated default scheme, used when Material You is off/unsupported.
    var seedColor: Int
        get() = settings.getInt("seed_color", defaultSeedColor)
        set(value) { settings.putInt("seed_color", value) }

    // How far album-derived colours spread through the UI (player only, + detail screens, or the whole
    // app). Defaults to Player — the original "tint enabled" behaviour.
    var albumTintMode: AlbumTintMode
        get() = AlbumTintMode.entries.getOrElse(settings.getInt("album_tint_mode", AlbumTintMode.AllUi.ordinal)) { AlbumTintMode.AllUi }
        set(value) { settings.putInt("album_tint_mode", value.ordinal) }

    // Whether other clients may remote-control ("Play On") this device. Off by default.
    var allowRemoteControl: Boolean
        get() = settings.getBoolean("allow_remote_control", true)
        set(value) { settings.putBoolean("allow_remote_control", value) }

    // Whether the in-app volume slider drives the device (media stream) volume rather than an in-app
    // gain. On by default; the engine falls back to in-app gain where device volume isn't controllable
    // (e.g. desktop). Android-specific in effect.
    var useDeviceVolume: Boolean
        get() = settings.getBoolean("use_device_volume", true)
        set(value) { settings.putBoolean("use_device_volume", value) }

    // Whether to hold the screen awake while the full player is open and something is playing. On by
    // default; takes effect on platforms that can keep the screen on (Android).
    var keepScreenOnWhilePlaying: Boolean
        get() = settings.getBoolean("keep_screen_on_while_playing", true)
        set(value) { settings.putBoolean("keep_screen_on_while_playing", value) }
}