package com.TapLinkX3.app

import android.webkit.WebView

/** Keeps YouTube captions available for hearing accessibility.
 *
 * Important: do not repeatedly drive the IFrame captions API here. On the
 * glasses that path can reset YouTube's caption clock and leave captions out
 * of sync. This helper instead makes a sparse, idempotent attempt to turn on
 * captions through YouTube's own CC button / textTracks, then only keeps the
 * caption containers visible while chrome fades.
 */
object YouTubeCaptionEnforcer {
    fun maybeInject(view: WebView?, url: String?) {
        if (view == null || !isYouTubeUrl(url)) return
        view.evaluateJavascript(SCRIPT, null)
    }

    private fun isYouTubeUrl(url: String?): Boolean {
        val lower = url?.lowercase(java.util.Locale.US).orEmpty()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private val SCRIPT =
        """
        (function taplinkYouTubeCaptionEnforcer() {
            try {
                if (!window.__taplink_caption_css_bound) {
                    window.__taplink_caption_css_bound = true;
                    if (!document.getElementById('__tl_caption_visible_style')) {
                        var s = document.createElement('style');
                        s.id = '__tl_caption_visible_style';
                        // Only the OUTER caption-window containers — never the inner
                        // .ytp-caption-segment / .captions-text nodes, because YouTube
                        // relies on those for cue-swap transitions.
                        s.textContent =
                            '.ytp-caption-window-container,' +
                            '.caption-window,' +
                            '.ytm-caption-window-container,' +
                            '.ytm-caption-window,' +
                            '.ytp-mobile-caption-window-container{' +
                            'opacity:1!important;visibility:visible!important;' +
                            'pointer-events:none}';
                        document.head.appendChild(s);
                    }
                }
                if (window.__taplink_caption_enable_bound) {
                    if (typeof window.__taplink_enable_captions_once === 'function') {
                        window.__taplink_enable_captions_once('reinjected');
                    }
                    return;
                }
                window.__taplink_caption_enable_bound = true;

                function videoKey() {
                    var v = document.querySelector('video');
                    return location.href.split('&t=')[0] + '|' + (v && (v.currentSrc || v.src) || '');
                }
                var attempts = Object.create(null);
                var timer = 0;

                function captionsActive(btn) {
                    if (!btn) return false;
                    var pressed = (btn.getAttribute('aria-pressed') || '').toLowerCase();
                    if (pressed === 'true') return true;
                    var label = (
                        btn.getAttribute('aria-label') || btn.getAttribute('title') || ''
                    ).toLowerCase();
                    return label.indexOf('turn off') !== -1 ||
                        label.indexOf('captions on') !== -1 ||
                        btn.classList.contains('ytp-button-active');
                }

                function captionsUnavailable(btn) {
                    if (!btn) return true;
                    var label = (
                        btn.getAttribute('aria-label') || btn.getAttribute('title') || ''
                    ).toLowerCase();
                    return label.indexOf('unavailable') !== -1 ||
                        label.indexOf('disabled') !== -1;
                }

                function findCcButton() {
                    return document.querySelector('.ytp-subtitles-button') ||
                        document.querySelector('button[aria-label*="captions" i]') ||
                        document.querySelector('button[title*="captions" i]');
                }

                function showNativeTracks() {
                    var v = document.querySelector('video');
                    if (!v || !v.textTracks) return false;
                    var changed = false;
                    for (var i = 0; i < v.textTracks.length; i++) {
                        var tr = v.textTracks[i];
                        var kind = (tr.kind || '').toLowerCase();
                        if (kind === 'captions' || kind === 'subtitles') {
                            if (tr.mode !== 'showing') {
                                tr.mode = 'showing';
                                changed = true;
                            }
                            break;
                        }
                    }
                    return changed;
                }

                function enableNow(reason) {
                    var key = videoKey();
                    var now = Date.now();
                    var st = attempts[key] || (attempts[key] = { count: 0, last: 0 });
                    if (st.count > 8 && now - st.last < 10000) return;
                    st.count++;
                    st.last = now;

                    try { showNativeTracks(); } catch (_e) {}
                    var btn = findCcButton();
                    if (btn && !captionsUnavailable(btn) && !captionsActive(btn)) {
                        try {
                            btn.click();
                            console.log('[TapLink-CC] enabled via native button reason=' + reason);
                        } catch (e) {
                            console.log('[TapLink-CC] button enable failed: ' + e);
                        }
                    }
                }

                function schedule(reason, delay) {
                    setTimeout(function(){ enableNow(reason); }, delay);
                }

                window.__taplink_enable_captions_once = enableNow;
                schedule('init-1', 250);
                schedule('init-2', 900);
                schedule('init-3', 1800);
                schedule('init-4', 3500);
                setInterval(function(){ enableNow('interval'); }, 6000);

                document.addEventListener('play', function(ev) {
                    if (ev && ev.target && ev.target.tagName === 'VIDEO') enableNow('video-play');
                }, true);
                document.addEventListener('loadedmetadata', function(ev) {
                    if (ev && ev.target && ev.target.tagName === 'VIDEO') enableNow('metadata');
                }, true);
                new MutationObserver(function() {
                    clearTimeout(timer);
                    timer = setTimeout(function(){ enableNow('mutation'); }, 250);
                }).observe(document.documentElement, { childList: true, subtree: true });

                console.log('[TapLink-CC] enforcer = native button/textTracks auto-enable');
            } catch (e) {
                try { console.log('[TapLink-CC] enforcer init failed: ' + e); } catch (_e) {}
            }
        })();
        """.trimIndent()
}
