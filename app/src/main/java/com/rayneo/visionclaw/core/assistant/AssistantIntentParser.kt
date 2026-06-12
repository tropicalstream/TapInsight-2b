package com.rayneo.visionclaw.core.assistant

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface AssistantIntent {
    data class OpenWeb(
        val url: String,
        val displayLabel: String
    ) : AssistantIntent

    data class Research(
        val topic: String
    ) : AssistantIntent

    data class Learn(
        val prompt: String,
        val topicHint: String
    ) : AssistantIntent
}

object AssistantIntentParser {
    data class YouTubePlaybackSpec(
        val items: List<String>,
        val mode: String
    )

    private val OPEN_PATTERNS = listOf(
        Regex("(?i)^\\s*(?:open|launch|go to|visit|browse to|take me to|show me)\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:open up)\\s+(.+?)\\s*$")
    )

    private val RESEARCH_PATTERNS = listOf(
        Regex("(?i)^\\s*research\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:please\\s+)?research\\s+(?:for me\\s+)?(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:do|run)\\s+research\\s+on\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:give me|do)\\s+a\\s+deep\\s+dive\\s+on\\s+(.+?)\\s*$"),
        Regex("(?i)^\\s*(?:analyze|brief me on)\\s+(.+?)\\s*$")
    )

    private val EXPLICIT_LEARN_PREFIX = Regex("(?i)^\\s*learnlm\\b[:\\-\\s]*(.*?)\\s*$")
    // Loose variant: matches "learn lm", "learn LM", "learn l.m.", etc.
    // Speech recognition often splits "learnlm" into separate words.
    private val LOOSE_LEARN_LM_PREFIX = Regex("(?i)^\\s*learn\\s+l\\.?m\\.?\\b[:\\-\\s]*(.*?)\\s*$")

    // ── Voice notes ──────────────────────────────────────────────────────
    // "note that …", "jot down …", "remember that …" → capture content.
    // Validated against a 19-case match/no-match suite before landing.
    private val NOTE_REQUEST_PATTERNS = listOf(
        Regex("""(?i)^(?:hey |ok(?:ay)? |please )*note (?:that|this|down)[:,\- ]+(.+)$"""),
        Regex("""(?i)^(?:hey |ok(?:ay)? |please )*(?:make|take|add|leave|write|jot) (?:me )?(?:a |an )?(?:quick )?notes?(?: (?:that|to|saying|about|down))?[:,\- ]+(.+)$"""),
        Regex("""(?i)^(?:hey |ok(?:ay)? |please )*jot (?:that|this)? ?down[:,\- ]+(.+)$"""),
        Regex("""(?i)^(?:hey |ok(?:ay)? |please )*write (?:that|this)? ?down[:,\- ]+(.+)$"""),
        Regex("""(?i)^(?:hey |ok(?:ay)? |please )*remember (?:that|this)[:,\- ]+(.+)$"""),
        Regex("""(?i)^(?:hey |ok(?:ay)? |please )*add (?:that )?to my notes?[:,\- ]+(.+)$""")
    )

    private val NOTES_RECALL = Regex(
        """(?i)\b(?:my|the)\s+notes?\b|\bwhat\s+(?:did|have)\s+i\s+note\b|\bnotes?\s+(?:about|on|for)\b|\bcheck\s+my\s+notes?\b|\bread\s+(?:me\s+)?my\s+notes?\b"""
    )

    // "what song is this / what's playing / who's the artist" — validated
    // against a 14-match / 8-reject suite (apostrophe optional for STT).
    private val IDENTIFY_SONG = Regex(
        """(?i)\b(?:what(?:'?s| is)\s+(?:this\s+|the\s+)?(?:song|track|tune|artist|band)|what\s+song\s+is\s+(?:this|playing|that)|name\s+(?:this|the|that)\s+(?:song|track|tune)|identify\s+(?:this|the|that)\s+(?:song|track|tune|music)|who(?:'?s| is|\s+sings|\s+sang)\s+(?:this|the\s+artist|singing)|what(?:'?s| is)\s+playing|song\s+name|what\s+am\s+i\s+listening\s+to)\b"""
    )

    private val STATUS_BRIEF_PATTERNS = listOf(
        Regex("(?i)^\\s*status\\s*$"),
        Regex("(?i)^\\s*status\\s+update\\s*$"),
        Regex("(?i)^\\s*give\\s+me\\s+(?:a\\s+)?status\\s+update\\s*$"),
        Regex("(?i)^\\s*what(?:'s|\\s+is)?\\s+my\\s+status\\s*$"),
        Regex("(?i)^\\s*give\\s+me\\s+(?:a|the)?\\s*brief\\s*$"),
        Regex("(?i)^\\s*brief\\s+me\\s*$"),
        Regex("(?i)^\\s*what(?:'s|\\s+is)?\\s+the\\s+brief\\s*$"),
        Regex("(?i)^\\s*give\\s+me\\s+my\\s+brief\\s*$")
    )

    private val YOUTUBE_PLAYBACK_PREFIX =
        Regex(
            "(?i)^\\s*(?:play|open|start|watch|pull\\s+up|put\\s+on|turn\\s+on|" +
                "queue(?:\\s+up)?|launch|show(?:\\s+me)?|bring\\s+up)\\b"
        )

    private val YOUTUBE_LOOKUP_PREFIX =
        Regex(
            "(?i)^\\s*(?:are|is|do|does|did|can|could|what|which|who|when|where|" +
                "why|how|any|find|search(?:\\s+for)?|look\\s+up|tell\\s+me(?:\\s+about)?|" +
                "recommend|suggest|latest|most\\s+recent|newest)\\b"
        )

    private val LEARN_CONTINUATION_PATTERNS = listOf(
        Regex("(?i)^\\s*(?:continue|keep going|go on|next step|what should i try next)\\s*(?:on|with)?\\s*(?:the\\s+)?(?:previous|same)?\\s*(?:problem|lesson|topic)?\\s*$"),
        Regex("(?i)^\\s*(?:continue|pick up)\\s+(?:where we left off|from before|the previous problem|the last lesson)\\s*$"),
        Regex("(?i)^\\s*(?:help me with|teach me)\\s+(?:the next step|the next part|the same problem)\\s*$")
    )

    private val DOMAIN_REGEX =
        Regex("(?i)\\b((?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?:/\\S*)?)")

    private val LOCAL_APP_TARGETS = setOf(
        "settings",
        "chat",
        "calendar",
        "camera",
        "radio",
        "tapradio",
        "browser",
        "tapbrowser",
        "hud",
        "dashboard"
    )

    private val KNOWN_SITES = linkedMapOf(
        "cnn" to "https://www.cnn.com",
        "bbc" to "https://www.bbc.com",
        "wikipedia" to "https://www.wikipedia.org",
        "youtube" to "https://www.youtube.com",
        "reddit" to "https://www.reddit.com",
        "github" to "https://github.com",
        "x" to "https://x.com",
        "twitter" to "https://x.com",
        "gmail" to "https://mail.google.com",
        "google" to "https://www.google.com",
        "google maps" to "https://maps.google.com",
        "maps" to "https://maps.google.com",
        "google calendar" to "https://calendar.google.com",
        "calendar" to "https://calendar.google.com",
        "khan academy" to "https://www.khanacademy.org",
        "nasa" to "https://www.nasa.gov",
        "stack overflow" to "https://stackoverflow.com",
        "stackoverflow" to "https://stackoverflow.com",
        "openai" to "https://openai.com",
        "rayneo" to "https://www.rayneo.com"
    )

    fun parse(text: String): AssistantIntent? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        parseResearch(trimmed)?.let { return it }
        parseLearn(trimmed)?.let { return it }
        parseOpenWeb(trimmed)?.let { return it }
        return null
    }

    fun isExplicitLearnRequest(text: String): Boolean {
        val t = text.trim()
        return EXPLICIT_LEARN_PREFIX.matches(t) || LOOSE_LEARN_LM_PREFIX.matches(t)
    }

    /** Loose check only — matches "learn LM ..." from speech recognition */
    fun isLooseLearnLmPrefix(text: String): Boolean =
        LOOSE_LEARN_LM_PREFIX.matches(text.trim())

    fun extractExplicitLearnPrompt(text: String): String? {
        return (EXPLICIT_LEARN_PREFIX.find(text) ?: LOOSE_LEARN_LM_PREFIX.find(text))
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trimEnd('.', '?', '!')
    }

    fun isStatusBriefingRequest(text: String): Boolean {
        val trimmed = text.trim().trimEnd('.', '?', '!')
        if (trimmed.isBlank()) return false
        return STATUS_BRIEF_PATTERNS.any { it.matches(trimmed) }
    }

    /** If [transcript] is a "note that / jot down / remember that …" request,
     *  returns the note content with the trigger stripped; else null. */
    fun parseNoteRequest(transcript: String): String? {
        val t = transcript.trim()
        if (t.isBlank()) return null
        for (p in NOTE_REQUEST_PATTERNS) {
            val m = p.find(t) ?: continue
            val content = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (content.isNotBlank()) return content
        }
        return null
    }

    /** True when a request is asking about the user's saved notes, so the
     *  glasses should inject the on-device notes file as agent context. */
    fun isNotesRecallRequest(transcript: String): Boolean =
        NOTES_RECALL.containsMatchIn(transcript.trim())

    /** True when the user is asking to identify the currently-playing song. */
    fun isIdentifySongRequest(transcript: String): Boolean =
        IDENTIFY_SONG.containsMatchIn(transcript.trim())

    fun hasExplicitYouTubePlaybackVerb(text: String): Boolean {
        val trimmed = text.trim().trimEnd('.', '?', '!')
        if (trimmed.isBlank()) return false
        return YOUTUBE_PLAYBACK_PREFIX.containsMatchIn(trimmed)
    }

    fun isYouTubeLookupRequest(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (hasExplicitYouTubePlaybackVerb(trimmed)) return false

        val lower = trimmed.lowercase(Locale.US)
        val mentionsYouTubeOrVideos =
            lower.contains("youtube") ||
                lower.contains("video") ||
                lower.contains("videos")
        if (!mentionsYouTubeOrVideos) return false

        return trimmed.endsWith("?") ||
            YOUTUBE_LOOKUP_PREFIX.containsMatchIn(trimmed) ||
            lower.contains("most recent") ||
            lower.contains("latest") ||
            lower.contains("newest")
    }

    fun parseExplicitYouTubePlaybackRequest(text: String): YouTubePlaybackSpec? {
        val trimmed = text.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null
        if (isYouTubeLookupRequest(trimmed)) return null

        val lower = trimmed.lowercase(Locale.US)
        val explicitPlayback = hasExplicitYouTubePlaybackVerb(trimmed)
        val terseYouTubeCommand =
            lower.startsWith("youtube music ") ||
                lower.startsWith("youtube songs ") ||
                lower.startsWith("youtube videos ") ||
                lower.startsWith("youtube video ")
        val mentionsYouTube = lower.contains("youtube") || lower.startsWith("yt ")
        if (!terseYouTubeCommand && (!explicitPlayback || !mentionsYouTube)) return null

        var topic = trimmed
        topic = Regex(
            "(?i)^\\s*(?:please\\s+)?(?:can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+)?" +
                "(?:play|open|start|watch|pull\\s+up|put\\s+on|turn\\s+on|" +
                "queue(?:\\s+up)?|launch|show(?:\\s+me)?|bring\\s+up|listen\\s+to)\\b[\\s,.:;-]*"
        ).replace(topic, "")
        topic = Regex("(?i)^\\s*(?:youtube|yt)\\b[\\s,.:;-]*").replace(topic, "")
        topic = Regex("(?i)\\b(?:on|from|in)\\s+(?:youtube|yt)\\b.*$").replace(topic, "")
        topic = topic
            .trim()
            .trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')
            .trimEnd('.', '!', '?', ',', ';', ':')

        val musicMode = lower.contains("youtube music") ||
            lower.contains("song") ||
            lower.contains("music") ||
            lower.contains("listen")
        val mode = if (musicMode) "music" else "video"

        topic = Regex("(?i)^\\s*(?:youtube|yt)\\b[\\s,.:;-]*").replace(topic, "")
        topic = Regex(
            "(?i)^\\s*(?:music|videos?|songs?|tracks?|clips?|playlist|playlists?)\\b[\\s,.:;-]*" +
                "(?:(?:by|from|about|on|for)\\b)?[\\s,.:;-]*"
        ).replace(topic, "")
        topic = topic.replace(Regex("\\s+"), " ").trim()
        if (topic.isBlank()) return null

        val items = splitExplicitMediaItems(topic)
        if (items.isEmpty()) return null
        return YouTubePlaybackSpec(items = items, mode = mode)
    }

    private fun splitExplicitMediaItems(raw: String): List<String> {
        val normalized = raw
            .replace(Regex("(?i)\\s+(?:and\\s+then|then|followed\\s+by|after\\s+that)\\s+"), ";;")
            .replace(Regex("\\s*;\\s*"), ";;")
            .replace(Regex("\\s*,\\s*(?:and\\s+)?"), ";;")
        if (normalized == raw && Regex("(?i)\\s+and\\s+").containsMatchIn(raw)) {
            val parts = Regex("(?i)\\s+and\\s+").split(raw, limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.split(Regex("\\s+")).size >= 2 }) {
                return parts
            }
        }
        return normalized
            .split(";;")
            .map { part ->
                part
                    .trim()
                    .trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019')
                    .trimEnd('.', '!', '?', ',', ';', ':')
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
    }

    fun extractTapLinkUrl(resultText: String): String? {
        val trimmed = resultText.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("taplink://", ignoreCase = true)) {
            return normalizeTapLinkUrl(trimmed.substring("taplink://".length))
        }
        return DOMAIN_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { normalizeTapLinkUrl(it) }
    }

    fun normalizeTapLinkUrl(raw: String): String? {
        val candidate = raw
            .trim()
            .trim('"', '\'', '`')
            .takeIf { it.isNotBlank() }
            ?: return null

        return when {
            candidate.startsWith("file://", ignoreCase = true) -> candidate
            candidate.startsWith("https://", ignoreCase = true) ||
                candidate.startsWith("http://", ignoreCase = true) -> sanitizeDomain(candidate)
            candidate.startsWith("//") -> sanitizeDomain("https:$candidate")
            candidate.startsWith("/") -> null
            DOMAIN_REGEX.matches(candidate) -> normalizeUrl(candidate)
            else -> null
        }
    }

    fun displayLabelForUrl(url: String): String = hostLabel(url)

    private fun parseResearch(text: String): AssistantIntent.Research? {
        val topic = RESEARCH_PATTERNS
            .firstNotNullOfOrNull { pattern ->
                pattern.find(text)?.groupValues?.getOrNull(1)
            }
            ?.trim()
            ?.trimEnd('.', '?', '!')
            .orEmpty()

        if (topic.isBlank()) return null
        return AssistantIntent.Research(topic)
    }

    private fun parseLearn(text: String): AssistantIntent.Learn? {
        val explicitPrompt = extractExplicitLearnPrompt(text)
            ?: return null

        val normalizedPrompt = explicitPrompt.ifBlank { "continue on the previous problem" }
        return AssistantIntent.Learn(
            prompt = normalizedPrompt,
            topicHint = deriveLearnTopicHint(normalizedPrompt)
        )
    }


    private fun isLearnContinuation(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.US)
        if (LEARN_CONTINUATION_PATTERNS.any { it.matches(trimmed) }) return true
        return listOf(
            "continue",
            "resume",
            "pick up",
            "where we left off",
            "from before",
            "previous problem",
            "last problem",
            "same problem",
            "previous lesson",
            "last lesson",
            "same lesson",
            "previous topic",
            "last topic",
            "same topic"
        ).any { lower.contains(it) }
    }

    private fun deriveLearnTopicHint(prompt: String): String {
        val cleaned = prompt
            .trim()
            .removePrefix("help me learn")
            .removePrefix("Teach me")
            .removePrefix("teach me")
            .removePrefix("show me how to")
            .removePrefix("walk me through")
            .removePrefix("help me understand")
            .removePrefix("help me study")
            .removePrefix("how do i")
            .removePrefix("how can i")
            .removePrefix("how to")
            .trim(' ', '.', '?', '!')
        return if (isLearnContinuation(prompt)) "" else cleaned
    }

    private fun parseOpenWeb(text: String): AssistantIntent.OpenWeb? {
        val rawTarget = OPEN_PATTERNS
            .firstNotNullOfOrNull { pattern ->
                pattern.find(text)?.groupValues?.getOrNull(1)
            }
            ?.trim()
            ?.trimEnd('.', '?', '!')
            .orEmpty()

        if (rawTarget.isBlank()) return null

        val cleanedTarget = rawTarget
            .removePrefix("the ")
            .removePrefix("website ")
            .removePrefix("webpage ")
            .trim()

        if (cleanedTarget.isBlank()) return null
        if (LOCAL_APP_TARGETS.contains(cleanedTarget.lowercase(Locale.US))) return null

        val directMatch = DOMAIN_REGEX.find(cleanedTarget)?.groupValues?.getOrNull(1)
        if (!directMatch.isNullOrBlank()) {
            val url = normalizeUrl(directMatch)
            return AssistantIntent.OpenWeb(url = url, displayLabel = hostLabel(url))
        }

        val normalizedKey = cleanedTarget.lowercase(Locale.US)
        val mapped = KNOWN_SITES[normalizedKey]
        if (!mapped.isNullOrBlank()) {
            return AssistantIntent.OpenWeb(url = mapped, displayLabel = hostLabel(mapped))
        }

        val queryUrl = buildGoogleSearchUrl(cleanedTarget)
        return AssistantIntent.OpenWeb(url = queryUrl, displayLabel = cleanedTarget)
    }

    private fun normalizeUrl(raw: String): String {
        val noSpaces = raw.trim().replace(" ", "")
        val withScheme = if (noSpaces.startsWith("http://") || noSpaces.startsWith("https://")) {
            noSpaces
        } else {
            "https://$noSpaces"
        }
        return sanitizeDomain(withScheme)
    }

    /**
     * Strip encoded whitespace (%20) from the domain portion of a URL.
     * Literal spaces are stripped by the caller; %20 in the domain causes
     * ERR_NAME_NOT_RESOLVED (e.g. "media.tap%20claw.app" → "media.tapclaw.app").
     * Path / query %20 is left intact because it may be intentional.
     */
    private fun sanitizeDomain(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + 3
        val pathStart = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
        val domain = url.substring(0, pathStart).replace("%20", "")
        val rest = url.substring(pathStart)
        return rewriteHallucinatedMediaDomain(domain + rest)
    }

    // ── Hallucinated domain rewriter ──────────────────────────────
    //
    // AI models (GPT-5.4 in OpenClaw, Gemini on glasses) persistently
    // hallucinate plausible-sounding domains for the media relay when
    // constructing URLs. The ONLY valid media relay is the operator's
    // configured relay host (build-time TAPINSIGHT_RELAY_BASE property).
    // This function catches any URL that looks like a TapClaw/OpenClaw
    // media request on a wrong domain and rewrites it deterministically.
    // This is a code-level safety net — it does not depend on the model
    // following instructions.

    /** Hostname of the operator's configured relay; blank when the build
     *  wasn't configured with one — the rewriter then passes URLs through
     *  untouched (there is no known-correct host to rewrite TO). */
    private val CORRECT_MEDIA_RELAY: String =
        com.rayneo.visionclaw.core.network.RelayUrlHelper.TAPINSIGHT_RELAY_BASE
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
            .lowercase(java.util.Locale.US)

    /** Domains the AI has hallucinated in the past, and any future
     *  pattern that contains "tapclaw" or "openclaw" in the hostname. */
    private val KNOWN_HALLUCINATED_DOMAINS = setOf(
        "api.tapclaw.com",
        "app-media.tapclaw.io",
        "media.tapclaw.io",
        "api.tapclaw.run",
        "api.tapclaw.dev",
        "media.tapclaw.com",
        "tapclaw.io",
        "tapclaw.com",
        "tapclaw.run",
        "tapclaw.dev",
        "openclaw.io",
        "openclaw.com",
        "api.openclaw.io",
        "api.openclaw.com"
    )

    /** Path patterns that indicate a media/workspace file request. */
    private val MEDIA_PATH_PATTERNS = listOf(
        "/media/", "/v1/media/", "/v1/workspace/", "/workspace/",
        "/files/", "/v1/files/", "/audio/", "/v1/audio/"
    )

    /**
     * If a URL is on a hallucinated domain and has a media-like path,
     * rewrite it to the correct relay. Also catches any unknown domain
     * containing "tapclaw" or "openclaw" as a substring.
     * Non-media URLs and URLs already on the correct domain pass through.
     */
    fun rewriteHallucinatedMediaDomain(url: String): String {
        if (CORRECT_MEDIA_RELAY.isBlank()) return url
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + 3
        val pathStart = url.indexOf('/', authorityStart).let { if (it < 0) url.length else it }
        val host = url.substring(authorityStart, pathStart).lowercase(Locale.US)
            .substringBefore(':')  // strip port if present
        val path = url.substring(pathStart)

        // Already correct
        if (host == CORRECT_MEDIA_RELAY) return url

        // Check if this looks like a media request on a bad domain
        val isKnownBad = KNOWN_HALLUCINATED_DOMAINS.contains(host)
        val isSuspectHost = host.contains("tapclaw") || host.contains("openclaw")
        val hasMediaPath = MEDIA_PATH_PATTERNS.any { path.lowercase(Locale.US).contains(it) }

        if ((isKnownBad || isSuspectHost) && hasMediaPath) {
            // Normalize the path: strip /v1 prefix if present so we get /media/<file>
            val normalizedPath = path
                .replaceFirst(Regex("^/v1/workspace/"), "/media/")
                .replaceFirst(Regex("^/workspace/"), "/media/")
                .replaceFirst(Regex("^/v1/media/"), "/media/")
                .replaceFirst(Regex("^/v1/files/"), "/media/")
                .replaceFirst(Regex("^/files/"), "/media/")
                .replaceFirst(Regex("^/v1/audio/"), "/media/")
                .replaceFirst(Regex("^/audio/"), "/media/")
            val scheme = url.substring(0, schemeEnd)
            val corrected = "$scheme://$CORRECT_MEDIA_RELAY$normalizedPath"
            android.util.Log.w("URLRewriter",
                "Rewrote hallucinated media URL: $url → $corrected")
            return corrected
        }

        // Also catch bare hallucinated domains even without a media path
        // (e.g. "https://api.tapclaw.com/something") — redirect to relay
        if (isKnownBad || isSuspectHost) {
            val scheme = url.substring(0, schemeEnd)
            val corrected = "$scheme://$CORRECT_MEDIA_RELAY$path"
            android.util.Log.w("URLRewriter",
                "Rewrote hallucinated domain URL: $url → $corrected")
            return corrected
        }

        return url
    }

    private fun buildGoogleSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "https://www.google.com/search?q=$encoded"
    }

    private fun hostLabel(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .removePrefix("www.")
    }
}
