package com.rayneo.visionclaw.core.session

import android.content.Context
import com.rayneo.visionclaw.core.tools.ToolDispatcher
import com.rayneo.visionclaw.ui.MainViewModel

/**
 * Phase 4i — shared orchestrator for Gemini Live tool-call dispatch.
 *
 * Codex's note (paraphrased) — the unipanel Service path now has a
 * ToolDispatcher (bb20ac4), but ~11 Activity-only guardrails still
 * live exclusively in visionclaw MainActivity. Once both paths
 * funnel through this coordinator, the Service gets the same
 * "nuanced all-Gemini" behavior the Hermes branch had:
 *
 *   - lookup/list requests cache with send_video_list display=cache
 *     and do not open browser
 *   - "show/send the list" opens picker with display=glasses
 *   - "play the first/second/that one/all" resolves cached titles
 *     deterministically
 *   - explicit "play <keyword> on YouTube" launches YouTube search
 *     with taplink_autoplay extras
 *   - capability questions ("can you play X") answer verbally and
 *     don't auto-open
 *   - media type conflicts don't route YouTube into TapRadio or
 *     vice-versa
 *
 * Migration is incremental — this commit lays the SKELETON only.
 * Subsequent commits move guards one-by-one out of MainActivity and
 * into this class, deleting the Activity-only copies as each lands.
 *
 * The intermediate state during migration is safe because both paths
 * still drive their existing dispatcher; the coordinator is only
 * wired once a guard has been fully ported and tested.
 *
 * Guards (in order they'll move):
 *   1. mediaServiceCapabilityQuestionResponse                — pure
 *   2. explicitMediaTypeConflictResponse                     — pure
 *   3. tapRadioPodcastActionWithoutCurrentConsentResponse    — pure
 *   4. youtubeSuggestionListWithoutCurrentConsentResponse    — pure
 *   5. redundantMediaLookupAfterSuggestionCacheResponse      — uses prefs
 *   6. rewriteTapRadioArgsForRequestedMediaType              — pure
 *   7. forceTapRadioPlaybackArgsIfExplicit                   — pure
 *   8. resolveRecentYouTubeSuggestionListDisplayRequest      — uses prefs
 *   9. resolveRecentYouTubeFollowUpPlaybackRequest           — uses prefs
 *  10. research_topic local readout/suppression behavior     — uses sink.speak
 *  11. local-agent Hermes/TapClaw direct presentation        — uses sink.present…
 *
 * Pure functions move first (cheapest to test). Prefs-driven guards
 * second (depend on AppPreferences only). Sink-driven guards last
 * (need the Service-side implementation of [LiveToolCallActionSink]
 * to exist first).
 *
 * State fields the coordinator owns (lifted out of MainActivity):
 *   - pendingLiveInputTranscript
 *   - latestLiveTranscript
 *   - lastHandledLiveInputTranscript
 *   - lastToolAssistTranscript
 *   - recent_youtube_suggestions_json/ms snapshot
 *   - suppressGeminiOutputUntilMs (for local-direct handoff)
 */
class LiveToolCallCoordinator(
    private val appContext: Context,
    private val viewModel: MainViewModel,
    private val toolDispatcher: ToolDispatcher,
    private val sink: LiveToolCallActionSink
) {

    // ────────────────────────────────────────────────────────────────
    // Shared state — moved out of MainActivity as guards migrate.
    // All access is via this coordinator so MainActivity and the
    // Service path can never disagree about "what's the current turn".
    // ────────────────────────────────────────────────────────────────

    @Volatile var pendingLiveInputTranscript: String = ""
    @Volatile var latestLiveTranscript: String = ""
    @Volatile var lastHandledLiveInputTranscript: String = ""
    @Volatile var lastToolAssistTranscript: String = ""

    /** Set to a future timestamp by guards that take over the turn
     *  locally (e.g. a capability-question short-circuit). While
     *  System.currentTimeMillis() < this value, late Gemini output
     *  for the same turn is dropped so the local result wins. */
    @Volatile var suppressGeminiOutputUntilMs: Long = 0L

    fun isGeminiOutputSuppressed(): Boolean =
        System.currentTimeMillis() < suppressGeminiOutputUntilMs

    // ────────────────────────────────────────────────────────────────
    // Public API — called by both MainActivity and GeminiVoicePipeline
    // ────────────────────────────────────────────────────────────────

    /**
     * Entry point. Drives the full Hermes-era pipeline: guardrails
     * → ToolDispatcher → sink for any UI / launch / response side
     * effects.
     *
     * Phase 4i scope: this skeleton DELEGATES back to the caller's
     * own dispatcher (no logic moved yet). Subsequent commits
     * progressively move guard logic in here and unwire the
     * Activity-side copies.
     */
    fun dispatch(callId: String, toolName: String, args: String) {
        // Phase 4i — placeholder. Real implementation lands in 4i.2
        // when the first pure-function guard migrates.
        //
        // For now, callers continue to use their own dispatch paths;
        // this class exists only to define the contract.
    }

    // ────────────────────────────────────────────────────────────────
    // Guard helpers — stubs for the in-flight migration.
    // Each will become a `fun returning String?` (null = no
    // short-circuit, non-null = the response text to send back to
    // Gemini via sink.sendToolResponse). Wiring them into [dispatch]
    // is the second step of each guard's migration commit.
    // ────────────────────────────────────────────────────────────────

    // TODO(4i.2)  fun mediaServiceCapabilityQuestionResponse(transcript: String): String?
    // TODO(4i.3)  fun explicitMediaTypeConflictResponse(transcript: String, args: String): String?
    // TODO(4i.4)  fun tapRadioPodcastActionWithoutCurrentConsentResponse(transcript: String): String?
    // TODO(4i.5)  fun youtubeSuggestionListWithoutCurrentConsentResponse(transcript: String): String?
    // TODO(4i.6)  fun redundantMediaLookupAfterSuggestionCacheResponse(...): String?
    // TODO(4i.7)  fun rewriteTapRadioArgsForRequestedMediaType(transcript: String, args: String): String?
    // TODO(4i.8)  fun forceTapRadioPlaybackArgsIfExplicit(transcript: String, args: String): String?
    // TODO(4i.9)  fun resolveRecentYouTubeSuggestionListDisplayRequest(...): Boolean
    // TODO(4i.10) fun resolveRecentYouTubeFollowUpPlaybackRequest(transcript: String): YouTubePlaybackRequest?
    //
    // NOTE: code-level enforcement of these guards on the Service path was tried
    // and reverted. Every version depended on knowing the user's current-turn
    // words at tool-dispatch time, but Gemini Live does not guarantee the input
    // transcription (or a verbatim keyword in the tool args) is present when the
    // call arrives — so the guards intermittently blocked legitimate "hermes …"
    // / "openclaw …" requests, causing Hermes to go silent and Gemini to answer
    // over it. These rules therefore stay PROMPT-enforced (RULE ZERO / ZERO-H in
    // GeminiRouter) until the Live protocol gives us a turn-synchronized
    // transcript to gate on. Dispatch must never be blocked speculatively.
}
