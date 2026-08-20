![Yama Logo](https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/branding/banner.svg)
Yama is a Kotlin Multiplatform (KMP) music app.

## Goals

The goal of this app is to provide a unified experience across various platforms and music backends.

> [!NOTE]  
> The majority of the code in this project is AI-written. If you oppose to that, there are many great alternatives like [Finamp](https://github.com/finamp-app/finamp) (mobile), [Symfonium](https://www.symfonium.app/) (mobile, paid) or [Feishin](https://github.com/jeffvli/feishin) (desktop). 

## Major Features

![Yama Screenshots](https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/screenshots.png)


<details>
<summary>Downloads and seamless offline playback</summary>

<img alt="Downloads Screenshot" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/downloads.png" width = 300/>
Yama lets you download tracks for offline playback, and can handle a spotty connection fairly well (if the internet connection drops out, tracks unavaliable offline become grayed out)
You can also configure the app to automatically download recently played tracks, and keep a certain amout of them.
</details>

<details>
<summary>
Remote playback control
</summary>

<img alt="Remote Playback Screenshot" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/remote_playback.png" width = 300/>
Yama implements Jellyfin's "Play On" feature, allowing other jellyfin clients to control the app and vice-versa. 
That being said, the implementation in the official clients is kind of... bad, so controlling them won't work that well.
I am hoping I will be able to implement a solution which will work across all music sources, not just Jellyfin.
</details>

<details>
<summary>Line-by-line lyric support</summary>

<img alt="Lyrics Screenshot" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/lyrics.png" width = 300/>
</details>

<details>
<summary> (extra) Dynamic colour scheme </summary>

<img alt="Dynamic Colour Screenshot" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/colour_scheme.png" width = 300/>

Yama will change its UI colours to match the album art whenever possible. When the track is playing, the UI will match that, and when viewing albums in the library, their pages will tint to the album colours.

In addition to dynamic colours, the entire UI is a hybrid of Material Design and the frosted glass aesthetic. The latter can tuned down or turned off.
</details>

<details>
<summary> Listen scrobbling to ListenBrainz </summary>

<img alt="Scrobbling Screenshot" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/scrobbling.png" width = 300/>
</details>


<details>
<summary> UI which scales for (almost) any size of screen </summary>

<img alt="UI Scaling Screenshot" src="https://raw.githubusercontent.com/MHanak1/Yama/refs/heads/main/resources/screenshots/ui_scaling.png" width = 600/>

The main goal when designing this app was to make it fit as many screens as possible, providing a continuous experience accross all those platforms.

I do not guarantee it will work on a smartwatch, at least not yet.
</details>


## Supported backends

| Backend             | Support |
|---------------------|---------|
| Jellyfin            | ✅️      |
| Navidrome           | ✅️      |
| Local Files         | ✅️      |
| Music Assistant     | 🚧      |
| Plex                | ➖️      |
| Other Media Servers | ➖️      |
| Streaming Services  | ❌️\*    |

\*Most, if not all commercial streaming services disallow 3rd party apps, so even if it would be possible for the app to play audio from them, it would be against their TOS.

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

## License

Yama is free software licensed under the **GNU General Public License v3.0** (GPLv3). See [`LICENSE`](LICENSE) for the full text.

Copyright (C) 2026 Michał Hanak

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

The project is GPLv3 because it links [`vlcj`](https://github.com/caprica/vlcj) (GPLv3) for desktop audio playback. It also uses [`jellyfin-sdk-kotlin`](https://github.com/jellyfin/jellyfin-sdk-kotlin) (LGPLv3);
