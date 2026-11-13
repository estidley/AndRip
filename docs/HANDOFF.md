# AndRip handoff

Updated Friday, Nov 13, 2026, 2:20 PM CST (America/Chicago). This is the first operational `docs/HANDOFF.md` for AndRip. Prior status lives in the [README](../README.md), the version notes under `docs/RELEASE-*.md`, the support matrix in [docs/MEDIA-SUPPORT.md](MEDIA-SUPPORT.md), the audit in [docs/red-team.md](red-team.md), and [docs/RELEASING.md](RELEASING.md). This file summarizes that work instead of repeating those matrices.

AndRip (`com.andrip.browser`) is Ethan's in-app browser stream downloader: a Kotlin / Compose WebView client that finds media on a page, previews it, and saves an authorized copy. The current published source and GitHub release are **1.0.0 (versionCode 10)**, tagged [v1.0.0](https://github.com/estidley/AndRip/releases/tag/v1.0.0) on Monday, Oct 19, 2026 (6:40 PM CST). That release turns on R8, resource shrinking, and native symbol tables for release builds only; debug stays unminified. `main` is at `08480f0` with no open pull requests and no open issues. The 0.3.x series that led up to 1.0.0 landed page-playback-safe detection (no fetch/XHR wrapping), an offline ad blocker, a compact address bar, download deletion, session-end browsing-data wipe, AES-128 HLS decrypt when the playlist itself serves the key, live HLS/DASH as a DVR-window snapshot, and a single clear DRM refusal message. Commercial DRM is out of scope on purpose. This reset-day pass only adds this handoff; it does not change app code, signing, or releases.

## Ethan's instructions and goal

- Goal: the most versatile downloader Ethan can ship — HLS, DASH, clear-key AES-128, track merge, live windows, and retries — with no Widevine or other commercial DRM support, and a clear message when a stream cannot be saved (“Protected content, can't be saved.”).
- Do not obtain DRM licenses, bypass DRM, or unwrap a content key the page does not already receive.
- The release signing key is kept privately. Do not mention keystore paths, passwords, or certificate material in docs.
- Do not put a private key in the repo or attach one to a GitHub release. Official APKs are signed through the local release process in [docs/RELEASING.md](RELEASING.md).
- Merges, Play uploads, and new releases are Ethan's decisions. There is no open PR to merge today.

## Checklist

### Done

- [x] In-app browser shell, stream detection, preview, and authorized downloads (Kotlin, Compose / Material 3, WebView, Media3, OkHttp, WorkManager, Room)
- [x] Universal APK for Android 6.0+ (API 23); Android 10+ shared MediaStore exports; older-Android app-specific Downloads (removed on uninstall)
- [x] Playback-safe detection in 0.3.3: no fetch/XHR wrapping; manifests and segments are never replaced
- [x] Offline host ad blocker, compact always-visible address bar, confirmed download deletion, browsing-data wipe only when a session fully ends
- [x] AES-128 HLS (playlist-served key), muxed TS kept as one file, live HLS/DASH current-window save, one playlist refresh after a rejected segment URL, unified DRM refusal message
- [x] Red-team audit of the “save one playable file” job, with fixed and deferred items recorded in [docs/red-team.md](red-team.md)
- [x] Release 1.0.0 with release-only R8, resource shrinking, and native symbol tables (2026-10-19)
- [x] This operational `docs/HANDOFF.md` drafted 2026-11-13 (this PR; not merged)

### Left — deferred in the approved 1.0.0 / red-team scope (not a promise to implement next)

These are documented as deferred in [docs/red-team.md](red-team.md) and [docs/MEDIA-SUPPORT.md](MEDIA-SUPPORT.md). They were not approved as the next slice; do not start them until Ethan picks one.

- [ ] Changing fMP4 initialization maps, and fMP4 discontinuity timestamp rebuild
- [ ] SAMPLE-AES subsample decrypt (message is already correct; decrypt is not)
- [ ] Record a live stream until Stop, instead of one DVR window
- [ ] Multi-period DASH and a broader presentationTimeOffset / separate TS A/V mux
- [ ] Audio language picker; filtering short preview clips that are not on an ad path; reading extensionless manifests from the response body
- [ ] Physical-device / emulator confirmation still called out in the docs: real A/V sync and remux, authenticated sources, process-death and storage edge cases. No new approval is on file to run a device pass.

### Left — awaiting Ethan

- [ ] What, if anything, to build after 1.0.0. The repo has no open PRs, no open issues, and no written “do this next” from Ethan.
- [ ] Any Play Console listing or production upload. Not an agent action without Ethan's OK.
