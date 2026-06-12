package com.rayneo.visionclaw.core.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rayneo.visionclaw.core.model.DeviceLocationContext

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    /** Optional AudD music-recognition token — only used as the fallback for
     *  identify_song when a station broadcasts no ICY metadata. Empty = the
     *  acoustic fallback is disabled (the free ICY path still works). */
    var auddApiToken: String
        get() = prefs.getString("audd_api_token", "") ?: ""
        set(value) = prefs.edit().putString("audd_api_token", value).apply()

    /** How "note that…/jot down…" voice requests are handled:
     *  "builtin" (default) = capture locally to the glasses notes file;
     *  "hermes" = route to the Hermes note-taking skill; "off" = let Gemini
     *  decide. Exposed so the user can manually pick the detector. */
    var noteCaptureMode: String
        get() = prefs.getString("note_capture_mode", "builtin") ?: "builtin"
        set(value) = prefs.edit().putString("note_capture_mode", value).apply()

    var researchProvider: String
        get() = prefs.getString(KEY_RESEARCH_PROVIDER, "gemini") ?: "gemini"
        set(value) = prefs.edit().putString(KEY_RESEARCH_PROVIDER, value).apply()

    var researchApiKey: String
        get() = prefs.getString(KEY_RESEARCH_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_API_KEY, value).apply()

    var researchModel: String
        get() = prefs.getString(KEY_RESEARCH_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_MODEL, value).apply()

    var researchPrompt: String
        get() = prefs.getString(KEY_RESEARCH_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_PROMPT, value).apply()

    var researchTtsModel: String
        get() = prefs.getString(KEY_RESEARCH_TTS_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_TTS_MODEL, value).apply()

    var researchTtsVoiceName: String
        get() = prefs.getString(KEY_RESEARCH_TTS_VOICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_TTS_VOICE_NAME, value).apply()

    var researchTtsLanguage: String
        get() = prefs.getString(KEY_RESEARCH_TTS_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_TTS_LANGUAGE, value).apply()

    var researchTtsDirectorNotes: String
        get() = prefs.getString(KEY_RESEARCH_TTS_DIRECTOR_NOTES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_TTS_DIRECTOR_NOTES, value).apply()

    var learnLmModel: String
        get() = prefs.getString(KEY_LEARNLM_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LEARNLM_MODEL, value).apply()

    var calendarApiKey: String
        get() = prefs.getString(KEY_CALENDAR_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_API_KEY, value).apply()

    var calendarId: String
        get() = prefs.getString(KEY_CALENDAR_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_ID, value).apply()

    var openClawEndpoint: String
        get() = prefs.getString(KEY_OPENCLAW_ENDPOINT, DEFAULT_OPENCLAW_ENDPOINT) ?: DEFAULT_OPENCLAW_ENDPOINT
        set(value) = prefs.edit().putString(KEY_OPENCLAW_ENDPOINT, value).apply()

    /** OpenClaw gateway bearer token for authentication. */
    var openClawToken: String
        get() = prefs.getString(KEY_OPENCLAW_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENCLAW_TOKEN, value).apply()

    /** OpenClaw session ID. Defaults to "main". Use different IDs for isolated contexts. */
    var openClawSessionId: String
        get() = prefs.getString(KEY_OPENCLAW_SESSION_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_OPENCLAW_SESSION_ID, value).apply()

    /** OpenClaw request timeout in seconds. 0 = default (30s). */
    var openClawTimeoutSeconds: Int
        get() = prefs.getInt(KEY_OPENCLAW_TIMEOUT_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_OPENCLAW_TIMEOUT_SECONDS, value).apply()

    /** Whether OpenClaw integration is enabled. */
    var openClawEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPENCLAW_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENCLAW_ENABLED, value).apply()

    /** OpenClaw heartbeat poll interval in seconds (10–120, default 20). */
    var openClawHeartbeatIntervalSeconds: Int
        get() = prefs.getInt(KEY_OPENCLAW_HEARTBEAT_INTERVAL_SECONDS, 20).coerceIn(10, 120)
        set(value) = prefs.edit().putInt(KEY_OPENCLAW_HEARTBEAT_INTERVAL_SECONDS, value.coerceIn(10, 120)).apply()

    /**
     * How often (seconds) the HUD heartbeat ticker refreshes the persistent
     * agent-status line (Hermes / TapClaw reachability). Default 30. Keeps the
     * ticker permanently populated so a stalled agent query is visible instead
     * of leaving the ticker blank ("limbo"). 10–300s.
     */
    var agentStatusPollSeconds: Int
        get() = prefs.getInt(KEY_AGENT_STATUS_POLL_SECONDS, 30).coerceIn(10, 300)
        set(value) = prefs.edit().putInt(KEY_AGENT_STATUS_POLL_SECONDS, value.coerceIn(10, 300)).apply()

    /**
     * Days of chat history retained for the H / O badge history overlay.
     * Each completed Gemini turn appends a record to the per-agent history
     * arrays (chat_history_hermes / chat_history_openclaw), and records
     * older than this window are pruned on next write. 1–5 days, default 3.
     * The companion app surfaces this as a dropdown in the Agents section.
     */
    var hudChatHistoryDays: Int
        get() = prefs.getInt(KEY_HUD_CHAT_HISTORY_DAYS, 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_HUD_CHAT_HISTORY_DAYS, value.coerceIn(1, 5)).apply()

    // ── OpenClaw mode brackets ──────────────────────────────────────────
    // OpenClaw / TapClaw exposes per-turn slash commands like /fast and
    // /think <low|medium|high> that select the inference profile for
    // subsequent prompts in the session. These four prefs let the user
    // configure a "bracket": a prefix sent on the first message of each
    // session, and (optionally) an after-turn slash command sent
    // fire-and-forget once the agent reports completion. Empty / "off"
    // values mean don't send anything.
    /** "off" or "fast". When "fast", a "/fast" line is prepended to the first message of each session. */
    var openClawFastMode: String
        get() = prefs.getString(KEY_OPENCLAW_FAST_MODE, "off") ?: "off"
        set(value) = prefs.edit().putString(KEY_OPENCLAW_FAST_MODE, value).apply()

    /** "" (no override), "low", "medium", or "high". When non-empty, "/think <level>" is prepended. */
    var openClawThinkLevel: String
        get() = prefs.getString(KEY_OPENCLAW_THINK_LEVEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENCLAW_THINK_LEVEL, value).apply()

    /** "off" or "fast". When "fast", "/fast" is sent fire-and-forget AFTER the turn completes. */
    var openClawAfterFastMode: String
        get() = prefs.getString(KEY_OPENCLAW_AFTER_FAST_MODE, "off") ?: "off"
        set(value) = prefs.edit().putString(KEY_OPENCLAW_AFTER_FAST_MODE, value).apply()

    /** Same shape as openClawThinkLevel but applied AFTER the turn completes (used to restore default). */
    var openClawAfterThinkLevel: String
        get() = prefs.getString(KEY_OPENCLAW_AFTER_THINK_LEVEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENCLAW_AFTER_THINK_LEVEL, value).apply()

    /**
     * When enabled, after a Hermes-routed turn completes and Gemini's
     * TTS finishes reading the response, the Live session is kept
     * listening for [HERMES_FOLLOWUP_WINDOW_MS] (default 30 s) so the
     * user can ask a natural follow-up without re-activating. When
     * disabled, the regular Gemini-Live idle timeout applies.
     *
     * Surfaced in the Hermes section of the companion app.
     */
    var hermesAutoFollowupEnabled: Boolean
        get() = prefs.getBoolean(KEY_HERMES_AUTO_FOLLOWUP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_AUTO_FOLLOWUP_ENABLED, value).apply()

    // ══════════════════════════════════════════════════════════════════════
    // Hermes Agent — NousResearch/hermes-agent integration
    //
    // The HermesClient + HermesTool combo talks to a user's Hermes API
    // server (default 127.0.0.1:8642) via OpenAI-compatible HTTP/SSE.
    // Keys live in `~/.hermes/.env` on the host; this Android side just
    // needs the public endpoint URL the Cloudflare tunnel exposes and
    // the bearer token. Keys must match the May 14 build's names so
    // existing SharedPreferences values are picked up automatically.
    // ══════════════════════════════════════════════════════════════════════

    /** Base URL of the Hermes API server, e.g. http://192.168.1.170:8642 or https://hermes.example.com */
    var hermesEndpoint: String
        get() = prefs.getString(KEY_HERMES_ENDPOINT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_ENDPOINT, value).apply()

    /** Bearer token — value of API_SERVER_KEY in ~/.hermes/.env on the host. */
    var hermesApiKey: String
        get() = prefs.getString(KEY_HERMES_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HERMES_API_KEY, value).apply()

    /** X-Hermes-Session-Id header value. "main" by default for cross-reconnect continuity. */
    var hermesSessionId: String
        get() = prefs.getString(KEY_HERMES_SESSION_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_HERMES_SESSION_ID, value).apply()

    /** Hermes HTTP timeout in seconds; 0 = default (30 s). */
    var hermesTimeoutSeconds: Int
        get() = prefs.getInt(KEY_HERMES_TIMEOUT_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_HERMES_TIMEOUT_SECONDS, value).apply()

    /** Whether the Hermes integration is enabled. */
    var hermesEnabled: Boolean
        get() = prefs.getBoolean(KEY_HERMES_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HERMES_ENABLED, value).apply()

    /** Whether Gemini assistant starts with camera on by default (false = audio-only). */
    var assistantDefaultCamera: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_DEFAULT_CAMERA, false)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_DEFAULT_CAMERA, value).apply()

    // ── Battery Saver ──────────────────────────────────────────────────

    /** Whether battery saver mode is currently active. */
    var batterySaverActive: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_SAVER_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_SAVER_ACTIVE, value).apply()

    /** Stash of original HUD refresh interval before battery saver overrode it. */
    var batterySaverOrigRefresh: Int
        get() = prefs.getInt(KEY_BATTERY_SAVER_ORIG_REFRESH, 0)
        set(value) = prefs.edit().putInt(KEY_BATTERY_SAVER_ORIG_REFRESH, value).apply()

    /** Battery level (%) at which battery saver auto-enables. 0 = manual only. */
    var batterySaverAutoThreshold: Int
        get() = prefs.getInt(KEY_BATTERY_SAVER_AUTO_THRESHOLD, 20)
        set(value) = prefs.edit().putInt(KEY_BATTERY_SAVER_AUTO_THRESHOLD, value).apply()

    // ── Quick Actions ──────────────────────────────────────────────────

    /** JSON array of user-defined quick actions. */
    var quickActionsJson: String
        get() = prefs.getString(KEY_QUICK_ACTIONS_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_QUICK_ACTIONS_JSON, value).apply()

    /** User's home address for "heading home" quick action. */
    var homeAddress: String
        get() = prefs.getString(KEY_HOME_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_ADDRESS, value).apply()

    /** User's work address for "leaving work" quick action. */
    var workAddress: String
        get() = prefs.getString(KEY_WORK_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORK_ADDRESS, value).apply()

    // ── Translation ────────────────────────────────────────────────────

    /** Default target language for translation (e.g., "Spanish", "ja"). */
    var translateDefaultLanguage: String
        get() = prefs.getString(KEY_TRANSLATE_DEFAULT_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TRANSLATE_DEFAULT_LANGUAGE, value).apply()

    /** Whether auto-translate mode is enabled (translate all visible text). */
    var translateAutoMode: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_AUTO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSLATE_AUTO_MODE, value).apply()

    var ttsVolume: Float
        get() = prefs.getFloat(KEY_TTS_VOLUME, 0.80f)
        set(value) = prefs.edit().putFloat(KEY_TTS_VOLUME, value.coerceIn(0f, 1f)).apply()

    /**
     * Companion-app window brightness (0.0f – 1.0f). 1.0f means "ride the
     * system brightness ceiling" and matches the hardware default: the
     * 6 000-nit MicroLED optics are easy to read at full blast outdoors but
     * can be painful indoors, hence the user-facing slider.
     *
     * A minimum of 0.05f keeps the panel visible — dropping all the way to
     * 0.0f would render the UI unusable on most devices.
     */
    var screenBrightness: Float
        get() = prefs.getFloat(KEY_SCREEN_BRIGHTNESS, DEFAULT_SCREEN_BRIGHTNESS)
        set(value) = prefs.edit().putFloat(KEY_SCREEN_BRIGHTNESS, value.coerceIn(0.05f, 1f)).apply()

    var ttsMuted: Boolean
        get() = prefs.getBoolean(KEY_TTS_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_MUTED, value).apply()

    /** Preferred system TTS voice name for the media player text reader. Blank = auto. */
    var mediaTtsVoice: String
        get() = prefs.getString(KEY_MEDIA_TTS_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MEDIA_TTS_VOICE, value).apply()

    /** Whether to underline the current word during media player TTS read-aloud. */
    var mediaTtsUnderline: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_TTS_UNDERLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_MEDIA_TTS_UNDERLINE, value).apply()

    var musicMuted: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_MUTED, value).apply()

    var webDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_WEB_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_WEB_DESKTOP_MODE, value).apply()

    var webPointerSensitivity: Float
        get() = prefs.getFloat(KEY_WEB_POINTER_SENSITIVITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_WEB_POINTER_SENSITIVITY, value.coerceIn(0.4f, 1.8f)).apply()

    var webForceDarkMode: Boolean
        get() = prefs.getBoolean(KEY_WEB_FORCE_DARK_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_FORCE_DARK_MODE, value).apply()

    var browserShowSystemInfo: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, value).apply()

    /** Custom system prompt override. If blank, the built-in prompt is used. */
    var customSystemPrompt: String
        get() = prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, value).apply()

    /** Personality description injected after the system prompt. */
    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    /** Editable prompt section: Identity (who is TapInsight). Blank = use default. */
    var promptIdentity: String
        get() = prefs.getString(KEY_PROMPT_IDENTITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_IDENTITY, value).apply()

    /** Editable prompt section: Tool routing rules. Blank = use default. */
    var promptRoutingRules: String
        get() = prefs.getString(KEY_PROMPT_ROUTING_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_ROUTING_RULES, value).apply()

    /** Editable prompt section: Proactive behavior + HUD output + privacy. Blank = use default. */
    var promptBehavior: String
        get() = prefs.getString(KEY_PROMPT_BEHAVIOR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_BEHAVIOR, value).apply()

    /** Editable prompt section: URL generation rules. Blank = use default. */
    var promptUrlRules: String
        get() = prefs.getString(KEY_PROMPT_URL_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_URL_RULES, value).apply()

    /** Spotify OAuth client ID. */
    var spotifyClientId: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_ID, value).apply()

    /** Spotify OAuth client secret. Optional for PKCE flows, required for the
     *  confidential-client code-exchange. */
    var spotifyClientSecret: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_SECRET, value).apply()

    /** User-authorized Spotify access token (short-lived, typically 1h). */
    var spotifyAccessToken: String
        get() = prefs.getString(KEY_SPOTIFY_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_ACCESS_TOKEN, value).apply()

    /** User-authorized Spotify refresh token (long-lived). */
    var spotifyRefreshToken: String
        get() = prefs.getString(KEY_SPOTIFY_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_REFRESH_TOKEN, value).apply()

    /** Epoch-ms expiry for the current Spotify access token. */
    var spotifyAccessTokenExpiryMs: Long
        get() = prefs.getLong(KEY_SPOTIFY_ACCESS_TOKEN_EXPIRY_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_SPOTIFY_ACCESS_TOKEN_EXPIRY_MS, value).apply()

    /** Space-separated list of OAuth scopes that the token was issued for. */
    var spotifyScopes: String
        get() = prefs.getString(KEY_SPOTIFY_SCOPES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_SCOPES, value).apply()

    /** Spotify account display name (for the companion "Connected as X" chip). */
    var spotifyUserDisplayName: String
        get() = prefs.getString(KEY_SPOTIFY_USER_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_USER_DISPLAY_NAME, value).apply()

    /** Spotify user ID (needed for /me endpoints that take a user id). */
    var spotifyUserId: String
        get() = prefs.getString(KEY_SPOTIFY_USER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_USER_ID, value).apply()

    /** Spotify subscription tier ("premium" / "free" / "open"). Full-track
     *  playback requires "premium". We surface this on the companion so the
     *  user knows up-front if their account doesn't support full streaming. */
    var spotifyUserProduct: String
        get() = prefs.getString(KEY_SPOTIFY_USER_PRODUCT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_USER_PRODUCT, value).apply()

    /** PKCE code verifier — stashed only for the duration of the auth flow
     *  (cleared as soon as the callback exchanges the code for tokens). */
    var spotifyPkceVerifier: String
        get() = prefs.getString(KEY_SPOTIFY_PKCE_VERIFIER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_PKCE_VERIFIER, value).apply()

    /** CSRF state parameter for the in-flight OAuth redirect. Cleared after
     *  the callback matches it. */
    var spotifyAuthState: String
        get() = prefs.getString(KEY_SPOTIFY_AUTH_STATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_AUTH_STATE, value).apply()

    /** Redirect URI the Spotify app dashboard is registered with. We default to
     *  the companion server loopback address so Spotify can bounce the user
     *  back to us. */
    var spotifyRedirectUri: String
        get() = prefs.getString(KEY_SPOTIFY_REDIRECT_URI, DEFAULT_SPOTIFY_REDIRECT_URI)
            ?: DEFAULT_SPOTIFY_REDIRECT_URI
        set(value) = prefs.edit().putString(KEY_SPOTIFY_REDIRECT_URI, value).apply()

    /** Returns true if a non-expired Spotify user-OAuth access token exists. */
    fun isSpotifyUserTokenValid(): Boolean =
        spotifyAccessToken.isNotBlank() &&
            System.currentTimeMillis() < spotifyAccessTokenExpiryMs

    /** Returns true if any Spotify user-OAuth tokens are stored. */
    fun hasSpotifyUserTokens(): Boolean =
        spotifyRefreshToken.isNotBlank() || spotifyAccessToken.isNotBlank()

    /** Clears all Spotify user-OAuth tokens + profile metadata (forces sign-in). */
    fun clearSpotifyUserTokens() {
        prefs.edit()
            .remove(KEY_SPOTIFY_ACCESS_TOKEN)
            .remove(KEY_SPOTIFY_REFRESH_TOKEN)
            .remove(KEY_SPOTIFY_ACCESS_TOKEN_EXPIRY_MS)
            .remove(KEY_SPOTIFY_SCOPES)
            .remove(KEY_SPOTIFY_USER_DISPLAY_NAME)
            .remove(KEY_SPOTIFY_USER_ID)
            .remove(KEY_SPOTIFY_USER_PRODUCT)
            .remove(KEY_SPOTIFY_PKCE_VERIFIER)
            .remove(KEY_SPOTIFY_AUTH_STATE)
            .apply()
    }

    /** Google OAuth client ID (for Calendar, Keep, Contacts). */
    var googleOAuthClientId: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_CLIENT_ID, value).apply()

    /** Google OAuth client secret (for Web application type). */
    var googleOAuthClientSecret: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_CLIENT_SECRET, value).apply()

    /** Google OAuth access token (short-lived). */
    var googleOAuthAccessToken: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_ACCESS_TOKEN, value).apply()

    /** Google OAuth refresh token (long-lived). */
    var googleOAuthRefreshToken: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_REFRESH_TOKEN, value).apply()

    /** Expiry timestamp (epoch ms) for the current access token. */
    var googleOAuthTokenExpiryMs: Long
        get() = prefs.getLong(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS, value).apply()

    /** Returns true if a non-expired OAuth access token exists. */
    fun isGoogleOAuthTokenValid(): Boolean =
        googleOAuthAccessToken.isNotBlank() &&
                System.currentTimeMillis() < googleOAuthTokenExpiryMs

    /** Returns true if any Google OAuth tokens are stored (even if expired). */
    fun hasGoogleOAuthTokens(): Boolean =
        googleOAuthRefreshToken.isNotBlank()

    /** Clears all Google OAuth tokens (forces re-authorization). */
    fun clearGoogleOAuthTokens() {
        prefs.edit()
            .remove(KEY_GOOGLE_OAUTH_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_OAUTH_REFRESH_TOKEN)
            .remove(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS)
            .apply()
    }

    // ── HUD Display Settings ────────────────────────────────────────────

    /** Show calendar widget on HUD. */
    var hudShowCalendar: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_CALENDAR, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_CALENDAR, value).apply()

    /** Show traffic & commute info on HUD. */
    var hudShowTraffic: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_TRAFFIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_TRAFFIC, value).apply()

    /** Show floating notifications on HUD. */
    var hudShowNotifications: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_NOTIFICATIONS, value).apply()

    /** HUD widget refresh interval in seconds (5–300). */
    var hudRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_HUD_REFRESH_INTERVAL_SECONDS, 60).coerceIn(5, 300)
        set(value) = prefs.edit().putInt(KEY_HUD_REFRESH_INTERVAL_SECONDS, value.coerceIn(5, 300)).apply()

    /** Show event time/date on the HUD calendar widget. */
    var hudShowEventTime: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_EVENT_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_EVENT_TIME, value).apply()

    /** Comma-separated list of enabled Google Calendar IDs. Empty = primary only. */
    var enabledCalendarIds: Set<String>
        get() {
            val raw = prefs.getString(KEY_ENABLED_CALENDAR_IDS, "") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_ENABLED_CALENDAR_IDS, value.joinToString(",")).apply()

    /** Show tasks widget on HUD. */
    var hudShowTasks: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_TASKS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_TASKS, value).apply()

    /** Show news headlines on HUD. */
    var hudShowNews: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_NEWS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_NEWS, value).apply()

    /** Number of tasks to show on HUD (1-10). */
    var tasksItemCount: Int
        get() = prefs.getInt(KEY_TASKS_ITEM_COUNT, 5).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_TASKS_ITEM_COUNT, value.coerceIn(1, 10)).apply()

    /** Number of news headlines to show on HUD (1-10). */
    var newsItemCount: Int
        get() = prefs.getInt(KEY_NEWS_ITEM_COUNT, 3).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_NEWS_ITEM_COUNT, value.coerceIn(1, 10)).apply()

    /** News headline refresh interval in seconds (60-3600). */
    var newsRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_NEWS_REFRESH_INTERVAL_SECONDS, 600).coerceIn(60, 3600)
        set(value) = prefs.edit().putInt(KEY_NEWS_REFRESH_INTERVAL_SECONDS, value.coerceIn(60, 3600)).apply()

    /** Comma-separated display order for HUD cards (e.g. "calendar,tasks,news"). */
    var hudDisplayOrder: String
        get() = prefs.getString(KEY_HUD_DISPLAY_ORDER, "calendar,tasks,news") ?: "calendar,tasks,news"
        set(value) = prefs.edit().putString(KEY_HUD_DISPLAY_ORDER, value).apply()

    /** JSON map of calendar ID → item count. E.g. {"primary":3,"other@gmail.com":5} */
    var calendarItemCounts: String
        get() = prefs.getString(KEY_CALENDAR_ITEM_COUNTS, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_CALENDAR_ITEM_COUNTS, value).apply()

    /** Get item count for a specific calendar. Default 3. */
    fun getCalendarItemCount(calendarId: String): Int {
        return try {
            val map: Map<String, Double> = gson.fromJson(
                calendarItemCounts,
                object : TypeToken<Map<String, Double>>() {}.type
            )
            map[calendarId]?.toInt()?.coerceIn(1, 10) ?: 3
        } catch (_: Exception) { 3 }
    }

    /** Google Maps / Routes API key. */
    var googleMapsApiKey: String
        get() = prefs.getString(KEY_GOOGLE_MAPS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_MAPS_API_KEY, value).apply()

    /** Gemini model override (e.g. "gemini-2.5-pro"). Blank = use default. */
    var geminiModelOverride: String
        get() = prefs.getString(KEY_GEMINI_MODEL_OVERRIDE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL_OVERRIDE, value).apply()

    // ── Gemini Live Voice & AI Settings ─────────────────────────────────

    /** Gemini Live voice name (e.g. "Puck", "Kore"). Blank = default (Puck). */
    var liveVoiceName: String
        get() = prefs.getString(KEY_LIVE_VOICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_VOICE_NAME, value).apply()

    /** Gemini TTS voice name for non-live text-to-speech (all 30 voices). Blank = default. */
    var ttsVoiceName: String
        get() = prefs.getString(KEY_TTS_VOICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_VOICE_NAME, value).apply()

    /** Gemini Live thinking level: minimal, low, medium, high. Blank = minimal. */
    var liveThinkingLevel: String
        get() = prefs.getString(KEY_LIVE_THINKING_LEVEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_THINKING_LEVEL, value).apply()

    /** Gemini Live temperature (0.0–2.0). -1 = use model default. */
    var liveTemperature: Float
        get() = prefs.getFloat(KEY_LIVE_TEMPERATURE, -1f)
        set(value) = prefs.edit().putFloat(KEY_LIVE_TEMPERATURE, value).apply()

    /** Enable session resumption for Gemini Live. */
    var liveSessionResumption: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SESSION_RESUMPTION, true)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_SESSION_RESUMPTION, value).apply()

    /** Enable context window compression for longer Live sessions. */
    var liveContextCompression: Boolean
        get() = prefs.getBoolean(KEY_LIVE_CONTEXT_COMPRESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_CONTEXT_COMPRESSION, value).apply()

    /** Target token count for context window compression trigger. */
    var liveCompressionTokens: Int
        get() = prefs.getInt(KEY_LIVE_COMPRESSION_TOKENS, 0)
        set(value) = prefs.edit().putInt(KEY_LIVE_COMPRESSION_TOKENS, value).apply()

    /** Enable proactive audio (model speaks when relevant without being asked). */
    var liveProactiveAudio: Boolean
        get() = prefs.getBoolean(KEY_LIVE_PROACTIVE_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_PROACTIVE_AUDIO, value).apply()

    /** Gemini Live language code (e.g. "en-US", "es-ES"). Blank = auto. */
    var liveLanguageCode: String
        get() = prefs.getString(KEY_LIVE_LANGUAGE_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_LANGUAGE_CODE, value).apply()

    /** Barge-in sensitivity multiplier for interrupting Gemini speech. Higher = less sensitive. */
    var liveBargeInSensitivity: Float
        get() = prefs.getFloat(KEY_LIVE_BARGE_IN_SENSITIVITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_LIVE_BARGE_IN_SENSITIVITY, value.coerceIn(0.6f, 2.5f)).apply()

    /** Microphone silence threshold (PCM16 peak). Audio below this level is treated as silence.
     *  Higher values filter out more background noise. Default 600 (~1.8% of max). Range: 100–3000. */
    var liveSilenceThreshold: Int
        get() = prefs.getInt(KEY_LIVE_SILENCE_THRESHOLD, 600)
        set(value) = prefs.edit().putInt(KEY_LIVE_SILENCE_THRESHOLD, value.coerceIn(100, 3000)).apply()

    /** When true, Gemini is never interrupted — it always finishes speaking before listening.
     *  Disables both server-side VAD interruption and client-side barge-in. */
    var liveDisableInterrupt: Boolean
        get() = prefs.getBoolean(KEY_LIVE_DISABLE_INTERRUPT, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_DISABLE_INTERRUPT, value).apply()

    // ── Timeout Settings ─────────────────────────────────────────────────

    /** Gemini Live idle timeout in seconds before auto-disconnect. 0 = use default. */
    var timeoutLiveIdleSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_LIVE_IDLE_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_LIVE_IDLE_SECONDS, value).apply()

    /** Research model HTTP timeout in seconds. 0 = use default (45s). */
    var timeoutResearchSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_RESEARCH_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_RESEARCH_SECONDS, value).apply()

    /** LearnLM model HTTP timeout in seconds. 0 = use default (45s). */
    var timeoutLearnLmSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_LEARNLM_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_LEARNLM_SECONDS, value).apply()

    /** Standard Gemini text/audio model HTTP timeout in seconds. 0 = use default. */
    var timeoutGeminiSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_GEMINI_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_GEMINI_SECONDS, value).apply()

    // ── Accessibility Settings ───────────────────────────────────────────

    /** HUD font scale multiplier (0.5–3.0). 1.0 = normal. */
    var hudFontScale: Float
        get() = prefs.getFloat(KEY_HUD_FONT_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_HUD_FONT_SCALE, value.coerceIn(0.5f, 3.0f)).apply()

    /** High-contrast mode for HUD text. */
    var hudHighContrast: Boolean
        get() = prefs.getBoolean(KEY_HUD_HIGH_CONTRAST, false)
        set(value) = prefs.edit().putBoolean(KEY_HUD_HIGH_CONTRAST, value).apply()

    /**
     * Whether the chat-panel "core eye" orb is shown at all. When false, the
     * orb ImageView and its surrounding glow layers are hidden — useful for
     * users who prefer a clean panel. Whether a CUSTOM image is shown is
     * determined by the presence of OrbImageStore.customFile() on disk; this
     * flag controls visibility independently.
     */
    var chatOrbVisible: Boolean
        get() = prefs.getBoolean(KEY_CHAT_ORB_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_ORB_VISIBLE, value).apply()

    /** TTS speech rate multiplier (0.25–4.0). 1.0 = normal. */
    var ttsSpeechRate: Float
        get() = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, value.coerceIn(0.25f, 4.0f)).apply()

    /** Auto-read all assistant responses via TTS. */
    var ttsAutoRead: Boolean
        get() = prefs.getBoolean(KEY_TTS_AUTO_READ, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_AUTO_READ, value).apply()

    /** Enable optional phone-to-glasses GPS bridge from the companion app. */
    var phoneLocationBridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHONE_LOCATION_BRIDGE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_LOCATION_BRIDGE_ENABLED, value).apply()

    /** Latest phone-provided location fix pushed from the companion app. */
    fun getPhoneLocationBridgeContext(): DeviceLocationContext? {
        val raw = prefs.getString(KEY_PHONE_LOCATION_BRIDGE_CONTEXT, "") ?: ""
        if (raw.isBlank()) return null
        return runCatching { gson.fromJson(raw, DeviceLocationContext::class.java) }.getOrNull()
    }

    fun setPhoneLocationBridgeContext(context: DeviceLocationContext?) {
        val editor = prefs.edit()
        if (context == null) editor.remove(KEY_PHONE_LOCATION_BRIDGE_CONTEXT)
        else editor.putString(KEY_PHONE_LOCATION_BRIDGE_CONTEXT, gson.toJson(context))
        editor.apply()
    }

    fun getBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return runCatching {
            gson.fromJson<List<Bookmark>>(raw, object : TypeToken<List<Bookmark>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun addBookmark(bookmark: Bookmark) {
        val updated = getBookmarks().toMutableList().apply { add(bookmark) }
        prefs.edit().putString(KEY_BOOKMARKS, gson.toJson(updated)).apply()
    }

    fun removeBookmark(url: String) {
        val updated = getBookmarks().filterNot { it.url == url }
        prefs.edit().putString(KEY_BOOKMARKS, gson.toJson(updated)).apply()
    }

    fun getAssistantCardHistory(): List<PersistedAssistantCard> {
        val raw = prefs.getString(KEY_ASSISTANT_CARD_HISTORY, "[]") ?: "[]"
        val structured = runCatching {
            gson.fromJson<List<PersistedAssistantCard>>(
                raw,
                object : TypeToken<List<PersistedAssistantCard>>() {}.type
            )
        }.getOrNull()
            ?.mapNotNull { card ->
                val text = card.text.trim()
                if (text.isBlank()) null else card.copy(text = text)
            }
            .orEmpty()
        if (structured.isNotEmpty()) return structured

        // Backward compatibility: migrate legacy string-only history.
        val legacy = runCatching {
            gson.fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type)
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (legacy.isEmpty()) return emptyList()

        val base = System.currentTimeMillis()
        return legacy.mapIndexed { index, text ->
            PersistedAssistantCard(
                text = text,
                url = null,
                timestampMs = base + index
            )
        }
    }

    fun setAssistantCardHistory(cards: List<PersistedAssistantCard>) {
        prefs.edit().putString(KEY_ASSISTANT_CARD_HISTORY, gson.toJson(cards)).apply()
    }

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_RESEARCH_PROVIDER = "research_provider"
        private const val KEY_RESEARCH_API_KEY = "research_api_key"
        private const val KEY_RESEARCH_MODEL = "research_model"
        private const val KEY_RESEARCH_PROMPT = "research_prompt"
        private const val KEY_RESEARCH_TTS_MODEL = "research_tts_model"
        private const val KEY_RESEARCH_TTS_VOICE_NAME = "research_tts_voice_name"
        private const val KEY_RESEARCH_TTS_LANGUAGE = "research_tts_language"
        private const val KEY_RESEARCH_TTS_DIRECTOR_NOTES = "research_tts_director_notes"
        private const val KEY_LEARNLM_MODEL = "learnlm_model"
        private const val KEY_CALENDAR_API_KEY = "calendar_api_key"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_OPENCLAW_ENDPOINT = "openclaw_endpoint"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_ASSISTANT_CARD_HISTORY = "assistant_card_history"
        private const val KEY_TTS_VOLUME = "tts_volume"
        private const val KEY_TTS_MUTED = "tts_muted"
        private const val KEY_SCREEN_BRIGHTNESS = "screen_brightness"
        /**
         * Default companion-app brightness. 1.0f = ride the system ceiling
         * (matches the legacy hardcoded BRIGHTNESS_OVERRIDE_FULL behaviour,
         * so users upgrading from a brightness-less build see no change
         * until they reach for the slider).
         */
        const val DEFAULT_SCREEN_BRIGHTNESS = 1.0f
        private const val KEY_MEDIA_TTS_VOICE = "media_tts_voice"
        private const val KEY_MEDIA_TTS_UNDERLINE = "media_tts_underline"
        private const val KEY_MUSIC_MUTED = "music_muted"
        private const val KEY_WEB_DESKTOP_MODE = "web_desktop_mode"
        private const val KEY_WEB_POINTER_SENSITIVITY = "web_pointer_sensitivity"
        private const val KEY_WEB_FORCE_DARK_MODE = "web_force_dark_mode"
        private const val KEY_BROWSER_SHOW_SYSTEM_INFO = "browser_show_system_info"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_PERSONALITY = "personality"
        private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
        private const val KEY_SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
        private const val KEY_SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
        private const val KEY_SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token"
        private const val KEY_SPOTIFY_ACCESS_TOKEN_EXPIRY_MS = "spotify_access_token_expiry_ms"
        private const val KEY_SPOTIFY_SCOPES = "spotify_scopes"
        private const val KEY_SPOTIFY_USER_DISPLAY_NAME = "spotify_user_display_name"
        private const val KEY_SPOTIFY_USER_ID = "spotify_user_id"
        private const val KEY_SPOTIFY_USER_PRODUCT = "spotify_user_product"
        private const val KEY_SPOTIFY_PKCE_VERIFIER = "spotify_pkce_verifier"
        private const val KEY_SPOTIFY_AUTH_STATE = "spotify_auth_state"
        private const val KEY_SPOTIFY_REDIRECT_URI = "spotify_redirect_uri"
        private const val DEFAULT_SPOTIFY_REDIRECT_URI = "http://127.0.0.1:19110/spotify/callback"
        private const val KEY_GOOGLE_OAUTH_CLIENT_ID = "google_oauth_client_id"
        private const val KEY_GOOGLE_OAUTH_CLIENT_SECRET = "google_oauth_client_secret"
        private const val KEY_GOOGLE_OAUTH_ACCESS_TOKEN = "google_oauth_access_token"
        private const val KEY_GOOGLE_OAUTH_REFRESH_TOKEN = "google_oauth_refresh_token"
        private const val KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS = "google_oauth_token_expiry_ms"
        private const val KEY_GOOGLE_MAPS_API_KEY = "google_maps_api_key"
        private const val KEY_GEMINI_MODEL_OVERRIDE = "gemini_model_override"
        private const val KEY_PHONE_LOCATION_BRIDGE_ENABLED = "phone_location_bridge_enabled"
        private const val KEY_PHONE_LOCATION_BRIDGE_CONTEXT = "phone_location_bridge_context"
        private const val KEY_HUD_SHOW_CALENDAR = "hud_show_calendar"
        private const val KEY_HUD_SHOW_TRAFFIC = "hud_show_traffic"
        private const val KEY_HUD_SHOW_NOTIFICATIONS = "hud_show_notifications"
        private const val KEY_HUD_REFRESH_INTERVAL_SECONDS = "hud_refresh_interval_seconds"
        private const val KEY_HUD_SHOW_EVENT_TIME = "hud_show_event_time"
        private const val KEY_ENABLED_CALENDAR_IDS = "enabled_calendar_ids"
        private const val KEY_HUD_SHOW_TASKS = "hud_show_tasks"
        private const val KEY_HUD_SHOW_NEWS = "hud_show_news"
        private const val KEY_TASKS_ITEM_COUNT = "tasks_item_count"
        private const val KEY_NEWS_ITEM_COUNT = "news_item_count"
        private const val KEY_NEWS_REFRESH_INTERVAL_SECONDS = "news_refresh_interval_seconds"
        private const val KEY_CALENDAR_ITEM_COUNTS = "calendar_item_counts"
        private const val KEY_HUD_DISPLAY_ORDER = "hud_display_order"
        private const val KEY_PROMPT_IDENTITY = "prompt_identity"
        private const val KEY_PROMPT_ROUTING_RULES = "prompt_routing_rules"
        private const val KEY_PROMPT_BEHAVIOR = "prompt_behavior"
        private const val KEY_PROMPT_URL_RULES = "prompt_url_rules"
        // Gemini Live Voice & AI
        private const val KEY_LIVE_VOICE_NAME = "live_voice_name"
        private const val KEY_TTS_VOICE_NAME = "tts_voice_name"
        private const val KEY_LIVE_THINKING_LEVEL = "live_thinking_level"
        private const val KEY_LIVE_TEMPERATURE = "live_temperature"
        private const val KEY_LIVE_SESSION_RESUMPTION = "live_session_resumption"
        private const val KEY_LIVE_CONTEXT_COMPRESSION = "live_context_compression"
        private const val KEY_LIVE_COMPRESSION_TOKENS = "live_compression_tokens"
        private const val KEY_LIVE_PROACTIVE_AUDIO = "live_proactive_audio"
        private const val KEY_LIVE_LANGUAGE_CODE = "live_language_code"
        private const val KEY_LIVE_BARGE_IN_SENSITIVITY = "live_barge_in_sensitivity"
        private const val KEY_LIVE_SILENCE_THRESHOLD = "live_silence_threshold"
        private const val KEY_LIVE_DISABLE_INTERRUPT = "live_disable_interrupt"
        // Timeout settings
        private const val KEY_TIMEOUT_LIVE_IDLE_SECONDS = "timeout_live_idle_seconds"
        private const val KEY_TIMEOUT_RESEARCH_SECONDS = "timeout_research_seconds"
        private const val KEY_TIMEOUT_LEARNLM_SECONDS = "timeout_learnlm_seconds"
        private const val KEY_TIMEOUT_GEMINI_SECONDS = "timeout_gemini_seconds"
        // Accessibility
        private const val KEY_HUD_FONT_SCALE = "hud_font_scale"
        private const val KEY_HUD_HIGH_CONTRAST = "hud_high_contrast"
        private const val KEY_CHAT_ORB_VISIBLE = "chat_orb_visible"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_AUTO_READ = "tts_auto_read"
        // OpenClaw
        private const val KEY_OPENCLAW_TOKEN = "openclaw_token"
        private const val KEY_OPENCLAW_SESSION_ID = "openclaw_session_id"
        private const val KEY_OPENCLAW_TIMEOUT_SECONDS = "openclaw_timeout_seconds"
        private const val KEY_OPENCLAW_ENABLED = "openclaw_enabled"
        private const val DEFAULT_OPENCLAW_ENDPOINT = ""

        private const val KEY_OPENCLAW_HEARTBEAT_INTERVAL_SECONDS = "openclaw_heartbeat_interval_seconds"
        private const val KEY_AGENT_STATUS_POLL_SECONDS = "agent_status_poll_seconds"
        private const val KEY_HUD_CHAT_HISTORY_DAYS = "hud_chat_history_days"
        // Per-agent history arrays appended by GeminiVoicePipeline on turn
        // completion. JSON arrays of {ts, agent, query, response, snippet}
        // records. Gemini covers direct Gemini Live turns (no tool routed
        // to Hermes/OpenClaw); the agent-specific keys are written from
        // the agent-readout tool branch.
        const val KEY_CHAT_HISTORY_GEMINI = "chat_history_gemini"
        const val KEY_CHAT_HISTORY_HERMES = "chat_history_hermes"
        const val KEY_CHAT_HISTORY_OPENCLAW = "chat_history_openclaw"
        private const val KEY_OPENCLAW_FAST_MODE = "openclaw_fast_mode"
        private const val KEY_OPENCLAW_THINK_LEVEL = "openclaw_think_level"
        private const val KEY_OPENCLAW_AFTER_FAST_MODE = "openclaw_after_fast_mode"
        private const val KEY_OPENCLAW_AFTER_THINK_LEVEL = "openclaw_after_think_level"
        private const val KEY_HERMES_AUTO_FOLLOWUP_ENABLED = "hermes_auto_followup_enabled"
        private const val KEY_HERMES_ENDPOINT = "hermes_endpoint"
        private const val KEY_HERMES_API_KEY = "hermes_api_key"
        private const val KEY_HERMES_SESSION_ID = "hermes_session_id"
        private const val KEY_HERMES_TIMEOUT_SECONDS = "hermes_timeout_seconds"
        private const val KEY_HERMES_ENABLED = "hermes_enabled"

        /**
         * How long Gemini Live keeps the mic open after a Hermes-routed
         * turn finishes when [hermesAutoFollowupEnabled] is on, so the
         * user can ask a natural follow-up without re-activating.
         */
        const val HERMES_FOLLOWUP_WINDOW_MS = 30_000L
        private const val KEY_ASSISTANT_DEFAULT_CAMERA = "assistant_default_camera"

        // Battery Saver
        private const val KEY_BATTERY_SAVER_ACTIVE = "battery_saver_active"
        private const val KEY_BATTERY_SAVER_AUTO_THRESHOLD = "battery_saver_auto_threshold"
        private const val KEY_BATTERY_SAVER_ORIG_REFRESH = "battery_saver_orig_refresh"

        // Quick Actions
        private const val KEY_QUICK_ACTIONS_JSON = "quick_actions_json"
        private const val KEY_HOME_ADDRESS = "home_address"
        private const val KEY_WORK_ADDRESS = "work_address"

        // Translation
        private const val KEY_TRANSLATE_DEFAULT_LANGUAGE = "translate_default_language"
        private const val KEY_TRANSLATE_AUTO_MODE = "translate_auto_mode"
    }
}

data class Bookmark(
    val title: String,
    val url: String
)

data class PersistedAssistantCard(
    val text: String,
    val url: String?,
    val timestampMs: Long
)
