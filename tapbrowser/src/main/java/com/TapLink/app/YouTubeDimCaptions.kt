package com.TapLinkX3.app

import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import java.util.WeakHashMap

/**
 * One dedicated module, one state machine, ONE writer for YouTube dim-mode
 * captions.
 *
 * History (why this module exists): captions on the dim mask used to be fed
 * by TWO writers — the masked now-playing poll (DualWebViewGroup /
 * MainActivity scrape path) and the in-page push path — fighting over one
 * TextView, while a native freshness gate (2.5s) blanked long cues between
 * sparse pushes. The fast path never engaged and the line jittered. This
 * module consolidates everything: the entire caption state machine lives in
 * ONE injected block, and the ONLY producer of dim caption text is its 200ms
 * in-page ticker pushing through the native bridge
 * `MediaInterface.onDimCaption(line)`. The legacy poll continues only as a
 * title carrier; it must never write captions again.
 *
 * Behavior of the injected engine:
 *  - Starts in DOM-scrape mode: per-line scraping of YouTube's actually
 *    rendered caption line elements (`.caption-visual-line`), with fallbacks
 *    for layout variants (bare `.ytp-caption-segment` runs, whole caption
 *    windows, ytm mobile containers).
 *  - Upgrades once-and-permanently per video to timedtext cues rendered off
 *    the video clock with a constant 500ms lead (`CC_LEAD_MS`, declared at
 *    the top of the injected block) so the dim line keeps pace with speech
 *    instead of trailing the renderer.
 *  - Timedtext discovery: the resource-timing buffer is enlarged at page
 *    START (before it overflows), and the player's OWN tokenized
 *    `/api/timedtext` request URL is captured and refetched exactly —
 *    modern YouTube requires the `pot=` proof-of-origin token; untokenized
 *    URLs answer with an EMPTY document. The player-response caption track
 *    list is the secondary source (human tracks preferred over
 *    auto-generated `asr`). Discovery retries every second; empty responses
 *    retry instead of locking; a new video resets state and refetches.
 *  - Two-line roll in BOTH modes, CEA-608 roll-up convention: top line is
 *    settled history, bottom line is append-only growth, lines shift up when
 *    full. 220-char cap trimmed at a word boundary (same rule the old
 *    flattened scrape used).
 *  - A 200ms ticker pushes each caption CHANGE through
 *    `MediaInterface.onDimCaption`; a quiet heartbeat re-sends the unchanged
 *    line every 1.5s so long captions can't blink away under any
 *    freshness-window logic downstream.
 *
 * Install contract:
 *  - [install] is the whole public API. It is idempotent and cheap, and is
 *    meant to be called BOTH at page start (where injections can land in the
 *    dying page — hence the retry ladder, the same race the codec-steering
 *    code beats with retries) AND repeatedly from DualWebViewGroup's
 *    dim-mode poll, which guarantees the engine is alive within ~0.5s of
 *    dimming even if every page-start injection was lost.
 *  - Scope is YouTube-dim only. Spotify karaoke, radio lyrics and
 *    media-file captions have their own pipelines and are untouched: this
 *    module refuses to inject anywhere but youtube.com / youtu.be.
 *
 * Threading: call [install] from the UI thread only (WebView rule); both
 * call sites — WebViewClient callbacks and the dim-mode poll — already are.
 */
object YouTubeDimCaptions {

    private const val TAG = "YouTubeDimCaptions"

    /**
     * Minimum spacing between full retry ladders per WebView. The dim-mode
     * poll calls [install] roughly every 750ms; without this gate every poll
     * would stack three more postDelayed injections. The immediate inject
     * always runs (the in-page guard makes it a no-op when the engine is
     * alive), only the ladder is debounced.
     */
    private const val RETRY_LADDER_MIN_INTERVAL_MS = 5_000L

    /** Uptime of the last scheduled retry ladder, per WebView (UI thread only). */
    private val lastLadderAt = WeakHashMap<WebView, Long>()

    /**
     * Install (or re-assert) the dim-caption engine on [webView].
     *
     * Idempotent: the injected block guards itself with
     * `window.__tl_dimcc_installed`, so calling this on every dim-poll tick
     * and on every page-start callback is safe and intended. No-op for
     * non-YouTube URLs.
     */
    fun install(webView: WebView?, url: String?) {
        if (webView == null || !isYouTubeUrl(url)) return
        val inject = Runnable {
            try {
                webView.evaluateJavascript(SCRIPT, null)
            } catch (e: Exception) {
                Log.w(TAG, "install: evaluateJavascript failed: ${e.message}")
            }
        }
        // Immediate attempt — if the page is live this is all that's needed.
        inject.run()
        // Page-start injections can land in the dying page (the navigation
        // hasn't committed yet), and an install with no retries loses the
        // race silently — that regression is exactly why this ladder exists.
        val now = SystemClock.uptimeMillis()
        val last = lastLadderAt[webView] ?: 0L
        if (now - last >= RETRY_LADDER_MIN_INTERVAL_MS) {
            lastLadderAt[webView] = now
            webView.postDelayed(inject, 600L)
            webView.postDelayed(inject, 1800L)
            webView.postDelayed(inject, 4000L)
        }
    }

    private fun isYouTubeUrl(url: String?): Boolean {
        val lower = url?.lowercase(java.util.Locale.US).orEmpty()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private val SCRIPT =
        """
        (function taplinkYouTubeDimCaptions() {
            var CC_LEAD_MS = 500;       // timedtext cues lead the video clock by this much
            var TICK_MS = 200;          // in-page ticker cadence
            var HEARTBEAT_MS = 1500;    // quiet re-push of an unchanged line
            var DISCOVERY_MS = 1000;    // timedtext discovery retry cadence
            var MAX_LINE = 220;         // per-line cap, trimmed at a word boundary
            var CLEAR_AFTER_MS = 4000;  // blank the roll after captions vanish
            var URL_RETRY_MS = 5000;    // empty responses retry (never lock out a URL)
            try {
                // Enlarge the resource-timing buffer at page START, before the
                // player's burst of media requests overflows it — otherwise the
                // tokenized timedtext request is evicted before captions load.
                if (!window.__tl_dimcc_rtbuf) {
                    window.__tl_dimcc_rtbuf = true;
                    try { performance.setResourceTimingBufferSize(4096); } catch (e) {}
                }
                if (window.__tl_dimcc_installed) return;
                window.__tl_dimcc_installed = true;

                var st = {
                    vid: '',            // current video identity
                    mode: 'dom',        // 'dom' until timedtext locks, then 'cues' permanently per video
                    cues: null,         // [{start, end, segs:[{at, text}]}] sorted by start
                    curCue: null,       // active cue object (cues mode)
                    capturedUrl: '',    // the player's OWN /api/timedtext request
                    capturedHasPot: false,
                    badUrls: {},        // url -> last failure time (retried after URL_RETRY_MS)
                    fetching: false,
                    lastDiscoveryAt: 0,
                    top: '',            // settled history line
                    bot: '',            // append-only growth line
                    lastSeenAt: 0,
                    lastPushed: null,
                    lastPushAt: 0
                };

                function clean(value) {
                    return String(value || '').replace(/\s+/g, ' ').trim();
                }

                function clip(text) {
                    if (text.length > MAX_LINE) {
                        text = text.substring(0, MAX_LINE).replace(/\s+\S*${'$'}/, '').trim();
                    }
                    return text;
                }

                function videoIdOf(href) {
                    var m = href.match(/[?&]v=([^&#]+)/);
                    if (m) return m[1];
                    m = href.match(/\/(shorts|embed|live)\/([A-Za-z0-9_-]{6,})/);
                    if (m) return m[2];
                    return href.split('#')[0];
                }

                function push(text) {
                    try {
                        if (window.MediaInterface &&
                            typeof window.MediaInterface.onDimCaption === 'function') {
                            window.MediaInterface.onDimCaption(text);
                        }
                    } catch (e) {}
                }

                function resetForVideo(vid) {
                    st.vid = vid;
                    st.mode = 'dom';
                    st.cues = null;
                    st.curCue = null;
                    st.capturedUrl = '';
                    st.capturedHasPot = false;
                    st.badUrls = {};
                    st.fetching = false;
                    st.lastDiscoveryAt = 0;
                    st.top = '';
                    st.bot = '';
                    st.lastSeenAt = 0;
                    if (st.lastPushed) {
                        st.lastPushed = '';
                        push('');
                    }
                }

                // ── timedtext discovery ─────────────────────────────────────
                function noteTimedtextUrl(name) {
                    if (!name || name.indexOf('/api/timedtext') === -1) return;
                    // A stale request from the previous video must not poison
                    // the new one.
                    var v = (name.match(/[?&]v=([^&#]+)/) || [])[1] || '';
                    if (v && st.vid && v !== st.vid) return;
                    var hasPot = name.indexOf('pot=') !== -1;
                    // Modern YouTube needs the pot= proof-of-origin token —
                    // untokenized URLs return empty documents — so a tokenized
                    // capture always wins over one without.
                    if (hasPot || !st.capturedHasPot) {
                        st.capturedUrl = name;
                        st.capturedHasPot = hasPot;
                    }
                }

                try {
                    new PerformanceObserver(function(list) {
                        var entries = list.getEntries();
                        for (var i = 0; i < entries.length; i++) {
                            noteTimedtextUrl(entries[i].name || '');
                        }
                    }).observe({ type: 'resource', buffered: true });
                } catch (e) {
                    // Observer unavailable — the buffer scans below still work.
                }

                function scanResourceLog() {
                    try {
                        var entries = performance.getEntriesByType('resource');
                        for (var i = entries.length - 1; i >= 0; i--) {
                            var name = entries[i].name || '';
                            if (name.indexOf('/api/timedtext') === -1) continue;
                            noteTimedtextUrl(name);
                            if (st.capturedHasPot) return;
                        }
                    } catch (e) {}
                }

                function trackListUrl() {
                    var pr = null;
                    try {
                        var mp = document.getElementById('movie_player');
                        if (mp && typeof mp.getPlayerResponse === 'function') {
                            pr = mp.getPlayerResponse();
                        }
                    } catch (e) {}
                    if (!pr) pr = window.ytInitialPlayerResponse || null;
                    var tracks = pr && pr.captions &&
                        pr.captions.playerCaptionsTracklistRenderer &&
                        pr.captions.playerCaptionsTracklistRenderer.captionTracks;
                    if (!tracks || !tracks.length) return '';
                    var best = null;
                    for (var i = 0; i < tracks.length; i++) {
                        // Human tracks preferred over auto-generated ('asr').
                        if ((tracks[i].kind || '') !== 'asr') { best = tracks[i]; break; }
                    }
                    if (!best) best = tracks[0];
                    var url = best.baseUrl || '';
                    if (!url) return '';
                    if (url.indexOf('fmt=') === -1) url += '&fmt=json3';
                    return url;
                }

                function parseJson3(text) {
                    var data = JSON.parse(text);
                    var events = data && data.events;
                    if (!events || !events.length) return [];
                    var cues = [];
                    for (var i = 0; i < events.length; i++) {
                        var ev = events[i];
                        if (!ev || !ev.segs) continue;
                        var start = ev.tStartMs || 0;
                        var segs = [];
                        var whole = '';
                        for (var j = 0; j < ev.segs.length; j++) {
                            var segText = String(ev.segs[j].utf8 || '');
                            if (!clean(segText)) continue;
                            segs.push({ at: start + (ev.segs[j].tOffsetMs || 0), text: segText });
                            whole += segText;
                        }
                        whole = clean(whole);
                        if (!whole) continue;
                        cues.push({
                            start: start,
                            end: start + (ev.dDurMs || 3000),
                            segs: segs,
                            text: whole
                        });
                    }
                    return cues;
                }

                function parseXmlTimedtext(text) {
                    var cues = [];
                    try {
                        var doc = new DOMParser().parseFromString(text, 'text/xml');
                        var nodes = doc.getElementsByTagName('text');
                        for (var i = 0; i < nodes.length; i++) {
                            var n = nodes[i];
                            var start = Math.round(parseFloat(n.getAttribute('start') || '0') * 1000);
                            var dur = Math.round(parseFloat(n.getAttribute('dur') || '3') * 1000);
                            var t = clean(n.textContent || '');
                            if (!t) continue;
                            cues.push({ start: start, end: start + dur, segs: [{ at: start, text: t }], text: t });
                        }
                    } catch (e) {}
                    return cues;
                }

                function urlBad(url) {
                    var failedAt = st.badUrls[url];
                    return !!failedAt && (Date.now() - failedAt < URL_RETRY_MS);
                }

                function discover() {
                    if (st.fetching || st.mode === 'cues') return;
                    scanResourceLog();
                    // The player's own tokenized request first; the track list
                    // is the secondary source.
                    var url = '';
                    if (st.capturedUrl && !urlBad(st.capturedUrl)) url = st.capturedUrl;
                    if (!url) {
                        var tl = trackListUrl();
                        if (tl && !urlBad(tl)) url = tl;
                    }
                    if (!url) return; // retry next second
                    st.fetching = true;
                    var fetched = url;
                    var fetchedVid = st.vid;
                    fetch(fetched, { credentials: 'same-origin' }).then(function(r) {
                        return r.ok ? r.text() : '';
                    }).then(function(body) {
                        st.fetching = false;
                        if (st.mode === 'cues' || st.vid !== fetchedVid) return;
                        if (!body) { st.badUrls[fetched] = Date.now(); return; }
                        var cues = null;
                        try { cues = parseJson3(body); } catch (e) { cues = null; }
                        if (cues === null || !cues.length) {
                            var xml = parseXmlTimedtext(body);
                            if (xml.length) cues = xml;
                        }
                        if (cues && cues.length) {
                            cues.sort(function(a, b) { return a.start - b.start; });
                            st.cues = cues;
                            st.mode = 'cues'; // once-and-permanently for this video
                            console.log('[TapLink-DimCC] timedtext locked: ' + cues.length +
                                ' cues (pot=' + (fetched.indexOf('pot=') !== -1) + ')');
                        } else {
                            // Empty document (e.g. untokenized fetch) — retry,
                            // never lock the engine out of timedtext.
                            st.badUrls[fetched] = Date.now();
                        }
                    }).catch(function() {
                        st.fetching = false;
                    });
                }

                // ── DOM-scrape mode ─────────────────────────────────────────
                function scrapeDomLines() {
                    var lines = [];
                    function pushLine(t) {
                        t = clean(t);
                        if (t && lines[lines.length - 1] !== t) lines.push(t);
                    }
                    // Primary: the renderer's real per-line elements.
                    var nodes = document.querySelectorAll(
                        '.ytp-caption-window-container .caption-visual-line, ' +
                        '.caption-window .caption-visual-line, ' +
                        '.ytm-caption-window-container .caption-visual-line');
                    if (nodes.length) {
                        for (var i = 0; i < nodes.length; i++) {
                            pushLine(nodes[i].textContent || nodes[i].innerText || '');
                        }
                        return lines;
                    }
                    // Layout variant: bare segments with no visual-line wrapper.
                    var segs = document.querySelectorAll('.ytp-caption-segment');
                    if (segs.length) {
                        var joined = '';
                        for (var j = 0; j < segs.length; j++) {
                            joined += ' ' + (segs[j].textContent || segs[j].innerText || '');
                        }
                        pushLine(joined);
                        return lines;
                    }
                    // Last resort: the whole caption window flattened.
                    var wins = document.querySelectorAll(
                        '.ytp-caption-window-container, .ytm-caption-window-container, .caption-window');
                    for (var k = 0; k < wins.length; k++) {
                        pushLine(wins[k].textContent || wins[k].innerText || '');
                        if (lines.length) break;
                    }
                    return lines;
                }

                function tickDom(now) {
                    var lines = scrapeDomLines();
                    if (!lines.length) return; // expiry handled by CLEAR_AFTER_MS
                    if (lines.length >= 2) {
                        // The renderer already rolls — mirror its last two lines.
                        st.top = lines[lines.length - 2];
                        st.bot = lines[lines.length - 1];
                        st.lastSeenAt = now;
                        return;
                    }
                    // 640px players render only ONE caption line — synthesize
                    // the roll ourselves.
                    var text = lines[0];
                    if (text === st.bot) {
                        st.lastSeenAt = now;
                    } else if (st.bot && text.indexOf(st.bot) === 0) {
                        st.bot = text; // append-only growth of the current utterance
                        st.lastSeenAt = now;
                    } else if (st.bot && st.bot.indexOf(text) === 0) {
                        st.lastSeenAt = now; // transient shrink artifact — hold
                    } else {
                        if (st.bot) st.top = st.bot; // roll up (CEA-608)
                        st.bot = text;
                        st.lastSeenAt = now;
                    }
                }

                // ── timedtext (cues) mode ───────────────────────────────────
                function cueTextAt(cue, t) {
                    var out = '';
                    for (var i = 0; i < cue.segs.length; i++) {
                        if (cue.segs[i].at <= t) out += cue.segs[i].text;
                    }
                    return clean(out) || cue.text;
                }

                function tickCues(now) {
                    var v = document.querySelector('video');
                    if (!v || !st.cues) return;
                    var t = v.currentTime * 1000 + CC_LEAD_MS;
                    var active = [];
                    for (var i = 0; i < st.cues.length; i++) {
                        var c = st.cues[i];
                        if (c.start > t) break;
                        if (t < c.end) active.push(c);
                    }
                    if (!active.length) return; // expiry handled by CLEAR_AFTER_MS
                    var cur = active[active.length - 1];
                    if (cur !== st.curCue) {
                        // New cue: settled history is the fully-grown previous
                        // cue (overlapping ASR cues keep both lines on screen).
                        if (active.length > 1) {
                            st.top = clean(active[active.length - 2].text);
                        } else if (st.bot) {
                            st.top = st.bot;
                        }
                        st.curCue = cur;
                    }
                    st.bot = cueTextAt(cur, t); // append-only growth (word timing)
                    st.lastSeenAt = now;
                }

                // ── single writer ───────────────────────────────────────────
                function compose() {
                    if (!st.top && !st.bot) return '';
                    var top = clip(st.top);
                    var bot = clip(st.bot);
                    return top ? top + '\n' + bot : bot;
                }

                function tick() {
                    try {
                        var vid = videoIdOf(location.href);
                        if (vid !== st.vid) resetForVideo(vid);
                        var now = Date.now();
                        if (st.mode === 'cues') {
                            tickCues(now);
                        } else {
                            tickDom(now);
                            if (now - st.lastDiscoveryAt >= DISCOVERY_MS) {
                                st.lastDiscoveryAt = now;
                                discover();
                            }
                        }
                        if ((st.top || st.bot) && st.lastSeenAt &&
                            now - st.lastSeenAt > CLEAR_AFTER_MS) {
                            st.top = '';
                            st.bot = '';
                            st.curCue = null;
                        }
                        var text = compose();
                        if (text !== st.lastPushed) {
                            st.lastPushed = text;
                            st.lastPushAt = now;
                            push(text);
                        } else if (text && now - st.lastPushAt >= HEARTBEAT_MS) {
                            // Quiet heartbeat: keeps long captions from blinking
                            // away under freshness windows downstream.
                            st.lastPushAt = now;
                            push(text);
                        }
                    } catch (e) {}
                }

                setInterval(tick, TICK_MS);
                console.log('[TapLink-DimCC] engine installed');
            } catch (e) {
                try { console.log('[TapLink-DimCC] install failed: ' + e); } catch (e2) {}
            }
        })();
        """.trimIndent()
}
