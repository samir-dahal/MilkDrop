# Changelog

All notable changes to this app. Versions follow [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-10-03

### Added

- New controls: Prev, Next, Shuffle, Favourite, List and Settings as icons with labels, plus
  media keys (previous track, play/pause, next track) for whatever audio app is playing.
- Settings sheet with every option visible at once: Auto-advance, Change on big beats, Time per
  preset (10/15/30/60 s), Transition, Frame rate, Quality and Audio source.
- Landscape: the app follows the phone's rotation; the controls become a single row.
- Preset name at the top while the controls are up, and for a couple of seconds on each change.
- Favourite and hidden presets. Favourite from the control bar or the List; the List can show
  favourites only. Hidden presets are skipped by Next, Shuffle and Auto-advance and stay in the
  List, dimmed. Both are kept in `MilkDropApp/favourites.txt` and `MilkDropApp/hidden.txt`.
- "No prompt" audio source: phone audio without Android's screen-capture consent dialog, at
  lower detail. Audio source is now Mic, Phone audio or No prompt.
- MilkDrop 3 presets:
  - 16 custom shapes and waves per preset (was 4).
  - `get_fft()` / `get_fft_hz()` in preset shaders, used by MilkDrop 3's equalizers.
  - Texture samplers declared by the texture's plain name.

### Changed

- The screen stays on while the visualizer is showing.
- Audio reaches the visuals in ~10 ms steps instead of ~80 ms, so they react sooner.
- The microphone uses the unprocessed input (no voice auto-gain or noise suppression) on phones
  that support it.
- Presets get the real frame rate. It was always reported as 35, so presets that scale motion
  by it ran too fast at higher frame rates.

### Fixed

- Many MilkDrop 3 presets whose shaders failed to load now work: failures among the 390 MilkDrop
  3 presets went from 60 to 9. The fixes are in the shader translation (`tanh`/`sinh`/`cosh`,
  macro handling, declaration scoping, `sincos`, integer multiply, variables named like GLSL
  built-ins) and apply to all presets.
- Shader failures are logged with their cause (logcat tag `MilkDropShader`).

## [1.0.0]

First build: projectM 4.1.7 visualizer with internal-audio and microphone capture, preset list
with search, and tuned frame pacing for the Galaxy F15.

[1.1.0]: https://github.com/samir-dahal/MilkDrop/compare/5c195a4...v1.1.0
