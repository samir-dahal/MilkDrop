# Building the release APK

The release APK is non-debuggable and builds native code with `CMAKE_BUILD_TYPE=Release`.
R8 minification is currently disabled. It uses the local Android debug signing key for
sideloading; a production distribution key is not configured.

## Requirements

- JDK 17, matching CI. Select it as Android Studio's Gradle JDK or set `JAVA_HOME`.
  Opening Android Studio's terminal does not necessarily select that JDK.
- Android SDK platform 35, NDK `27.0.12077973` and CMake `3.22.1`.
- Git and initialized recursive submodules. The first build needs network access
  to download dependencies and any missing SDK tools.

Use the repository's Gradle wrapper. Set `sdk.dir` in the untracked `local.properties`
file to your SDK location, using forward slashes on Windows, for example:

```properties
sdk.dir=C:/Users/YOUR_USER/AppData/Local/Android/Sdk
```

The unified APK includes `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`. Devices need
Android 9 (API 28) or newer and working OpenGL ES 3.0 graphics. Android 9 retains
mono Internal capture; optional stereo Internal capture requires Android 10+ and
system consent. See [audio capture details](SHADER_INPUTS.md).

## Build

Run from the repository root in PowerShell:

```powershell
# If needed, replace this example with your installed JDK 17 directory.
$env:JAVA_HOME = 'C:/path/to/jdk-17'
git submodule update --init --recursive
.\gradlew.bat --version
.\gradlew.bat :app:assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`

On Linux/macOS, use `./gradlew` instead of `.\gradlew.bat`.

Local submodule initialization uses the projectM commit recorded in the repository.
To deliberately update to upstream `master` before building:

```shell
git submodule update --init --remote native/projectm
git -C native/projectm submodule update --init --recursive
git -C native/projectm log -1 --format="projectM commit: %H %s"
```

Compatibility patches apply to build-directory copies of projectM. Upstream changes
can invalidate a patch and stop CMake configuration. Review the affected patch rather
than bypassing its source checks. Engine information in Settings identifies the
projectM version and commit actually compiled into the APK.

### Optional custom Google Cast receiver

By default, Google Cast uses Google's Default Media Receiver. To select a registered
MilkDrop web receiver, build with its eight-digit hexadecimal application ID:

```powershell
.\gradlew.bat :app:assembleRelease -PmilkdropCastReceiverAppId=YOUR_APP_ID
```

Replace `YOUR_APP_ID` with the registered ID. Alternatively set the environment variable
`MILKDROP_CAST_RECEIVER_APP_ID`; the Gradle property takes precedence. An empty ID uses
the default receiver; an invalid non-empty ID fails the build. See [receiver setup](CAST_RECEIVER.md)
for hosting and registration. This setting does not affect Miracast or the direct
Android MilkDrop Live receiver.

## GitHub Actions

The **Release** workflow (`.github/workflows/release.yml`) runs only when a version tag such
as `v2.1.0` (or `v2.1.0-rc.1` for a pre-release) is pushed. It does not run on ordinary pushes
or pull requests. It uses Java 17 and updates projectM to the latest upstream `master`,
logging the commit. A release can therefore use a newer engine than a local build of the
recorded revision; the commit is also written at the end of the release notes.

Set the repository Actions variable `MILKDROP_CAST_RECEIVER_APP_ID` to configure a custom
receiver. The workflow also checks the receiver JavaScript syntax.

Fresh CI runners use their own debug signing key, which may differ from your local key
or earlier releases. Do not rely on a release APK upgrading an existing installation in place.

### Publishing a release

1. In `app/build.gradle.kts`, raise `versionName` (for example `2.1.0`) and `versionCode`.
2. In `CHANGELOG.md`, rename the entries under `## [Unreleased]` to
   `## [2.1.0] - YYYY-MM-DD` and leave an empty `## [Unreleased]` above it.
3. Commit, then tag and push the tag:

   ```shell
   git tag v2.1.0
   git push origin main v2.1.0
   ```

The workflow stops within seconds, before building, if the tag is not a version, if it differs
from `versionName`, or if `CHANGELOG.md` has no section for it. Otherwise it builds the APK and
creates the release **MilkDrop 2.1.0** with:

- `MilkDrop-2.1.0.apk` attached to the release.
- The changelog section as the release notes, followed by the install and preset setup
  instructions from `.github/release-notes/install.md`. Edit that file to change the
  instructions for every later release.

If the workflow fails, fix the cause, delete the tag (`git tag -d v2.1.0` and
`git push origin :refs/tags/v2.1.0`), and tag again.

## Install on the phone

With the phone connected over adb (USB or wireless):

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

Or copy the APK to the phone and open it from a file manager.

### Switching between debug and release builds

Both variants share the application ID and, under the same local signing configuration,
the debug key. A matching key allows an in-place update that keeps settings. A different
key produces `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Prefer rebuilding with the original
key. If unavailable, uninstalling allows installation but removes private app data:

```powershell
adb uninstall com.milkdrop.visualizer
```

## What uninstalling removes

- **Kept:** `/storage/emulated/0/MilkDropApp/` (presets and textures). It lives in shared
  storage, not in the app's private folders. Its `favourites.txt` and `hidden.txt` also remain.
- **Removed:** private preferences, including audio, timing, quality, mesh and gesture
  choices, the last preset path, and the cached preset list.

Grant required permissions again after reinstalling. Installation alone does not create
the shared folders: on launch, after storage access is granted, MilkDrop creates missing
`presets/` and `textures/` folders without clearing existing contents. Denying access
defers creation and scanning. Grant access and return to the app to reload the library.
Presets and textures must be supplied separately.

## Debug build (for development)

```powershell
.\gradlew.bat :app:installDebug
```

Native code is optimized in both variants, but debug remains debuggable. Debugger attachment,
logging and build differences can affect performance; identical FPS is not guaranteed.

## Troubleshooting

Check `gradlew --version` for the JVM Gradle actually uses, and verify the SDK path and
exact NDK/CMake versions if configuration fails. Include the app commit, projectM commit
and full build error when reporting a failure. For device crashes, include the model,
Android version and Logcat; see [startup diagnostics](../README.md#troubleshooting-startup).

A successful build alone does not verify rendering parity, audio latency or compatibility
with a particular casting receiver.
