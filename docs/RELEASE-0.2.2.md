# AndRip 0.2.2 — visible background downloads

Install `AndRip-0.2.2.apk` on Android 10 or later. It uses the same development signing certificate as 0.2.1, with version code 4.

## What you will see

- Downloads run in the existing foreground WorkManager worker, independently of the browser activity. Leaving AndRip does not cancel them.
- An ongoing notification shows a progress bar plus **42% · 84 of 200 segments** for HLS/DASH. Progressive files show downloaded bytes instead; unknown sizes use an indeterminate bar.
- Parallel segment completions are serialized so the counter stays in order. Matching cached segments count toward the total; selected video/audio media segments are included, initialization segments are excluded.
- Segment counts remain visible while merging and saving. A 100% transfer bar can still say “Merging” or “Saving”; the export is not reported complete until publication and the saved queue entry succeed.
- The final notification says **Download complete**, shows the saved filename and segment total, and opens Downloads when tapped. It is dismissible. Failed downloads never receive this completion label.
- Pause/Cancel notification controls, retained partial data, automatic network/OS-stop recovery and bounded transient-failure retries from 0.2.1 remain enabled.
- If notifications are disabled, Downloads shows an Enable notifications action linking to Android's app or download-channel notification settings.

## Try it

1. Install the APK and allow AndRip notifications when prompted.
2. Start a permitted HLS or DASH VOD download, then press Home.
3. Expand its notification and check the percentage, segment counter and Pause/Cancel actions.
4. Leave it running. After saving, look for “Download complete” and tap to open Downloads.
5. For a progressive file, expect byte progress rather than a made-up segment count.

## Verification

`testDebugUnitTest assembleRelease lintDebug` succeeded: **52 tests passed**, zero failures/errors/skips. New checks cover ordered concurrent segment updates, determinate/indeterminate notification bars, final segment totals, dismissible completion notifications, tap behavior and the absence of a completion label on access-denied failures.

Lint has **0 errors and 68 warnings**, mostly dependency/tooling update suggestions. The APK signature, version, matching update certificate and logo resource were verified. Source ZIP integrity, full patch reverse applicability and diff whitespace checks passed. Changes remain local; nothing was pushed.

No Android device was attached. Real background operation, notification permissions, playback/remuxing and reboot/battery-restriction behavior still need a physical-device smoke test.

## Android behavior

The user must allow notifications for progress to appear in the notification drawer. Android can still stop or delay background work; force-stopping the app requires opening it again. The app does not bypass those OS controls or promise indefinite activity. See [Android notification permissions](https://developer.android.com/develop/ui/compose/notifications/notification-permission) and [long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

Protocol support is unchanged: authorized progressive HTTP/HTTPS and supported clear HLS/DASH VOD. DRM, live recording and unsupported layouts remain excluded. The complete support matrix accompanies this build, including the narrow cross-database export crash window and unverified Memberful/Vimeo cases.
