package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.HermesClient
import com.rayneo.visionclaw.core.network.RelayUrlHelper
import com.rayneo.visionclaw.core.session.CaptureFeedback
import com.rayneo.visionclaw.core.storage.NotesStore
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HermesTool — Gemini Live tool registered as `hermes_agent`.
 *
 * When the user says something prefixed with "hermes" (case-insensitive),
 * RULE ZERO in GeminiRouter routes the full request to this tool. The
 * tool forwards the text + optional camera frame to the user's Hermes
 * Agent API server via [HermesClient] and returns the streamed response.
 */
class HermesTool(
    context: Context,
    private val hermesClient: HermesClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    companion object {
        private const val TAG = "HermesTool"

        /** Words that suggest the user wants vision/image analysis */
        private val VISION_KEYWORDS = setOf(
            "see", "look", "image", "photo", "picture", "camera",
            "analyze", "describe", "identify", "recognize", "read",
            "what is this", "what's this", "show", "visual", "scan",
            "ocr", "text in", "sign", "label", "screen", "view"
        )

        private val EXPLICIT_CAMERA_QUERY = Regex(
            """(?i)\b(camera|photo|picture|image|screen|sign|label|what\s+do\s+you\s+see|what\s+am\s+i\s+looking\s+at|through\s+my\s+glasses|visible|in\s+front\s+of\s+me)\b"""
        )

        /**
         * Save-intent phrasings that should PERSIST the live camera frame into
         * the agent workspace (via the image relay), on top of the normal
         * vision triggers. Covers many natural ways to ask for a snapshot:
         * "save this picture", "take a photo", "capture what I'm looking at",
         * "snapshot this", "save it to your workspace", etc. Kept image-specific
         * so ordinary "save a note" / "save the file" requests don't trip it.
         */
        private val SAVE_IMAGE_QUERY = Regex(
            """(?i)\b(?:save|capture|grab|store|keep|take|snap|snapshot|photograph)\b[^.?!]{0,40}\b(?:picture|photo|image|snapshot|shot|frame|camera|view|what\s+i(?:'m| am)?\s+(?:looking at|seeing))\b""" +
                """|\btake\s+a\s+(?:picture|photo|snapshot|shot)\b""" +
                """|\bsnapshot\s+this\b""" +
                """|\bsave\s+(?:this|it|that)\s+to\s+(?:your|the)\s+(?:workspace|files)\b"""
        )
    }

    override val name = "hermes_agent"
    private val appContext = context.applicationContext
    private val artifactStore = ReadableArtifactStore(context)
    private val prefs = AppPreferences(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val query = args["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return Result.failure(Exception("No query provided for Hermes."))
        }

        // An explicit on-screen frame (raw WebView screenshot) supplied by the
        // caller wins over the live camera: when the user asked Hermes to look
        // at what's ON SCREEN, send those exact pixels, not the camera.
        val screenFrame = args["image_base64"]?.takeIf { it.isNotBlank() }
        val explicitCameraQuery = EXPLICIT_CAMERA_QUERY.containsMatchIn(query)
        val saveRequest = SAVE_IMAGE_QUERY.containsMatchIn(query)
        val includeImage = screenFrame != null
            || (args["include_image"]?.toBooleanStrictOrNull() == true && explicitCameraQuery)
            || isVisionQuery(query)
            || saveRequest
        val imageBase64 = screenFrame ?: if (includeImage) frameProvider() else null
        val hasImage = !imageBase64.isNullOrBlank()

        // Explicit "save the picture" but the camera isn't on → say so plainly
        // instead of silently sending a text-only request Hermes can't fulfil.
        if (saveRequest && !hasImage) {
            return Result.failure(
                Exception("I can't save a picture — the camera isn't active. Turn the camera on and try again.")
            )
        }

        // Persist the live frame into the agent's workspace via the image relay
        // (same mechanism OpenClaw uses). The relay writes camera_frame.jpg AND
        // a permanent timestamped copy under saved_photos/, so Hermes can open
        // or process the real file — not only the inline image. Best-effort: if
        // the relay isn't reachable, the inline image still goes through.
        // We always do this when an image is attached so the workspace file is
        // available; a save-intent phrase additionally tells Hermes to confirm.
        val savedToWorkspace = if (hasImage) {
            CaptureFeedback.captured() // frame is locked in — stop holding still
            withContext(Dispatchers.IO) { uploadFrameToRelay(imageBase64!!) }
        } else false
        if (savedToWorkspace) CaptureFeedback.delivered("your workspace")

        Log.d(TAG, "Hermes call: vision=$includeImage hasFrame=$hasImage save=$saveRequest saved=$savedToWorkspace query=${query.take(100)}")

        // When the user asks Hermes about their notes, hand over the on-glasses
        // notes file content as context so Hermes answers from the real notes —
        // this is how Hermes "finds" notes that live on the glasses, without a
        // flaky reverse-fetch.
        val baseContext = args["context"]?.takeIf { it.isNotBlank() }
        val notesContext = if (AssistantIntentParser.isNotesRecallRequest(query)) {
            NotesStore.readRecent(appContext)?.let {
                "[The user's saved voice notes (source: glasses ${NotesStore.NOTES_RELATIVE_PATH}) — " +
                    "answer from these]:\n$it"
            }
        } else null
        val context = listOfNotNull(baseContext, notesContext)
            .joinToString("\n\n").takeIf { it.isNotBlank() }

        // Tell Hermes the frame is sitting in its workspace as a real file.
        val savedNote = if (savedToWorkspace) {
            buildString {
                append("\n\n[CAMERA FRAME SAVED: the current camera frame is in your workspace as ")
                append("camera_frame.jpg, with a permanent timestamped copy under saved_photos/. ")
                append("You can open or process that file directly. ")
                if (saveRequest) {
                    append("The user explicitly asked you to SAVE this picture — confirm it is saved ")
                    append("(mention saved_photos/) and, if helpful, copy it to a descriptive filename.")
                }
                append("]")
            }
        } else ""

        // Append the media-delivery contract so Hermes knows HOW to actually
        // play / show media on the glasses (not just describe a link). The
        // glasses act on an `open_taplink:<url>` directive line in the reply.
        val augmentedQuery = query + savedNote + mediaRoutingHint()

        return when (val result = hermesClient.sendMessage(augmentedQuery, context, imageBase64)) {
            is HermesClient.ClawResult.Success -> {
                // Inline-vision case (image went in the request, not the
                // workspace): confirm Hermes actually received it.
                if (hasImage && !savedToWorkspace) CaptureFeedback.delivered("Hermes")
                val response = buildString {
                    append(result.text)
                    if (!result.sessionId.isNullOrBlank() && result.sessionId != "main") {
                        append("\n[Hermes session: ${result.sessionId}]")
                    }
                }
                val readable = extractReadableText(response)
                if (readable.isNotBlank()) {
                    artifactStore.saveLatest(
                        kind = ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT,
                        title = deriveTitle(readable),
                        text = readable,
                        sourceLabel = "hermes"
                    )
                }
                Result.success(response)
            }
            is HermesClient.ClawResult.NotConfigured ->
                Result.failure(Exception("Hermes is not configured. Set the endpoint URL and API key in TapInsight setup."))
            is HermesClient.ClawResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /**
     * Media-delivery contract appended to every Hermes query. Tells Hermes how
     * to make the glasses actually PLAY/SHOW media (audio, video, images) — not
     * just speak a link — and how to SAVE it into the on-glasses Media Library.
     * The glasses parse an `open_taplink:<url>` directive line out of the reply,
     * open it in the native TapInsight player, and strip it from the spoken /
     * displayed text. Guidance only — it has no effect when no media is involved.
     */
    private fun mediaRoutingHint(): String {
        val relayBase = buildRelayBaseUrl()
        val relayInstruction = if (relayBase != null) {
            "The configured media relay for this glasses session is $relayBase. " +
                "The relay exposes these Mac folders: /Users/me/hermes-media and " +
                "/Users/me/.openclaw/workspace. When the user says 'media folder', search those " +
                "exact folders first, especially /Users/me/hermes-media; do not waste time searching " +
                "Downloads/Desktop/Pictures unless the user specifically asks for those locations. " +
                "For existing media requested by voice, the preferred lookup is: " +
                "python3 ~/.hermes/skills/mlops/minimax-media/scripts/serve.py \"<user request>\". " +
                "Use the open_taplink line printed by that script. " +
                "You may list exposed files safely at $relayBase/media-index.json or $relayBase/media. " +
                "If Hermes creates or selects a local file, save/copy it under one of those exposed " +
                "folders, then use $relayBase/media/<filename>. Do not invent another media host. "
        } else {
            "No concrete media relay URL is configured in TapInsight. If the file only exists locally, " +
                "first expose it at a public media relay URL and use that exact URL. "
        }
        return "\n\n[TapInsight media delivery — how to PLAY or SHOW media on the user's AR glasses " +
            "(not just send a link the user must tap): end your reply with a directive on its OWN " +
            "final line, exactly 'open_taplink:<URL>'. The <URL> MUST be an absolute, publicly " +
            "fetchable http(s) URL pointing at the media in its NATIVE format (e.g. .mp3/.m4a/.aac " +
            "audio, .mp4/.webm video, .jpg/.png image) — NEVER a Hermes server-local path such as " +
            "/home/.../song.mp3 (the glasses cannot read your filesystem). If the file only exists " +
            "locally, use the configured media relay below. $relayInstruction Pick the " +
            "player by type: " +
            "AUDIO → open_taplink:https://appassets.androidplatform.net/assets/media_player.html?type=audio&url=<URL-ENCODED-AUDIO-URL>&title=<NAME>; " +
            "VIDEO → the same with type=video; " +
            "IMAGE → open_taplink:<direct-image-URL> (the viewer displays it); " +
            "a direct video page like YouTube → open_taplink:<watch-url>. " +
            "Keep the SPOKEN sentence short (e.g. 'Playing Woven Strings Atlas.') — the directive " +
            "line is consumed by the glasses and is never read aloud. " +
            "To additionally SAVE media into the on-glasses Media Library (folders Music/, Videos/, " +
            "Text/, Playlists/) rather than only playing it, use the TapInsight companion API " +
            "(POST /api/library/write); only state it was saved after the write or a library " +
            "listing confirms it.]"
    }

    /**
     * Hermes media files are normally emitted on the same host as the Hermes
     * API server, with tools/image_relay.py serving them on port 18790. Mirror
     * OpenClaw's existing convention so Hermes gets an exact relay origin
     * instead of guessing domains like api.tapclaw.com or local filesystem paths.
     */
    private fun buildRelayBaseUrl(): String? {
        // Shared RelayUrlHelper logic. Prefer-public flag set: Hermes'
        // safety layer may reject plain LAN http://192.168.x.x URLs, while
        // the Cloudflare relay is exactly what the glasses can fetch from
        // any network.
        return RelayUrlHelper.baseFromEndpoints(
            prefs.hermesEndpoint.trim(),
            prefs.openClawEndpoint.trim(),
            preferTapInsightPublicForLocal = true
        )
    }

    /**
     * POST the raw camera frame to the image relay (`<relayBase>/frame`) so it
     * lands in the agent workspace as camera_frame.jpg (+ a timestamped archive
     * under saved_photos/). Mirrors OpenClawClient.uploadToRelay. Best-effort
     * and short-timeout: returns false (never throws) if the relay is down, so
     * the inline-image request to Hermes still proceeds.
     */
    private fun uploadFrameToRelay(imageBase64: String): Boolean {
        val relayBase = buildRelayBaseUrl() ?: return false
        val url = "$relayBase/frame"
        return try {
            val bytes = Base64.decode(imageBase64, Base64.NO_WRAP)
            if (bytes.isEmpty()) return false
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .post(bytes.toRequestBody("image/jpeg".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Log.d(TAG, "Hermes camera frame saved to workspace via relay $url")
                    true
                } else {
                    Log.w(TAG, "Hermes relay upload HTTP ${resp.code} at $url")
                    false
                }
            }
        } catch (e: java.net.ConnectException) {
            Log.w(TAG, "Hermes relay not reachable at $url — image_relay.py may not be running")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Hermes relay upload failed: ${e.message}")
            false
        }
    }

    private fun extractReadableText(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("open_taplink:", ignoreCase = true) ||
                    trimmed.startsWith("[Hermes session:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun deriveTitle(text: String): String {
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(72)
            ?.trimEnd()
            .orEmpty()
            .ifBlank { "Hermes result" }
    }

    private fun isVisionQuery(query: String): Boolean {
        val lower = query.lowercase()
        return VISION_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
