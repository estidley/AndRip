# Signing and releasing AndRip

Official releases from 0.3.0 onward use a private signing key. The public `app/andrip-dev.keystore` is only for debug builds. Never publish an APK signed with it as an official release.

Release builds enable R8 (`isMinifyEnabled`) and `shrinkResources`. Debug builds stay unminified. Release native libraries use NDK 27.2.12479018 and `debugSymbolLevel = SYMBOL_TABLE`, which writes `app/build/outputs/mapping/release/mapping.txt` and `app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip`. Keep rules cover the WebView JavaScript bridge, WorkManager workers, Room types, manifest components, and Media3. Do not upload the keystore or its passwords. Upload the mapping file and native symbol zip with the release so crashes can be retraced.

## Build

Use JDK 17 and Android SDK platform 35. Run:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Without signing variables this produces `app-release-unsigned.apk`. CI intentionally builds this unsigned artifact and publishes reports, not a GitHub release.

To sign a release, supply these environment variables from a private secret store before building:

- `ANDRIP_KEYSTORE`: absolute path to the private keystore
- `ANDRIP_STORE_PASSWORD`
- `ANDRIP_KEY_ALIAS`
- `ANDRIP_KEY_PASSWORD`

Do not place their values in Git, shell history, release notes, screenshots or source archives. Preserve an encrypted offline backup of the key and its recovery credentials. Losing the private key prevents future updates under this signing identity.

Verify the signed APK with Android SDK `apksigner verify --verbose --print-certs`, inspect package/version with `aapt dump badging`, and compare its certificate fingerprint to the previous private-key release. Run the protocol and device checks listed in `MEDIA-SUPPORT.md`. Verify zero lint errors separately because the project retains nonfatal lint warnings.

## Publish

1. Increment version name/code and update release/support notes.
2. Commit the tested sources and push to GitHub. Require a successful CI build.
3. Tag that exact commit. Create a draft release, upload the verified APK, SHA-256 checksums, support matrix and validation report, and verify remote asset digests.
4. Publish the release. Label it a prerelease while physical-device validation remains outstanding.

Do not upload the private signing key or passwords. GitHub's generated source archives contain only committed sources. The repository's older public development keystore remains explicitly nonsecret.

## Migration from public-development-key builds

The new release APK cannot update an older APK signed with the repository's public development key. Back up important exported media, then uninstall the older build and install 0.3.0. Uninstalling clears the private browser session, queue and partial downloads. Exported shared-storage files should remain, but their access/ownership can change after reinstall; keep backups of important files. The release process itself does not uninstall an app or remove device data.

Future releases signed by the private key can update 0.3.0 in place. Reusing the exposed development key would preserve upgrades but would also allow anyone possessing that key to impersonate a signed update; it is therefore excluded from official release signing.
