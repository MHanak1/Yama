<p align="center">
    <img alt="MCManager" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/banner.svg"/>
</p>
Yama is a Kotlin Multiplatform (KMP) music app.

## Goals

The goal of this app is to provide a unified experience across various platforms and music backends.

## Major Features

<p align="center">
    <img alt="MCManager" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots.png"/>
</p>

- Downloads and seamless offline playback
- Remote playback control\*
- Line-by-line lyric support
- Dynamic colour scheme based on system theme, played tracks and viewed albums
- Listen scrobbling to ListenBrainz
- UI which scales for (almost) any size of screen


## Supported backends

| Backend             | Support |
|---------------------|---------|
| Jellyfin            | ✅️      |
| Navidrome           | ✅️      |
| Local Files         | ✅️\*    |
| Music Assistant     | 🚧      |
| Plex                | ➖️      |
| Other Media Servers | ➖️      |
| Streaming Services  | ❌️\*\*  |

\*Local files support is still sub-par, and will require more work

\*\*Most, if not all commercial streaming services disallow 3rd party apps, so even if it would be possible for the app to play audio from them, it would be against their TOS.

- ✅️ - Supported
- 🚧 - In progress / Planned
- ➖️ - Not planned, but possible to implement
- ❌️ - Not planned.

## Supported Platforms

| Platform   | Support |
|------------|---------|
| Android    | ✅️      |
| iOS        | ❌️\*    |
| Windows    | ✅️      |
| Linux      | ✅️      |
| MacOS      | ❌️\*    |
| Android TV | ✅️\*\*  |
| Apple TV   | ❌️\*    |

- ✅️ - Supported
- ❌️ - Not planned.

\*I don't own any Apple devices, so it is impossible for me to compile the app any Apple devices. Also, some libraries (like `jellyfin-sdk`) do not support Apple targets.

\*\* TV focus handling still has some bugs to work out.