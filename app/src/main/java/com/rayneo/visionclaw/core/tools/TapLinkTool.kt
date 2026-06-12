package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.storage.LastUrlStore

class TapLinkTool(private val context: Context) : AiTapTool {
    override val name = "open_taplink"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val url = args["url"] ?: ""

        Log.d("TapLinkTool", "REWRITER-V2 url=$url")

        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("No URL provided"))
        }

        val normalized = AssistantIntentParser.normalizeTapLinkUrl(url)
            ?: return Result.failure(
                IllegalArgumentException(
                    "open_taplink requires a full https://, http://, protocol-relative //host/path, or file:/// URL. Relative paths are not supported."
                )
            )

        // GUARDRAIL — refuse bare / home-feed YouTube URLs. Opening
        // https://www.youtube.com/ or https://www.youtube.com/feed/history
        // loads the SIGNED-IN user's personalized feed (home / watch history
        // / subscriptions), which is almost never what the user actually
        // asked for. The intentional local voice-command handlers in
        // MainViewModel set `taplink_autoplay=history|subscriptions` when
        // they genuinely want those destinations; absent that marker we
        // bounce the call back to Gemini with an instruction to retry
        // with a real /results?search_query=… URL.
        val lower = normalized.lowercase(java.util.Locale.US)
        val isYouTubeHost = lower.contains("youtube.com") || lower.contains("youtu.be")
        if (isYouTubeHost) {
            val hasAutoplayMarker = lower.contains("taplink_autoplay=")
            val isBareHost = Regex("""^https?://(?:www\.|m\.)?youtube\.com/?(?:\?.*)?$""")
                .containsMatchIn(lower)
            val isPersonalFeed = Regex("""^https?://(?:www\.|m\.)?youtube\.com/feed/(?:history|subscriptions|library|you)\b""")
                .containsMatchIn(lower)
            if ((isBareHost || isPersonalFeed) && !hasAutoplayMarker) {
                Log.w(
                    "TapLinkTool",
                    "Refusing to open bare/home-feed YouTube URL without intent marker: $normalized"
                )
                return Result.failure(
                    IllegalArgumentException(
                        "Refusing to open a bare or personal-feed YouTube URL. " +
                            "Build a search URL instead: " +
                            "https://www.youtube.com/results?search_query=QUERY+HERE " +
                            "(replace spaces with +, keep it specific to what the user asked for)."
                    )
                )
            }
        }

        // Record the URL as "ground truth" so tapclaw_agent queries later on
        // ("email me this page", "share this link") can substitute the real
        // URL instead of a Gemini-hallucinated lookalike. We record the
        // pre-browser-rebuild URL here; DualWebViewGroup.onPageFinished will
        // refine to the canonical URL once the page actually resolves.
        runCatching {
            LastUrlStore(context).record(url = normalized)
        }.onFailure { Log.w("TapLinkTool", "LastUrlStore.record failed: ${it.message}") }

        // The URL will be opened by the TapBrowser panel via the ViewModel
        return Result.success("taplink://$normalized")
    }
}
