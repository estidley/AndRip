# AndRip 0.3.2

## Download verification update in 0.3.2

Real emulator downloads exposed audio loss in HLS-to-MP4 conversion and negative-timestamp handling in DASH on newer Android versions. The exporter now preserves a clear single-track-plan HLS source as TS when Android cannot remux its audio timestamps safely, and recognizes DASH encoder-priming samples instead of treating them as end-of-stream. Merge errors are reported as finalization failures rather than retried as connection errors. See [device test coverage](docs/DOWNLOAD-DEVICE-TESTS.md) and [release notes](docs/RELEASE-0.3.2.md).

An Android browser that discovers media, previews it with seeking, and saves authorized downloads.

Kotlin, Jetpack Compose / Material 3, WebView, Media3, OkHttp, WorkManager and Room.
Minimum Android 6.0 (API 23); compile/target SDK 35; JDK 17.

## Compatibility update in 0.3.1

Fixes the installation parsing error caused by combining two data schemes in a package-visibility query. HTTP and HTTPS now use separate query intents, as required by Android's package parser.

The universal APK supports Android 6.0+ (API 23), with ARM 32/64-bit and x86 32/64-bit libraries. It is signed with both v1 and v2 schemes and the same private key as 0.3.0, so 0.3.0 can update in place.

Android 6–9 use permission-free app-specific export storage; open saved media from Downloads. **Uninstalling AndRip deletes those older-Android exports.** Android 10+ retain shared MediaStore exports. Notification channels, WebView diagnostics and DRM metadata calls are gated by API availability; core Java library desugaring supports older runtimes. Android below 6.0 is unsupported.

The install workflow validates the exact signed candidate APK (checksum pinned) on Android 6, 9, 10, 15 and 16 emulators before publication. It records fresh installation, visible app launch and reinstall results; this does not imply every manufacturer's device or codec has been tested.

## Changes in 0.3

0.3.0 fixes the nine adversarial-review findings: cross-origin referrer disclosure, non-URL log secrets, aggregate DASH memory growth, WebView renderer death, unverified export publication, stale source credentials, deleted-output deduplication, DASH period duration and inherited presentation offsets.

Finished transfers have a durable export journal, publication is verified, and **Retry saving** reuses the completed private file. Additional safeguards include low-storage checks, an unmetered-network preference, a 200-source detection cap and compatible DASH audio selection.

**Installation:** 0.3.0 uses a new private release signing key. Older public-development-key builds must be uninstalled first; this clears their private queue, session and partial files. Copy any data you need before uninstalling. Already exported files should be retained, but back up important videos first. Subsequent releases signed by the new key can update 0.3.0 normally. No app data is removed automatically by this release process.

See [release notes](docs/RELEASE-0.3.0.md) and [signing/release instructions](docs/RELEASING.md).

## Changes in 0.2

0.2.1 adds notification Pause/Cancel actions, tap-to-open Downloads, completion/error notifications, startup queue reconciliation, safe system-stop handling and bounded delayed retries after temporary transfer failures. See the support document for Android scheduling limits and remaining device checks.

0.2.2 shows percentage plus segment counts in the foreground progress bar, retains counts through finalization, posts an explicit Download complete notification after saving, and provides notification settings when alerts are disabled.

- Preserves browser request context across redirects with origin-scoped credentials and per-URL cookies.
- Validates file identity and byte ranges before resume; bounded retries, cancellation and clear access-denied errors.
- Clear HLS/DASH VOD exports with quality preferences, separate audio and segment checkpoint reuse.
- Persistent deduplicated queue with pause/resume, filters and source-page recovery.
- In-app Media3 preview with a native scrubber; playback pauses when backgrounded.
- New teal/mint identity and logo, adaptive/themed launcher icon, media cards and bottom actions.
- Non-destructive Room migration; URL-redacted logs; tests for transfer, parser and queue behavior.

See [the full protocol support matrix and known limits](docs/MEDIA-SUPPORT.md) before assuming a stream can be downloaded.
DRM is detected and refused. Live recording, encrypted HLS export, multi-period DASH export and unsupported layouts are not silently converted into partial files.

## Build and test

```sh
./gradlew testDebugUnitTest assembleRelease lintDebug
```

Windows uses `gradlew.bat`. Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` (or local.properties) to an SDK containing platform 35.
Without private signing variables, the build produces `app-release-unsigned.apk`, which cannot be installed directly. A configured release signer produces `app-release.apk`.

The repository development key is used for debug builds only. Official release APKs use a private key supplied through environment variables; no private release key is checked in or uploaded as a release asset.

## Logs

Menu > Logs > Share exports a redacted `andrip-log.txt`. Logs rotate in app-private storage and include failure stack traces.
URLs are reduced to host names so signed paths and query tokens are not shared. For connected devices: `adb logcat -s AndRip`.

## Layout

- `browser/`: WebView hooks, session state and external-browser recovery.
- `detect/`: URL/MIME classification and known DRM/fragment checks.
- `download/`: HTTP policy, tested byte transfers, HLS/DASH parsing, remuxing, worker and queue.
- `data/`: Room queue and schema migration.
- `ui/`: Compose browser, media list, downloads and Media3 preview.
- `docs/`: protocol matrix and visual design specification.

Figma skills were available but no Figma execution tools were exposed. The local HTML design specification is included instead.
Device playback/remuxing and real authenticated websites still need a physical-device smoke test.
