package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ResearchRouter(
    private val providerProvider: () -> String?,
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String?,
    private val geminiFallbackApiKeyProvider: () -> String? = { null },
    private val context: Context? = null,
    private val timeoutSecondsProvider: () -> Int = { 0 },
    private val customResearchPromptProvider: () -> String? = { null }
) {

    companion object {
        private const val TAG = "ResearchRouter"
        private const val GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"
        private const val GROQ_RESPONSES_URL = "https://api.groq.com/openai/v1/responses"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val DEFAULT_GEMINI_MODEL = "gemini-3.1-pro-preview"
        private const val DEFAULT_OPENAI_CODEX_MODEL = "gpt-5.2-codex"
        private const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        private const val RESEARCH_SYSTEM_PROMPT =
            "You are the dedicated research report generator for RayNeo X3 AR glasses. " +
                "Return ONLY the final report text. " +
                "Write exactly 2 or 3 medium-length analytical paragraphs in continuous prose, suitable for verbatim readout aloud. " +
                "Be concrete, current, and insight-driven. Cover the core facts, what matters most, and the practical or strategic implications. " +
                "When timing matters, anchor claims with specific years or dates. " +
                "No greeting. No preamble. No first-person meta commentary. " +
                "Do not say things like 'I've generated a report', 'here is a report', or 'would you like me to continue'. " +
                "Do not ask a follow-up question. Do not add bullets unless the user explicitly asks for them. " +
                "Avoid code unless the user explicitly asks for it."

        /**
         * System prompt for research_topic mode='links'. Tells the Pro model to
         * use its googleSearch grounding (already enabled in performGeminiResearch's
         * request body) to retrieve real source URLs and emit them as a chat-card-
         * shaped markdown bullet list with explicit (type:X) tags. The output is
         * parsed by CardUrlExtractor on the device, so the format below must
         * match what that parser accepts: each entry on its own line, list
         * marker (`-` or `1.`), title, optional summary, type tag, URL.
         *
         * NEVER hallucinate URLs — if grounding doesn't return enough sources,
         * return fewer entries rather than padding with guesses. The user will
         * tap each entry; broken URLs are an immediate trust failure.
         */
        private const val LINKS_SYSTEM_PROMPT =
            "You are the dedicated link-discovery agent for RayNeo X3 AR glasses. " +
                "Use your googleSearch grounding tool to retrieve REAL, current source URLs " +
                "for the user's topic. Issue multiple distinct grounding queries from different " +
                "angles (overview, interview, archive, video, official site, encyclopedia) to " +
                "gather a broad source set. Aim for 4–8 grounded sources.\n" +
                "Return ONLY a markdown bullet list of source URLs, one per line, in this exact " +
                "shape:\n" +
                "    Here are sources for <topic>:\n" +
                "    \n" +
                "    - <Title> (type:<X>): <URL>\n" +
                "    - <Title> — <one-line description> (type:<X>): <URL>\n" +
                "    - <Title> (type:<X>): <URL>\n" +
                "    \n" +
                "    Tap any of them, or say \"open the second\" or \"open the YouTube one\".\n" +
                "Where <X> is one of: video, podcast, audio, article, pdf, image, web. Pick the " +
                "most specific type for each entry — the device uses the tag to route the open " +
                "action to the right surface.\n" +
                "URL RULES — NON-NEGOTIABLE:\n" +
                "  • Only emit URLs you actually retrieved from grounding. Hallucinated URLs " +
                "    are an immediate trust failure when the user taps them.\n" +
                "  • Prefer canonical / authoritative sources: Wikipedia, official sites, major " +
                "    publications, archive.org, recognized journals.\n" +
                "  • For YouTube videos, use search URLs " +
                "    (https://www.youtube.com/results?search_query=...) rather than specific " +
                "    /watch?v=ID URLs unless grounding gave you the exact ID. Hallucinated 11-" +
                "    character video IDs 404.\n" +
                "  • Skip an entry rather than guess. A 4-entry list of working links beats an " +
                "    8-entry list with 4 broken ones.\n" +
                "  • No vertexaisearch.cloud.google.com redirect URLs — extract the underlying " +
                "    source URL from the grounding metadata.\n" +
                "If the user's prompt below includes a 'MEDIA-TYPE RESTRICTION' clause, that " +
                "restriction is ABSOLUTE. Restrict your search to the named source types and " +
                "named domain hints; do NOT include encyclopedia / Wikipedia results unless " +
                "the user explicitly requested 'article' as a type. A 3-entry list of the " +
                "requested types beats an 8-entry list that's mostly encyclopedia mismatches.\n" +
                "Do not include preamble or meta-commentary. Do not write paragraphs. Just the " +
                "markdown list as shown above."

        fun formatForDisplay(result: ResearchResult.Success): String =
            "[Research model: ${result.model}]\n${result.text.trim()}"
    }

    sealed class ResearchResult {
        data class Success(
            val text: String,
            val provider: String,
            val model: String
        ) : ResearchResult()

        data class Error(val message: String) : ResearchResult()
        object ApiKeyMissing : ResearchResult()
    }

    suspend fun research(topic: String): ResearchResult = withContext(Dispatchers.IO) {
        val customPrompt = customResearchPromptProvider()?.trim()?.takeIf { it.isNotBlank() }
        val effectiveSystemPrompt = buildResearchSystemPrompt(customPrompt)
        Log.d(TAG, "research: using ${if (customPrompt != null) "CUSTOM" else "default"} system prompt (${effectiveSystemPrompt.length} chars)")
        runPromptInternal(
            prompt = buildResearchUserPrompt(topic),
            systemPrompt = effectiveSystemPrompt
        )
    }

    /**
     * URL-list research mode. Same provider/model surface as research(),
     * but with a different system prompt that demands a markdown bullet
     * list of source URLs (RULE 19 G shape). Pro models use the
     * googleSearch grounding tool already enabled in performGeminiResearch
     * to retrieve real sources, then emit them in the format
     * CardUrlExtractor parses on the device.
     *
     * Why this exists: Gemini Live's grounding caps at 1 chunk per turn,
     * which is too thin to feel like a useful list. The Pro REST endpoint
     * in this Router can issue multiple grounding queries per call and
     * return a much richer source set. This is the path the user takes
     * when they ask for "research links" or pick the "research mode"
     * option from the URL-list offer.
     */
    suspend fun researchLinks(
        topic: String,
        mediaTypes: List<String> = emptyList()
    ): ResearchResult = withContext(Dispatchers.IO) {
        // Build a media-type bias clause when the user specified one.
        // Without this clause the search is unconstrained and the codex
        // / Pro model defaults to encyclopedic sources (Wikipedia,
        // Britannica) because that's the highest-trust shape for the
        // word "research". Naming concrete domains per type gives the
        // grounded search a cleaner signal of where to look.
        val typeBias = buildMediaTypeBiasClause(mediaTypes)
        val effectivePrompt = if (typeBias.isBlank()) {
            "Find a list of authoritative source URLs about: $topic"
        } else {
            "Find a list of source URLs about: $topic.\n\n$typeBias"
        }
        Log.d(
            TAG,
            "researchLinks: topic='${topic.take(80)}' mediaTypes=$mediaTypes " +
                "biasLen=${typeBias.length}"
        )
        val result = runPromptInternal(
            prompt = effectivePrompt,
            systemPrompt = LINKS_SYSTEM_PROMPT,
            // CRITICAL: enable web-search grounding so the model returns
            // REAL URLs from a live search rather than hallucinating from
            // training memory. For Gemini (Pro) the googleSearch tool is
            // already in the request body unconditionally; for OpenAI /
            // Groq we plumb this flag through so the right tool config
            // gets attached.
            enableWebSearch = true
        )
        // Even with grounding enabled, the model still RETYPES URLs into its
        // text and can mis-pair a right title with a wrong/old URL. Re-verify
        // every URL in the output before it reaches the device: resolve
        // grounding redirects to their real page, and re-ground any
        // from-memory URL to the top real result. Defensive — any failure
        // leaves the original line untouched.
        if (result is ResearchResult.Success) {
            val geminiKey = resolveApiKey(geminiFallbackApiKeyProvider())
                ?: resolveApiKey(apiKeyProvider())
            val verified = runCatching { resolveLinksInText(result.text, geminiKey) }
                .getOrDefault(result.text)
            result.copy(text = verified)
        } else {
            result
        }
    }

    /**
     * Re-verify every URL inside a links-mode markdown list. For each line that
     * carries a URL we pull the title (text before the "(type:X)" tag or the
     * URL) and the type tag, then hand them to [GroundedUrlResolver.resolveEntry]
     * which follows grounding redirects, re-grounds from-memory URLs, and falls
     * back to an on-topic search page. Lines are processed in parallel; any
     * line that fails to parse/resolve is left exactly as-is.
     */
    private suspend fun resolveLinksInText(
        text: String,
        geminiApiKey: String?
    ): String = coroutineScope {
        val urlRx = Regex("""https?://\S+""")
        val typeRx = Regex("""\(type:\s*([A-Za-z]+)\s*\)""")
        text.split("\n").map { line ->
            async(Dispatchers.IO) {
                val match = urlRx.find(line) ?: return@async line
                val rawUrl = match.value.trimEnd('.', ',', ')', ']', '>', '"', '\'', ' ')
                if (rawUrl.isBlank()) return@async line
                val type = typeRx.find(line)?.groupValues?.get(1)?.lowercase() ?: "web"
                val beforeUrl = line.substringBefore(match.value)
                val title = beforeUrl
                    .substringBefore("(type:")
                    .trim()
                    .trimStart('-', '*', '•', ' ')
                    .trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ')', ' ')
                    .substringBefore(" — ")
                    .substringBefore(" – ")
                    .substringBefore(" - ")
                    .trim()
                    .ifBlank { rawUrl }
                val resolved = runCatching {
                    GroundedUrlResolver.resolveEntry(geminiApiKey, title, type, rawUrl)
                }.getOrNull()
                if (resolved.isNullOrBlank() || resolved == rawUrl) line
                else line.replace(rawUrl, resolved)
            }
        }.awaitAll().joinToString("\n")
    }

    /**
     * Translate the user's media-type filter into a prompt clause that
     * tells the model which kinds of sites to prefer. The mapping is
     * intentionally small and well-known — encyclopedia results are
     * fine when the user didn't filter, but when they did, naming
     * concrete domains per type beats abstract instruction.
     *
     * Sites listed are NOT exhaustive; they're anchors to bias the
     * grounded search. The system prompt also tells the model to drop
     * non-matching results rather than pad the list with mismatches.
     */
    private fun buildMediaTypeBiasClause(mediaTypes: List<String>): String {
        if (mediaTypes.isEmpty()) return ""
        val mapped = mediaTypes.mapNotNull { type ->
            when (type) {
                "video", "videos" ->
                    "VIDEO sources — prefer youtube.com, vimeo.com, " +
                        "dailymotion.com. Use search-form URLs " +
                        "(youtube.com/results?search_query=…) when you don't " +
                        "have a specific video ID; never invent a /watch?v=ID."
                "podcast", "podcasts" ->
                    "PODCAST sources — prefer podcasts.apple.com, " +
                        "podbean.com, spotify.com/show/, anchor.fm, " +
                        "buzzsprout.com, simplecast.com, ivoox.com."
                "audio", "music", "song", "track", "mp3" ->
                    "AUDIO sources — prefer archive.org/details/, " +
                        "soundcloud.com, bandcamp.com, freemusicarchive.org, " +
                        "and direct mp3/m4a/wav stream URLs from " +
                        "trustworthy archives."
                "article", "blog", "essay", "post" ->
                    "ARTICLE sources — prefer recognized publishers, " +
                        "Wikipedia, Substack, Medium, major newspapers, " +
                        "academic journals. Avoid SEO content farms."
                "pdf" ->
                    "PDF sources — prefer arxiv.org, .gov / .edu " +
                        "domains, recognized publishers' .pdf files."
                "image", "img", "photo" ->
                    "IMAGE sources — prefer wikipedia.org, archive.org, " +
                        "museum / library digital collections."
                "web", "page", "site" ->
                    "WEB sources — official sites, primary references, " +
                        "well-known landing pages."
                else -> null
            }
        }
        if (mapped.isEmpty()) return ""
        val typeNames = mediaTypes.joinToString(", ")
        return buildString {
            append("MEDIA-TYPE RESTRICTION — STRICT. The user explicitly ")
            append("asked for sources of these types ONLY: $typeNames. ")
            append("Honor this restriction. Drop any candidate that doesn't ")
            append("match — it's better to return 3 entries that are the ")
            append("requested types than 8 that include unrequested types ")
            append("like Wikipedia or general encyclopedias when those ")
            append("weren't asked for.\n")
            for (line in mapped) {
                append("  • ")
                append(line)
                append('\n')
            }
            append("Tag every entry with the matching (type:X) annotation. ")
            append("If a search returns no usable results within the ")
            append("requested types, return a shorter list rather than ")
            append("substituting a different type.")
        }
    }

    suspend fun runPrompt(
        prompt: String,
        systemPrompt: String = RESEARCH_SYSTEM_PROMPT
    ): ResearchResult = withContext(Dispatchers.IO) {
        runPromptInternal(prompt = prompt, systemPrompt = systemPrompt)
    }

    private suspend fun runPromptInternal(
        prompt: String,
        systemPrompt: String,
        enableWebSearch: Boolean = false
    ): ResearchResult = withContext(Dispatchers.IO) {
        val resolvedProvider = resolveProvider(providerProvider())
        val apiKey = when (resolvedProvider) {
            Provider.GEMINI -> resolveApiKey(apiKeyProvider()) ?: resolveApiKey(geminiFallbackApiKeyProvider())
            Provider.OPENAI_CODEX, Provider.GROQ -> resolveApiKey(apiKeyProvider())
        }

        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Missing API key for provider=$resolvedProvider")
            return@withContext ResearchResult.ApiKeyMissing
        }

        val model = resolveModel(resolvedProvider, modelProvider())
        Log.d(
            TAG,
            "runPromptInternal: provider=$resolvedProvider model=$model " +
                "enableWebSearch=$enableWebSearch promptLen=${prompt.length} " +
                "systemPromptLen=${systemPrompt.length}"
        )

        return@withContext try {
            when (resolvedProvider) {
                Provider.GEMINI -> performGeminiResearch(apiKey, model, prompt, systemPrompt)
                Provider.OPENAI_CODEX -> performOpenAiResearch(apiKey, model, prompt, systemPrompt, enableWebSearch)
                Provider.GROQ -> performGroqResearch(apiKey, model, prompt, systemPrompt, enableWebSearch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Research request failed provider=$resolvedProvider model=$model", e)
            ResearchResult.Error(e.localizedMessage ?: "Research request failed")
        }
    }

    private fun performGeminiResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String
    ): ResearchResult {
        val requestBody = JSONObject()
            .put("systemInstruction", JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", systemPrompt))
            ))
            .put("contents", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(
                        JSONObject().put(
                            "text",
                            prompt
                        )
                    ))
            ))
            .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.25)
                .put("maxOutputTokens", 2048)
            )

        var lastError: String? = null
        for (candidate in buildGeminiModelFallbacks(model)) {
            val response = postJson(
                url = "$GOOGLE_BASE_URL/$candidate:generateContent?key=$apiKey",
                requestBody = requestBody,
                headers = mapOf("Content-Type" to "application/json")
            )
            if (response.code in 200..299) {
                val text = extractGeminiText(response.body).ifBlank {
                    throw IllegalStateException("Gemini research returned empty output")
                }
                Log.d(TAG, "Gemini research succeeded model=$candidate chars=${text.length}")
                return ResearchResult.Success(text = text, provider = "gemini", model = candidate)
            }

            lastError = "Gemini research HTTP ${response.code}"
            Log.w(TAG, "Gemini research failed model=$candidate code=${response.code} body=${response.body.take(240)}")
            if (response.code != 404) {
                break
            }
        }

        throw IllegalStateException(lastError ?: "Gemini research unavailable")
    }

    private fun performOpenAiResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String,
        enableWebSearch: Boolean
    ): ResearchResult {
        return performOpenAiCompatibleResearch(
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            systemPrompt = systemPrompt,
            endpoint = OPENAI_RESPONSES_URL,
            providerLabel = "openai_codex",
            enableWebSearch = enableWebSearch
        )
    }

    private fun performGroqResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String,
        enableWebSearch: Boolean
    ): ResearchResult {
        return performOpenAiCompatibleResearch(
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            systemPrompt = systemPrompt,
            endpoint = GROQ_RESPONSES_URL,
            providerLabel = "groq",
            // Groq's OpenAI-compatible endpoint doesn't have a hosted
            // web-search tool. We forward the flag for symmetry but
            // it's a no-op there — the tool array stays empty. URL-
            // hallucination on Groq is therefore unavoidable; users
            // who want grounded URLs should pick Gemini Pro or OpenAI.
            enableWebSearch = false
        )
    }

    private fun performOpenAiCompatibleResearch(
        apiKey: String,
        model: String,
        prompt: String,
        systemPrompt: String,
        endpoint: String,
        providerLabel: String,
        enableWebSearch: Boolean
    ): ResearchResult {
        val requestBody = JSONObject()
            .put("model", model)
            .put("input", JSONArray()
                .put(JSONObject()
                    .put("role", "system")
                    .put("content", JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", systemPrompt)
                    )))
                .put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(
                        JSONObject()
                            .put("type", "input_text")
                            .put("text", prompt)
                    )))
            )
            .put("max_output_tokens", 3072)

        // OpenAI's hosted web-search tool. When attached, the model
        // can issue real-time queries against the web during reasoning
        // and the URLs in its output are pulled from actual search
        // results rather than fabricated from training memory. This is
        // the difference between "research returned working URLs" and
        // "research hallucinated plausible-looking 404s".
        //
        // Different OpenAI model families accept slightly different
        // tool type names. We prefer "web_search" (the stable name)
        // and keep "web_search_preview" as a fallback because some
        // older / preview models reject the stable name with HTTP 400.
        // The tool config is small enough that sending both isn't
        // accepted — we have to pick one. Try the stable name first;
        // if the user's model rejects it the error surfaces clearly
        // in the log and they can switch models.
        if (enableWebSearch) {
            requestBody.put(
                "tools",
                JSONArray().put(JSONObject().put("type", "web_search"))
            )
            // Hint the model to actually USE the tool (rather than
            // ignoring it and answering from memory). Setting
            // tool_choice="required" forces a tool call before final
            // output. If the model doesn't support this knob, the API
            // ignores it.
            requestBody.put(
                "tool_choice",
                JSONObject().put("type", "web_search")
            )
        }

        Log.d(
            TAG,
            "$providerLabel research POST endpoint=$endpoint model=$model " +
                "webSearch=$enableWebSearch bodyChars=${requestBody.toString().length}"
        )

        val response = postJson(
            url = endpoint,
            requestBody = requestBody,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey"
            )
        )
        if (response.code !in 200..299) {
            // Surface the error body — the OpenAI API returns very
            // specific failure reasons (e.g. "tool web_search not
            // supported on model X") that help us iterate without
            // guessing.
            Log.w(
                TAG,
                "$providerLabel research HTTP ${response.code} body=${response.body.take(400)}"
            )
            throw IllegalStateException("$providerLabel research HTTP ${response.code}: ${response.body.take(180)}")
        }

        val text = extractOpenAiText(response.body).ifBlank {
            throw IllegalStateException("$providerLabel research returned empty output")
        }
        Log.d(
            TAG,
            "$providerLabel research returned ${text.length} chars; " +
                "first 240=${text.take(240).replace('\n', ' ')}"
        )
        return ResearchResult.Success(text = text, provider = providerLabel, model = model)
    }

    private fun extractGeminiText(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: return ""
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            val builder = StringBuilder()
            for (j in 0 until parts.length()) {
                val text = parts.optJSONObject(j)?.optString("text", "").orEmpty().trim()
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(text)
                }
            }
            val value = builder.toString().trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun buildResearchSystemPrompt(customPrompt: String?): String {
        if (customPrompt.isNullOrBlank()) return RESEARCH_SYSTEM_PROMPT
        return buildString {
            append(RESEARCH_SYSTEM_PROMPT)
            append("\n\nThe following user-specified research instructions are authoritative and should be followed exactly unless they conflict with the non-negotiable output rules above:\n")
            append(customPrompt)
        }
    }

    private fun buildResearchUserPrompt(topic: String): String =
        buildString {
            append("Topic: ")
            append(topic.trim())
            append("\n\n")
            append("Return the finished research report only. ")
            append("Write 2 or 3 analytical paragraphs, not a teaser or acknowledgement. ")
            append("Do not greet the user. Do not mention that you generated a report. ")
            append("Do not ask whether they want more. ")
            append("Make the prose readable aloud on AR glasses.")
        }

    private fun extractOpenAiText(body: String): String {
        val root = JSONObject(body)
        val topLevel = root.optString("output_text", "").trim()
        if (topLevel.isNotBlank()) return topLevel

        val output = root.optJSONArray("output") ?: return ""
        val builder = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                val text = part.optString("text", "").trim()
                if (text.isBlank()) continue
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(text)
            }
        }
        return builder.toString().trim()
    }

    private fun postJson(
        url: String,
        requestBody: JSONObject,
        headers: Map<String, String>
    ): HttpResponse {
        val userTimeout = timeoutSecondsProvider()
        val effectiveReadTimeout = if (userTimeout > 0) userTimeout * 1000 else READ_TIMEOUT_MS
        val response = ActiveNetworkHttp.postJson(
            url = url,
            jsonBody = requestBody.toString(),
            headers = headers,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = effectiveReadTimeout
        )
        return HttpResponse(code = response.code, body = response.body)
    }

    private fun resolveApiKey(raw: String?): String? {
        val key = raw.orEmpty().trim()
        return key.takeIf { it.isNotBlank() }
    }

    private fun resolveModel(provider: Provider, configured: String?): String {
        val value = configured.orEmpty().trim()
        if (value.isNotBlank()) return value
        return when (provider) {
            Provider.GEMINI -> DEFAULT_GEMINI_MODEL
            Provider.OPENAI_CODEX -> DEFAULT_OPENAI_CODEX_MODEL
            Provider.GROQ -> DEFAULT_GROQ_MODEL
        }
    }

    private fun resolveProvider(raw: String?): Provider {
        return when (raw.orEmpty().trim().lowercase()) {
            "openai_codex", "openai-codex", "codex", "openai" -> Provider.OPENAI_CODEX
            "groq" -> Provider.GROQ
            else -> Provider.GEMINI
        }
    }

    private fun buildGeminiModelFallbacks(requested: String): List<String> {
        return listOf(
            requested.trim(),
            DEFAULT_GEMINI_MODEL,
            "gemini-3-flash-preview",
            "gemini-2.5-pro"
        ).filter { it.isNotBlank() }.distinct()
    }

    private enum class Provider {
        GEMINI,
        OPENAI_CODEX,
        GROQ
    }

    private data class HttpResponse(
        val code: Int,
        val body: String
    )
}
