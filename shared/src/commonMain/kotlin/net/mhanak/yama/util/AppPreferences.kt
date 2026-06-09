package net.mhanak.yama.util

import com.russhwolf.settings.Settings

// commonMain
object AppPreferences {
    private val settings: Settings = Settings()

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
        get() = settings.getFloat("ui_opacity", 0.7f)
        set(value) { settings.putFloat("ui_opacity", value) }

    var themeMode: ThemeMode
        get() = ThemeMode.entries.getOrElse(settings.getInt("theme_mode", ThemeMode.System.ordinal)) { ThemeMode.System }
        set(value) { settings.putInt("theme_mode", value.ordinal) }

    // Whether other clients may remote-control ("Play On") this device. Off by default.
    var allowRemoteControl: Boolean
        get() = settings.getBoolean("allow_remote_control", true)
        set(value) { settings.putBoolean("allow_remote_control", value) }
}