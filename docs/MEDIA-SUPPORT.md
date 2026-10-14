# AndRip 0.3.2 media support

The app saves media that the current browser session can access and that the user is authorized to download. It does not obtain DRM licenses, bypass DRM, solve site challenges, or defeat access restrictions. A detected URL is a candidate, not a guarantee that the server permits a separate download.

## Android compatibility

Android 6.0+ (API 23) is supported by the universal APK; Android 5 and older are not supported. All four common ABIs are included. Both legacy JAR/v1 and APK/v2 signatures are supplied, using the same private key as 0.3.0.

On Android 6–9, exports are saved under AndRip's app-specific external Downloads folder without requesting broad storage permission and opened through FileProvider. They are removed when the app is uninstalled. On Android 10+, exports use shared MediaStore storage. Older devices may support fewer codecs; a supported Android version is not a promise that every media format can be decoded or muxed.

## Supported and unsupported cases

| Source | Saving | Preview and seeking |
| --- | --- | --- |
| Progressive HTTP/HTTPS video | MP4, WebM, MOV, MKV, M4V and TS candidates; final media must be readable by the device's MediaExtractor | Media3 playback; seeking depends on the container, server and device codec |
| Progressive HTTP/HTTPS audio | MP3, M4A, AAC, Ogg/Opus, WAV and FLAC candidates; same device validation | Media3 playback, with seeking where the format permits |
| Extensionless media | MIME detection from HTML media elements, observed fetch/XHR responses and WebView DownloadListener; MIME determines the saved extension when available | Explicit HLS/DASH MIME hints passed to Media3 |
| HLS VOD | Clear master/media playlists, automatic/default separate audio for fMP4; single-plan TS saved intact, or fMP4 with a fixed initialization segment, exact byte ranges, quality preference | Media3 HLS preview and native seek controls |
| DASH VOD | Clear **single-period** MPDs; inherited SegmentTemplate, Number/Time, bounded timelines (including negative repeats ending at the next timeline entry), SegmentList ranges, whole-file BaseURL/SegmentBase representations; one video and compatible audio track | Media3 DASH preview and native seek controls |
| HTTP redirects | Followed; manifest-relative paths resolve against the final URL; signed URL queries are retained | Same browser-aware OkHttp transport |
| Cookies and headers | CookieManager consulted for **each actual request URL**; Set-Cookie responses written back; captured referer, UA, Origin and selected headers retained | Same cookie and header context |
| Byte-range resume | Strong ETag or Last-Modified plus If-Range; validates Content-Range and byte counts. Missing validators, changed content, ignored ranges or ambiguous 416 responses restart safely | Native player manages its own reads |
| Live HLS/DASH | **Not saved**; no recording or live-to-VOD conversion | May play; seeking limited to the available DVR window |
| AES-128 HLS | **Not saved** by this exporter (ordinary encryption is reported separately from DRM) | Media3 may play ordinary identity-key AES-128 where the server authorizes key access |
| Widevine, FairPlay, PlayReady, SAMPLE-AES/CENC | **Not saved**; guards check known protected URLs, manifest declarations, file metadata and encrypted samples before export | No DRM license configuration; protected tracks stop preview |
| HLS discontinuities, gaps or changing initialization segments | **Not saved**, with an explicit unsupported-layout message | Media3 may preview supported layouts |
| Multi-period DASH or non-zero presentation-time offsets | **Not saved**, rather than exporting only part of the presentation | Media3 may preview supported layouts |
| RTMP, RTSP, SmoothStreaming, WebRTC, raw sockets, blob/data URLs | **Not offered for saving** | Not wired into this preview flow |
| Subtitles, multiple audio-language export, manual codec selection, trimming, transcoding | **Not implemented** | Native playback controls only; no editor or thumbnail scrub strip |

HLS/DASH export retains the existing Android MediaExtractor/MediaMuxer pipeline, which merges compatible clear tracks into MP4 or WebM without re-encoding. Single clear TS streams stay in TS; separate TS audio/video export is unsupported. Other failed merges produce an error rather than a possibly incomplete file. Formats/codecs that Android cannot extract or mux on a particular device can fail even when the manifest layout is supported. This is not a universal stream recorder.

## Request and queue behavior

- Detection is passive: request interception returns `null` and does not replace WebView's networking. Network observations upgrade earlier page-script detections with actual request headers. Range fragments and common segment names are filtered out.
- Authorization and X-* headers are scoped to the original media **origin** (scheme, host and port). They are not forwarded to a different CDN origin. Each host receives only its own browser cookies. Full referer paths/queries are sent only to the same origin as the referring page; other origins receive the origin alone, and HTTPS referers are not sent to HTTP destinations. This can intentionally require a different supported source if a site expects cross-origin credential forwarding.
- Progressive files, manifests and segments use at most three attempts. Retryable HTTP responses are 408, 429, 500, 502, 503 and 504; network I/O failures also retry. Backoff includes jitter and respects Retry-After up to five minutes. 401/403 and unsupported streams fail immediately.
- Re-saving a stopped source refreshes captured credentials, discards old partials under the worker lock, and queues the current source. Active jobs keep their original context. Saved-file readability is checked before deduplication and on Open; missing files can be downloaded again.
- The queue deduplicates exact URL + quality preference. Signed query parameters are never stripped to merge supposedly similar videos. Different signed URLs can therefore appear as separate jobs.
- WorkManager persists scheduling and Room persists request context. Pause preserves private partial files; resume validates file identity or reuses completed segments only when their complete plan fingerprint matches. Old 0.1 partial files without validators restart instead of being appended blindly.
- There are at most two active downloads, each with up to four segment requests. Per-download locks prevent a canceled worker and its replacement from writing the same files. Network cancellation closes the current call; copy and merge loops check cancellation.
- Removing a queue row removes its private partial data. It does **not** delete a completed export from shared storage.
- Exports normally go to Movies/AndRip, with Download/AndRip as the storage fallback. The UI says “Saved on this device” because either location can be used.
- The database migrates from schema 1 to 2 without clearing existing rows. Captured headers and selected quality are added. Private request context is excluded from backup/device transfer rules.
- Logs redact URL paths/queries, authorization/cookie headers, bearer/basic credentials and common secret fields. Arbitrary page console message bodies are omitted. Crash logs are sanitized before writing; shared logs are sanitized again, including older entries. This is a defensive filter, not a guarantee against every possible encoding of a secret.

## Background behavior in 0.2.1

- WorkManager waits for a connected network and persists jobs across ordinary process loss and reboot. Android determines when queued work may run; this is not an always-running service.
- A stopped transfer retains private partial files. Explicit Pause/Cancel keeps its chosen state; a network/OS stop returns it to the queue rather than marking it canceled. Resume still depends on a valid server validator or matching segment plan.
- After the transport's three attempts, retryable transfer failures can schedule **two additional worker attempts**, with exponential backoff starting at five minutes (then ten minutes). Android may delay these further. Access denial, unsupported streams, DRM, foreground-service rejection and export failures do not receive automatic background retries. OkHttp may also perform a bounded protocol-level follow-up within an attempt, for example a 503 with Retry-After: 0.
- Notifications show transfer details and provide Pause/Cancel actions synchronized with the queue. Tapping a progress or result notification opens Downloads. Completion/failure notifications require notification permission; download state remains visible in the app when permission is denied.
- A foreground-service start failure stops the transfer with a message to open AndRip and retry. The worker does not silently proceed without its foreground service.
- On application startup, reconciliation repairs rows inserted before scheduling and stale Running rows. Explicitly paused/canceled jobs stay stopped. WorkManager jobs canceled outside the queue become paused for manual resumption; failed or unexpectedly finished jobs receive a recovery error.
- A late Pause/Cancel or worker update cannot overwrite a saved row. A completed publication takes precedence if cancellation arrives during the final export commit.
- Force-stopping the app suspends its background work until the user opens it again. Battery restrictions, data-sync foreground-service limits and Android 16 job quotas can delay/stop work. This version does not implement a separate user-initiated data-transfer JobService.
- A completed private file is recorded in an atomic checkpoint before export. A random pending-row token is journaled before MediaStore insertion; the URI is recorded before publication. Retry reconciles this journal, verifies byte length and publication state, and reuses a published URI after interruption. Completion is posted only after Room commits the verified URI. A notification could still be missed if Android kills the process immediately after the Room commit; the saved row remains authoritative.
- Low-space checks run before transfers, periodically during writes, and before joining, remuxing and export. WorkManager also waits when Android reports storage low. Storage can still run out between checks, particularly with simultaneous jobs; errors retain available partials/finished media. Remove history clears private cache and pending exports, while preserving published files.
- Unmetered-only is an opt-in preference for new/resumed jobs; changing it does not interrupt jobs already scheduled. Android decides whether a network is metered. Concurrency remains bounded to two jobs and four segment requests per job.
- Renderer crashes destroy and replace the affected WebView with a recoverable Reload message. Detection is capped at 200 candidates per page; embedded/service-worker sources may remain invisible to the hooks.
- DASH plans are limited across all representations to 100,000 media segments and 8 million URL characters, with at most 256 representations and a 4 MB manifest. Non-zero effective presentation offsets are refused in Template/List/Base modes, including inherited values. A period starting after zero uses the remaining presentation duration.

### Notification refinements in 0.2.2

Active transfers use an ongoing foreground notification with a progress bar and explicit percentage. HLS/DASH display counts such as **42% · 84 of 200 segments**. The count includes media segments across the selected audio/video tracks, including matching cached segments retained from an earlier attempt; initialization segments are excluded. Progressive files show bytes instead. Unknown-length downloads use an indeterminate bar.

Parallel segment updates are serialized to keep the displayed count from moving backwards. Counts remain visible during merge and saving. **100% means all transfer segments/bytes are available**, and the adjacent status explains finalization; it does not yet mean the export is complete. Only after publishing the saved file and committing its queue row does the app post **Download complete**, with the filename and segment total when applicable. Completion notifications are dismissible and open Downloads when tapped.

The Downloads screen detects disabled app/channel notifications and links to the relevant Android notification settings. Android 13+ still requires the user to allow notifications; the app cannot force the notification drawer to display them after denial. Downloads run independently of the activity and continue when it is backgrounded, subject to the Android constraints above.

## The earlier log

The supplied October 5–6 log shows three distinct conditions:

1. DuckDuckGo `ERR_NAME_NOT_RESOLVED`: hostname resolution failed. The UI now distinguishes this from a download error and suggests checking connection/VPN/Private DNS.
2. Memberful `ERR_CONNECTION_REFUSED`: connection failure before an HTTP authorization response. Passive interception does not itself explain the refusal. The app now shows a retry action and an external-browser action that excludes AndRip itself. External browser sign-in does not automatically transfer cookies back into WebView. The original service/network failure was **not reproduced or proven fixed**.
3. Vimeo signed progressive URLs returned HTTP 403. The original detector discarded most observed headers, and retries repeated the same URL. Request context is now preserved and a 403 points to signing in/replaying the source to discover a fresh URL. Expiration, entitlement, site policy or other server checks can still cause 403; the fix does not guarantee those original URLs will work. No expired/private logged media URLs were replayed during testing.

Some other Vimeo URLs in the log explicitly contained `/drm/cenc` and range-fragment parameters. They are not equivalent to an ordinary standalone MP4; these now receive protection/fragment handling.

## Design workflow

Figma's installed `figma-use` workflow was inspected. No callable Figma file-creation or execution integration was exposed in this session, so no Figma cloud file was created. `AndRip-design.html` is the local visual specification, translated into Compose. It uses illustrative content and a simulated preview slider.

The app uses a fixed teal/mint palette in light and dark modes, Material 3 controls, a persistent media/download action bar, source cards, quality preferences, queue filters and a native Media3 player with a scrubber. Preview pauses when the app backgrounds and releases resources on dismiss. The new AndRip mark is a generated transparent PNG integrated into the adaptive launcher icon, monochrome launcher layer and browser/download headers. The original PNG and generation prompt are included with the deliverables.

## Verification and remaining device checks

Run `./gradlew testDebugUnitTest assembleRelease lintDebug` with JDK 17 and Android SDK 35. Automated tests exercise parsers, DRM/fragment detection, redirect credential scoping, cookies, signed URL preservation, range validation, interrupted/resumed transfers, retry policy, cancellation, database migration and real Room/WorkManager queue operations under Robolectric. Background tests run the worker against local mock servers to verify terminal 403 behavior, bounded delayed retries, foreground-service rejection and system/user cancellation. Queue tests cover missing scheduling, stale Running rows and saved-row race guards. Mock servers supply test media bytes; tests do not access a user's accounts.

The [real download instrumentation suite](DOWNLOAD-DEVICE-TESTS.md) now exercises actual foreground-service notifications, screen-off transfers, pause/resume/cancel, native extraction/remuxing, device exports and seeking with generated H.264/AAC media. Consult the release's attached reports for the tested revision and outcomes. Clear single-plan TS HLS sources are preserved byte-for-byte instead of relying on native TS-to-MP4 packet conversion. Separate TS audio/video export is explicitly unsupported. Repeated audio timestamps are refused by the muxer instead of silently dropping samples. DASH samples with negative encoder-priming timestamps are retained and normalized with the other tracks. Finalization errors no longer trigger automatic connection retries.

Further physical-device/emulator work is still required to verify:

- Actual video/audio playback, seeking, accessibility at large font sizes and landscape layouts.
- HLS/DASH decoding and remux correctness, A/V synchronization and export on representative devices.
- Real authenticated sources, including Memberful connectivity and a newly discovered authorized Vimeo URL.
- OS constraint changes, process death/reboot recovery, storage exhaustion, manufacturer-specific background restrictions and launcher masking.

No physical device was attached during this change. The 0.3.1 signed-release installation workflow adds emulator checks for Android 6, 9, 10, 15 and 16; the release validation attachment records actual outcomes. The 0.3.0 regression suite additionally covers referrer/log privacy, renderer replacement, DASH aggregate limits/timing, refreshed queue credentials, deleted outputs, export failure/retry and publication recovery. Passing JVM/Robolectric tests and a successful APK build do not establish those device behaviors.

## References

- [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats): preview support depends on stream layout and device decoders.
- [Media3 downloading media](https://developer.android.com/media/media3/exoplayer/downloading-media): Media3's offline cache workflow was considered; this change keeps AndRip's existing shared-file export pipeline and adds Media3 for preview.
- [WorkManager management and stop behavior](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work): network constraints and OS interruption can stop a worker without a user cancellation.
- [Long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running): foreground notifications and Android 16 job quota limitations.
- [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): permission controls whether foreground progress appears in the notification drawer.
