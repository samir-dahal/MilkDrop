## Install

1. Download **{{APK}}** from the assets below and open it on the phone. Allow installing from your browser or file manager when Android asks. Or use adb:

       adb install -r {{APK}}

2. Open MilkDrop and allow microphone and preset-folder access when requested.
   Android 5 grants permissions at installation; Android 6–10 use runtime permissions,
   and Android 11+ uses **All files access** for the shared preset folder.

Needs Android 5.0 or newer. Local visuals require OpenGL ES 3.0; devices without
it can use Live Receiver with a compatible AVC decoder.

> The APK is signed with a development key, not a Play Store key, and every release is built on a fresh CI runner with its own key. Android refuses to update over a build signed with a different key, so uninstall the old app first. Your `MilkDropApp` folder (presets, textures, favourites) is kept; only the app's settings reset.

## Set up presets and textures

The app has no presets of its own. If you don't have a preset pack yet:

1. Download **Presets_Textures.zip** from the [first release](https://github.com/{{REPO}}/releases/download/v0.0.1/Presets_Textures.zip). It holds two folders: `presets` and `textures`.
2. Open your file manager and go to internal storage (`/storage/emulated/0`).
3. Create a folder named **MilkDropApp** and open it.
4. Put **Presets_Textures.zip** in this folder and extract it. If your file manager can't extract ZIP files, use an app like [ZArchiver](https://play.google.com/store/apps/details?id=ru.zdevs.zarchiver&hl=en).

   > ⚠️ **Important:** Select **"Extract here"**, not **"Extract files"**. The wrong option breaks the folder structure.
   > Add an empty `.nomedia` file inside the **MilkDropApp** folder so the texture images don't show up in your gallery.

## Verification

Make sure your folder structure matches the screenshot below:

<img width="433" height="520" alt="Folder structure" src="https://github.com/user-attachments/assets/a97b068b-cb34-4b4a-9f33-f7a6a1eb36d3" />

## Running the app

1. Open MilkDrop and allow the permissions asked.
2. Tap the screen to show or hide the controls.
3. Tap the **List** button and confirm that the downloaded presets appear.
4. Enjoy the visuals!

<img width="420" height="856" alt="screengif" src="https://github.com/user-attachments/assets/6fbfc5df-1552-4568-988b-6703da6a8091" />

More in the [README](https://github.com/{{REPO}}#readme).
