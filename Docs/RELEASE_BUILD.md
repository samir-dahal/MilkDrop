# Building the release APK

The release build is fully optimized: the native projectM engine is compiled optimized and
stripped, and the app runs non-debuggable. It is signed with the local Android debug key, so it
installs directly on your own phone (not suitable for Play Store distribution).

## Build

Run from the project root (`E:\Repos\Personal\experimental\MilkDropApp`):

```powershell
$env:JAVA_HOME = "D:\AndroidStudio\jbr"   # skip if running from Android Studio's terminal
.\gradlew.bat assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`

## Install on the phone

With the phone connected over adb (USB or wireless):

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

Or copy the APK to the phone and open it from a file manager.

### Switching between debug and release builds

Both builds are signed with this PC's debug key, so `adb install -r` upgrades in place and keeps
your saved settings — no uninstall needed. Only if you build on another PC (different debug key)
will Android refuse with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; then uninstall first:

```powershell
adb uninstall com.milkdrop.visualizer
```

## What uninstalling removes

- **Kept:** `/storage/emulated/0/MilkDropApp/` (presets and textures). It lives in shared
  storage, not in the app's private folders, so Android leaves it alone on uninstall.
- **Removed:** the app's private data — saved toggle states (shuffle, auto, transition, FPS,
  quality, audio source), the last preset shown, and the cached preset list. Toggles reset to
  defaults, and the first launch afterwards rescans the presets folder (a few seconds, with a
  "Loading presets…" line). You'll also need to grant the permissions (microphone,
  notifications, All files access) again.

## Debug build (for development)

```powershell
.\gradlew.bat installDebug
```

Native code is built optimized in the debug build too (see `app/build.gradle.kts`), so rendering
performance matches release; only the Kotlin side runs slower because the app is debuggable.
