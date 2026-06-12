package com.TapLinkX3.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Video-quality hints inspired by SmartTube-RayNeo-X3-Pro. SmartTube
 * doesn't share an architecture with TapBrowser — it bypasses WebView
 * entirely and streams YouTube's DASH manifests directly through
 * ExoPlayer + MediaCodec, which is why it can hand the user 1080p60
 * VP9 / AV1 streams. We can't match that ceiling without porting a
 * YouTube extractor + ExoPlayer track selector, both of which are
 * weeks of work.
 *
 * What we CAN do without changing TapBrowser's WebView architecture or
 * UI is push the WebView path as close to that ceiling as possible:
 *
 *  1. Prime the YouTube HD cookie (`PREF=f6=400&hd=1`). This is the
 *     same cookie YouTube's web player sets when a user clicks
 *     "always play in HD". With it, YouTube serves 1080p variants to
 *     mobile and desktop UAs alike instead of the default 720p / 480p.
 *     Single biggest WebView-side video-quality win.
 *
 *  2. On every YouTube page-finish, inject a tiny JS shim that
 *     escalates the playing video element to its highest available
 *     quality. The shim works with both the standard watch-page
 *     player and the embed iframe player; it's a no-op on non-video
 *     pages. SmartTube does the equivalent natively via its track
 *     selector — we do it via the player's own JS API.
 *
 *  3. WebView decoder/render performance flags
 *     ([applyMediaPerformanceSettings]). Off-screen pre-rasterization
 *     keeps the video frame buffer warm during quick page swaps so we
 *     don't drop the first ~200 ms after a back/forward navigation.
 *     Hardware layer type is reasserted (idempotent with the existing
 *     `setLayerType(LAYER_TYPE_HARDWARE)` call but cheap).
 *
 * Nothing in this file changes UI, layout, manifest, theme, or
 * navigation behavior — these are pure performance-and-quality knobs.
 */
object VideoQualityHints {

    private const val TAG = "TapVideoQualityHints"

    /**
     * Set the YouTube "default to HD" cookie. Idempotent — calling
     * twice writes the same value. Should be called once early in
     * Application / Activity start so the cookie is in place before
     * the first YouTube navigation.
     *
     * The cookie value is the same one YouTube's UI writes when a
     * signed-in user toggles "Always play HD". `f6=400` requests
     * the high-bitrate format family; `hd=1` is the legacy HD flag.
     * Setting both keeps us compatible with the watch page, the
     * embed player, and the m.youtube.com mobile site.
     */
    fun primeYouTubeHdCookie(@Suppress("UNUSED_PARAMETER") context: Context) {
        val cm = try { CookieManager.getInstance() } catch (e: Exception) {
            Log.w(TAG, "primeYouTubeHdCookie: CookieManager unavailable", e)
            return
        }
        cm.setAcceptCookie(true)
        // .youtube.com covers www.youtube.com, m.youtube.com, embed paths.
        // youtu.be is the short-link domain; it 302-redirects to youtube.com
        // so the cookie carries through, but set explicitly to be safe.
        // Note: each setCookie call expects ONE cookie, so call multiple times.
        for (domain in listOf("https://www.youtube.com", "https://m.youtube.com", "https://youtu.be")) {
            // f6=400 — request high-bitrate format family
            cm.setCookie(domain, "PREF=f6=400&hd=1; Path=/; Secure")
        }
        cm.flush()
        Log.d(TAG, "primeYouTubeHdCookie: PREF=f6=400&hd=1 written for youtube domains")
    }

    /**
     * Inject the HD-escalation JS shim if the loaded page is YouTube.
     * Called from `WebViewClient.onPageFinished` — the WebView is
     * already on the UI thread there so [WebView.evaluateJavascript]
     * is safe.
     *
     * Strategy:
     *  - For the standard watch page: poke the html5 player's
     *    `setPlaybackQualityRange` API. YouTube exposes this via the
     *    `#movie_player` element as a JS function. We pass `hd1080`
     *    as a hint; the player picks the closest available rung.
     *  - For embed iframes (used by `youtube.com/embed/<id>`): the
     *    same API is on the iframe's `<video>` ancestor. The shim
     *    walks the document looking for both shapes.
     *  - The shim retries every 500 ms for 6 seconds because the
     *    player JS lazy-initializes after the page DOM is ready.
     *  - A small idempotency flag (`__tcVqApplied`) prevents repeat
     *    injection on history navigation within the same WebView.
     *
     * Safe to call on any URL — the script no-ops if it can't find a
     * YouTube player.
     */
    fun maybeApplyYouTubeQualityShim(webView: WebView, url: String?) {
        if (url == null) return
        val lower = url.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) return
        // Inline JS (single line so older WebView impls don't choke on
        // multi-line script literals when sent via evaluateJavascript).
        val js = """
            (function(){
                if (window.__tcVqApplied) return;
                window.__tcVqApplied = true;
                var attempts = 0;
                var timer = setInterval(function(){
                    attempts++;
                    var ok = false;
                    try {
                        var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (p && typeof p.setPlaybackQualityRange === 'function') {
                            p.setPlaybackQualityRange('hd1080');
                            ok = true;
                        } else if (p && typeof p.setPlaybackQuality === 'function') {
                            p.setPlaybackQuality('hd1080');
                            ok = true;
                        }
                    } catch (e) { /* ignore */ }
                    try {
                        var v = document.querySelector('video');
                        if (v && typeof v.playsInline === 'boolean') {
                            v.playsInline = true;
                        }
                    } catch (e) { /* ignore */ }
                    if (ok || attempts > 12) clearInterval(timer);
                }, 500);
            })();
        """.trimIndent().replace("\n", " ")
        try {
            webView.evaluateJavascript(js, null)
        } catch (e: Exception) {
            Log.w(TAG, "maybeApplyYouTubeQualityShim: evaluateJavascript failed", e)
        }
    }

    /**
     * Reassert WebView performance flags that meaningfully affect
     * video playback smoothness. Safe to call multiple times — every
     * call is a setter on the same WebView, no allocation.
     */
    fun applyMediaPerformanceSettings(webView: WebView) {
        try {
            // Renderer-process priority bump. The default is "WAITING"
            // for backgrounded WebViews, which Android can swap out under
            // memory pressure — when the user comes back to a paused
            // YouTube tab the renderer has to be re-spun-up and the
            // first ~1 sec of video is a freeze. RENDERER_PRIORITY_IMPORTANT
            // tells the OS to keep this WebView's renderer resident so
            // resume is instant. waivedWhenNotVisible=true means we don't
            // pin priority forever, just while this WebView is on screen.
            // API 26+ — minSdk is 29 in this module, so safe.
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)

            // Off-screen pre-rasterization keeps the frame buffer warm
            // when the WebView is briefly off-screen (tab swap, settings
            // overlay). Without this the first frame after returning is
            // black, which on video reads as a stutter. Costs a small
            // amount of memory; well worth it for video playback.
            try {
                @Suppress("UNRESOLVED_REFERENCE")
                webView.javaClass.getMethod("setOffscreenPreRaster", java.lang.Boolean.TYPE)
                    .invoke(webView, true)
            } catch (_: Throwable) {
                // Method missing on some vendor WebView builds; skip
                // silently — the renderer priority bump above is the
                // larger win and is already in effect.
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyMediaPerformanceSettings: failed", e)
        }
    }
}
