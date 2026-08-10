package net.mhanak.yama.ui.screens

/**
 * Which top-level screen the app opens on after login. Persisted globally in
 * [net.mhanak.yama.util.AppPreferences.launchDestination] and consumed by [MainScreen] to pick the
 * NavHost start destination. Defaults to [Home]; users who prefer to land in their library set
 * [Library] (which itself opens on its default Albums tab).
 */
enum class LaunchDestination(val label: String) {
    Home("Home"),
    Library("Library"),
}
