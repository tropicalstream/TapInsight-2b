package com.TapLinkX3.app

import android.webkit.WebView

/** Auto-pins the Hermes glasses log tail to the bottom of the WebView.
 *
 * The Hermes session log page at `<relay-host>/hermes/log/...` is a
 * static HTML dump that refreshes itself every ~30 seconds. the user reported
 * that opening the page from the H badge lands on the top of the log, and
 * every subsequent refresh resets the scroll back up. To read the latest
 * lines the user had to manually scroll down each time.
 *
 * This helper injects a tiny script on `onPageFinished` for matching URLs.
 * The script pins the document scroll to the bottom immediately, then:
 *  - watches the body with a MutationObserver so any DOM update (ticker
 *    append, full-page meta-refresh re-render) re-pins to the bottom.
 *  - keeps a 1 Hz interval as a belt-and-suspenders fallback in case the
 *    refresh uses a mechanism the observer can't see (e.g. meta refresh
 *    replacing the whole document — onPageFinished re-fires and we re-inject
 *    anyway, but the interval covers transient races).
 *
 * Idempotent via a window flag so re-injection on observer reset is a no-op.
 */
object HermesLogAutoScroll {
    fun maybeInject(view: WebView?, url: String?) {
        if (view == null || !isHermesLogUrl(url)) return
        view.evaluateJavascript(SCRIPT, null)
    }

    private fun isHermesLogUrl(url: String?): Boolean {
        val lower = url?.lowercase(java.util.Locale.US).orEmpty()
        // Match the relay host plus the /hermes/log/ path so we don't pin
        // unrelated pages on the same host (e.g. /media/, /openclaw/...).
        val relayHost = BuildConfig.DEFAULT_RELAY_BASE
            .removePrefix("https://").removePrefix("http://")
            .trim('/').lowercase(java.util.Locale.US)
        return relayHost.isNotBlank() &&
            lower.contains(relayHost) &&
            lower.contains("/hermes/log")
    }

    private val SCRIPT =
        """
        (function taplinkHermesLogAutoPin() {
            try {
                if (window.__tl_hermes_log_pin_bound) {
                    // Re-inject after observer reset: pin once, leave the
                    // existing observer / interval in place.
                    try {
                        window.scrollTo(0, document.documentElement.scrollHeight);
                    } catch (e) {}
                    return;
                }
                window.__tl_hermes_log_pin_bound = true;

                function pin() {
                    try {
                        var h = Math.max(
                            document.body ? document.body.scrollHeight : 0,
                            document.documentElement ? document.documentElement.scrollHeight : 0
                        );
                        window.scrollTo(0, h);
                    } catch (e) {}
                }

                // Initial pin — may run before the page is fully laid out, so
                // schedule a couple of follow-ups across the first second.
                pin();
                setTimeout(pin, 100);
                setTimeout(pin, 400);
                setTimeout(pin, 900);

                // Re-pin on any subsequent DOM mutation. Hermes appends new
                // log lines into the body as plain text nodes; subtree:true
                // catches both append-style updates and full re-renders.
                if (window.MutationObserver && document.body) {
                    try {
                        new MutationObserver(function() { pin(); }).observe(
                            document.body,
                            { childList: true, subtree: true, characterData: true }
                        );
                    } catch (e) {}
                }

                // Belt and suspenders for refresh paths the observer misses
                // (e.g. meta-refresh replacing the document mid-frame). 1 Hz
                // is light enough to stay invisible and frequent enough to
                // catch user expectations.
                setInterval(pin, 1000);
            } catch (e) {}
        })();
        """.trimIndent()
}
