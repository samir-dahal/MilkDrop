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

Favourite and hidden presets are kept next to them, in `MilkDropApp/favourites.txt` and
`MilkDropApp/hidden.txt`: one preset per line, relative to `MilkDropApp/presets`. They survive
reinstalling the app, can be copied to another phone with the presets, and can be edited by hand
(lines starting with `#` are ignored).

## Controls

Tap the visuals to show the controls; tap again to hide them. They stay up until you tap again.
The preset's name shows at the top while they're up, and for a couple of seconds on each change.

- **Prev / Next** — step through presets. Prev goes back through the presets you actually saw,
  even with shuffle on.
- **Shuffle** — random order instead of folder order.
- **Favourite** — mark the preset that's showing as a favourite.
- **List** — browse and search all presets; tap one to jump to it. Each row has a favourite and a
  hide button, and **Favourites** shows only favourites. Hidden presets stay in the list, dimmed,
  but Next, Shuffle and Auto-advance skip them. Back closes the list.
- **Settings** — see below.
- **⏮ ⏯ ⏭** — previous track, play/pause and next track in whatever audio app is playing.

The app follows the phone's rotation; in landscape the controls are a single row.

### Settings

- **Auto-advance** — switch preset on a timer; when off, stays on the current preset.
- **Change on big beats** — also switch on a sudden jump in loudness.
- **Time per preset** — 10, 15, 30 or 60 seconds.
- **Transition** — Smooth blends into the next preset over a second; Instant cuts straight to it.
- **Frame rate** — 30, 45, 60 or Max. MilkDrop's motion advances per frame, so presets move
  faster at higher frame rates.
- **Quality** — render resolution (High / Medium / Low). Low also uses a coarser warp mesh.
- **Audio source**:
  - *Mic* — the microphone; works with any player and picks up the room.
  - *Phone audio* — what the phone plays, in full detail. Android asks for screen-capture
    consent each time it starts (required by the `AudioPlaybackCapture` API), and some apps
    (e.g. Spotify) block capture by design.
  - *No prompt* — what the phone plays, without the consent dialog, through the `Visualizer`
    API: coarser, and apps that block capture come through silent.

All settings, and the preset that was showing, are remembered across launches. The preset list
is cached, so the app resumes within a moment of opening; it rescans the presets folder in the
background and picks up added or removed presets automatically.

## Licensing note

`libprojectM` and `projectm-playlist` are LGPL-2.1; this app links them as dynamically-loaded
shared libraries (`.so`), which keeps LGPL compliance straightforward. `projectm-eval` (vendored
inside the projectM submodule) is MIT-licensed.
