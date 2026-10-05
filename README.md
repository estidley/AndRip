# AndRip

An Android browser that spots the video stream on a page and saves it.

Kotlin, Jetpack Compose, WebView, OkHttp, WorkManager, Room. Min Android 10 (API 29).

## What works in this first cut

- **Browser**: address bar (URL or search), back / forward / reload, opens links from other apps.
- **Detection**: every request the page makes is checked for HLS (`.m3u8`), DASH (`.mpd`) and
  direct video files (`.mp4`, `.webm`, `.mov`, `.mkv`, `.m4v`). A small injected script also reports
  `<video>` sources and `fetch` / XHR URLs. Tap **Videos (n)** to see what was found.
- **Downloads** (every one ends up as a single file with picture and sound)
  - Direct files, with resume on retry.
  - HLS: picks the best quality and fetches segments four at a time. If the audio is in its own
    playlist, that is downloaded too. Everything is merged into one `.mp4`.
  - DASH (`.mpd`): best video plus best matching audio, merged into one `.mp4` (or `.webm` for
    VP9/Opus streams). On-demand manifests only, first period only.
  - Merging uses Android's own MediaExtractor / MediaMuxer: no re-encoding, no ffmpeg.
  - Cookies, referer and user agent from the browser are sent along.
  - Runs as a foreground job with a progress notification; saved to `Movies/AndRip`.
  - Retry reuses segments already fetched.
- **Logs**: see below.

## Not done yet

- Tabs, fullscreen video, bookmarks/history.
- Encrypted HLS and live streams are refused with a clear message.
- Picking a quality or audio language by hand (it always takes the best / default).

DRM-protected streams (Widevine, FairPlay, PlayReady) are detected and refused. That is deliberate.

## Logs and crash reports

Everything the app does is written to `files/logs/andrip.log` inside the app's private storage
(rotates at about 1 MB, keeping one older file).

- Any uncaught exception on any thread is written with its **full stack trace**, including every
  `Caused by`, before the app closes.
- Failed downloads log the full stack trace too; the downloads list only shows the short message.
- Each app start writes a header with app version, device, Android version and WebView version.

To send a log: **menu (three dots) > Logs > Share**. That attaches one `andrip-log.txt` file to
whatever app you pick. After a crash, just reopen the app and share; the crash is already in the file.

With a cable instead: `adb logcat -s AndRip` shows the same lines live.

## Build and release

Open the folder in Android Studio, or:

```
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # parser / detector / log tests
```

GitHub Actions builds and tests every push to `main`. Pushing a version tag publishes the APK
under Releases:

```
git tag v0.1.0 && git push origin v0.1.0
```

All builds are signed with `app/andrip-dev.keystore`, a development key kept in the repo so that
each new APK installs over the last one. Replace it before distributing publicly.

## Layout

```
app/src/main/java/com/andrip/browser/
  AndRipApp.kt            installs the logger + crash handler
  MainActivity.kt         owns the WebView
  browser/                WebView setup, request interception, injected script, view model
  detect/MediaDetector.kt URL / MIME classification
  download/               HLS + DASH parsers, download engine, Remuxer (audio/video merge),
                          WorkManager worker, MediaStore saving
  data/Downloads.kt       Room table for the queue
  log/                    LogFile (rotating file) and AppLog (logcat + file + share)
  ui/AndRipScreen.kt      Compose UI: browser bar, video sheet, downloads, logs
```
