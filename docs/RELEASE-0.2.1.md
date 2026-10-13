# AndRip 0.2.1 — background download build

This build includes the 0.2 media engine, Compose UI, seekable Media3 preview, teal/mint colors and new AndRip logo, plus background queue and notification improvements.

## What changed

- Progress notifications now show details, open Downloads on tap, and provide Pause and Cancel actions that update the same persistent queue as the UI.
- Completed downloads and terminal errors produce tappable result notifications when notifications are allowed.
- Losing network connectivity or being stopped by Android retains partial data and queues the download again. Explicit user Pause/Cancel stays stopped.
- Temporary transfer failures receive up to two additional scheduled worker attempts, starting with five- and ten-minute backoff. HTTP 401/403, unsupported/protected streams, export errors and foreground-service rejection require user action.
- Startup reconciliation repairs missing scheduling and stale Running states without automatically restarting explicitly stopped jobs.
- Conditional database updates prevent stale pause/cancel/error events from overwriting a completed export.
- Foreground-service failure produces an actionable error before transfer starts. Downloads no longer silently run without the foreground service.

## Try the build

Install `AndRip-0.2.1.apk` on Android 10 or later. Package is `com.andrip.browser`, version code 3. It uses the same development signing certificate as the earlier 0.2.0 APK, verified during packaging.

1. Open a site where you are authorized to save media, sign in if needed, and play a video.
2. Open Media, choose a quality, and try Preview/scrubbing, then Save.
3. Background the app; check the progress notification. Tap it to return to Downloads.
4. Pause from the notification, then Resume from Downloads. Briefly disconnect/reconnect the network and check that the row is queued rather than canceled.
5. Open a saved result. If the source returns HTTP 403, reopen its source page and discover a fresh authorized link.

## Verification

- `testDebugUnitTest assembleRelease lintDebug` succeeded.
- **49 tests passed**, zero failures/errors/skips: 8 background, 4 Room/WorkManager queue, 12 HTTP transfer, 9 protocol safety and 16 logic tests.
- Worker tests use local mock servers for access-denied failures, delayed retry decisions and cancellation. Foreground updates are controlled test doubles; they do not establish real OS service behavior.
- Lint: **0 errors, 68 warnings** (66 dependency/tooling update suggestions, 1 backup-attribute deprecation, 1 obsolete resource qualifier).
- APK signature verified and matched the previous build. Version and embedded logo resource verified.
- Source archive integrity and reverse applicability of the full patch checked. Patch base is `9b6652fb1391a3440870606ce92734cc4aa41893`.
- No device is attached. Physical-device playback/remuxing, notification permission behavior, reboot, process death and battery/quota restrictions remain unverified.
- Changes remain local on `improve-media-downloads`; nothing was pushed or published.

## Support and limits

Progressive HTTP/HTTPS media, clear HLS VOD and supported single-period clear DASH VOD are supported. Server session/headers, redirect rules, device codecs and valid resume metadata still determine success. DRM is detected and refused. Live recording, encrypted HLS export, multi-period DASH export, RTMP/RTSP/WebRTC, transcoding and editing are not implemented. The accompanying support matrix details layout restrictions.

Memberful connection refusals and the original signed Vimeo 403 were not reproduced or proven fixed. Context preservation and fresh-link error handling are included; the build cannot promise access to server-restricted media.

Android controls background scheduling, including [long-running worker quotas](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running). Force-stop requires reopening the app. The narrow MediaStore publication/Room commit crash window remains documented in the support matrix; check shared storage before retrying an export interrupted at that instant.

Figma execution was unavailable in this session. The existing local HTML design specification and teal/mint logo remain included.
