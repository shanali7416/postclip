# PostClip by PostCora — Android app

Native Kotlin app (Jetpack Compose + Material 3) that cuts a short clip out of a YouTube video and
saves it to **Downloads/PostClip**. Only the selected time range is fetched — the app never downloads
the full video.

- Package: `com.postcora.postclip` · minSdk 26 · targetSdk 35
- Video info: NewPipe Extractor v0.27.1 (OkHttp downloader, no login, no API key)
- Cutting: Media3 Transformer 1.4.1 straight from the remote streams (HTTP Range requests),
  best MP4 video-only ≤ chosen quality (720p default) + best m4a audio, re-encoded to H.264 + AAC,
  written with Media3 `InAppMuxer`.

## Build

Requirements: **JDK 17** and the **Android SDK** (platform 35, build-tools 35.0.0).
Point the build at your SDK with `ANDROID_HOME` or a `local.properties` file (`sdk.dir=/path/to/sdk`).

```bash
cd android
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests (time parsing, validation, size estimate)
```

CI (`.github/workflows/android.yml`) runs both on every push to `android/**` and uploads the APK as the
`PostClip-debug-apk` artifact.

### Signing

Debug builds are signed with the committed `keystore/debug.keystore` (alias `androiddebugkey`,
password `android`) so every build — local or CI — has the same signature and installs as an update
over the previous one. It is a debug key only; no release keystore is in this repo.

## How a clip is made

1. Paste a link (or share it from the YouTube app) → title, length, thumbnail.
2. Start / End accept `h:mm:ss` or `m:ss` (`0:30` = 30 s). Presets 15/30/60/90 s set End = Start + preset
   (crosses hour boundaries: `59:50` + 30 s = `1:00:20`).
3. Download runs in a foreground service (type `dataSync`) with progress (% of the clip, MB, speed, ETA)
   and Cancel, holding a partial wake lock + Wi-Fi lock. Finished clips get **Open** and **Share**.
4. If the cut fails, PostClip retries automatically once with a different pipeline (single merged source,
   full re-encode). If that fails too it shows the error and **Try again**. There is no full-video download.

Live streams are rejected (ended videos only). If the audio stream is shorter than the video, the end of
the clip is video-only.

`tools/range-harness/` is an off-device test harness (uses the app's own core code plus ffmpeg).

Use PostClip only for your own content or content you are authorized to reuse.
Created by [PostCora.com](https://postcora.com).
