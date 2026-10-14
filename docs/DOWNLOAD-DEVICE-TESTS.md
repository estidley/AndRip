# Real download device tests

The `Real download device tests` GitHub Actions workflow runs instrumentation tests on Android 6, 10, 15 and 16 (API 23, 29, 35 and 36). It uses the production queue, Room database, WorkManager scheduler, foreground service, notification actions, HTTP client, MediaExtractor/MediaMuxer and export storage. The APK is the debug variant with the same production implementation; no mocked foreground updater or test WorkManager replaces Android services.

`scripts/generate-download-fixtures.py` creates a 24-second H.264/AAC test pattern with FFmpeg, then packages it as MP4, HLS/TS and separate DASH audio/video tracks. These generated files are test-only assets, excluded from Git and the production APK. No user account, private media URL or external media provider is accessed.

The on-device local HTTP server deliberately slows transfers so tests can observe intermediate progress. It checks cookies, Referer and User-Agent, supports validated range requests, and injects HTTP failures.

## Assertions

- Progressive MP4 follows a redirect that sets a session cookie, continues after Home, pauses through the actual notification PendingIntent, resumes using Range/If-Range, and finishes with the screen off. The saved file's SHA-256 must match the source.
- HLS and DASH display determinate progress with segment counts, keep progressing with the screen off, recover from a transient segment error, and post a completion result after export.
- Every successful export must contain both audio and video, readable samples through the end of the source, and readable samples after seeking near the midpoint. This catches truncated media and missing tracks, rather than accepting a nonempty output file.
- Cancel from the notification stops the worker without exporting or posting completion.
- HTTP 403 produces an actionable fresh-link error without retry loops, output or a false completion message.
- Active notifications must be real foreground-service notifications; completion must replace progress with a dismissible result.

The workflow uploads JUnit reports, logcat and screenshots of progress/completion notifications. Use the workflow run's conclusion and test reports as the outcome; this document describes coverage, not a claim that an unrun revision passed.

## Run locally

With FFmpeg, JDK 17, Android SDK 35 and a disposable connected emulator:

```sh
python3 scripts/generate-download-fixtures.py
./gradlew connectedDebugAndroidTest
```

The tests install AndRip and write/delete their own downloads. Use a disposable emulator, not a phone holding important AndRip data.

## Limits

Screen-off testing is not forced Doze, process death, reboot or a multi-hour soak. The tests do not prove audiovisual sync perceptually, decoder playback on every chipset, manufacturer battery-management behavior, full disks, real Memberful/Vimeo authorization, or every supported protocol variant. They verify synthetic H.264/AAC MP4, HLS/TS and DASH/fMP4 paths plus native sample extraction and seeking.

Android can delay or stop scheduled work. In particular, [long-running WorkManager jobs are subject to Android 16 job quotas](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running). AndRip retains resumable state, but does not promise uninterrupted transfers after force-stop or exemption from platform limits.
