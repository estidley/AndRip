# AndRip 0.3.3

Playback while AndRip is open, an offline ad blocker, a shorter address bar, download deletion, and browsing-data clearing on a full close.

## Playback

JW Player error 232011 means the HLS manifest request failed a cross-origin check. AndRip was not re-fetching that manifest. `shouldInterceptRequest` already returned null. The page script did replace `window.fetch` and `XMLHttpRequest.prototype.open`, so the player's own CORS request no longer used the native functions.

Detection is now read-only: resource timing, media elements, and a request observer that does not supply a response. The only synthetic response is an empty body for a blocked ad or tracker host. Manifest and segment URLs are exempt, and any ad-block error lets the original request through. Download transfers still send the page's captured headers and cookies. There is no DRM circumvention.

This was not replayed against a JW Player page on a device. Unit tests cover the probe script, the pass-through rule, and the WebView client returning null for an m3u8 on an ad host.

## Ad blocker

A bundled host list plus a few cosmetic selectors. The **Ads** control in the address bar and the **Ad blocker** menu item toggle it and reload the page. The setting is stored with app preferences, not browsing data, and defaults to on. It is not the full EasyList or AdGuard list and it does not update over the network.

## Address bar

One compact row, always visible: back, URL, ad-blocker toggle, menu. Downloads remain in the menu and on the bottom bar. The bar does not hide on scroll.

## Downloads

**Delete** confirms, cancels an active transfer, deletes the file from MediaStore (Android 10+) or AndRip's app-specific export folder (Android 6–9), and removes the row. Long-press selects more than one. **Remove history** still keeps a finished export.

## Browsing data

Cleared on task swipe (`SessionGuardService.onTaskRemoved`), on **Exit and clear browsing data**, and on the next cold start if the previous process ended without recording a clean wipe. Not cleared on pause, stop, or merely switching apps while the process stays alive. Downloads, the queue, logs, and preferences are kept.

`onDestroy` is not used. Android does not always call it for a task swipe, and it can also run when an activity is reclaimed without the user leaving the session for good.
