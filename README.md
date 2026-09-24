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

## Setting up presets

On first launch the app creates two folders on external storage:

- `/storage/emulated/0/MilkDropApp/presets/` — drop preset pack folders here (e.g. "Cream of
  the Crop", "Milkdrop2", "Classic projectM"). Subfolders are scanned recursively.
- `/storage/emulated/0/MilkDropApp/textures/` — drop the Milkdrop base texture pack here, since
  many presets reference shared textures by name.

Grant the "All files access" permission when prompted so the app can read these folders.

## Controls

Tap anywhere to show/hide the control bar: Play/Pause (auto-advance), Previous, Next, Random,
and a toggle between internal-playback audio capture and the microphone. Internal capture asks
for a one-time screen-capture-style consent dialog each time it starts — that's required by
Android's `AudioPlaybackCapture` API. Some apps (e.g. Spotify) block third-party playback
capture by design; switch to the microphone source for those.

## Licensing note

`libprojectM` and `projectm-playlist` are LGPL-2.1; this app links them as dynamically-loaded
shared libraries (`.so`), which keeps LGPL compliance straightforward. `projectm-eval` (vendored
inside the projectM submodule) is MIT-licensed.
