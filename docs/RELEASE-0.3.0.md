# AndRip 0.3.0

This prerelease brings the redesigned teal/mint browser and logo, authorized media downloads, scrub-capable preview, persistent background queue and the fixes from the adversarial review into one GitHub release.

## Install notice

**New private signing key:** older development-key versions must be uninstalled before installing this APK. Back up important exported videos first. Uninstalling clears browser sessions, queue history and partial downloads. Future releases signed with the new key can update this version normally.

## Fixed in this release

- Cross-origin redirects receive only the referrer's origin; page paths/query secrets stay scoped to the page origin. Cookies are selected per destination and captured credentials remain origin-scoped.
- Authorization/cookie headers, common token fields and bearer credentials are redacted from persisted/exported logs. Arbitrary page console text is omitted.
- DASH parsing enforces aggregate segment/URL budgets and representation limits. Period start times reduce the available duration correctly, and unsupported inherited timing offsets are refused in all addressing modes.
- WebView renderer death is handled by destroying and replacing the failed view, with a Reload message.
- MediaStore publication must update one row and pass publication/readability/size checks. An atomic local export journal recovers interrupted saves without duplicating a published output. **Retry saving** reuses the completed local file.
- Re-saving a stopped source refreshes its session/header context and clears stale partials. Running jobs are left stable. Missing saved files can be downloaded again instead of being permanently deduplicated.
- Low-storage checks protect transfer/finalization, an unmetered-only preference applies to new/resumed jobs, and per-page detection is capped at 200 candidates. DASH refuses incompatible audio/video container combinations.
- External app intents accept HTTP/HTTPS page URLs only.
- Official release signing uses a private key; CI produces unsigned build artifacts and cannot accidentally publish a development-key APK.

## Included download and playback features

- Progressive HTTP/HTTPS audio/video, validated byte-range resume, bounded retries, redirects and browser cookie/header context.
- Clear HLS VOD and single-period DASH VOD, quality preferences, supported separate-audio merging, exact segment byte ranges and retained segment progress.
- Persistent queue with deduplication, pause/resume/cancel, source recovery, filters and private-cache cleanup through Remove history.
- Foreground progress notifications with percentage and segment counts; completion notifications after verified saving. Android scheduling/permission restrictions still apply.
- In-app Media3 preview with seeking where the source/device permits, plus the teal/mint Compose interface and new logo.

## Validation and limits

Automated JVM/Robolectric tests, signed APK assembly and lint checks are run for this release. The attached validation report records exact counts, warnings and SHA-256 fingerprints.

No physical Android device was connected. Real decoding/remuxing, A/V sync, OS reboot/background limits, large-font layouts and authenticated website behavior still require device testing. The prior Memberful connection refusal and Vimeo 403 were not reproduced or proven fixed; new errors provide source/session recovery guidance without bypassing access controls.

DRM is detected and refused. Live recording, encrypted HLS export, multi-period/offset DASH export, subtitles/multiple-language export, transcoding, RTMP/RTSP/WebRTC and unsupported layouts remain unavailable. Exact rendition/language selection, richer library management, ETA for every protocol and a dedicated Android user-initiated transfer service remain follow-up work. See `MEDIA-SUPPORT.md` for the complete protocol matrix.
