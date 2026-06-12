package com.rayneo.visionclaw.core.tools

import android.util.Log
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import org.json.JSONObject

/**
 * Client-side tool assist that supplements the Gemini Live model's function
 * calling.  When the user's spoken input matches a tool-worthy pattern we
 * proactively execute the tool locally and return the result text so the caller
 * can inject it into the Live session as a clientContent message.
 *
 * This approach treats the Live model as a *conversational layer* —
 * we feed it the data and it speaks about it, instead of relying solely on
 * the model's built-in function calling.
 */
class ToolAssistEngine(
    private val toolDispatcher: ToolDispatcher,
    private val locationProvider: () -> DeviceLocationContext?
) {

    companion object {
        private const val TAG = "ToolAssistEngine"

        // ── Pattern groups ────────────────────────────────────────────
        // Each entry maps a regex to the tool name + arg builder.

        // Tightened patterns: only match when the user clearly and explicitly asks
        // for nearby places. Removed casual matches like "I'm hungry" or "I need coffee"
        // that caused proactive tool calls interrupting active Gemini conversations.
        private val PLACES_PATTERNS = listOf(
            // "find a cafe near me", "nearest gas station", "closest restaurant"
            Regex("(?i)\\b(find|show|are there|where('?s| is| are)|look for|search for|nearest|closest)\\b.{0,40}\\b(restaurant|cafe|coffee shop|gas station|fuel|pharmacy|drug ?store|grocery|supermarket|hospital|clinic|bar|pub|bakery|bank|atm|parking|gym|hotel|motel|lodging)s?\\b"),
            // "restaurants near me", "coffee shop nearby"
            Regex("(?i)\\b(restaurant|cafe|coffee shop|gas station|fuel|pharmacy|grocery|supermarket|hospital|bar|bakery|bank|atm|parking|gym|hotel)s?\\b.{0,20}\\b(near me|nearby|around here|close by)\\b"),
            // "what's open near me", "places open nearby"
            Regex("(?i)\\b(what'?s|places?) open\\b.{0,20}\\b(near me|nearby|around here)\\b")
        )

        private val ROUTES_PATTERNS = listOf(
            // "directions to X", "navigate to X", "how do I get to X", "car to X"
            Regex("(?i)\\b(direction|navigate|route|driving|drive|car)s?\\s+(to|from)\\b"),
            Regex("(?i)\\bhow (do i|to) get to\\b"),
            // "traffic to Houston", "traffic on I-10", "how's traffic"
            Regex("(?i)\\b(traffic|commute|travel time|drive time|eta)\\b.{0,40}\\b(to|on|from|for|like|right now)\\b"),
            Regex("(?i)\\bhow('?s| is| long)\\b.{0,20}\\b(traffic|drive|commute|car)\\b"),
            // Standalone traffic queries: "check traffic", "what's the traffic", "traffic update"
            Regex("(?i)\\b(check|what('?s| is)|show|give me|any)\\s+(the\\s+)?(traffic|commute)\\b"),
            Regex("(?i)\\b(traffic|commute)\\s+(update|report|check|conditions|status|info)\\b"),
            // "take me to X", "go to X", "car ride to X"
            Regex("(?i)\\b(take me|go|head|car ride|ride)\\s+(to|toward|towards)\\b")
        )

        private val LOCATION_PATTERNS = listOf(
            // "where am I", "what's my location", "my coordinates"
            Regex("(?i)\\bwhere am i\\b"),
            Regex("(?i)\\b(what'?s|what is) my (location|position|coordinates|address)\\b"),
            Regex("(?i)\\bmy (current )?(location|position|coordinates|gps)\\b"),
            Regex("(?i)\\bwhere (are we|is this|is here)\\b")
        )

        private val ASK_MAPS_EXPLORE_PATTERNS = listOf(
            // "tell me about the Golden Gate Bridge", "what is the Eiffel Tower"
            Regex("(?i)\\b(tell me about|what('?s| is)|explore|describe|info on|about)\\b.{1,60}\\b(bridge|tower|museum|monument|park|building|church|cathedral|stadium|arena|temple|palace|castle|plaza|square|landmark|statue|memorial)\\b"),
            // "explore [place name]"
            Regex("(?i)^\\s*(explore|tell me about|what('?s| is))\\s+(.{3,})\\s*$"),
            // "what landmarks are nearby", "nearby landmarks"
            Regex("(?i)\\b(landmark|landmarks|notable place|points? of interest)s?\\b.{0,20}\\b(near|around|nearby|close|here)\\b"),
            Regex("(?i)\\b(near|around|nearby)\\b.{0,20}\\b(landmark|landmarks|notable|points? of interest)s?\\b")
        )

        private val ASK_MAPS_3D_NAV_PATTERNS = listOf(
            // "navigate 3D to X", "3D directions to X", "drive/go 3D to X"
            Regex("(?i)\\b(navigate|navigation|directions?|drive|go)\\s+(in\\s+)?3[dD]\\s+(to\\s+)?"),
            Regex("(?i)\\b3[dD]\\s+(navigate|navigation|directions?|route)\\b"),
            // "navigate to X in 3D"
            Regex("(?i)\\b(navigate|drive|go)\\b.{1,60}\\bin\\s+3[dD]\\b")
        )

        // Landmark / place "show me" patterns — these open a 3D preview of the place,
        // NOT a driving route. This prevents asking for "the Space Needle" from framing
        // the midpoint between the user and Seattle.
        private val ASK_MAPS_3D_SHOW_PATTERNS = listOf(
            // "show me a 3d map of the space needle", "3d map of Paris"
            Regex("(?i)\\b(show\\s+(me\\s+)?)?(a\\s+)?(photo\\s*realistic\\s+)?3[dD]\\s+(map|view|look|rendering|flyover|fly[- ]over)\\s+of\\b"),
            // "show me the space needle in 3d", "show X in 3d"
            Regex("(?i)\\bshow\\s+(me\\s+)?.{1,80}\\bin\\s+3[dD]\\b"),
            // "show pizza restaurants in Oakland in the 3D map"
            Regex("(?i)\\bshow\\s+(me\\s+)?.{1,100}\\bin\\s+(the\\s+)?3[dD]\\s+map\\b"),
            // "see X in 3d"
            Regex("(?i)\\b(see|view|look\\s+at)\\s+.{1,80}\\bin\\s+3[dD]\\b"),
            // "3d view of X"
            Regex("(?i)\\b3[dD]\\s+(view|look|picture|photo|rendering)\\s+of\\b"),
            // "photorealistic X"
            Regex("(?i)\\bphoto\\s*realistic\\s+(view|map|render|rendering)\\s+of\\b")
        )

        // Fly-over patterns — spin the camera around a landmark / location.
        private val ASK_MAPS_FLYOVER_PATTERNS = listOf(
            Regex("(?i)\\bfly[- ]over\\s+"),
            Regex("(?i)\\bfly\\s+(over|around|by)\\s+"),
            Regex("(?i)\\b(do|start|begin|give\\s+me)\\s+a?\\s*fly[- ]?over\\b"),
            Regex("(?i)\\b(orbit|circle|spin\\s+around)\\s+"),
            Regex("(?i)\\b(take\\s+me|fly\\s+me)\\s+(over|around|to|above)\\s+.{2,}"),
            Regex("(?i)\\b(cinematic|aerial|bird'?s?\\s+eye)\\s+(view|tour)\\s+of\\b")
        )

        private val RESEARCH_PATTERNS = listOf(
            Regex("(?i)^\\s*research\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:please\\s+)?research\\s+(?:for me\\s+)?(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:do|run)\\s+research\\s+on\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:give me|do)\\s+a\\s+deep\\s+dive\\s+(?:on|into)\\s+(.+?)\\s*$")
        )


        // Translation patterns
        private val TRANSLATE_PATTERNS = listOf(
            Regex("(?i)\\btranslate\\b.{0,60}\\b(to|into|in)\\s+(\\w+)"),
            Regex("(?i)\\b(say|how do you say)\\b.{1,40}\\b(in)\\s+(\\w+)"),
            Regex("(?i)\\bwhat does (that|this|it)\\s+(say|mean)\\b.{0,30}\\b(in)\\s+(\\w+)"),
            Regex("(?i)^\\s*translate\\b[:\\-\\s]+(.+?)\\s*$"),
            Regex("(?i)\\b(translate|translation)\\s+(this|that|what I see|the sign|the menu|the text)\\b")
        )

        // Battery patterns
        private val BATTERY_PATTERNS = listOf(
            Regex("(?i)\\b(battery|power)\\s+(saver|save|saving|level|status|life|check)\\b"),
            Regex("(?i)\\b(enable|disable|turn on|turn off|activate|deactivate)\\s+(battery|power)\\s*(saver|saving|save)?\\b"),
            Regex("(?i)\\b(low power|power save|save battery|save power)\\b"),
            Regex("(?i)\\bhow much (battery|power|charge)\\b"),
            Regex("(?i)\\bbattery\\s*(level|percentage|left|remaining)?\\s*\\??\\s*$")
        )

        // Quick action patterns
        private val QUICK_ACTION_PATTERNS = listOf(
            Regex("(?i)^\\s*(good morning|leaving work|heading home|meeting mode|what'?s nearby)\\s*$"),
            Regex("(?i)\\b(run|trigger|start|do)\\s+(quick action|macro|shortcut)\\b"),
            Regex("(?i)\\b(list|show)\\s+(quick actions|macros|shortcuts)\\b")
        )

        // TapClaw/OpenClaw prefix pattern ("tapclaw", "tap claw", "openclaw", "open claw")
        private val CLAW_PREFIX = Regex("(?i)^\\s*(?:(?:tap|open)\\s*claw)\\b[:\\-,\\s]+(.*?)\\s*$")
        private val VERBATIM_FILE_READ_PATTERN = Regex(
            "(?i)\\b(read|recite)\\b.*\\b(verbatim|txt\\s+file|text\\s+file|\\.txt|\\.md|\\.log)\\b"
        )

        private val LEARN_CONTINUATION_PATTERNS = listOf(
            Regex("(?i)^\\s*(?:continue|keep going|go on|next step|what should i try next)\\s*(?:on|with)?\\s*(?:the\\s+)?(?:previous|same)?\\s*(?:problem|lesson|topic)?\\s*$"),
            Regex("(?i)^\\s*(?:continue|pick up)\\s+(?:where we left off|from before|the previous problem|the last lesson)\\s*$"),
            Regex("(?i)^\\s*(?:help me with|teach me)\\s+(?:the next step|the next part|the same problem)\\s*$")
        )
    }

    data class AssistResult(
        val toolName: String,
        val resultText: String,
        val contextPrompt: String,  // what we inject into the Live session
        val preferLiveVoice: Boolean = false  // true = route through Gemini Live voice, not local TTS
    )

    /**
     * Rewrite any hallucinated media domain URLs in the given text.
     * AI models (GPT-5.4, Gemini) persistently hallucinate wrong domains
     * (api.tapclaw.com, media.tapclaw.io, etc.) — the only correct media
     * relay is the operator's configured host. This function rewrites them
     * deterministically before the text is injected into Gemini Live.
     */
    private fun rewriteAllUrlsInText(text: String): String {
        val urlPattern = Regex("""https?://[^\s"'<>\]]+""")
        var result = text
        for (match in urlPattern.findAll(text)) {
            val original = match.value
            val rewritten = AssistantIntentParser.rewriteHallucinatedMediaDomain(original)
            if (rewritten != original) {
                result = result.replace(original, rewritten)
                Log.w(TAG, "Rewrote hallucinated URL in tool assist: $original → $rewritten")
            }
        }
        return result
    }

    /**
     * Analyse the user's spoken transcript and, if it matches a tool pattern,
     * proactively execute the tool and return the result.  Returns null when
     * no pattern matches (i.e. let Gemini handle it normally).
     */
    suspend fun maybeAssist(transcript: String): AssistResult? {
        val text = transcript.trim()
        if (text.length < 4) return null

        // ── Quick Actions ("good morning", "leaving work") ────────────
        if (QUICK_ACTION_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleQuickAction(text)
        }

        // ── TapClaw ("tapclaw check my emails") ──────────────────────
        val clawMatch = CLAW_PREFIX.find(text)
        if (clawMatch != null) {
            val clawQuery = clawMatch.groupValues[1].trim()
            return handleClaw(clawQuery.ifBlank { text })
        }

        // ── Status brief ("status") ─────────────────────────────────
        if (AssistantIntentParser.isStatusBriefingRequest(text)) {
            return handleStatusBriefing(text)
        }

        // ── Translation ("translate this to Spanish") ────────────────
        if (TRANSLATE_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleTranslate(text)
        }

        // ── Battery ("battery status", "save battery") ──────────────
        if (BATTERY_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleBattery(text)
        }

        // ── Location ("where am I?") ─────────────────────────────────
        if (LOCATION_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleLocation(text)
        }

        // ── Ask Maps explicit 3D requests ─────────────────────────────
        // Run before generic places/routes so "show pizza restaurants in
        // Oakland in the 3D map" becomes pushpins, not a plain places card.
        if (ASK_MAPS_FLYOVER_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMapsFlyOver(text)
        }
        if (ASK_MAPS_3D_SHOW_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMaps3DShow(text)
        }
        if (ASK_MAPS_3D_NAV_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMaps3DNav(text)
        }

        // ── Places ("find a cafe near me") ───────────────────────────
        if (PLACES_PATTERNS.any { it.containsMatchIn(text) }) {
            return handlePlaces(text)
        }

        // ── Routes / Traffic ("traffic to Houston") ──────────────────
        if (ROUTES_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleRoutes(text)
        }

        // ── Ask Maps Fly-Over ("fly over the space needle") ───────
        // Must run BEFORE 3D nav / show / explore so "fly over X" doesn't get
        // mis-routed through navigation code that builds a driving route.
        if (ASK_MAPS_FLYOVER_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMapsFlyOver(text)
        }

        // ── Ask Maps 3D Show ("show me a 3d map of the space needle") ──
        // Landmark preview — opens 3D viewer centered on the landmark, not a route.
        if (ASK_MAPS_3D_SHOW_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMaps3DShow(text)
        }

        // ── Ask Maps 3D Nav ("navigate 3D to X") ──────────────────
        if (ASK_MAPS_3D_NAV_PATTERNS.any { it.containsMatchIn(text) }) {
            return handleAskMaps3DNav(text)
        }

        // ── Ask Maps Explore ("tell me about the Golden Gate Bridge") ─
        if (shouldUseAskMapsExplore(text)) {
            return handleAskMapsExplore(text)
        }

        val researchTopic = RESEARCH_PATTERNS
            .firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (researchTopic != null) {
            return handleResearch(text, researchTopic)
        }

        // ── LearnLM / Tutoring — activated only by "learnlm" prefix ──
        val explicitLearnPrompt = AssistantIntentParser.extractExplicitLearnPrompt(text)
        if (explicitLearnPrompt != null) {
            val normalizedPrompt = explicitLearnPrompt.ifBlank { "continue on the previous problem" }
            return handleLearn(normalizedPrompt, extractLearnTopicHint(normalizedPrompt))
        }


        return null
    }

    // ── Handler: Location ─────────────────────────────────────────────

    private fun handleLocation(transcript: String): AssistResult {
        val loc = locationProvider()
        if (loc == null) {
            return AssistResult(
                toolName = "location",
                resultText = "GPS not available",
                contextPrompt = "[SYSTEM: The user asked about their location but GPS is not available. " +
                    "Tell them to enable Location Services on their glasses.]"
            )
        }
        val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
        val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60} minutes ago"
        val info = buildString {
            append("Latitude: ${loc.latitude}, Longitude: ${loc.longitude}")
            append(" (accuracy: ${loc.accuracyMeters?.toInt() ?: "unknown"}m, $fresh)")
            loc.altitudeMeters?.let { alt: Double -> append(", altitude: ${alt.toInt()}m") }
            loc.speedMps?.let { spd: Float -> if (spd > 0.5f) append(", speed: ${"%.1f".format(spd * 2.237)} mph") }
        }
        return AssistResult(
            toolName = "location",
            resultText = info,
            contextPrompt = "[TOOL RESULT — location]\n$info\n" +
                "[Describe the user's approximate location using these coordinates. " +
                "Mention the nearest city/area and any relevant context. The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Places ───────────────────────────────────────────────

    private suspend fun handlePlaces(transcript: String): AssistResult {
        val loc = locationProvider()
        if (loc == null) {
            return AssistResult(
                toolName = "google_places",
                resultText = "GPS not available for nearby search",
                contextPrompt = "[SYSTEM: The user asked about nearby places but GPS is not available. " +
                    "Tell them to enable Location Services on their glasses.]"
            )
        }

        // Extract the place type from the transcript
        val placeType = extractPlaceType(transcript)
        val args = buildString {
            append("{\"type\":\"$placeType\"")
            // Pass the raw query too so the tool can use it
            val cleaned = transcript.replace(Regex("(?i)(near me|nearby|around here|close by|around|here)"), "").trim()
            if (cleaned.isNotBlank()) append(",\"query\":\"$cleaned\"")
            append(",\"radius\":\"1500\"}")
        }

        Log.d(TAG, "Places assist: type=$placeType args=$args")

        val result = toolDispatcher.dispatch("google_places", args)
        val resultText = result.getOrElse { "Places search failed: ${it.message}" }

        return AssistResult(
            toolName = "google_places",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — google_places nearby search]\n$resultText\n" +
                "[Use these results faithfully. Lead with the nearest OPEN option if one exists. " +
                "If the closest place is closed, say that briefly and then promote the nearest open one. " +
                "Preserve ETA, weather, and Maps details instead of replacing them with a generic summary. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Routes / Traffic ─────────────────────────────────────

    private suspend fun handleRoutes(transcript: String): AssistResult {
        // Extract origin and destination from transcript
        val (explicitOrigin, destination) = extractOriginAndDestination(transcript)

        if (destination.isBlank()) {
            return AssistResult(
                toolName = "google_routes",
                resultText = "Could not determine destination",
                contextPrompt = "[SYSTEM: The user asked about traffic/directions but I couldn't determine " +
                    "the destination. Ask them: 'Where would you like directions to?']"
            )
        }

        // Use explicit origin if user spoke one, otherwise fall back to GPS
        val origin: String = if (explicitOrigin.isNotBlank()) {
            explicitOrigin
        } else {
            val loc = locationProvider()
            if (loc == null) {
                return AssistResult(
                    toolName = "google_routes",
                    resultText = "GPS not available for route calculation",
                    contextPrompt = "[SYSTEM: The user asked about directions/traffic but GPS is not available. " +
                        "Tell them to enable Location Services on their glasses, or say 'from [address] to [destination]'.]"
                )
            }
            "${loc.latitude},${loc.longitude}"
        }

        val args = "{\"origin\":\"$origin\",\"destination\":\"$destination\"}"

        Log.d(TAG, "Routes assist: origin=$origin destination=$destination explicitOrigin=${explicitOrigin.isNotBlank()}")

        val result = toolDispatcher.dispatch("google_routes", args)
        val resultText = result.getOrElse { "Route lookup failed: ${it.message}" }

        return AssistResult(
            toolName = "google_routes",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — google_routes]\n$resultText\n" +
                "[Tell the user about the route, travel time, and traffic conditions naturally. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: OpenClaw ───────────────────────────────────────────

    private suspend fun handleClaw(query: String): AssistResult {
        Log.d(TAG, "TapClaw assist: query=${query.take(100)}")

        // Graceful check: if TapClaw tool isn't registered, give a helpful message
        if (!toolDispatcher.isSupported("tapclaw_agent")) {
            val msg = "TapClaw is not enabled. Enable it in the TapInsight companion app under the TapClaw section, and set your gateway URL and token."
            return AssistResult(
                toolName = "tapclaw_agent",
                resultText = msg,
                contextPrompt = "[SYSTEM: The user said 'tapclaw' but TapClaw is not enabled. " +
                    "Tell them to enable TapClaw in TapInsight setup and configure the gateway URL and token.]"
            )
        }

        val args = JSONObject().put("query", query).toString()
        val result = toolDispatcher.dispatch("tapclaw_agent", args)
        val resultTextRaw = result.getOrElse { err ->
            "TapClaw: ${err.message ?: "unavailable right now."}"
        }
        // Rewrite any hallucinated media URLs before they're injected into
        // Gemini Live via contextPrompt — prevents Gemini from parroting
        // wrong domains (e.g. api.tapclaw.com → the configured relay).
        val resultText = rewriteAllUrlsInText(resultTextRaw)

        return AssistResult(
            toolName = "tapclaw_agent",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — tapclaw_agent]\n$resultText\n" + (
                if (VERBATIM_FILE_READ_PATTERN.containsMatchIn(query)) {
                    "[The user asked: \"$query\". If the TapClaw response above is file content, read it verbatim word-for-word and do not summarize.]"
                } else {
                    "[Relay this TapClaw response naturally to the user. The user asked: \"$query\"]"
                }
            )
        )
    }

    // ── Handler: Translation ───────────────────────────────────────

    private suspend fun handleTranslate(transcript: String): AssistResult {
        val (text, targetLang) = extractTranslation(transcript)
        Log.d(TAG, "Translate assist: text=${text.take(60)} target=$targetLang")

        val args = JSONObject()
            .put("text", text.ifBlank { "camera" })
            .put("target_language", targetLang)
            .toString()

        val result = toolDispatcher.dispatch("translate_text", args)
        val resultText = result.getOrElse { "Translation unavailable: ${it.message}" }

        return AssistResult(
            toolName = "translate_text",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — translate_text]\n$resultText\n" +
                "[Provide the translation naturally. If this is a vision/camera request, " +
                "look at the camera feed and translate visible text to $targetLang. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Battery ────────────────────────────────────────────

    private suspend fun handleBattery(transcript: String): AssistResult {
        val lower = transcript.lowercase()
        val action = when {
            lower.contains("enable") || lower.contains("turn on") ||
                lower.contains("activate") || lower.contains("save battery") ||
                lower.contains("save power") || lower.contains("low power") -> "enable"
            lower.contains("disable") || lower.contains("turn off") ||
                lower.contains("deactivate") -> "disable"
            else -> "check"
        }

        val args = JSONObject().put("action", action).toString()
        val result = toolDispatcher.dispatch("battery_saver", args)
        val resultText = result.getOrElse { "Battery info unavailable: ${it.message}" }

        return AssistResult(
            toolName = "battery_saver",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — battery_saver]\n$resultText\n" +
                "[Tell the user about their battery status naturally. The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Status briefing ────────────────────────────────────

    private suspend fun handleStatusBriefing(transcript: String): AssistResult {
        val result = toolDispatcher.dispatch("status_briefing", "{}")
        val resultText = result.getOrElse { "Status unavailable right now: ${it.message}" }

        return AssistResult(
            toolName = "status_briefing",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — status_briefing]\n$resultText\n" +
                "[Read this brief naturally and keep the conversation open for follow-up questions. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Quick Action ───────────────────────────────────────

    private suspend fun handleQuickAction(transcript: String): AssistResult {
        val lower = transcript.trim().lowercase()
        val isListRequest = lower.contains("list") || lower.contains("show")
        val action = if (isListRequest) "list" else lower

        val args = JSONObject()
            .put("action", action)
            .put("query", transcript.trim())
            .toString()

        val result = toolDispatcher.dispatch("quick_action", args)
        val resultText = result.getOrElse { "Quick action failed: ${it.message}" }

        return AssistResult(
            toolName = "quick_action",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — quick_action]\n$resultText\n" +
                "[Present the quick action results naturally. The user triggered: \"$transcript\"]"
        )
    }

    private suspend fun handleResearch(transcript: String, topic: String): AssistResult {
        val args = JSONObject().put("topic", topic).toString()
        Log.d(TAG, "Research assist: topic=$topic")
        val result = toolDispatcher.dispatch("research_topic", args)
        val resultText = result.getOrElse { "Research unavailable right now." }

        return AssistResult(
            toolName = "research_topic",
            resultText = resultText,
            contextPrompt = "[INSTRUCTION: Read the ENTIRE research report below to the user VERBATIM. " +
                "Do NOT summarize, shorten, skip paragraphs, or paraphrase ANY part of it. " +
                "Do NOT ask follow-up questions UNTIL you have finished reading the COMPLETE report. " +
                "Start reading now.]\n\n$resultText"
        )
    }
    private fun isLearnContinuation(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
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

    private fun extractLearnTopicHint(prompt: String): String {
        val trimmed = prompt.trim()
        if (isLearnContinuation(trimmed)) return ""
        return trimmed
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
    }

    private suspend fun handleLearn(transcript: String, topic: String): AssistResult {
        val continuation = isLearnContinuation(transcript)
        val args = JSONObject().put("query", transcript).put("topic", topic).toString()
        Log.d(TAG, "LearnLM assist: topic=$topic, continuation=$continuation")
        val result = toolDispatcher.dispatch("learn_topic", args)
        val resultText = result.getOrElse { "Tutor mode is unavailable right now." }

        val contextPrompt = if (continuation) {
            "[TOOL RESULT — learn_topic — CONTINUATION]\n$resultText\n" +
                "[The user wants to continue their previous tutoring session. " +
                "Start by giving a brief 1-2 sentence verbal summary of where they left off on the previous problem, " +
                "then continue the voice tutoring conversation naturally. Keep the voice session going — do NOT end it. " +
                "The user asked: \"$transcript\"]"
        } else {
            "[TOOL RESULT — learn_topic]\n$resultText\n" +
                "[Present this as a concise tutoring response for the user. The user asked: \"$transcript\"]"
        }

        return AssistResult(
            toolName = "learn_topic",
            resultText = resultText,
            contextPrompt = contextPrompt,
            preferLiveVoice = continuation
        )
    }

    // ── Handler: Ask Maps — Explore ──────────────────────────────────

    private suspend fun handleAskMapsExplore(transcript: String): AssistResult {
        // Extract the place/query from transcript
        val query = extractExploreQuery(transcript)
        if (query.isBlank()) {
            return AssistResult(
                toolName = "ask_maps",
                resultText = "Could not determine what place to explore",
                contextPrompt = "[SYSTEM: The user asked about a place but I couldn't determine which one. " +
                    "Ask them: 'Which place would you like me to tell you about?']"
            )
        }

        val action = if (transcript.lowercase().contains("landmark") ||
            transcript.lowercase().contains("point of interest")) "nearby_landmarks" else "explore"

        val args = "{\"action\":\"$action\",\"query\":\"$query\"}"
        Log.d(TAG, "Ask Maps explore: query=$query action=$action")

        val result = toolDispatcher.dispatch("ask_maps", args)
        val resultText = result.getOrElse { "Place exploration failed: ${it.message}" }

        return AssistResult(
            toolName = "ask_maps",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — ask_maps explore]\n$resultText\n" +
                "[Share the AI-generated place summary naturally. Include key details like rating, " +
                "hours, and any interesting facts. If a 3D navigation link is available, mention " +
                "the user can say 'navigate 3D' to see it in photorealistic 3D. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Ask Maps — 3D Navigation ──────────────────────────

    private suspend fun handleAskMaps3DNav(transcript: String): AssistResult {
        val destination = extract3DNavDestination(transcript)
        if (destination.isBlank()) {
            return AssistResult(
                toolName = "ask_maps",
                resultText = "Could not determine 3D navigation destination",
                contextPrompt = "[SYSTEM: The user asked for 3D navigation but I couldn't determine the destination. " +
                    "Ask them: 'Where would you like 3D navigation to?']"
            )
        }

        val args = "{\"action\":\"navigate_3d\",\"destination\":\"$destination\"}"
        Log.d(TAG, "Ask Maps 3D nav: destination=$destination")

        val result = toolDispatcher.dispatch("ask_maps", args)
        val resultText = result.getOrElse { "3D navigation failed: ${it.message}" }

        return AssistResult(
            toolName = "ask_maps",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — ask_maps navigate_3d]\n$resultText\n" +
                "[Tell the user about the route with driving/walking ETAs. " +
                "Mention the 3D photorealistic view is loading. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Ask Maps — 3D Landmark Show ───────────────────────
    // Opens a landmark-centered 3D view (no driving route). This fixes the
    // "asked for a 3D map of the Space Needle from SF and got countryside" bug:
    // the viewer previously framed the route midpoint between user and destination.
    private suspend fun handleAskMaps3DShow(transcript: String): AssistResult {
        val query = extract3DShowQuery(transcript)
        if (query.isBlank()) {
            return AssistResult(
                toolName = "ask_maps",
                resultText = "Could not determine what place to show in 3D",
                contextPrompt = "[SYSTEM: The user asked for a 3D view but I couldn't determine the place. " +
                    "Ask them: 'Which place would you like to see in 3D?']"
            )
        }

        val args = JSONObject()
            .put("action", "show_3d")
            .put("query", query)
            .toString()
        Log.d(TAG, "Ask Maps 3D show: query=$query")

        val result = toolDispatcher.dispatch("ask_maps", args)
        val resultText = result.getOrElse { "3D view failed: ${it.message}" }

        return AssistResult(
            toolName = "ask_maps",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — ask_maps show_3d]\n$resultText\n" +
                "[Briefly introduce the landmark — one or two interesting facts. " +
                "Mention the 3D photorealistic view is opening. If the user wants " +
                "a cinematic orbit, they can say 'fly over it'. " +
                "The user asked: \"$transcript\"]"
        )
    }

    // ── Handler: Ask Maps — Fly-Over ───────────────────────────────
    // Opens a 3D view that orbits (circles) the landmark. Uses Google Maps
    // Map3DElement's flyCameraAround under the hood.
    private suspend fun handleAskMapsFlyOver(transcript: String): AssistResult {
        val query = extractFlyOverQuery(transcript)
        if (query.isBlank()) {
            return AssistResult(
                toolName = "ask_maps",
                resultText = "Could not determine fly-over target",
                contextPrompt = "[SYSTEM: The user asked for a fly-over but I couldn't determine the place. " +
                    "Ask them: 'Where should I fly over?']"
            )
        }

        val args = "{\"action\":\"fly_over\",\"query\":\"$query\"}"
        Log.d(TAG, "Ask Maps fly-over: query=$query")

        val result = toolDispatcher.dispatch("ask_maps", args)
        val resultText = result.getOrElse { "Fly-over failed: ${it.message}" }

        return AssistResult(
            toolName = "ask_maps",
            resultText = resultText,
            contextPrompt = "[TOOL RESULT — ask_maps fly_over]\n$resultText\n" +
                "[Briefly set the scene — one short, evocative line about the landmark " +
                "as the camera starts orbiting. Do NOT narrate the whole history; keep it " +
                "glasses-friendly. The user asked: \"$transcript\"]"
        )
    }

    // ── Extractors ────────────────────────────────────────────────────

    private fun extractPlaceType(transcript: String): String {
        val lower = transcript.lowercase()
        return when {
            lower.contains("coffee") || lower.contains("cafe") -> "cafe"
            lower.contains("restaurant") || lower.contains("food") ||
                lower.contains("eat") || lower.contains("hungry") -> "restaurant"
            lower.contains("gas") || lower.contains("fuel") -> "gas_station"
            lower.contains("pharmacy") || lower.contains("drug") -> "pharmacy"
            lower.contains("grocery") || lower.contains("supermarket") -> "supermarket"
            lower.contains("hospital") || lower.contains("clinic") ||
                lower.contains("emergency") -> "hospital"
            lower.contains("bar") || lower.contains("pub") -> "bar"
            lower.contains("bakery") -> "bakery"
            lower.contains("bank") || lower.contains("atm") -> "bank"
            lower.contains("parking") -> "parking"
            lower.contains("gym") || lower.contains("fitness") -> "gym"
            lower.contains("hotel") || lower.contains("motel") ||
                lower.contains("lodging") -> "lodging"
            lower.contains("store") || lower.contains("shop") -> "store"
            else -> "restaurant" // reasonable default
        }
    }

    /**
     * Extract both an explicit origin and a destination from the user's transcript.
     * Handles patterns like:
     *   "directions from 123 Main St to 456 Oak Ave"
     *   "navigate from downtown to the airport"
     *   "how do I get from work to home"
     *   "directions to 456 Oak Ave"  (no origin → empty string)
     */
    private fun extractOriginAndDestination(transcript: String): Pair<String, String> {
        // Pattern 1: "from <origin> to <destination>"
        val fromTo = Regex("(?i)\\bfrom\\s+(.+?)\\s+to\\s+(.+?)\\s*[?.!]*$").find(transcript)
        if (fromTo != null) {
            val origin = fromTo.groupValues[1].trim().replace(Regex("[?.!]+$"), "").trim()
            val dest = fromTo.groupValues[2].trim().replace(Regex("[?.!]+$"), "").trim()
            if (origin.isNotBlank() && dest.isNotBlank()) {
                return origin to dest
            }
        }

        // No explicit origin — fall back to destination-only extraction
        return "" to extractDestination(transcript)
    }

    private fun extractExploreQuery(transcript: String): String {
        val patterns = listOf(
            Regex("(?i)(?:tell me about|what(?:'?s| is)|explore|describe|info on)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            Regex("(?i)(?:nearby|near me|around here)\\s+(.+?)\\s*[?.!]*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(transcript)
            if (match != null) {
                val query = match.groupValues[1].trim()
                    .replace(Regex("[?.!]+$"), "").trim()
                if (query.isNotBlank()) return query
            }
        }
        // Fallback: strip common prefixes and return the rest
        return transcript
            .replace(Regex("(?i)^\\s*(tell me about|what('?s| is)|explore|describe|info on|nearby)\\s+"), "")
            .replace(Regex("[?.!]+$"), "")
            .trim()
    }

    private fun shouldUseAskMapsExplore(transcript: String): Boolean {
        if (ASK_MAPS_EXPLORE_PATTERNS.none { it.containsMatchIn(transcript) }) return false

        val query = extractExploreQuery(transcript)
        val queryLower = query.lowercase()

        // Do not let the broad "tell me about / what is / history of" map
        // pattern steal learning, media, or callsign/entity questions. Gemini's
        // own routing rules can still choose tapradio info_station or grounded
        // conversation, but Ask Maps should require an actual place/map signal.
        val hasInformationalLead = Regex(
            "(?i)^\\s*(?:tell me about|what(?:'?s| is)|describe|info on|history of|what'?s the history of)\\b"
        ).containsMatchIn(transcript)
        val hasPlaceClass = Regex(
            "(?i)\\b(bridge|tower|museum|monument|park|building|church|cathedral|stadium|arena|temple|palace|castle|plaza|square|landmark|statue|memorial|airport|station|campus|university|college|city|town|neighborhood|neighbourhood|district|beach|mountain|lake|river|trail|zoo|aquarium)\\b"
        ).containsMatchIn(transcript)
        val hasMapOrLocationSignal = Regex(
            "(?i)\\b(map|maps|nearby|near me|around here|where is|where's|located|location|directions?|navigate|route|3d|fly[- ]?over|landmarks?|points? of interest)\\b"
        ).containsMatchIn(transcript)
        val looksLikeRadioCallsign = Regex("""(?i)\b[KW][A-Z0-9]{2,5}(?:[-\s]?(?:FM|AM|HD\d?))?\b""")
            .containsMatchIn(query)
        val asksForHistory = Regex("(?i)\\bhistory of\\b").containsMatchIn(transcript)
        val mediaEntitySignal = Regex(
            "(?i)\\b(radio|podcast|youtube|video|song|music|album|artist|film|movie|show|episode|station)\\b"
        ).containsMatchIn(transcript)

        if (looksLikeRadioCallsign) return false
        if (asksForHistory && !hasMapOrLocationSignal && !hasPlaceClass) return false
        if (hasInformationalLead && mediaEntitySignal && !hasMapOrLocationSignal && !hasPlaceClass) return false
        if (hasInformationalLead && !hasPlaceClass && !hasMapOrLocationSignal) return false
        if (queryLower.startsWith("history of ") && !hasMapOrLocationSignal) return false

        return true
    }

    private fun extract3DNavDestination(transcript: String): String {
        val patterns = listOf(
            Regex("(?i)(?:navigate|navigation|directions?)\\s+(?:in\\s+)?3[dD]\\s+(?:to\\s+)?(.+?)\\s*[?.!]*$"),
            Regex("(?i)3[dD]\\s+(?:navigate|navigation|directions?|route)\\s+(?:to\\s+)?(.+?)\\s*[?.!]*$"),
            // "navigate to X in 3D"
            Regex("(?i)(?:navigate|drive|go)\\s+to\\s+(.+?)\\s+in\\s+3[dD]\\s*[?.!]*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(transcript)
            if (match != null) {
                val dest = match.groupValues[1].trim()
                    .replace(Regex("[?.!]+$"), "").trim()
                if (dest.isNotBlank()) return cleanLandmarkQuery(dest)
            }
        }
        // Fallback: extract destination from the "to X" pattern
        val toMatch = Regex("(?i)\\bto\\s+(.{3,})$").find(transcript)
        if (toMatch != null) {
            return cleanLandmarkQuery(toMatch.groupValues[1].replace(Regex("[?.!]+$"), "").trim())
        }
        return ""
    }

    /**
     * Extract the landmark/place name from a "show me a 3D map of X" style query.
     * Handles variants like:
     *   "show me a 3d map of the space needle"  → "space needle"
     *   "3d view of paris"                      → "paris"
     *   "photorealistic view of Mount Fuji"     → "Mount Fuji"
     *   "show me the space needle in 3d"        → "space needle"
     *   "see the Eiffel Tower in 3d"            → "Eiffel Tower"
     */
    private fun extract3DShowQuery(transcript: String): String {
        val patterns = listOf(
            // "show me a 3d (map|view|rendering|flyover) of X"
            Regex("(?i)\\b(?:show\\s+(?:me\\s+)?)?(?:a\\s+|the\\s+)?(?:photo\\s*realistic\\s+)?3[dD]\\s+(?:map|view|look|rendering|flyover|fly[- ]over|picture|photo)\\s+of\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "3d view of X"
            Regex("(?i)\\b3[dD]\\s+(?:view|look|map|picture|photo|rendering)\\s+of\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "photorealistic view/map of X"
            Regex("(?i)\\bphoto\\s*realistic\\s+(?:view|map|render|rendering)\\s+of\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "show me X in 3d" / "see X in 3d"
            Regex("(?i)(?:show\\s+(?:me\\s+)?|see|view|look\\s+at)\\s+(?:the\\s+)?(.+?)\\s+in\\s+3[dD]\\s*[?.!]*$"),
            // "show pizza restaurants in Oakland in the 3D map"
            Regex("(?i)(?:show\\s+(?:me\\s+)?)\\s+(?:the\\s+)?(.+?)\\s+in\\s+(?:the\\s+)?3[dD]\\s+map\\s*[?.!]*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(transcript)
            if (match != null) {
                val q = match.groupValues[1].trim().replace(Regex("[?.!]+$"), "").trim()
                if (q.isNotBlank()) return cleanLandmarkQuery(q)
            }
        }
        return ""
    }

    /**
     * Extract the landmark/place name from a fly-over request.
     * Handles "fly over X", "fly around X", "fly me over X", "orbit X",
     * "cinematic aerial view of X", etc.
     */
    private fun extractFlyOverQuery(transcript: String): String {
        val patterns = listOf(
            // "fly over|around|by X" / "fly me over X"
            Regex("(?i)\\bfly(?:\\s+me)?\\s+(?:over|around|by|above)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "fly-over of X" / "flyover of X"
            Regex("(?i)\\bfly[- ]?over\\s+(?:of\\s+)?(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "do|start|give me a fly-over of X"
            Regex("(?i)\\b(?:do|start|begin|give\\s+me)\\s+(?:a\\s+)?fly[- ]?over\\s+(?:of\\s+)?(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "orbit X" / "circle X" / "spin around X"
            Regex("(?i)\\b(?:orbit|circle|spin\\s+around)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "take me over X" / "take me to X" (aerial context)
            Regex("(?i)\\b(?:take\\s+me|fly\\s+me)\\s+(?:over|around|above|to)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"),
            // "cinematic|aerial|bird's eye view of X"
            Regex("(?i)\\b(?:cinematic|aerial|bird'?s?\\s+eye)\\s+(?:view|tour)\\s+of\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(transcript)
            if (match != null) {
                val q = match.groupValues[1].trim().replace(Regex("[?.!]+$"), "").trim()
                if (q.isNotBlank()) return cleanLandmarkQuery(q)
            }
        }
        return ""
    }

    /**
     * Strip trailing filler words that confuse Places API geocoding
     * ("please", "for me", "on the map", "in 3d", "now", etc.).
     * Keeps the core landmark name intact.
     */
    private fun cleanLandmarkQuery(raw: String): String {
        var q = raw.trim()
        // Strip leading "the"
        q = q.replace(Regex("(?i)^the\\s+"), "")
        // Strip trailing fillers
        val tail = Regex("(?i)\\s+(please|for me|on the map|on my glasses|now|already|thanks)\\s*$")
        while (tail.containsMatchIn(q)) q = q.replace(tail, "")
        // Strip redundant "in 3d" / "in 3-d" that slipped through
        q = q.replace(Regex("(?i)\\s+in\\s+3[dD]\\s*$"), "")
        // Collapse whitespace and remove trailing punctuation
        q = q.replace(Regex("\\s+"), " ").replace(Regex("[?.!,;:]+$"), "").trim()
        return q
    }

    private fun extractTranslation(transcript: String): Pair<String, String> {
        // Pattern: "translate X to/into Y"
        val translateTo = Regex("(?i)translate\\s+(.+?)\\s+(to|into|in)\\s+(\\w+)\\s*[?.!]*$").find(transcript)
        if (translateTo != null) {
            return translateTo.groupValues[1].trim() to translateTo.groupValues[3].trim()
        }
        // Pattern: "say X in Y" / "how do you say X in Y"
        val sayIn = Regex("(?i)(?:how do you )?say\\s+(.+?)\\s+in\\s+(\\w+)\\s*[?.!]*$").find(transcript)
        if (sayIn != null) {
            return sayIn.groupValues[1].trim() to sayIn.groupValues[2].trim()
        }
        // Pattern: "translate: X" (use default language)
        val translateOnly = Regex("(?i)translate[:\\-\\s]+(.+?)\\s*[?.!]*$").find(transcript)
        if (translateOnly != null) {
            return translateOnly.groupValues[1].trim() to ""
        }
        // Pattern: "translate this/that to Y" (vision mode)
        val translateVision = Regex("(?i)translate\\s+(this|that|what I see|the sign|the menu)\\s*(to|into|in)?\\s*(\\w*)").find(transcript)
        if (translateVision != null) {
            return "camera" to translateVision.groupValues[3].trim()
        }
        return "" to ""
    }

    private fun extractDestination(transcript: String): String {
        val lower = transcript.lowercase()
        // Strip known prefixes to isolate the destination
        val patterns = listOf(
            Regex("(?i)(give me |get me |show me )?(direction|navigate|route|driving|car)s?\\s+(to|for)\\s+"),
            Regex("(?i)how (do i|to|long to) (get to|drive to|reach)\\s+"),
            Regex("(?i)(traffic|commute|drive time|travel time|eta)\\s+(to|for|from here to)\\s+"),
            Regex("(?i)how('?s| is| long is)\\s+(the )?(traffic|drive|commute)\\s+(to|for)\\s+"),
            Regex("(?i)(take me|go|head|car ride|ride)\\s+(to|toward|towards)\\s+")
        )
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val dest = transcript.substring(match.range.last + 1).trim()
                    .replace(Regex("[?.!]+$"), "")  // strip trailing punctuation
                    .trim()
                if (dest.isNotBlank()) return dest
            }
        }
        // Fallback: look for "to <destination>" pattern
        val toMatch = Regex("(?i)\\bto\\s+(.{3,})$").find(lower)
        if (toMatch != null) {
            return toMatch.groupValues[1].replace(Regex("[?.!]+$"), "").trim()
        }
        return ""
    }
}
