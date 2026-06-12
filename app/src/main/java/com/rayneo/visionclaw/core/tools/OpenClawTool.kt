package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.OpenClawClient
import com.rayneo.visionclaw.core.storage.LastUrlStore
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * OpenClawTool — bridges Gemini Live tool calls to a user's personal
 * OpenClaw AI assistant running on their own server/device.
 *
 * Triggered via the "tapclaw" prefix in voice commands:
 *   "tapclaw check my emails"
 *   "tapclaw turn off the lights"
 *   "tapclaw what do you see" (sends camera frame as attachment)
 *
 * OpenClaw handles smart home, email, calendars, web automation,
 * health data, productivity apps, custom agent skills, and
 * image/vision analysis (when a camera frame is attached).
 */
class OpenClawTool(
    context: Context,
    private val openClawClient: OpenClawClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    companion object {
        private const val TAG = "OpenClawTool"
        private const val PREFS_NAME = "visionclaw_prefs"
        private const val SESSION_TOKEN_KEY = "companion_session_token"
        private const val COMPANION_PORT = 19110

        /** Keywords that suggest the user wants vision/image analysis */
        private val VISION_KEYWORDS = setOf(
            "see", "look", "image", "photo", "picture", "camera",
            "analyze", "describe", "identify", "recognize", "read",
            "what is this", "what's this", "show", "visual", "scan",
            "ocr", "text in", "sign", "label", "screen", "view"
        )

        private val MEDIA_LIBRARY_QUERY = Regex(
            """(?i)\b(media\s+library|media\s+browser|m3u8?|playlist|txt\s+file|text\s+file|media\s+file|verbatim|\.txt|\.md|\.log|\.mp3|\.m4a|\.aac|\.ogg|\.opus|\.wav|\.flac|\.mp4|\.webm|\.mkv|\.mov|\.m4v)\b"""
        )

        private val EXPLICIT_CAMERA_QUERY = Regex(
            """(?i)\b(camera|photo|picture|image|screen|sign|label|what\s+do\s+you\s+see|what\s+am\s+i\s+looking\s+at|through\s+my\s+glasses|visible|in\s+front\s+of\s+me)\b"""
        )
    }

    override val name = "tapclaw_agent"
    private val appContext = context.applicationContext
    private val artifactStore = ReadableArtifactStore(context)
    private val lastUrlStore = LastUrlStore(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val rawQuery = args["query"]?.trim().orEmpty()
        if (rawQuery.isBlank()) {
            return Result.failure(Exception("No query provided for OpenClaw."))
        }

        // ── URL-grounding pass ─────────────────────────────────────────
        // Before we ship the query off to TapClaw, rewrite it so that any
        // URL Gemini tried to embed is either (a) a real URL the user
        // actually opened recently or (b) a safe, descriptive placeholder.
        // Without this step, Gemini routinely hallucinates YouTube URLs
        // when the user says "email me this video".
        val groundedQuery = groundUrlsInQuery(rawQuery)
        val query = addMediaLibraryHint(groundedQuery)
        if (query != rawQuery) {
            Log.i(
                TAG,
                "Prepared outgoing query — grounded URLs and/or added TapInsight routing hints. " +
                    "before=${rawQuery.take(240)}"
            )
        }

        // Check if query implies vision — attach camera frame if available.
        // An explicit on-screen frame (raw WebView screenshot) supplied by the
        // caller wins over the live camera: when the user asked OpenClaw to look
        // at what's ON SCREEN, send those exact pixels, not the camera.
        val screenFrame = args["image_base64"]?.takeIf { it.isNotBlank() }
        val mediaFileQuery = MEDIA_LIBRARY_QUERY.containsMatchIn(query)
        val explicitCameraQuery = EXPLICIT_CAMERA_QUERY.containsMatchIn(query)
        val includeImage = screenFrame != null
            || (args["include_image"]?.toBooleanStrictOrNull() == true && (!mediaFileQuery || explicitCameraQuery))
            || isVisionQuery(query)
        val imageBase64 = screenFrame ?: if (includeImage) frameProvider() else null
        val hasImage = !imageBase64.isNullOrBlank()

        val frameSizeKb = imageBase64?.length?.div(1024) ?: 0
        Log.d(TAG, "Sending to OpenClaw: query=${query.take(100)} vision=$includeImage hasFrame=$hasImage frameSizeKb=$frameSizeKb")
        if (hasImage) {
            Log.d(TAG, "Frame preview (first 80 chars): ${imageBase64!!.take(80)}")
        }

        // Skip location context entirely — the blocking GPS resolver adds ~13s
        // of delay before the WebSocket call even starts. OpenClaw has its own
        // context and doesn't need glasses GPS for most queries.
        // Only include Gemini-provided context if present.
        val context = args["context"]?.takeIf { it.isNotBlank() }

        return when (val result = openClawClient.sendMessage(query, context, imageBase64)) {
            is OpenClawClient.ClawResult.Success -> {
                val response = buildString {
                    append(result.text)
                    if (!result.sessionId.isNullOrBlank() && result.sessionId != "main") {
                        append("\n[OpenClaw session: ${result.sessionId}]")
                    }
                }
                // Defense-in-depth: rewrite hallucinated media domains at source.
                // GPT-5.4 persistently invents wrong domains (api.tapclaw.com, etc.)
                // — the only correct media relay is the operator's configured host.
                val sanitized = rewriteAllUrlsInText(response)
                val readable = extractReadableText(sanitized)
                if (readable.isNotBlank()) {
                    artifactStore.saveLatest(
                        kind = ReadableArtifactStore.ArtifactKind.TAPCLAW_RESULT,
                        title = deriveTitle(readable),
                        text = readable,
                        sourceLabel = "tapclaw"
                    )
                }
                Result.success(sanitized)
            }
            is OpenClawClient.ClawResult.NotConfigured ->
                Result.failure(Exception(
                    "TapClaw is not configured. Set the gateway URL and token in TapInsight setup."
                ))
            is OpenClawClient.ClawResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /** Rewrite hallucinated media URLs in text from OpenClaw responses. */
    private fun rewriteAllUrlsInText(text: String): String {
        val urlPattern = Regex("""https?://[^\s"'<>\]]+""")
        var result = text
        for (match in urlPattern.findAll(text)) {
            val original = match.value
            val rewritten = AssistantIntentParser.rewriteHallucinatedMediaDomain(original)
            if (rewritten != original) {
                result = result.replace(original, rewritten)
                Log.w(TAG, "Rewrote hallucinated URL at source: $original → $rewritten")
            }
        }
        return result
    }

    /**
     * Ground URLs in an outgoing TapClaw query so we never ask TapClaw to
     * email/share a URL Gemini hallucinated.
     *
     * Two transforms run in order:
     *
     *   1. Placeholder substitution — any of `{last_video_url}`,
     *      `{last_media_url}`, `{last_url}`, `{current_video}`,
     *      `{current_url}`, `{this_video}`, `{now_playing}` gets replaced
     *      with the most relevant URL we've actually opened recently.
     *      These placeholders are documented in the tapclaw_agent tool
     *      schema so Gemini is encouraged to use them instead of
     *      reproducing a URL from memory.
     *
     *   2. Hallucination check — any YouTube watch URL already embedded in
     *      the query is validated against [LastUrlStore]. If its 11-char
     *      video ID hasn't been seen in this session, it's replaced with
     *      the canonical last-viewed URL (or, absent that, stripped out
     *      and described as "[last viewed video]" so TapClaw's own AI can
     *      handle it gracefully).
     *
     * Relay-domain rewrites (api.tapclaw.com → the configured relay) are
     * still performed via [rewriteAllUrlsInText] once the response comes
     * back — that's a different failure mode.
     */
    private fun groundUrlsInQuery(raw: String): String {
        var s = raw

        // ── Pass 1: placeholder substitution ──
        val currentMedia = lastUrlStore.currentMedia()
        val currentAny = lastUrlStore.latest()
        val videoEntry = lastUrlStore.latestOfKinds(
            LastUrlStore.UrlKind.YOUTUBE_VIDEO,
            LastUrlStore.UrlKind.YOUTUBE_SEARCH
        )

        data class Placeholder(val pattern: Regex, val resolver: () -> String?)
        val placeholders = listOf(
            Placeholder(Regex("""\{\s*(?:last|current|this)[_\s]*video(?:[_\s]*url)?\s*\}""", RegexOption.IGNORE_CASE)) {
                videoEntry?.url ?: currentMedia?.url
            },
            Placeholder(Regex("""\{\s*(?:last|current)[_\s]*media(?:[_\s]*url)?\s*\}""", RegexOption.IGNORE_CASE)) {
                currentMedia?.url
            },
            Placeholder(Regex("""\{\s*now[_\s]*playing\s*\}""", RegexOption.IGNORE_CASE)) {
                currentMedia?.url
            },
            Placeholder(Regex("""\{\s*(?:last|current)[_\s]*url\s*\}""", RegexOption.IGNORE_CASE)) {
                currentAny?.url
            }
        )
        for (p in placeholders) {
            if (!p.pattern.containsMatchIn(s)) continue
            val replacement = p.resolver()
            s = if (replacement.isNullOrBlank()) {
                p.pattern.replace(s, "[no recent URL recorded]")
            } else {
                p.pattern.replace(s, Regex.escapeReplacement(replacement))
            }
        }

        // ── Pass 2: hallucinated YouTube URL check ──
        // Scan for anything that looks like a YouTube watch URL and validate
        // its 11-char video ID against recently-recorded entries. If we've
        // never opened that ID, treat it as a hallucination.
        val ytWatchPattern = Regex(
            """https?://(?:www\.|m\.)?(?:youtube\.com/watch\?[^\s"'<>)]*v=[A-Za-z0-9_\-]{11}[^\s"'<>)]*|youtu\.be/[A-Za-z0-9_\-]{11}(?:[?&][^\s"'<>)]*)?)"""
        )
        val canonicalYouTubeForSubstitution = videoEntry?.url?.takeIf {
            LastUrlStore.isCanonicalYouTubeVideoUrl(it)
        }
        s = ytWatchPattern.replace(s) { match ->
            val candidate = match.value
            val videoId = LastUrlStore.extractYouTubeVideoId(candidate)
            when {
                videoId == null -> candidate
                lastUrlStore.hasSeenYouTubeVideoId(videoId) -> {
                    // Real URL — keep it, but normalize to the one we actually
                    // saw so analytics / deduping inside TapClaw aren't
                    // confused by surface variations (watch?v=ID&t=… etc.).
                    lastUrlStore.findByYouTubeVideoId(videoId)?.url ?: candidate
                }
                canonicalYouTubeForSubstitution != null -> {
                    Log.w(
                        TAG,
                        "Replacing likely-hallucinated YouTube URL $candidate " +
                            "(video id=$videoId never opened in TapBrowser) → " +
                            "last-seen $canonicalYouTubeForSubstitution"
                    )
                    canonicalYouTubeForSubstitution
                }
                else -> {
                    Log.w(
                        TAG,
                        "Stripping hallucinated YouTube URL $candidate " +
                            "(no canonical replacement available)"
                    )
                    "[last viewed video — video URL unavailable on glasses]"
                }
            }
        }

        return s
    }

    private fun addMediaLibraryHint(query: String): String {
        if (!MEDIA_LIBRARY_QUERY.containsMatchIn(query)) return query
        if (query.contains("[TapInsight media-file routing:", ignoreCase = true)) return query
        return query + "\n\n[TapInsight media-file routing: There are TWO separate workflows. 1) PLAY NOW / OPEN NOW: do not block on companion IP discovery. For workspace files created by OpenClaw, return an open_taplink to the media relay URL; the current relay base is ${com.rayneo.visionclaw.core.network.RelayUrlHelper.TAPINSIGHT_RELAY_BASE.ifBlank { "https://relay.<your-domain>" }}/media/<workspace-relative-path>. For direct playable URLs, return an open_taplink to the appropriate TapInsight player. Preserve the user's requested player. The TapInsight player asset pages all live on the same virtual https origin: https://appassets.androidplatform.net/assets/<page>.html. TapRadio/Exo controls should use https://appassets.androidplatform.net/assets/radio.html?playUrl=<absolute-url>&playKind=audio|playlist|radio&autoplay=1 (do NOT use file:///android_asset/radio.html — it works as a top-level open_taplink target but breaks when reached from inside other asset pages, and the absolute https form works in both cases). Media Browser/Media Library should use https://appassets.androidplatform.net/assets/media_player.html (single track: ?type=audio|video|text&url=<absolute-url>&title=<name>; library playlist: ?type=playlist&playlist=<library-relative-path>&start=0&source=bridge) or https://appassets.androidplatform.net/assets/library_local.html (browse the Media Library UI). 2) SAVE / CREATE / COPY INTO THE GLASSES MEDIA LIBRARY: use the TapInsight companion API below, not relay storage. For verbatim .txt/.md/.log reads, return the raw file contents exactly, not a summary. Media Library folders are Music/ for audio, Videos/ for video, Playlists/ for .m3u/.m3u8 station or media playlists, and Text/ for .txt/.md/.log/readable files. For multiple named local tracks, videos, saved files, stations, or stream URLs, create a compact M3U in Playlists/ and open that playlist through the requested player instead of opening unrelated last-played media or launching items one-by-one. For generated genre radio playlists such as hard rock, create a compact M3U from direct stream URLs, write it to Playlists/, verify with /api/library/list, and return promptly; if you cannot verify usable stream URLs quickly, return a clear failure instead of continuing to browse. PLAY-A-SAVED-LIBRARY-PLAYLIST CONTRACT: after a successful POST /api/library/write of <name>.m3u to Playlists/, do NOT pass the bare 'Playlists/<name>.m3u' path to open_taplink (relative paths are rejected). Instead build an absolute URL. For HTTP-stream playlists (radio/genre mixes), use https://appassets.androidplatform.net/assets/radio.html?playUrl=https%3A%2F%2Fappassets.androidplatform.net%2Fmedia%2FPlaylists%2F<name>.m3u&playKind=playlist&autoplay=1 (the playUrl value must be the absolute https URL of the saved M3U on the asset host, URL-encoded). For library-track playlists (local Music/Videos files), use https://appassets.androidplatform.net/assets/media_player.html?type=playlist&playlist=Playlists%2F<name>.m3u&start=0&source=bridge (the playlist value is the library-relative path, URL-encoded; source=bridge tells the page to use the on-glasses MediaLibraryBridge instead of the companion HTTP API). Do not treat ~/.openclaw/workspace/Android/... or any workspace Android-looking path as glasses storage; that is only a host workspace/relay path. Only say a file is saved in the glasses Media Library after /api/library/write or an actual device/library listing confirms it. ${companionMediaLibraryAccessHint()}]"
    }

    private fun companionMediaLibraryAccessHint(): String {
        val token = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SESSION_TOKEN_KEY, null)
            ?.trim()
            .orEmpty()

        val urls = detectLanIpv4Addresses()
            .map { "https://$it:$COMPANION_PORT" }
            .distinct()
        val urlText = if (urls.isNotEmpty()) {
            urls.joinToString(", ")
        } else {
            "https://<glasses-lan-ip>:$COMPANION_PORT"
        }

        return buildString {
            append("Companion API for this glasses session: try base URL(s) ")
            append(urlText)
            append(". Use curl/fetch with TLS verification disabled if needed because the companion server uses a local self-signed certificate. ")
            append("Use POST /api/library/write with JSON {filename, kind, content, optional folder, optional overwrite, optional encoding}; then verify with GET /api/library/list?path=Playlists, Music, Videos, or Text. ")
            append("ENCODING RULE — CRITICAL FOR BINARY FILES: ")
            append("when saving an audio file (.mp3 .m4a .ogg .wav .flac .opus), a video file (.mp4 .mov .webm .mkv .m4v), or an image (.png .jpg .jpeg .gif .webp), you MUST set encoding=\"base64\" and put the base64-encoded bytes in `content`. ")
            append("JSON cannot carry raw binary, so a binary file written without encoding=\"base64\" will land on disk as ASCII text and play back as garbage. ")
            append("For text files (.txt .md .log .m3u .m3u8 .json .csv .xml), omit encoding (or set it to \"text\") and put the raw text in `content`. ")
            append("If you have a binary file as bytes already, base64-encode it before sending. If you have it as a URL, fetch the bytes, base64-encode, then send. Either way, set encoding=\"base64\" in the JSON. ")
            append("PREFER /api/library/upload (multipart/form-data) for large binary files when you can — it streams the bytes without the base64 expansion overhead. Fallback to /api/library/write with encoding=\"base64\" only when multipart isn't available. ")
            if (token.isNotBlank()) {
                append("Send the session token ONLY as the X-Session-Token header, not in logs or chat text. X-Session-Token=")
                append(token)
                append(". ")
            } else {
                append("The companion session token is not available in this prompt, so do not claim a Media Library save unless another verified device/library listing confirms it. ")
            }
            append("Do not ask the user for the glasses IP before trying these provided LAN URLs. ")
            append("localhost is only valid from the glasses itself or when an explicit ADB forward is active, so a desktop/OpenClaw agent should not default to localhost.")
        }
    }

    private fun detectLanIpv4Addresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { iface -> iface.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { ip ->
                    ip.isNotBlank() &&
                        ip != "127.0.0.1" &&
                        !ip.startsWith("169.254.")
                }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun extractReadableText(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("open_taplink:", ignoreCase = true) ||
                    trimmed.startsWith("[OpenClaw session:", ignoreCase = true)
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
            .ifBlank { "TapClaw result" }
    }

    /**
     * Heuristic check: does the query imply the user wants OpenClaw to
     * look at something through the camera?
     */
    private fun isVisionQuery(query: String): Boolean {
        val lower = query.lowercase()
        if (MEDIA_LIBRARY_QUERY.containsMatchIn(query) && !EXPLICIT_CAMERA_QUERY.containsMatchIn(query)) {
            return false
        }
        return VISION_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
