package com.rayneo.visionclaw.core.network

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.learn.LearnLmMemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LearnLmRouter(
    private val apiKeyProvider: () -> String?,
    private val modelProvider: () -> String?,
    private val recentCardsProvider: () -> List<String>,
    context: Context,
    private val currentImageBase64Provider: (() -> String?)? = null,
    private val timeoutSecondsProvider: () -> Int = { 0 }
) {

    companion object {
        private const val TAG = "LearnLmRouter"
        private const val GOOGLE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val DEFAULT_MODEL = "learnlm-2.0-flash-experimental"
        private const val FALLBACK_MODEL = "gemini-3.1-pro-preview"
        private const val LEARN_SYSTEM_PROMPT =
            "You are LearnLM for RayNeo X3 AR glasses: a patient, practical personal tutor for academia, vocational work, gardening, cooking, and electronics. " +
                "On your FIRST response in a new session, briefly introduce yourself: 'I'm LearnLM, your personal tutor.' — then move straight into teaching. " +
                "Do NOT re-introduce yourself on follow-up turns in the same session. " +
                "Teach for mastery, not just quick answers. Start from the learner's goal, explain clearly, scaffold the steps, and continue from prior lesson context when it is relevant. " +
                "For procedural tasks, include tools or materials, safety notes, step-by-step guidance, common mistakes, and one short practice checkpoint. " +
                "For conceptual topics, connect ideas, give an intuitive explanation, then one concrete example. " +
                "Keep the response readable on AR glasses: short sections, concise bullets when useful, and no browser links unless explicitly requested."

        fun formatForDisplay(result: LearnResult.Success): String =
            "[LearnLM model: ${result.model}]\n${result.text.trim()}"
    }

    sealed class LearnResult {
        data class Success(
            val text: String,
            val model: String,
            val topic: String
        ) : LearnResult()

        data class Error(val message: String) : LearnResult()
        object ApiKeyMissing : LearnResult()
    }

    private val memoryStore = LearnLmMemoryStore(context)

    suspend fun teach(query: String): LearnResult = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider().orEmpty().trim()
        if (apiKey.isBlank()) return@withContext LearnResult.ApiKeyMissing

        val normalizedQuery = memoryStore.normalizeQuery(query)
        val recentCards = recentCardsProvider()
        val memoryContext = memoryStore.buildContext(normalizedQuery, recentCards)
        val currentImageBase64 = currentImageBase64Provider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
        val recalledImageBase64 = if (currentImageBase64 == null && memoryContext.isContinuation) {
            memoryStore.latestReferenceImageBase64(memoryContext)
        } else {
            null
        }
        val attachedImageBase64 = currentImageBase64 ?: recalledImageBase64
        val parts = JSONArray().apply {
            put(JSONObject().put("text", buildPrompt(normalizedQuery, memoryContext, currentImageBase64 != null, recalledImageBase64 != null)))
            if (attachedImageBase64 != null) {
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", attachedImageBase64)
                    )
                )
            }
        }
        val requestBody = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", LEARN_SYSTEM_PROMPT))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.6)
                    .put("maxOutputTokens", 1536)
            )

        val candidateModels = buildModelFallbacks(modelProvider())
        var lastError: String? = null

        for (candidate in candidateModels) {
            runCatching {
                val userTimeout = timeoutSecondsProvider()
                val effectiveReadTimeout = if (userTimeout > 0) userTimeout * 1000 else READ_TIMEOUT_MS
                val response = ActiveNetworkHttp.postJson(
                    url = "$GOOGLE_BASE_URL/$candidate:generateContent?key=$apiKey",
                    jsonBody = requestBody.toString(),
                    headers = mapOf("Content-Type" to "application/json"),
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = effectiveReadTimeout
                )
                if (response.code in 200..299) {
                    val text = extractGeminiText(response.body).trim()
                    if (text.isBlank()) {
                        lastError = "LearnLM tutor returned empty output. Try again."
                        Log.w(TAG, "LearnLM tutor empty output model=$candidate body=${response.body.take(240)}")
                        return@runCatching null
                    }
                    memoryStore.saveLesson(
                        topic = memoryContext.topicLabel,
                        query = normalizedQuery,
                        response = text,
                        relatedCards = memoryContext.relatedCards,
                        referenceImageBase64 = attachedImageBase64
                    )
                    Log.d(
                        TAG,
                        "LearnLM tutor succeeded model=$candidate chars=${text.length} continuation=${memoryContext.isContinuation} currentImage=${currentImageBase64 != null} recalledImage=${recalledImageBase64 != null} lessons=${memoryContext.priorLessons.size}"
                    )
                    return@withContext LearnResult.Success(
                        text = text,
                        model = candidate,
                        topic = memoryContext.topicLabel
                    )
                }

                lastError = "LearnLM tutor HTTP ${response.code}"
                Log.w(TAG, "LearnLM tutor failed model=$candidate code=${response.code} body=${response.body.take(240)}")
                if (response.code != 404) {
                    return@withContext LearnResult.Error(lastError ?: "LearnLM tutor unavailable")
                }
                null
            }.onFailure { error ->
                lastError = error.message?.trim().takeUnless { it.isNullOrBlank() } ?: "LearnLM tutor unavailable"
                Log.e(TAG, "LearnLM tutor exception model=$candidate", error)
            }
        }

        LearnResult.Error(lastError ?: "LearnLM tutor unavailable")
    }

    private fun buildPrompt(
        query: String,
        memoryContext: LearnLmMemoryStore.MemoryContext,
        hasCurrentImage: Boolean,
        hasRecalledImage: Boolean
    ): String {
        val primaryLesson = memoryContext.priorLessons.firstOrNull()
        val primaryLessonBlock =
            if (primaryLesson == null) {
                "None"
            } else {
                buildString {
                    append("Topic: ")
                    append(primaryLesson.topic)
                    append("\nOriginal learner request: ")
                    append(primaryLesson.query)
                    append("\nSaved summary: ")
                    append(primaryLesson.summary)
                    primaryLesson.lessonExcerpt?.takeIf { it.isNotBlank() }?.let {
                        append("\nSaved lesson excerpt: ")
                        append(it)
                    }
                    if (!primaryLesson.referenceImagePath.isNullOrBlank()) {
                        append("\nSaved reference image: yes")
                    }
                }
            }

        val priorLessonsBlock =
            if (memoryContext.priorLessons.isEmpty()) {
                "None"
            } else {
                memoryContext.priorLessons.joinToString("\n") { lesson ->
                    buildString {
                        append("- Topic: ")
                        append(lesson.topic)
                        append(" | request: ")
                        append(lesson.query)
                        append(" | summary: ")
                        append(lesson.summary)
                        lesson.referenceImagePath?.let {
                            append(" (reference image saved)")
                        }
                    }
                }
            }

        val relatedCardsBlock =
            if (memoryContext.relatedCards.isEmpty()) {
                "None"
            } else {
                memoryContext.relatedCards.joinToString("\n") { card ->
                    "- ${card.replace('\n', ' ').trim()}"
                }
            }

        return buildString {
            append("Learning goal: ")
            append(query.trim())
            append("\n\n")
            append("Topic label: ")
            append(memoryContext.topicLabel)
            append("\n\n")
            append("Continuation turn: ")
            append(if (memoryContext.isContinuation) "yes" else "no")
            append("\n\n")
            append("Canonical previous lesson to continue:\n")
            append(primaryLessonBlock)
            append("\n\n")
            append("Relevant prior lessons:\n")
            append(priorLessonsBlock)
            append("\n\n")
            append("Related recent chat cards:\n")
            append(relatedCardsBlock)
            append("\n\n")
            append("Current camera reference image attached: ")
            append(if (hasCurrentImage) "yes" else "no")
            append("\n")
            append("Prior saved lesson image reattached: ")
            append(if (hasRecalledImage) "yes" else "no")
            append("\n\n")
            append(
                "Respond like a continuing tutor. If a canonical previous lesson is provided above, that IS the previous problem. " +
                    "Do not say the context, image, or previous problem is missing when that block is present. " +
                    "If this is a continuation turn, START your response with a brief 1-2 sentence recap of where the learner left off " +
                    "(e.g. 'Last time we were working on [topic] and got to [step/concept]. Let's pick up from there.'). " +
                    "Then continue from that exact saved lesson instead of restarting from zero. " +
                    "If an attached image came from a prior saved lesson, use it as the visual reference for the continuation. " +
                    "After the recap, continue with the teaching, then a brief next-step or practice check."
            )
        }
    }

    private fun buildModelFallbacks(configuredModel: String?): List<String> {
        val configured = configuredModel.orEmpty().trim()
        return buildList {
            if (configured.isNotBlank()) add(configured)
            add(DEFAULT_MODEL)
            add(FALLBACK_MODEL)
        }.distinct()
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
}
