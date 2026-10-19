# Red team: save one playable file

Audit of AndRip’s core job: notice a video stream on a page and save it as one file with picture and sound. Commercial DRM (Widevine, PlayReady, FairPlay) is out of scope. Nothing here requests a DRM license or unwraps a content key the page does not already receive.

Reviewed against the 0.3.3 playback fix (passive detection, no `fetch` / `XMLHttpRequest` wrapping). That fix stays in place: `shouldInterceptRequest` still returns null except for a blocked ad host, and stream URLs are never replaced.

Libraries: export still uses the in-app HLS and DASH parsers plus Android `MediaExtractor` / `MediaMuxer`. AES-128 uses `javax.crypto`. Media3 (Apache-2.0) stays the preview player. FFmpegKit was not added. The upstream project is retired, and linking FFmpeg would pull a GPL or LGPL obligation plus a large native binary for a job the platform cipher and muxer already cover. No new third-party license was introduced.

## Summary

| Issue | Severity | Status |
| --- | --- | --- |
| AES-128 HLS refused even when the playlist serves the key | High | Fixed |
| Muxed TS with an audio rendition group was rejected instead of saved | High | Fixed |
| `file:` / `javascript:` segment URLs were resolved and could be requested | High | Fixed |
| HLS discontinuity aborted the whole save | High | Fixed for TS concatenation |
| EXT-X-GAP aborted the whole save | Medium | Fixed (segment skipped, sequence kept) |
| Live HLS and DASH were refused | High | Fixed as a DVR-window snapshot |
| Signed segment URLs were not refreshed after 401/403/404 | High | Fixed (one refresh if the path shape matches) |
| Commercial DRM failed with several different sentences, some easy to miss | Medium | Fixed (“Protected content, can't be saved.”) |
| SAMPLE-AES without a commercial key format was labeled as DRM | Medium | Fixed message; decrypt still deferred |
| Ad and preroll manifests were offered next to the real video | Medium | Fixed in the picker only; the request is not blocked |
| Progress had no ETA | Low | Fixed after 0.5s of transfer |
| MediaStore tried Movies before Downloads | Medium | Fixed (Downloads first) |
| Page-title filenames could contain `..` | Medium | Fixed |
| Recursive master playlists could follow themselves | Medium | Fixed (depth cap 4) |
| Subtitles were ignored | Medium | Fixed as a best-effort VTT/TTML sidecar |
| Changing fMP4 initialization map | High | Deferred |
| fMP4 discontinuity timestamp rebuild | High | Deferred (remux still refuses non-monotonic audio) |
| SAMPLE-AES subsample decrypt | High | Deferred |
| Record a live stream until Stop, instead of one window | Medium | Deferred |
| Multi-period DASH and non-zero `presentationTimeOffset` | Medium | Deferred |
| Audio language picker | Low | Deferred (default, else first) |
| Separate TS audio + video mux | Medium | Deferred |
| Extensionless manifests that never appear as a URL | Medium | Deferred (would require reading the response body) |
| Preview clips that are not on an ad path | Low | Deferred |
| Skip a corrupt segment and keep going | Medium | Deferred on purpose (a hole desyncs the file) |

## Formats

### HLS master, variants, TS, fMP4, byte ranges, MAP

**Severity:** already handled, with the muxed-audio bug below.

**Repro:** master with `RESOLUTION` and `CODECS`, media playlist of `.ts`, or `#EXT-X-MAP` plus `#EXT-X-BYTERANGE`. Quality chips Best / 1080 / 720 / 480 call `HlsMaster.pick`.

**Status:** Fixed earlier and kept. One init map is allowed. A second, different `#EXT-X-MAP` still throws. Byte ranges stay on the same URL; an implicit range that jumps to another URL is rejected. A single TS plan is copied as TS so AAC timestamps are not dropped by `MediaMuxer`. Two TS files (separate audio and video) are still refused.

### Muxed variant that also lists an audio group

**Severity:** high.

**Repro:** `#EXT-X-STREAM-INF` with `CODECS="avc1...,mp4a..."` and `AUDIO="aud"`. The old engine always downloaded `aud`, then refused to merge two TS files, so a self-contained stream was not saved.

**Status:** Fixed. Separate audio is fetched only when an audio group exists and the variant codecs do not already include an audio codec. Empty codecs plus an audio group still means the audio is separate.

### DASH SegmentTemplate, SegmentList, SegmentBase, separate audio

**Severity:** already handled for a single period.

**Repro:** number template, timeline with `r="-1"`, `SegmentList` ranges, `SegmentBase` / `BaseURL`. Video and audio adaptation sets are picked and muxed when the container matches (MP4 with MP4, WebM with WebM).

**Status:** Kept. Multi-period manifests and a non-zero `presentationTimeOffset` still fail with an explicit message. A text adaptation set is no longer dropped on the floor: its URL is a subtitle candidate.

### Progressive MP4 / WebM

**Severity:** already handled.

**Repro:** `video/mp4` or `video/webm` response, or a `.mp4` / `.webm` URL that is not a segment name. Range resume uses a strong ETag or Last-Modified.

**Status:** Kept. The saved extension follows the URL or the response MIME.

### blob: and MediaSource

**Severity:** medium. The real manifest is not inside the blob URL.

**Repro:** a player sets `video.src` to `blob:` or a MediaSource object URL. The page probe ignores `blob:` and `data:`. The underlying playlist is still an `http(s)` request.

**Status:** Kept. `shouldInterceptRequest` and resource timing see that HTTP request, including inside an iframe, and classify it. We do not wrap `fetch`. If a player builds the manifest entirely in memory and never requests it, there is nothing to save. That case stays deferred because sniffing it means intercepting and re-fetching, which is the 232011 failure mode.

## Encryption

### AES-128 with a key the stream serves

**Severity:** high. This is ordinary HLS encryption (RFC 8216), not DRM.

**Repro:** `#EXT-X-KEY:METHOD=AES-128,URI="key.bin"` with a 16-byte key response. IV is the `IV` attribute, or the media sequence number as a 16-byte big-endian integer. `#EXT-X-GAP` still advances the sequence.

**Status:** Fixed. The key is fetched with the same headers and cookies as the media, held only in memory for that download, and not written to the log. Segments are decrypted to disk before they are joined. PKCS#7 padding is tried first; a block-aligned body with bad padding falls back to no padding. A key that is not 16 bytes fails the download.

### SAMPLE-AES and commercial DRM

**Severity:** high if it fails silently. It did not fail silently, but the wording varied.

**Repro:** `METHOD=SAMPLE-AES` with `skd://` or a Widevine / FairPlay / PlayReady key format. Also a URL path containing `/drm/`, `/widevine/`, `/fairplay/`, or `/playready/`. Also `pssh` or `SAMPLE_FLAG_ENCRYPTED` in the container.

**Status:** Fixed message: `Protected content, can't be saved.` No license request. Identity SAMPLE-AES (an https key URI and no commercial key format) is a different sentence: sample encryption is not supported yet. Subsample decrypt is deferred because a wrong clear-lead pattern corrupts the picture and sound.

## Quality, audio, subtitles

**Severity:** low for the picker (it already existed), medium for languages and subtitles.

**Repro:** a master with 360p / 720p / 1080p, an `EXT-X-MEDIA` audio group with `DEFAULT=YES`, and a `TYPE=SUBTITLES` rendition or a DASH `text/vtt` representation.

**Status:** Best quality remains the default, and the chips still limit height. The default audio rendition (else the first) is the one that is merged when audio is separate. There is still no language picker; the playlist would have to be fetched before save, which we do not do while the page is playing. The first WebVTT or TTML rendition is written next to the video when the fetch works. A subtitle error is logged and the video still saves. Subtitles are not muxed into the video file.

## Requests

**Severity:** high for expired signed URLs; the rest was already scoped.

**Repro:** a media playlist whose segment query token changes between fetches. The first segment returns 403. Also cross-origin `Authorization`, cookies, and a full referer.

**Status:** Fixed for the token case. On 401, 403, or 404 the media playlist is fetched once more. If every segment still has the same path and byte range, the new URLs (and AES key URIs) replace the old ones and that segment is retried. If the window slid or the path changed, the save stops and asks you to reopen the page. Cookies still come from `CookieManager` for the URL actually being requested, including after a redirect. `Authorization` and `X-*` headers stay on the original origin. The referer is the full URL only for that same origin; other hosts get the origin, and an https referer is not sent to http. `Accept-Encoding` stays `identity`. Redirects are followed by OkHttp. 408, 429, 500, 502, 503, and 504 retry up to three times with jitter and `Retry-After`. The read timeout is 60 seconds so a slow CDN has room to send a segment. Manifest bodies over 4 MB are refused.

## Robustness

**Severity:** mixed. Resume, parallelism, disk checks, and the foreground worker were already there.

**Repro:** drop the network mid-playlist; background the app; a playlist with a missing segment; a very long VOD; a live playlist with no `#EXT-X-ENDLIST`.

**Status:**

- Each segment still has three transport attempts. The worker can schedule two further attempts after a retryable failure. Parts already on disk are reused when the plan fingerprint matches.
- WorkManager plus the download foreground service still covers process backgrounding. Pause keeps the partial files.
- Four segments are fetched at a time, and two downloads run at once.
- Free space is checked before joining, during large writes, and before export.
- Playlists are capped at 100,000 segments and 400,000 lines. DASH plans have the same segment cap plus an 8 million character budget. Master playlists deeper than four levels stop.
- A live playlist or dynamic MPD saves the segments it lists now. The progress line says `live window`. Following the live edge until Stop is deferred: a sliding window would rename segments that were already written.
- A short or empty segment fails that download. A corrupt segment with the right length is not skipped. Skipping it would leave a hole in the timeline. TS discontinuities are concatenated in order. fMP4 timestamp resets still fail in the muxer rather than dropping audio samples.

## Output

**Severity:** medium for the folder and the filename; low for ETA; already handled for the container choice.

**Repro:** save from a page titled `../etc/passwd`. Watch a long HLS save. On Android 10+, look for the file.

**Status:** The container stays TS for a single transport stream and MP4 or WebM after a successful remux. The filename is the sanitized page title. Path separators are removed, and `..` is collapsed, so the name cannot leave the AndRip folder. Android 10+ inserts into `Download/AndRip` first and `Movies/AndRip` only if that insert fails. Android 6–9 still uses the app-specific external Downloads folder, opened through FileProvider. Progress is `N of M segments`. After half a second it adds a rough ETA from the average segment time. 100% still means the bytes are local, not that export has finished.

A/V sync for fMP4 still shifts tracks so the earliest sample is at zero and refuses to drop an audio sample whose timestamp does not move forward. That guard is what keeps a bad discontinuity from producing a file that looks saved and plays wrong.

## Detection

**Severity:** medium for ads; the playback regression is high and must stay fixed.

**Repro:** a page with two playlists, one under `/ads/` or `preroll.m3u8`, plus the real master. An iframe player. A manifest requested after the player starts. A JW Player page that used to die with 232011.

**Status:** The detected list already lets you pick among streams and already ignores segment names (`seg-`, `init`, `chunk-`) and range fragments. Ad-host URLs and obvious ad paths (`/ads/`, `preroll`, `midroll`, `postroll`, `bumper`, `advert`) are no longer added to that list. The network request is not intercepted, so the page player and the 232011 fix are unchanged. `adaptive` and ordinary titles are not treated as ads. Iframes are visible to `shouldInterceptRequest` even though the injected probe runs in the main frame. Detection keeps running for the life of the page, so a late manifest is still offered. Preview clips that are just short and not on an ad path are still offered. Filtering those by duration is deferred.

## Security

**Severity:** high for non-HTTP URLs and cookie scope; medium for names and huge playlists.

**Repro:** a playlist whose segment is `file:///etc/passwd` or `javascript:alert(1)`. A title of `../etc/passwd`. A master that points at itself. A download whose CDN host is not the page host, with `Authorization` and cookies captured on the page.

**Status:** Non-HTTP segment, variant, key, and init URLs are rejected before any request. Filenames cannot contain separators or `..`. Playlist size and nesting are capped. Cookies are attached per request URL by `CookieManager`, not copied onto a different host. Authorization headers do not follow a redirect to another origin. Key bytes are not logged. Delete of a saved file still refuses a path outside AndRip’s export folder.

## Tests

`StreamRedTeamTest` covers AES-128 IV and gap accounting, non-HTTP URLs, the muxed-audio decision, ad paths, filename traversal, and a local `MockWebServer` for AES decrypt, signed-URL refresh, live and discontinuity concatenation, subtitle sidecars, the DRM and SAMPLE-AES messages, a DASH live window, and a recursive master. Parser fixtures already in `LogicTest` and `ProtocolSafetyTest` still cover byte ranges, master quality, separate-audio flags, DASH templates, and XML entities. The discontinuity fixture now expects both segments to be kept.

These tests do not run `MediaExtractor` or `MediaMuxer`. The engine tests replace container inspection and remux with local stand-ins. No device or emulator was attached, so real A/V sync, MediaStore visibility, and a JW Player page were not replayed here.
