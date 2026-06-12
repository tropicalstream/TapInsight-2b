package com.rayneo.visionclaw.ui.panels.chat

import android.util.Log
import java.util.Locale
import java.util.regex.Pattern

/**
 * Extracts line-start URL entries from chat-card text and pairs each with
 * a one-or-two-line summary that sits below it. Used by:
 *   • ChatAdapter rendering — replaces line-start URLs with bold tappable
 *     display titles + summary.
 *   • MainActivity voice resolver — "open the Nth link", "open the X link",
 *     "open all the links" against the focused card's extracted list.
 *   • TTS readout strip — when the user enables "strip URLs from voice
 *     readout", this is the canonical extractor that decides which spans
 *     to remove from the spoken text.
 *
 * Detection rules (matches the spec discussed in the discovery-gate doc):
 *   • A URL counts as line-start if it appears at the start of its line,
 *     optionally after a list marker: `1.`, `1)`, `-`, `*`, or `•`.
 *   • Mid-prose URLs are deliberately ignored — they keep the existing
 *     inline cyan highlight in the chat bubble.
 *   • Summary is the next non-blank line(s) until the next blank line or
 *     the next line-start URL, capped at ~280 chars for readability.
 *   • URLs are canonicalized (lowercase host, drop trailing slash, strip
 *     `utm_*` and `fbclid` params) and deduplicated. The canonical form
 *     is what the voice resolver matches against.
 */
object CardUrlExtractor {

    /**
     * What kind of media this URL points at. Drives the type-aware routing
     * the voice resolver and trackpad tap handler use to decide WHERE the
     * URL opens — browser, audio player, text reader, etc.
     *
     * Inferred via [classifyMediaType] from a combination of:
     *   • Explicit `(type:X)` / `[type:X]` annotations in the bullet text
     *     (preferred — Gemini RULE 19 G clause and the OpenClaw prompt
     *     instruct both to emit these).
     *   • URL host + path patterns (fallback when no annotation is given).
     *   • Bullet-text keywords like "MP3", "podcast", "video", "article".
     */
    enum class MediaType {
        VIDEO, PODCAST, AUDIO, ARTICLE, PDF, IMAGE, WEB, UNKNOWN;

        /** User-facing label used in the routing-confirmation heartbeat. */
        fun userLabel(): String = when (this) {
            VIDEO -> "video"
            PODCAST -> "podcast"
            AUDIO -> "audio"
            ARTICLE -> "article"
            PDF -> "PDF"
            IMAGE -> "image"
            WEB -> "web page"
            UNKNOWN -> "link"
        }
    }

    /** A line-start URL entry plus the offsets we need to manipulate the
     *  card text (replace URL with display title, drop or keep summary). */
    data class Entry(
        /** The URL exactly as it appeared in the source text (after trimming
         *  trailing punctuation). What we hand to `open_taplink`. */
        val rawUrl: String,
        /** Canonicalized form — used for dedupe and voice-keyword matching. */
        val canonicalUrl: String,
        /** The display title we render in the bold header. Tools that supply
         *  a title can include it on the same line or in markdown
         *  `[title](url)` form; otherwise we derive `host + path`. */
        val displayTitle: String,
        /** The summary text that follows the URL line. Empty if none. */
        val summary: String,
        /** Char offset where the URL token starts in the original text. */
        val urlStart: Int,
        /** Char offset where the URL token ends. */
        val urlEnd: Int,
        /** Char offset where the entire entry (line-marker through end of
         *  summary block) starts in the original text. */
        val entryStart: Int,
        /** Char offset where the entry ends (one past the last summary char). */
        val entryEnd: Int,
        /** Inferred media type — what kind of content the URL points at.
         *  WEB by default; the classifier promotes it when there's a clear
         *  URL host pattern, an explicit `(type:X)` tag, or a strong
         *  keyword in the bullet text. Used by the voice resolver and
         *  trackpad-tap handler to route the open action and to surface a
         *  type-confirmation heartbeat. */
        val mediaType: MediaType = MediaType.WEB
    )

    /**
     * Scan [text] and return every line-start URL entry, in source order,
     * deduplicated by canonical URL. Returns empty list when no line-start
     * URLs exist (the chat card then renders as today).
     */
    fun extract(text: String): List<Entry> {
        if (text.isBlank()) return emptyList()
        val lines = splitWithOffsets(text)
        if (lines.isEmpty()) return emptyList()

        val out = ArrayList<Entry>()
        val seenCanonical = HashSet<String>()
        // Track the most recent markdown section header (e.g.
        // "**Active Archive / Festival Sites**") so URL-bearing bullets
        // beneath it can prepend it to their summary for context.
        var currentSection: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val raw = line.content

            // Update the running section header if this line is a standalone
            // markdown bold heading like "**Active Archive / Festival Sites**".
            val sectionMatch = SECTION_HEADER_PATTERN.matcher(raw)
            if (sectionMatch.find()) {
                currentSection = sectionMatch.group(1)?.trim()?.takeIf { it.isNotBlank() }
                i++
                continue
            }

            // Find any URL on this line. We try multiple patterns in priority
            // order so common Gemini formats are all caught:
            //   1. Line-start URL (with optional list marker / markdown link)
            //   2. List bullet ending with description + URL
            //      e.g. "- sfSound archive: https://www.sfsound.org/"
            //   3. Markdown bold-link bullet
            //      e.g. "- **[KPFA Avant-Garde](https://kpfa.org)**"
            //   4. Standalone markdown link line
            //      e.g. "[KPFA Avant-Garde](https://kpfa.org)"
            //
            // URLs that appear mid-prose (no list marker, no markdown link
            // wrapper, no leading colon-like delimiter) are NOT extracted
            // here — they keep the existing inline cyan colour and don't
            // get the bold-header treatment.
            val parsed = parseUrlBearingLine(raw)
            if (parsed == null) {
                i++
                continue
            }

            val cleanedUrl = trimUrlTrailingPunct(parsed.rawUrl)
            val canonical = canonicalize(cleanedUrl)

            // Collect summary. Three tiers, in order of preference:
            //   (a) Inline summary from the SAME line (text before/after the
            //       URL with markdown markers stripped) — this is the
            //       common Gemini bullet shape.
            //   (b) Next 1–2 non-blank, non-URL-bearing lines.
            //   (c) Empty.
            val inlineSummary = parsed.inlineSummary.trim()
            val summaryBuilder = StringBuilder()
            if (inlineSummary.isNotEmpty()) {
                summaryBuilder.append(inlineSummary)
            }
            var summaryEndLineIdx = i
            var summaryLineCount = 0
            if (inlineSummary.isEmpty()) {
                var j = i + 1
                while (j < lines.size && summaryLineCount < SUMMARY_MAX_LINES) {
                    val candidate = lines[j]
                    if (candidate.content.isBlank()) break
                    if (parseUrlBearingLine(candidate.content) != null) break
                    if (SECTION_HEADER_PATTERN.matcher(candidate.content).find()) break
                    if (summaryBuilder.isNotEmpty()) summaryBuilder.append(' ')
                    summaryBuilder.append(candidate.content.trim())
                    summaryEndLineIdx = j
                    summaryLineCount++
                    j++
                }
            }
            // Tag the section context onto the summary if available so the
            // rendered card carries the grouping the model intended.
            val rawSummary = summaryBuilder.toString().trim()
            val summaryWithSection = if (currentSection.isNullOrBlank() ||
                rawSummary.contains(currentSection!!)
            ) {
                rawSummary
            } else {
                "$rawSummary  ·  ${currentSection}"
            }

            // Classify before stripping the type tag so the explicit hint
            // from Gemini / OpenClaw is preferred over URL heuristics.
            val mediaType = classifyMediaType(cleanedUrl, summaryWithSection)
            val summary = stripTypeAnnotations(summaryWithSection)
                .take(SUMMARY_MAX_CHARS)

            val displayTitle = parsed.displayTitle?.takeIf { it.isNotBlank() }
                ?.let { stripTypeAnnotations(it) }
                ?: pickDisplayTitle(null, cleanedUrl)
            val absUrlStart = line.start + parsed.urlStartInLine
            val absUrlEnd = line.start + parsed.urlEndInLine
            val entryStart = line.start
            val entryEnd = if (summaryLineCount > 0) {
                lines[summaryEndLineIdx].end
            } else {
                line.end
            }

            if (seenCanonical.add(canonical)) {
                // URLPipe diagnostic: every URL the extractor emits is logged
                // here so a regression run can reconstruct what the chat card
                // saw vs. what eventually got handed to the launcher. Tag is
                // grep-friendly: `URLPipe/extract`.
                if (parsed.rawUrl != cleanedUrl || cleanedUrl != canonical) {
                    Log.d(
                        TAG,
                        "URLPipe/extract raw='${parsed.rawUrl}' " +
                            "trimmed='$cleanedUrl' canonical='$canonical' " +
                            "type=${mediaType.name} title='${displayTitle.take(60)}'"
                    )
                } else {
                    Log.d(
                        TAG,
                        "URLPipe/extract url='$cleanedUrl' type=${mediaType.name} " +
                            "title='${displayTitle.take(60)}'"
                    )
                }
                out += Entry(
                    rawUrl = cleanedUrl,
                    canonicalUrl = canonical,
                    displayTitle = displayTitle,
                    summary = summary,
                    urlStart = absUrlStart,
                    urlEnd = absUrlEnd,
                    entryStart = entryStart,
                    entryEnd = entryEnd,
                    mediaType = mediaType
                )
            }
            i = if (summaryLineCount > 0) summaryEndLineIdx + 1 else i + 1
        }
        return out
    }

    /** Single line's parse result for any of the URL-bearing shapes we accept. */
    private data class ParsedLine(
        val rawUrl: String,
        val displayTitle: String?,
        val inlineSummary: String,
        val urlStartInLine: Int,
        val urlEndInLine: Int
    )

    /**
     * Try to extract a URL entry from a single line. Recognises:
     *   • Plain line-start URL: `https://example.com`
     *   • List marker + URL: `1. https://...`, `- https://...`, `* https://...`, `• https://...`
     *   • Markdown link: `[Title](https://...)` (with optional surrounding markers)
     *   • Bullet with URL anywhere: `- description text: https://...` or
     *     `1. description (https://...)`
     *   • `Section title: https://...` (line begins with descriptive text
     *     ending in `:` or `—`)
     * Returns null when the line isn't URL-bearing, or when a URL exists but
     * the surrounding context is mid-prose (no list marker, no markdown
     * wrapper, no leading delimiter).
     */
    private fun parseUrlBearingLine(line: String): ParsedLine? {
        if (line.isBlank()) return null

        // Find any URL in the line.
        val urlMatcher = URL_TOKEN_PATTERN.matcher(line)
        if (!urlMatcher.find()) return null
        val rawUrl = urlMatcher.group()
        val urlStart = urlMatcher.start()
        val urlEnd = urlMatcher.end()

        // Examine the prefix to decide whether this is a list-shaped line vs
        // mid-prose. We accept the line if any of these are true:
        //   (1) Prefix is empty/whitespace.
        //   (2) Prefix matches optional whitespace + list marker + optional
        //       text (with markdown bold markers stripped).
        //   (3) Prefix is a markdown link opener `[Title](`.
        val rawPrefix = line.substring(0, urlStart)
        val prefix = rawPrefix.trim()

        // Markdown link `[Title](https://...)` — capture title from prefix.
        if (prefix.endsWith("](")) {
            val openIdx = prefix.lastIndexOf('[')
            if (openIdx >= 0) {
                val title = prefix.substring(openIdx + 1, prefix.length - 2).trim()
                // Whatever sits BEFORE the `[Title](` (e.g. `- ` or `**`) is
                // treated as inline summary context only when there's text
                // after the closing `)` later in the line — typically there
                // isn't, so default to empty.
                val rawSuffix = line.substring(urlEnd)
                val suffix = stripMarkdownNoise(rawSuffix.trimStart(')').trim())
                return ParsedLine(rawUrl, title, suffix, urlStart, urlEnd)
            }
        }

        // List-bullet prefix: line starts with `1.` / `-` / `*` / `•` — the
        // descriptive text between marker and URL becomes the inline summary.
        // Also accepted: prefix is empty (URL on its own line, with optional
        // leading whitespace).
        if (prefix.isEmpty() || LIST_MARKER_AT_START_PATTERN.matcher(rawPrefix).find()) {
            val rawSuffix = line.substring(urlEnd)
            val markerStripped = stripListMarker(rawPrefix)
            val pre = stripMarkdownNoise(markerStripped.trimEnd(':', '—', '-', '–', '(', ' ').trim())
            val post = stripMarkdownNoise(rawSuffix.trimStart(')', ' ').trim())
            val inline = listOfNotNull(
                pre.takeIf { it.isNotBlank() },
                post.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            return ParsedLine(rawUrl, null, inline, urlStart, urlEnd)
        }

        // "Title: https://..." shape (no list marker, but a clear
        // delimiter telling us it's labeled link, not running prose).
        if (prefix.endsWith(":") || prefix.endsWith("—") || prefix.endsWith("–")) {
            val title = stripMarkdownNoise(prefix.trimEnd(':', '—', '–', ' ').trim())
                .takeIf { it.isNotBlank() && it.length <= 80 }
            if (title != null) {
                val rawSuffix = line.substring(urlEnd).trim()
                return ParsedLine(rawUrl, title, stripMarkdownNoise(rawSuffix), urlStart, urlEnd)
            }
        }

        // Otherwise the URL is mid-prose; keep it inline-only.
        return null
    }

    /**
     * Explicit media-type tag pattern. Matches `(type:podcast)`,
     * `[type:video]`, `(media:article)`, `[media:pdf]` — case-insensitive,
     * with optional whitespace inside the brackets. Captures the type name.
     * Both Gemini RULE 19 G and the OpenClaw prompt (see
     * tasks/openclaw-tapinsight-prompt.md) instruct emitters to attach
     * one of these to every URL list entry. The classifier picks this up
     * before falling back to URL/keyword heuristics.
     */
    private val TYPE_ANNOTATION_PATTERN: Pattern = Pattern.compile(
        "[(\\[]\\s*(?:type|media)\\s*[:=]\\s*([a-z]+)\\s*[)\\]]",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Best-guess media-type classifier for a URL+summary pair. Resolution
     * order, most authoritative first:
     *   1. Explicit `(type:X)` annotation in the summary (RULE 19 G).
     *   2. URL host + path patterns (YouTube, Spotify, Wikipedia, …).
     *   3. Keyword scan of the bullet description ("MP3", "podcast",
     *      "video", "article", "PDF", "interview").
     *   4. Default WEB.
     */
    fun classifyMediaType(url: String, summary: String): MediaType {
        // 1. Explicit annotation wins.
        TYPE_ANNOTATION_PATTERN.matcher(summary).let { m ->
            if (m.find()) {
                val tag = m.group(1)?.lowercase(Locale.US).orEmpty()
                tagToMediaType(tag)?.let { return it }
            }
        }

        val lowerUrl = url.lowercase(Locale.US)
        val lowerSummary = summary.lowercase(Locale.US)

        // 2. URL host / path patterns.
        when {
            lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") ||
                lowerUrl.contains("vimeo.com") || lowerUrl.contains("dailymotion.com") ->
                return MediaType.VIDEO

            lowerUrl.contains("podbean.com") || lowerUrl.contains("ivoox.com") ||
                lowerUrl.contains("podcasts.apple.com") ||
                lowerUrl.contains("apple.co/podcast") ||
                lowerUrl.contains("open.spotify.com/episode") ||
                lowerUrl.contains("open.spotify.com/show") ||
                lowerUrl.contains(".libsyn.com") ||
                lowerUrl.contains("anchor.fm") ||
                lowerUrl.contains("buzzsprout.com") ||
                lowerUrl.contains("simplecast.com") ->
                return MediaType.PODCAST

            lowerUrl.endsWith(".mp3") || lowerUrl.endsWith(".m4a") ||
                lowerUrl.endsWith(".wav") || lowerUrl.endsWith(".ogg") ||
                lowerUrl.endsWith(".flac") ||
                lowerUrl.contains("/stream") && (lowerUrl.contains(".mp3") || lowerUrl.contains("audio")) ->
                return MediaType.AUDIO

            lowerUrl.endsWith(".pdf") || lowerUrl.contains("arxiv.org/pdf/") ->
                return MediaType.PDF

            lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") || lowerUrl.endsWith(".gif") ||
                lowerUrl.endsWith(".webp") || lowerUrl.endsWith(".svg") ->
                return MediaType.IMAGE

            lowerUrl.contains("wikipedia.org/wiki/") ||
                lowerUrl.contains("medium.com/") ||
                lowerUrl.contains(".substack.com/p/") ||
                lowerUrl.contains("nytimes.com/") ||
                lowerUrl.contains("theguardian.com/") ||
                lowerUrl.contains("washingtonpost.com/") ||
                lowerUrl.contains("newyorker.com/") ||
                lowerUrl.contains("theatlantic.com/") ||
                lowerUrl.contains("/blog/") || lowerUrl.contains("/article/") ||
                lowerUrl.contains("/articles/") ->
                return MediaType.ARTICLE
        }

        // 3. Bullet-text keyword fallback. We give podcast/audio strong
        // priority because TapClaw's typical "(Podbean MP3)" annotation
        // won't be matched by URL heuristics for less-common hosts.
        if (containsAnyWord(lowerSummary, "podcast", "podcasts", "podcasting")) {
            return MediaType.PODCAST
        }
        if (containsAnyWord(lowerSummary, "mp3", "audio interview", "spoken word")) {
            return MediaType.AUDIO
        }
        if (containsAnyWord(lowerSummary, "video", "youtube", "vimeo", "watch")) {
            return MediaType.VIDEO
        }
        if (containsAnyWord(lowerSummary, "pdf")) {
            return MediaType.PDF
        }
        if (containsAnyWord(lowerSummary, "article", "essay", "blog", "post", "interview", "review", "feature")) {
            return MediaType.ARTICLE
        }

        // 4. Default — generic web page; the browser handles it.
        return MediaType.WEB
    }

    private fun tagToMediaType(tag: String): MediaType? = when (tag) {
        "video", "videos", "movie", "youtube" -> MediaType.VIDEO
        "podcast", "podcasts" -> MediaType.PODCAST
        "audio", "mp3", "music", "song", "track" -> MediaType.AUDIO
        "article", "blog", "essay", "post", "news" -> MediaType.ARTICLE
        "pdf" -> MediaType.PDF
        "image", "img", "photo", "picture" -> MediaType.IMAGE
        "web", "page", "site", "link", "url" -> MediaType.WEB
        else -> null
    }

    private fun containsAnyWord(haystack: String, vararg needles: String): Boolean {
        for (n in needles) {
            if (Regex("\\b${Regex.escape(n)}\\b").containsMatchIn(haystack)) return true
        }
        return false
    }

    /**
     * Remove explicit `(type:X)` / `[type:X]` annotations from a string so
     * they don't appear in the rendered chat card. We classify first, then
     * strip — so the explicit hint stays useful internally without leaking
     * into the user-facing text.
     */
    private fun stripTypeAnnotations(text: String): String {
        if (text.isEmpty()) return text
        val cleaned = TYPE_ANNOTATION_PATTERN.matcher(text).replaceAll("")
        return cleaned.replace(Regex("\\s{2,}"), " ").trim()
    }

    private fun stripMarkdownNoise(s: String): String {
        return s
            .replace(Regex("""\*\*([^*\n]+?)\*\*"""), "$1")
            .replace(Regex("""__([^_\n]+?)__"""), "$1")
            .replace(Regex("""\*([^*\n]+?)\*"""), "$1")
            .replace(Regex("""_([^_\n]+?)_"""), "$1")
            .replace(Regex("""`+"""), "")
            .trim()
    }

    private fun stripListMarker(prefix: String): String {
        // Removes leading list markers — the same set we accept in
        // LIST_MARKER_AT_START_PATTERN — and returns the remaining descriptive
        // text. Examples:
        //   "1. " → ""
        //   "- sfSound archive of recordings:" → "sfSound archive of recordings:"
        //   "* **KPFA Avant-Garde** —" → "**KPFA Avant-Garde** —"
        return prefix.replaceFirst(Regex("""^\s*(?:\d+[.)]|[-*•])\s*"""), "").trim()
    }

    /**
     * Voice resolver helper. Resolve a user transcript like "open the first
     * link" / "open the KPFA one" / "open all the links" against [entries].
     * Returns the matching [Entry] (or list of all entries for "all").
     */
    fun resolveVoiceIntent(transcript: String, entries: List<Entry>): VoiceIntent? {
        if (entries.isEmpty()) return null
        val lower = transcript.lowercase(Locale.US).trim()
        if (!URL_INTENT_VERB_PATTERN.matcher(lower).find()) return null

        // "open all the links" / "open all of them" / "open them all"
        if (URL_INTENT_ALL_PATTERN.matcher(lower).find()) {
            return VoiceIntent.OpenAll(entries)
        }

        // "open the first/second/third/.../tenth link/one"
        ORDINAL_TO_INDEX.entries.firstOrNull { (word, _) ->
            Pattern.compile("\\b$word\\b").matcher(lower).find()
        }?.let { (_, idx) ->
            entries.getOrNull(idx)?.let { return VoiceIntent.OpenOne(it) }
        }

        // "open link 3" / "open the 3rd"
        URL_INTENT_NUMERIC_PATTERN.matcher(lower).let { m ->
            if (m.find()) {
                val n = m.group(1)?.toIntOrNull() ?: 0
                entries.getOrNull(n - 1)?.let { return VoiceIntent.OpenOne(it) }
            }
        }

        // Keyword fuzzy match against displayTitle and host. Pick the best
        // overlap above a reasonable threshold so "open the kpfa one" hits
        // the kpfa.org entry.
        val best = entries.maxByOrNull { entry ->
            keywordOverlapScore(lower, entry)
        }
        if (best != null && keywordOverlapScore(lower, best) >= KEYWORD_MIN_SCORE) {
            return VoiceIntent.OpenOne(best)
        }
        return null
    }

    /**
     * Strip line-start URL entries from [text] for TTS readout when the user
     * has enabled "strip URLs from voice readout" in the companion app.
     * Removes the URL line and its summary block (the pair counts as one
     * unit — reading the summary without the URL would be misleading).
     * Mid-prose URLs are not affected here; the standard cleanReadoutText
     * regex already handles those.
     */
    fun stripEntriesForReadout(text: String, entries: List<Entry>): String {
        if (entries.isEmpty()) return text
        val sorted = entries.sortedByDescending { it.entryStart }
        val sb = StringBuilder(text)
        for (e in sorted) {
            // Be defensive in case offsets drifted due to upstream edits.
            if (e.entryStart < 0 || e.entryEnd > sb.length || e.entryStart >= e.entryEnd) continue
            sb.delete(e.entryStart, e.entryEnd)
        }
        return sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Voice resolver result. */
    sealed class VoiceIntent {
        data class OpenOne(val entry: Entry) : VoiceIntent()
        data class OpenAll(val entries: List<Entry>) : VoiceIntent()
    }

    // ── Internals ────────────────────────────────────────────────────────

    private const val TAG = "CardUrlExtractor"
    private const val SUMMARY_MAX_LINES = 2
    private const val SUMMARY_MAX_CHARS = 280
    private const val KEYWORD_MIN_SCORE = 1

    /**
     * Generic URL token. Used by [parseUrlBearingLine] to find the URL on a
     * single line; the shape decision is made by inspecting the surrounding
     * context separately (we don't want to over-match mid-prose URLs).
     */
    private val URL_TOKEN_PATTERN: Pattern = Pattern.compile(
        "(?:https?://|www\\.)[^\\s<>\"')]+",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * True when the line starts with a list marker — `1.`, `1)`, `-`, `*`, `•`.
     * The descriptive text between the marker and the URL is unconstrained:
     * we keep all bullet-shaped lines as accepted, then the parser cleans up
     * the descriptive text separately. This is broader than the previous
     * approach (which required a `:` / `—` delimiter before the URL) so it
     * also catches `- sfSound archive https://www.sfsound.org/` and
     * `1. KPFA Avant-Garde https://kpfa.org/program`.
     */
    private val LIST_MARKER_AT_START_PATTERN: Pattern = Pattern.compile(
        "^\\s*(?:\\d+[.)]|[-*•])\\s*"
    )

    /**
     * Standalone markdown bold heading line — used to track section context
     * so URL-bearing bullets beneath it can attach the section name to their
     * summary. Examples:
     *   • `**Active Archive / Festival Sites**`
     *   • `**References**`
     */
    private val SECTION_HEADER_PATTERN: Pattern = Pattern.compile(
        "^\\s*\\*\\*([^*\\n]+?)\\*\\*\\s*:?$"
    )

    private val URL_INTENT_VERB_PATTERN: Pattern = Pattern.compile(
        "\\b(?:open|show|pull\\s+up|launch|go\\s+to|visit|view|click)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val URL_INTENT_ALL_PATTERN: Pattern = Pattern.compile(
        "\\b(?:all\\s+(?:the\\s+)?(?:links|urls|of\\s+them)|them\\s+all|every\\s+(?:link|url))\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val URL_INTENT_NUMERIC_PATTERN: Pattern = Pattern.compile(
        "\\b(?:link|url|one|item)\\s+(?:number\\s+)?(\\d{1,2})\\b" +
            "|\\b(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:link|url|one|item)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val ORDINAL_TO_INDEX: Map<String, Int> = linkedMapOf(
        "first" to 0, "1st" to 0,
        "second" to 1, "2nd" to 1,
        "third" to 2, "3rd" to 2,
        "fourth" to 3, "4th" to 3,
        "fifth" to 4, "5th" to 4,
        "sixth" to 5, "6th" to 5,
        "seventh" to 6, "7th" to 6,
        "eighth" to 7, "8th" to 7,
        "ninth" to 8, "9th" to 8,
        "tenth" to 9, "10th" to 9,
        "last" to -1
    )

    /** Tracks each line's char range so we can map line-relative offsets
     *  back to absolute offsets in the source string. */
    private data class LineSpan(val content: String, val start: Int, val end: Int)

    private fun splitWithOffsets(text: String): List<LineSpan> {
        val out = ArrayList<LineSpan>(text.count { it == '\n' } + 1)
        var idx = 0
        while (idx <= text.length) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) {
                out += LineSpan(text.substring(idx), idx, text.length)
                break
            }
            out += LineSpan(text.substring(idx, nl), idx, nl + 1)
            idx = nl + 1
        }
        return out
    }

    private fun trimUrlTrailingPunct(raw: String): String {
        return raw.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?', '"', '\'')
    }

    private val TRACKING_PARAM_PATTERN: Pattern = Pattern.compile(
        "(?:^|&)(?:utm_[^=]+|fbclid|gclid|mc_[a-z]+)=[^&]*",
        Pattern.CASE_INSENSITIVE
    )

    private fun canonicalize(raw: String): String {
        val withProto = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
        // Strip tracking params.
        val qIdx = withProto.indexOf('?')
        val (base, query) = if (qIdx >= 0) {
            withProto.substring(0, qIdx) to withProto.substring(qIdx + 1)
        } else {
            withProto to ""
        }
        val cleanedQuery = if (query.isBlank()) {
            ""
        } else {
            TRACKING_PARAM_PATTERN.matcher(query).replaceAll("")
                .trimStart('&').trimEnd('&')
        }
        val rebuilt = if (cleanedQuery.isBlank()) base else "$base?$cleanedQuery"

        // Lowercase scheme + host, leave path alone (case can matter).
        val schemeIdx = rebuilt.indexOf("://")
        if (schemeIdx < 0) return rebuilt.trimEnd('/')
        val scheme = rebuilt.substring(0, schemeIdx).lowercase(Locale.US)
        val rest = rebuilt.substring(schemeIdx + 3)
        val firstSlash = rest.indexOf('/')
        val host = if (firstSlash >= 0) rest.substring(0, firstSlash) else rest
        val path = if (firstSlash >= 0) rest.substring(firstSlash) else ""
        val canonicalHost = host.lowercase(Locale.US)
        return ("$scheme://$canonicalHost$path").trimEnd('/')
    }

    private fun pickDisplayTitle(markdownTitle: String?, url: String): String {
        if (!markdownTitle.isNullOrBlank()) return markdownTitle.trim()
        // Derive host + a hint of path. Examples:
        //   https://kpfa.org/program/avant-garde → kpfa.org/program
        //   https://archive.org/details/foo      → archive.org/details
        //   https://www.nytimes.com              → nytimes.com
        val withProto = if (url.startsWith("http")) url else "https://$url"
        return runCatching {
            val schemeIdx = withProto.indexOf("://")
            val rest = withProto.substring(schemeIdx + 3)
            val firstSlash = rest.indexOf('/')
            val host = (if (firstSlash >= 0) rest.substring(0, firstSlash) else rest)
                .removePrefix("www.")
            if (firstSlash < 0) {
                host
            } else {
                val pathSegments = rest.substring(firstSlash).trim('/').split('/')
                if (pathSegments.isEmpty() || pathSegments[0].isBlank()) host
                else "$host/${pathSegments[0]}"
            }
        }.getOrDefault(url)
    }

    private fun keywordOverlapScore(lower: String, entry: Entry): Int {
        val tokens = lower.split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .map { it.trim(',', '.', ';', ':', '!', '?', '"', '\'') }
            .toSet()
        if (tokens.isEmpty()) return 0
        val haystack = (entry.displayTitle + " " + entry.canonicalUrl + " " + entry.summary)
            .lowercase(Locale.US)
        var score = 0
        for (t in tokens) {
            if (t in STOP_WORDS) continue
            if (haystack.contains(t)) score++
        }
        return score
    }

    private val STOP_WORDS = setOf(
        "the", "open", "show", "pull", "link", "url", "one", "item", "go",
        "to", "visit", "click", "and", "for", "with", "from", "that", "this",
        "please", "first", "second", "third", "fourth", "fifth", "sixth",
        "seventh", "eighth", "ninth", "tenth", "last", "next", "previous",
        "top", "bottom", "first", "list", "links", "urls", "all", "of"
    )
}
