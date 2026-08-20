# contrib/

Auxiliary helpers that ship alongside Yama but are **not part of the app or its
CI build**. They are provided **as-is**, are not covered by the same support or
compatibility guarantees as the app itself, and may lag behind the code. Use
them as a starting point and adapt to your environment.

Nothing in here is invoked by `.github/workflows/release.yml` — the release
pipeline is self-contained. These are the manual equivalents you can run
yourself.

## Contents

| Path | What it is |
|------|------------|
| `windows/build-msi.bat` | One-shot local Windows MSI build. Reproduces the CI `windows` job on a dev machine / VM: self-elevates, installs deps via Chocolatey, stages libvlc, compiles the SMTC shim, runs `packageMsi`. Keep `VLC_VERSION` in sync with `release.yml`. |
| `aur/PKGBUILD` | Arch Linux packaging template. Pulls the prebuilt tarball from the GitHub Release (does not compile from source). |
| `aur/yama.desktop` | Desktop entry installed by the AUR package. |

See `RELEASING.exclude.md` for how these fit into the release process.

> Maintainer tooling that regenerates committed assets (e.g. app icons) lives in
> `../tools/`, not here — that produces repo state, whereas `contrib/` is for
> things a third party runs on their own machine.
