# AndRip 0.3.1 — Android compatibility

This update broadens the APK from Android 10+ to **Android 6.0+ (API 23)** and adds installation checks for the exact signed release file.

**Fixed the installation failure:** the package-visibility query incorrectly combined HTTP and HTTPS in one intent. Android rejects this with `INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: intent tag may have at most one data scheme`. The two schemes now have separate query intents, and a regression test protects that constraint.

- One universal APK includes ARM 32/64-bit and x86 32/64-bit libraries.
- Both v1 and v2 APK signatures are included. The private signing key is unchanged from 0.3.0, allowing an in-place update from 0.3.0.
- Older Android runtimes get compatible notifications, WebView diagnostics, DRM metadata guards, Java library desugaring and a launcher icon fallback.
- Android 6–9 save into app-specific external storage without broad storage permission. Open these files from AndRip's Downloads list. **Uninstalling the app removes these files.** Android 10+ still use shared MediaStore exports.
- The signed candidate is downloaded by checksum and tested for clean installation, visible startup and reinstall on Android 6, 9, 10, 15 and 16 emulators. See the attached validation report for results.

The 0.3.0 APK passed checksum, ZIP, signature and alignment checks despite containing the malformed manifest query. A real Android installation test reproduced the parser rejection in the same unchanged query before the fix. Installer testing is now an explicit release gate. The older Android 10 minimum was an additional compatibility restriction, now lowered to Android 6.

Android below 6.0 remains unsupported. Media codec support still depends on the device. DRM, live recording and unsupported stream layouts remain unavailable as documented in MEDIA-SUPPORT.md.

Older builds signed with the public development key (before 0.3.0) still require a fresh installation after backing up important media. Do not uninstall 0.3.0 to update to 0.3.1.
