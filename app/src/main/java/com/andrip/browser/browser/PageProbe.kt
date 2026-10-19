package com.andrip.browser.browser

/**
 * Read-only page probe. It does not replace fetch or XMLHttpRequest, so a player keeps
 * making its own CORS requests with the WebView's normal headers, cookies and credentials.
 * Resource timing and media-element URLs are reported after the browser has already fetched them.
 */
object PageProbe {
    val SCRIPT = """
        (function () {
          if (window.__andripHooked) return;
          window.__andripHooked = true;
          function report(u, type) {
            try {
              if (!u || typeof u !== 'string') return;
              if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
              AndRipBridge.found(new URL(u, location.href).href, type || '');
            } catch (e) {}
          }
          function looksLikeMedia(u) {
            return /\.(m3u8|m3u|mpd|m4s|mp4|m4v|m4a|webm|mkv|mov|ts|aac|cmfv|cmfa|cmft)(\?|#|$)/i.test(u)
              || /m3u8|mpegurl|\.mpd/i.test(u);
          }
          function scan() {
            try {
              var nodes = document.querySelectorAll('video, video source, audio, audio source');
              for (var i = 0; i < nodes.length; i++) {
                report(nodes[i].currentSrc || nodes[i].src, nodes[i].type || '');
              }
            } catch (e) {}
          }
          var timer = null;
          function scanSoon() {
            if (timer) return;
            timer = setTimeout(function () { timer = null; scan(); }, 500);
          }
          try {
            var observer = new PerformanceObserver(function (list) {
              var entries = list.getEntries();
              for (var i = 0; i < entries.length; i++) {
                if (looksLikeMedia(entries[i].name)) report(entries[i].name, '');
              }
            });
            try { observer.observe({ type: 'resource', buffered: true }); }
            catch (e) { observer.observe({ entryTypes: ['resource'] }); }
          } catch (e) {}
          try {
            if (document.documentElement) {
              new MutationObserver(scanSoon).observe(document.documentElement, { childList: true, subtree: true });
            }
          } catch (e) {}
          document.addEventListener('play', scanSoon, true);
          document.addEventListener('loadedmetadata', scanSoon, true);
          scan();
        })();
    """.trimIndent()

    /**
     * Cosmetic rules are specific on purpose. Broad selectors such as [class*="ad"] also match
     * player, header and download containers, so they are not used. Video and audio elements are never targeted.
     */
    private const val CSS =
        "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],iframe[src*=\"googleadservices.com\"]," +
            "iframe[id^=\"google_ads_iframe\"],ins.adsbygoogle,div[id^=\"div-gpt-ad\"],div[id^=\"google_ads_iframe\"]," +
            "[data-ad-slot],[aria-label=\"Advertisement\"],.ad-banner,.ad-unit,.advertisement,.sponsored-ad" +
            "{display:none !important;}"

    fun cosmeticScript(enabled: Boolean): String {
        val css = CSS.replace("\\", "\\\\").replace("'", "\\'")
        val flag = if (enabled) "true" else "false"
        return "(function(){var id='andrip-adcosmetic';var n=document.getElementById(id);" +
            "if(!$flag){if(n)n.remove();return;}if(n)return;" +
            "var s=document.createElement('style');s.id=id;s.textContent='$css';" +
            "(document.head||document.documentElement).appendChild(s);})();"
    }
}
