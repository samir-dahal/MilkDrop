# MilkDrop Visualizer (personal build)

A personal, sideloaded Android app that renders MilkDrop/projectM-style visuals reacting to
whatever audio is playing on the phone (YouTube, browser audio, games), using presets and
textures loaded from external storage. Not intended for Play Store distribution.

## Building

Requires Android SDK with NDK `27.0.12077973` and CMake `3.22.1` installed, and a JDK 17+.
`local.properties` must point `sdk.dir` at your SDK.

```bash
git submodule update --init --recursive
./gradlew installDebug
```

The native build compiles `libprojectM` and `libprojectM_playlist` from source (vendored as a
git submodule at `native/projectm`, pinned to a released 4.1.x tag) rather than using a
prebuilt binary, so presets/API stay in sync with that exact version.

For the optimized, installable release APK, see [Docs/RELEASE_BUILD.md](Docs/RELEASE_BUILD.md).

## Setting up presets

On first launch the app creates two folders on external storage:

- `/storage/emulated/0/MilkDropApp/presets/` — drop preset pack folders here (e.g. "Cream of
  the Crop", "Milkdrop2", "Classic projectM"). Subfolders are scanned recursively.
- `/storage/emulated/0/MilkDropApp/textures/` — drop the Milkdrop base texture pack here, since
  many presets reference shared textures by name.

Grant the "All files access" permission when prompted so the app can read these folders.

## Controls

Tap the visuals to show the controls; tap again to hide them. They stay up until you tap again.

- **Prev / Next** — step through presets. Prev goes back through the presets you actually saw,
  even with shuffle on.
- **Shuffle** — random order instead of folder order.
- **List** — browse and search all presets; tap one to jump to it. Back closes the list.
- **Auto** — when on, switches preset every ~15 seconds; when off, stays on the current preset.
- **Media** — play/pause whatever audio app is playing.
- **Source** — switch between internal playback capture and the microphone. Internal capture
  asks for a screen-capture-style consent dialog each time it starts — that's required by
  Android's `AudioPlaybackCapture` API. Some apps (e.g. Spotify) block third-party playback
  capture by design; switch to the microphone for those.
- **FPS** — frame rate cap (30 / 45 / 60 / uncapped).
- **Quality** — render resolution (High / Medium / Low). Low also uses a coarser warp mesh.
- **Transition** — Smooth blends into the next preset over a second; Instant cuts straight to it.

All toggles, and the preset that was showing, are remembered across launches. The preset list
is cached, so the app resumes within a moment of opening; it rescans the presets folder in the
background and picks up added or removed presets automatically.

## Licensing note

`libprojectM` and `projectm-playlist` are LGPL-2.1; this app links them as dynamically-loaded
shared libraries (`.so`), which keeps LGPL compliance straightforward. `projectm-eval` (vendored
inside the projectM submodule) is MIT-licensed.
