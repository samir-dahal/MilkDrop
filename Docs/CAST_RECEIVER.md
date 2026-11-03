# MilkDrop Live Google Cast receiver

The custom receiver in `cast-receiver/` opens automatically when you tap a Google Cast
device in MilkDrop's Cast chooser. No second Android app is installed on the TV. The
phone still renders the presets and sends only the finished visualizer video.

This is one of three connection paths: the custom Google Cast web receiver described
here, the default Google Cast player used when no custom app ID is configured, and a
direct MilkDrop Live receiver running the Android app on a TV. Android's Wireless
Display / Miracast route is separate. See [casting controls](../README.md#casting-visuals)
for choosing and stopping each connection type.

## What changes

Instead of a three-second HLS playlist and Google's default video player, the phone
sends a continuous, video-only fragmented MP4 response. Each encoded frame gets its
own fragment. The receiver appends frames with Media Source Extensions, starts with
a short buffer, and catches up to recent frames after a stall. The server retains at
most half a second (and at most one second's frame count), and resumes slow clients
only at a key frame. Video frames that depend on discarded frames are never sent
as a substitute for a key frame.

The custom receiver reports whether it supports 1920×1080 at 60 FPS before encoding
starts. The encoder prefers that mode when confirmed, then 1280×720 and smaller frames before
30 FPS, requests realtime/no-reordering encoding, and increases bitrate with pixel
count and frame rate. The standard Google Cast video fallback and direct MilkDrop Live
connection also try 1080p first, then smaller frames if the sender's encoder cannot
configure that mode. Receiver decoding limits still apply; these two paths do not
negotiate decoder capabilities. A custom receiver that explicitly reports no Full HD
support retains its lower-resolution modes.
Android 9 remains supported, with codec/resolution fallbacks.
The phone's Quality setting scales the visualizer's render dimensions on casting paths
as well as locally; mesh size remains independent. Encoded video dimensions are negotiated
separately, so a lower-quality render can be scaled into a 1080p stream. A 1080p encoder
mode does not imply that the preset was rendered at full resolution. Frame-rate settings
remain subject to encoder, display and receiver limits.
Resolution, FPS and latency depend on both devices, the preset and the LAN. This is
not zero latency, and no end-to-end latency figure is claimed without physical testing.
Google's own application-launch screen can still appear before the hosted receiver loads.

## Register and host

1. Host the three files in `cast-receiver/` together on an HTTPS static site. Do not
   embed the receiver page in another page. Google hosts the Cast SDK script; it
   must not be copied into the project.
2. In the [Google Cast Developer Console](https://cast.google.com/publish/), register
   a **Custom Receiver**, using the hosted `index.html` URL. Do not enable audio-only
   devices or relay casting: this stream needs the TV to reach the phone on the LAN.
3. Register your test Cast device while the application is unpublished, as described
   in [Google's registration guide](https://developers.google.com/cast/docs/registration).
4. Build the Android app with the resulting eight-digit application ID:

   ```shell
   ./gradlew :app:assembleRelease -PmilkdropCastReceiverAppId=YOUR_APP_ID
   ```

   Alternatively set the `MILKDROP_CAST_RECEIVER_APP_ID` environment variable. For
   GitHub Actions, set a repository **Actions variable** with that name. The workflow
   passes it to Gradle. The ID is public configuration, not a credential.
5. Install that APK on the phone, keep the phone and Cast receiver on the same
   Wi-Fi/Ethernet LAN, and choose the device from **Cast**. No wireless-display or
   Miracast setting is involved.

An empty ID keeps the existing Google Default Media Receiver/HLS path working. Its
normal seconds of buffering remain. An invalid ID fails the build rather than
silently selecting a different receiver. This repository cannot supply a working
custom ID until the owner registers the hosted page with Google.

Published receiver pages use HTTPS. The phone's ephemeral media URL is HTTP on a
numeric LAN address, permitted for receiver media by Google's registration guide.
The server supplies CORS/private-network response headers and an opaque per-session
URL; the URL expires when casting stops. Guest Wi-Fi isolation, mixed-content or
private-network restrictions on particular receiver firmware must be checked on
real devices. Do not expose or port-forward the phone's stream server to the internet.

## Validation on receivers

For a new receiver or firmware version, test the connection on the target hardware.
Include the Philips TV, another recent Cast receiver, and an older receiver when available.
Check rapid preset changes, complex presets, reconnects, Stop cast, screen rotation,
background/foreground transitions and a slow or temporarily disconnected Wi-Fi link.
Confirm that stopping closes the network stream and restores local rendering.

Android logs report the negotiated encoder mode under `VisualStreamEncoder` and
receiver playback statistics under `CastingController`. `bufferSeconds` measures
the receiver's decoded-media timeline backlog; it is **not** total network-to-screen
latency. Measure total latency with both screens visible in a high-frame-rate camera
recording. The receiver never captures or transmits audio, so Bluetooth audio delay
is separate from the visual stream.
