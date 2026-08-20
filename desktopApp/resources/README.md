# App resources (bundled into the packaged desktop app)

Files here are copied into the jpackage app image and exposed at runtime via the
`compose.application.resources.dir` system property.

Layout:
- `common/`         — included on every platform.
- `windows-x64/`    — Windows-only. The release CI workflow populates `windows-x64/vlc/`
                      with `libvlc.dll`, `libvlccore.dll` and `plugins/` before building the MSI.

Nothing VLC-related is committed to git — the DLLs are downloaded fresh in CI (see
`.github/workflows/release.yml`, `VLC_VERSION`). See RELEASING.md.
