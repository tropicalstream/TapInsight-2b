package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Renders a scrollable picker list of video suggestions on the user's AR
 * glasses. Each row opens that specific title on YouTube; a "Play all as a
 * playlist" button opens YouTube's playlists-tab search for the topic.
 *
 * Flow: Gemini describes a few options verbally (Phase A in DEFAULT_URL_RULES),
 * then calls this tool so the user has something tappable to choose from.
 */
class SendVideoListTool(private val context: Context) : AiTapTool {
    override val name = "send_video_list"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topicArg = args["topic"].orEmpty().trim()
        val titleArg = args["title"].orEmpty().trim().ifBlank { topicArg }
        val rawVideos = args["videos"].orEmpty().trim()
        val display = args["display"].orEmpty().trim().lowercase(Locale.US)

        if (rawVideos.isBlank()) {
            return Result.failure(
                IllegalArgumentException("send_video_list requires a 'videos' JSON array")
            )
        }

        // Gemini sometimes wraps its JSON in backticks or code fences. Strip
        // them before parsing so we don't reject a good call for formatting.
        val cleaned = rawVideos
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val videosArray = try {
            JSONArray(cleaned)
        } catch (e: Exception) {
            Log.w("SendVideoListTool", "videos arg was not a JSON array: ${e.message}")
            return Result.failure(
                IllegalArgumentException(
                    "send_video_list 'videos' must be a JSON array of {title, creator, reason} objects."
                )
            )
        }

        if (videosArray.length() == 0) {
            return Result.failure(
                IllegalArgumentException("send_video_list needs at least one video in 'videos'.")
            )
        }

        // Normalize each entry so the HTML page can trust the shape.
        val normalized = JSONArray()
        for (i in 0 until videosArray.length()) {
            val entry = videosArray.opt(i)
            val obj = when (entry) {
                is JSONObject -> entry
                is String -> JSONObject().put("title", entry)
                else -> continue
            }
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            normalized.put(
                JSONObject()
                    .put("title", title)
                    .put("creator", obj.optString("creator").trim())
                    .put("reason", obj.optString("reason").trim())
            )
        }

        if (normalized.length() == 0) {
            return Result.failure(
                IllegalArgumentException("send_video_list couldn't read any valid video titles.")
            )
        }

        val payload = JSONObject()
            .put("title", titleArg.ifBlank { "Video picks" })
            .put("topic", topicArg)
            .put("videos", normalized)
            .toString()
        cacheRecentSuggestions(payload)

        if (display in setOf("cache", "voice", "none", "no_open", "hidden")) {
            // June-12 ctrl46 fix, proper form: this text is NOT narrated by
            // Gemini Live anymore. The voice pipeline routes send_video_list
            // through the deterministic readout engine (ENGINE_READOUT_TOOLS
            // in GeminiVoicePipeline), which speaks this string VERBATIM via
            // Fish/Gemini TTS while Gemini Live's audio is suppressed. Asking
            // the native-audio model to recite long verbatim titles sent it
            // into a repetition loop ending in a raw <ctrl46> control token —
            // the TTS engine has no such failure mode. So: build clean,
            // listenable text, full titles included, no model instructions.
            val listText = buildString {
                append("I found ${normalized.length()} YouTube ")
                append(if (normalized.length() == 1) "video" else "videos")
                if (topicArg.isNotBlank()) append(" about $topicArg")
                append(".\n")
                for (i in 0 until normalized.length()) {
                    val item = normalized.optJSONObject(i) ?: continue
                    append(i + 1)
                    append(". ")
                    append(item.optString("title").trim())
                    val creator = item.optString("creator").trim()
                    if (creator.isNotBlank()) {
                        append(", by ")
                        append(creator)
                    }
                    append(".\n")
                }
                append("Want the list on your screen, or should I play one?")
            }
            Log.d("SendVideoListTool", "Cached voice list topic='$topicArg' count=${normalized.length()}")
            return Result.success(listText.trim())
        }

        val encoded = URLEncoder.encode(payload, "UTF-8")
        val url = "file:///android_asset/video_list.html?data=$encoded"
        Log.d(
            "SendVideoListTool",
            "Rendering list topic='$topicArg' count=${normalized.length()} url=${url.take(120)}"
        )
        // Matching TapLinkTool's return shape so the MainActivity autoOpenUrl
        // interceptor can forward this straight to TapBrowser without a
        // round-trip through Gemini.
        return Result.success("taplink://$url")
    }

    private fun cacheRecentSuggestions(payload: String) {
        context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("recent_youtube_suggestions_json", payload)
            .putLong("recent_youtube_suggestions_ms", System.currentTimeMillis())
            .apply()
    }
}
