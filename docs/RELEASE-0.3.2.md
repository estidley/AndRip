# AndRip 0.3.2 — verified background downloads

Real Android download tests found and reproduced two audio-export bugs in 0.3.1:

- **HLS audio loss:** Android's TS extractor can return multiple AAC frames sharing a timestamp. The exporter silently dropped those frames when converting to MP4. It now refuses that lossy conversion and saves the original clear TS stream, preserving every byte and both tracks. Streams with safely muxable timestamps still export as MP4. Separate-track layouts that cannot be merged receive a clear error.
- **DASH audio missing on newer Android:** negative timestamps from encoder priming were mistaken for end-of-stream. End-of-stream is now determined from the extractor's sample track index, and timestamps are normalized across both tracks.
- **Misleading retries:** a failed local merge was labeled as a connection interruption and automatically retried. Finalization failures now stop with an actionable format/quality error.

The new device suite transfers generated audiovisual media through the real queue, foreground service, notifications and storage pipeline on Android 6, 10, 15 and 16. It checks progressive MP4 redirects/session context, byte-range resume, Home/screen-off continuation, notification Pause/Cancel, progress bars and segment counts, completion notifications, transient segment retries, HTTP 403, complete audio/video sample data and seeking. Attached reports record the actual results.

Android 6.0+ remains supported. The private signing key is unchanged, so 0.3.0/0.3.1 can update in place. On Android 6–9, uninstalling removes app-specific downloaded files; do not uninstall to update.

The emulator suite uses the debug variant with the same production implementation. Signed-release installation checks separately verify the downloadable APK. No physical handset, private Vimeo/Memberful session, forced Doze, process-kill/reboot scenario or multi-hour battery-management soak is claimed. Android background limits and notification permission still apply. DRM, live recording and the other exclusions in MEDIA-SUPPORT.md remain unsupported.
