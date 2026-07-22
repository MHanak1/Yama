package net.mhanak.yama.util

import com.russhwolf.settings.Settings
import net.mhanak.yama.defaultUseDeviceVolume
import net.mhanak.yama.ui.theme.ThemeMode
import net.mhanak.yama.ui.theme.AlbumTintMode
import net.mhanak.yama.ui.player.PlayerLayoutMode

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

    // How the full player arranges artwork vs. info/controls: Auto (by the player's aspect ratio),
    // or forced Vertical/Horizontal. Defaults to Auto.
    var playerLayoutMode: PlayerLayoutMode
        get() = PlayerLayoutMode.entries.getOrElse(settings.getInt("player_layout_mode", PlayerLayoutMode.Auto.ordinal)) { PlayerLayoutMode.Auto }
        set(value) { settings.putInt("player_layout_mode", value.ordinal) }

    // Whether other clients may remote-control ("Play On") this device. Off by default.
    var allowRemoteControl: Boolean
        get() = settings.getBoolean("allow_remote_control", true)
        set(value) { settings.putBoolean("allow_remote_control", value) }

    // Whether the in-app volume slider drives the system (media stream) volume rather than an in-app
    // gain. Defaults true on Android, false on desktop (opt-in there); the engine falls back to
    // in-app gain where system volume isn't controllable (e.g. Windows, or Linux without pactl).
    var useDeviceVolume: Boolean
        get() = settings.getBoolean("use_device_volume", defaultUseDeviceVolume)
        set(value) { settings.putBoolean("use_device_volume", value) }

    // Whether to hold the screen awake while the full player is open and something is playing. On by
    // default; takes effect on platforms that can keep the screen on (Android).
    var keepScreenOnWhilePlaying: Boolean
        get() = settings.getBoolean("keep_screen_on_while_playing", true)
        set(value) { settings.putBoolean("keep_screen_on_while_playing", value) }

    // Force TV/controller navigation layout on non-television devices: nav rail, D-pad focus, and
    // full-player scale behave as if isTelevisionDevice() returned true. Off by default. Ignored
    // (redundant) when the device is already detected as a television.
    var forceTvMode: Boolean
        get() = settings.getBoolean("force_tv_mode", false)
        set(value) { settings.putBoolean("force_tv_mode", value) }

    // The SourceType the user last had active (by name), restored on launch so the app reopens on the
    // same source. Null until the user first switches source — callers fall back to their own default.
    var lastSourceType: String?
        get() = settings.getStringOrNull("last_source_type")
        set(value) {
            if (value == null) settings.remove("last_source_type")
            else settings.putString("last_source_type", value)
        }

    // Last "Play On" target chosen for a given source ([key] = a SourceType name), restored when that
    // source becomes active. Stored as an opaque string the playback layer encodes/decodes; absent (or
    // null) means local playback. Kept per source so each source resumes its own cast device.
    fun lastRemoteTarget(key: String): String? = settings.getStringOrNull("last_remote_target_$key")

    fun setLastRemoteTarget(key: String, encoded: String?) {
        if (encoded == null) settings.remove("last_remote_target_$key")
        else settings.putString("last_remote_target_$key", encoded)
    }

    // Library IDs the user has *deselected* for a given session ([key] = "serverUrl|userId"). Storing
    // the excluded set (rather than the included one) means the default — nothing stored — includes
    // every library, and libraries added on the server later are included automatically.
    fun excludedLibraries(key: String): Set<String> {
        val raw = settings.getStringOrNull("excluded_libraries_$key") ?: return emptySet()
        return if (raw.isEmpty()) emptySet() else raw.split('\n').toSet()
    }

    fun setExcludedLibraries(key: String, ids: Set<String>) {
        if (ids.isEmpty()) settings.remove("excluded_libraries_$key")
        else settings.putString("excluded_libraries_$key", ids.joinToString("\n"))
    }

    // Streaming quality for sources that support server-side transcoding (Jellyfin). Original means no
    // bitrate cap — direct-play when the container matches, else transcode at source quality.
    var streamingQuality: StreamingQuality
        get() = StreamingQuality.entries.getOrElse(settings.getInt("streaming_quality", StreamingQuality.Original.ordinal)) { StreamingQuality.Original }
        set(value) { settings.putInt("streaming_quality", value.ordinal) }

    // --- Downloads & offline (see DOWNLOADS_PLAN.md) ---

    // Default quality for downloads (independent of streaming quality): the quality a plain tap on the
    // download button fetches at. Long-pressing the button picks a different one for that download.
    var downloadQuality: StreamingQuality
        get() = StreamingQuality.entries.getOrElse(settings.getInt("download_quality", StreamingQuality.Original.ordinal)) { StreamingQuality.Original }
        set(value) { settings.putInt("download_quality", value.ordinal) }

    // Only run background downloads on an unmetered (Wi-Fi/Ethernet) network. Off by default; ignored
    // on platforms with no metered concept (desktop). Only applies to the background queue — an
    // explicit download with background downloads off runs immediately regardless.
    var downloadOverWifiOnly: Boolean
        get() = settings.getBoolean("download_wifi_only", false)
        set(value) { settings.putBoolean("download_wifi_only", value) }

    // Queue downloads on a single background queue (respecting Wi-Fi-only) rather than starting each
    // immediately. On by default.
    var backgroundDownloads: Boolean
        get() = settings.getBoolean("background_downloads", true)
        set(value) { settings.putBoolean("background_downloads", value) }

    // Play-capture: cache the track that's *currently playing* so replaying it doesn't re-fetch. On
    // desktop this background-re-fetches a not-already-cached current track; on Android the playback
    // engine's read-through cache captures the stream for free. Off by default — opt in to spend disk.
    var cacheRecentTracks: Boolean
        get() = settings.getBoolean("cache_recent_tracks", false)
        set(value) { settings.putBoolean("cache_recent_tracks", value) }

    // Prefetch (fetch-ahead): proactively pull the next few upcoming queue tracks into the cache so
    // playback stays smooth and the queue survives going offline. Independent of [cacheRecentTracks] —
    // this is the predictive producer; that one is the reactive (current-track) producer. On by default.
    var prefetchUpcoming: Boolean
        get() = settings.getBoolean("prefetch_upcoming", true)
        set(value) { settings.putBoolean("prefetch_upcoming", value) }

    // Size budget (MB) for the recent-tracks cache; cached (not pinned) rows are evicted oldest-first
    // past this. Pinned downloads never count against it.
    var cacheSizeBudgetMb: Int
        get() = settings.getInt("cache_size_budget_mb", 1024)
        set(value) { settings.putInt("cache_size_budget_mb", value) }

    // Record completed plays that happen offline and flush them to the backend on reconnect. On by
    // default; the scrobble outbox always backs online reporting regardless of this flag.
    var recordOfflinePlays: Boolean
        get() = settings.getBoolean("record_offline_plays", true)
        set(value) { settings.putBoolean("record_offline_plays", value) }

    // --- Scrobbling (native ListenBrainz; token lives in SecureStorage, not here) -------------------

    // Master switch for first-party ListenBrainz scrobbling. Off by default — turned on once the user
    // configures a token. When off, the per-server modes below are ignored entirely (and grayed in UI).
    var scrobblingEnabled: Boolean
        get() = settings.getBoolean("scrobbling_enabled", false)
        set(value) { settings.putBoolean("scrobbling_enabled", value) }

    // Per-source native-LB scrobbling toggle, keyed by the account's stable partition key ([key] =
    // SourceAccount.stableKey / OfflineCapable.downloadSourceKey, e.g. "jellyfin:<hash>" or "local").
    // On by default for every source; the user turns it Off only for a source that already scrobbles
    // to ListenBrainz server-side, to avoid a duplicate listen.
    fun scrobbleEnabled(key: String): Boolean {
        settings.getBooleanOrNull("scrobble_enabled_$key")?.let { return it }
        // Migrate the legacy per-source ScrobbleMode: only an explicit "Off" stays off; the old
        // "Always"/"OfflineOnly" (and no stored value) all mean On.
        return settings.getStringOrNull("scrobble_mode_$key") != "Off"
    }

    fun setScrobbleEnabled(key: String, enabled: Boolean) {
        settings.putBoolean("scrobble_enabled_$key", enabled)
    }

    // Hide local files with no embedded metadata (no readable title) from the library. On by default;
    // the index still stores them, so toggling this off brings them back without a rescan.
    var skipTracksWithoutMetadata: Boolean
        get() = settings.getBoolean("skip_tracks_without_metadata", true)
        set(value) { settings.putBoolean("skip_tracks_without_metadata", value) }

    // Watched folders for the local-files source. Null (key absent) means "not configured yet" so the
    // source can seed the platform default Music dir on first run; an explicitly empty set is stored
    // as a sentinel so a user who removed every folder isn't re-seeded.
    fun localFolders(): List<String>? {
        val raw = settings.getStringOrNull("local_folders") ?: return null
        return if (raw.isEmpty()) emptyList() else raw.split('\n')
    }

    fun setLocalFolders(folders: List<String>) {
        // Store empty as a single newline sentinel so getStringOrNull stays non-null ("configured, but
        // empty") rather than reverting to the default-seed path.
        settings.putString("local_folders", if (folders.isEmpty()) "" else folders.joinToString("\n"))
    }

    // Favourites for the local source, persisted per kind (the local index itself stays favourite-
    // agnostic). [kind] is a FavoritableKind name, e.g. "Track" / "Album".
    fun localFavorites(kind: String): Set<String> {
        val raw = settings.getStringOrNull("local_fav_$kind") ?: return emptySet()
        return if (raw.isEmpty()) emptySet() else raw.split('\n').toSet()
    }

    fun setLocalFavorite(kind: String, id: String, favorite: Boolean) {
        val current = localFavorites(kind).toMutableSet()
        if (favorite) current.add(id) else current.remove(id)
        if (current.isEmpty()) settings.remove("local_fav_$kind")
        else settings.putString("local_fav_$kind", current.joinToString("\n"))
    }

    // Last-played queue per source — track IDs in order plus the ID of the current item.
    fun savedQueueTrackIds(sourceType: String): List<String> {
        val raw = settings.getStringOrNull("saved_queue_ids_$sourceType") ?: return emptyList()
        return if (raw.isEmpty()) emptyList() else raw.split('\n')
    }

    fun setSavedQueueTrackIds(sourceType: String, ids: List<String>) {
        if (ids.isEmpty()) settings.remove("saved_queue_ids_$sourceType")
        else settings.putString("saved_queue_ids_$sourceType", ids.joinToString("\n"))
    }

    fun savedQueueCurrentId(sourceType: String): String? =
        settings.getStringOrNull("saved_queue_current_$sourceType")

    fun setSavedQueueCurrentId(sourceType: String, id: String?) {
        if (id == null) settings.remove("saved_queue_current_$sourceType")
        else settings.putString("saved_queue_current_$sourceType", id)
    }
}