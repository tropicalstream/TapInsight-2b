package com.rayneo.visionclaw.ui

import android.app.Application
import android.location.Location
import android.util.Log
import android.util.Patterns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.assistant.AssistantIntent
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.config.AppConfig
import com.rayneo.visionclaw.core.location.DeviceLocationResolver
import com.rayneo.visionclaw.core.model.ChatMessage
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleNewsClient
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import com.rayneo.visionclaw.core.network.GeminiRouter
import com.rayneo.visionclaw.core.network.LearnLmRouter
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.notifications.NotificationCenter
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.storage.db.ChatDatabase
import com.rayneo.visionclaw.core.storage.db.ChatMessageDao
import com.rayneo.visionclaw.core.storage.db.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MainViewModel – central state holder for the TapInsight HUD.
 *
 * Responsibilities:
 *   • Coordinates API calls (Gemini, Calendar) and surfaces errors.
 *   • Emits [apiKeyRequired] when any API returns a missing-key result.
 *   • Manages chat messages, calendar summary, web navigation, and active panel.
 *   • Tool calls are dispatched by ToolDispatcher in MainActivity.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val MAX_ASSISTANT_CHAT_CARDS = 20
        private const val SESSION_ONLY_CHAT_LOG = true
        private const val KEY_PREVIOUS_CHAT = "previous_chat_summary"
        private const val KEY_PREVIOUS_CHAT_MS = "previous_chat_summary_ms"
        private const val MAX_PREVIOUS_CONTEXT_CHARS = 12000  // large enough for research reports
        // PREVIOUS CONVERSATION expires after this much idle time. Long enough
        // that "I came back from a meeting" still has context to anchor "that
        // article we were just discussing"; short enough that a session from
        // last week (or last month) doesn't poison today's tool arguments.
        // (e.g. older sessions about "Amiga 500" being grafted into a fresh
        // "Share that list" turn.)
        private const val PREVIOUS_CHAT_MAX_AGE_MS = 6L * 60L * 60L * 1000L
        private const val HUD_CALENDAR_REFRESH_MIN_INTERVAL_MS = 60_000L
        private const val VISION_LOCATION_PRECISE_MAX_AGE_MS = 2 * 60 * 1000L
        private const val VISION_LOCATION_PRECISE_MAX_ACCURACY_METERS = 250f
        private const val VISION_LOCATION_FALLBACK_MAX_AGE_MS = 15 * 60 * 1000L
        private const val VISION_LOCATION_TIMEOUT_MS = 5_000L
        const val PANEL_CHAT = 0
        const val PANEL_WEB = 1
    }

    // ── Preferences ───────────────────────────────────────────────────────
    private val prefs = AppPreferences(application)
    val preferences: AppPreferences get() = prefs
    val appConfig = AppConfig.load(application)
    private val deviceLocationResolver = DeviceLocationResolver(application)
    private val chatMessageDao: ChatMessageDao =
        ChatDatabase.getInstance(application).chatMessageDao()
    private val chatHistoryMutex = Mutex()

    // ── Network clients ──────────────────────────────────────────────────
    val geminiRouter = GeminiRouter(
        apiKeyProvider = {
            // Priority: SharedPreferences (companion app) > config.json > BuildConfig
            prefs.geminiApiKey.takeIf { it.isNotBlank() }
                ?: appConfig.apiKeys.geminiKey.trim().takeIf {
                    it.isNotBlank() && !it.equals("YOUR_KEY_HERE", ignoreCase = true)
                }
                ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
        },
        preferredModelProvider = {
            // Priority: SharedPreferences (companion app) > config.json
            prefs.geminiModelOverride.trim().takeIf { it.isNotBlank() }
                ?: appConfig.apiKeys.geminiModel.trim().takeIf { it.isNotBlank() }
        },
        personalityProvider = {
            prefs.personality.takeIf { it.isNotBlank() }
        },
        customSystemPromptProvider = {
            prefs.customSystemPrompt.takeIf { it.isNotBlank() }
        },
        identityProvider = {
            prefs.promptIdentity.takeIf { it.isNotBlank() }
        },
        routingRulesProvider = {
            prefs.promptRoutingRules.takeIf { it.isNotBlank() }
        },
        behaviorProvider = {
            prefs.promptBehavior.takeIf { it.isNotBlank() }
        },
        urlRulesProvider = {
            prefs.promptUrlRules.takeIf { it.isNotBlank() }
        },
        locationContextProvider = {
            latestDeviceLocationContext?.let { loc ->
                val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
                val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60}min ago"
                val now = Date()
                val zone = TimeZone.getDefault()
                val timestamp = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.US).apply {
                    timeZone = zone
                }.format(now)
                val nearbySnapshot = latestNearbyPlaceSnapshot?.takeIf {
                    val ageOk = System.currentTimeMillis() - it.timestampMs <= 10 * 60 * 1000L
                    if (!ageOk) return@takeIf false
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        it.latitude,
                        it.longitude,
                        loc.latitude,
                        loc.longitude,
                        distance
                    )
                    distance[0] <= 750f
                }
                buildString {
                    append("The user is at latitude ${loc.latitude}, longitude ${loc.longitude}")
                    append(" (accuracy: ${loc.accuracyMeters?.toInt() ?: "unknown"}m, $fresh).")
                    append(" Current local date/time: $timestamp (${zone.id}).")
                    append(" Use this for google_places nearby searches and google_routes origin.")
                    append(" When the user asks 'where am I', use these coordinates to describe their location.")
                    append(" When the user asks about the horizon, sun position, shadows, sunrise, sunset, or direction-dependent outdoor observations,")
                    append(" use both this location and local time to reason about what they are seeing.")
                    nearbySnapshot?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                        append(" Nearby Google Places snapshot (soft context for storefronts, landmarks, and transit clues): ")
                        append(summary)
                    }
                }
            }
        },
        liveVoiceNameProvider = { prefs.liveVoiceName.takeIf { it.isNotBlank() } },
        liveThinkingLevelProvider = { prefs.liveThinkingLevel.takeIf { it.isNotBlank() } },
        liveTemperatureProvider = { prefs.liveTemperature },
        liveSessionResumptionProvider = { prefs.liveSessionResumption },
        liveContextCompressionProvider = { prefs.liveContextCompression },
        liveCompressionTokensProvider = { prefs.liveCompressionTokens },
        liveProactiveAudioProvider = { prefs.liveProactiveAudio },
        liveBargeInSensitivityProvider = { prefs.liveBargeInSensitivity },
        liveDisableInterruptProvider = { prefs.liveDisableInterrupt },
        liveLanguageCodeProvider = { prefs.liveLanguageCode.takeIf { it.isNotBlank() } },
        timeoutSecondsProvider = { prefs.timeoutGeminiSeconds },
        previousChatContextProvider = { getPreviousChatContext() },
        previousChatContextAgeMsProvider = { getPreviousChatContextAgeMs() }
    )
    private val researchRouter = ResearchRouter(
        providerProvider = {
            prefs.researchProvider.trim().takeIf { it.isNotBlank() } ?: "gemini"
        },
        apiKeyProvider = {
            prefs.researchApiKey.trim().takeIf { it.isNotBlank() }
        },
        modelProvider = {
            prefs.researchModel.trim().takeIf { it.isNotBlank() }
        },
        geminiFallbackApiKeyProvider = {
            prefs.geminiApiKey.takeIf { it.isNotBlank() }
                ?: appConfig.apiKeys.geminiKey.trim().takeIf {
                    it.isNotBlank() && !it.equals("YOUR_KEY_HERE", ignoreCase = true)
                }
                ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
        },
        context = application,
        timeoutSecondsProvider = { prefs.timeoutResearchSeconds },
        customResearchPromptProvider = { prefs.researchPrompt.takeIf { it.isNotBlank() } }
    )
    @Volatile
    private var latestLearnFrameBase64: String? = null

    val learnLmRouter = LearnLmRouter(
        apiKeyProvider = {
            prefs.geminiApiKey.takeIf { it.isNotBlank() }
                ?: appConfig.apiKeys.geminiKey.trim().takeIf {
                    it.isNotBlank() && !it.equals("YOUR_KEY_HERE", ignoreCase = true)
                }
                ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
        },
        modelProvider = {
            prefs.learnLmModel.trim().takeIf { it.isNotBlank() }
        },
        recentCardsProvider = {
            getAssistantCardsSnapshot().map { it.text }
        },
        context = application,
        currentImageBase64Provider = {
            latestLearnFrameBase64
        },
        timeoutSecondsProvider = { prefs.timeoutLearnLmSeconds }
    )
    var calendarClient = GoogleCalendarClient(
        apiKeyProvider = { prefs.calendarApiKey },
        context = application
    )
        private set
    var airQualityClient = GoogleAirQualityClient(
        apiKeyProvider = { prefs.googleMapsApiKey },
        context = application
    )
        private set

    /** Replace the default calendar client with one that supports OAuth. */
    fun setCalendarClient(client: GoogleCalendarClient) {
        calendarClient = client
        refreshHudUpcomingCalendar(force = true)
    }

    fun setAirQualityClient(client: GoogleAirQualityClient) {
        airQualityClient = client
        refreshHudAirQuality(force = true)
    }

    fun updateLatestLearnFrame(base64: String?) {
        latestLearnFrameBase64 = base64?.trim()?.takeIf { it.isNotBlank() }
    }

    var tasksClient: GoogleTasksClient? = null
        private set

    fun setTasksClient(client: GoogleTasksClient) {
        tasksClient = client
        refreshHudTasks(force = true)
    }

    private val newsClient = GoogleNewsClient()

    @Volatile
    private var latestDeviceLocationContext: DeviceLocationContext? = null
    @Volatile
    private var latestNearbyPlaceSnapshot: NearbyPlaceSnapshot? = null
    @Volatile
    private var lastHudCalendarRefreshMs = 0L
    // Last events we successfully fetched. The HUD summary is re-derived from
    // this against the CURRENT clock on every refresh, so a now-past event drops
    // off even when a later refresh can't fetch fresh data (e.g. a transient
    // OAuth/API hiccup) — without this, a stale "9 AM" line could linger long
    // after 9 AM because the failed refresh left the old summary untouched.
    @Volatile
    private var lastCalendarEventsCache: List<GoogleCalendarClient.CalendarEvent> = emptyList()
    @Volatile
    private var lastHudTasksRefreshMs = 0L
    @Volatile
    private var lastHudNewsRefreshMs = 0L
    @Volatile
    private var lastHudAirQualityRefreshMs = 0L

    // ── Active panel ─────────────────────────────────────────────────────
    private val _activePanelIndex = MutableLiveData(PANEL_CHAT)
    val activePanelIndex: LiveData<Int> = _activePanelIndex

    fun setActivePanel(index: Int) {
        _activePanelIndex.value = if (index == PANEL_WEB) PANEL_WEB else PANEL_CHAT
    }

    // ── Chat messages (StateFlow for coroutine collection) ───────────────
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var liveAssistantWorkingTurn: String = ""
    private var liveAssistantWorkingCardIndex = -1

    /** Read-only snapshot of the assistant's current in-progress turn used
     *  by the chat-history persistence hook in GeminiVoicePipeline. Captures
     *  the text BEFORE resetLiveAssistantStream() clears the buffer at
     *  onTurnComplete. Empty when no turn is in progress or the turn was
     *  routed to an agent (Hermes / TapClaw) whose reply is captured
     *  separately via dispatchNativeTool. */
    fun snapshotLiveAssistantTurn(): String = liveAssistantWorkingTurn
    @Volatile private var historyHydrated = false

    private data class NearbyPlaceSnapshot(
        val summary: String,
        val latitude: Double,
        val longitude: Double,
        val timestampMs: Long = System.currentTimeMillis()
    )

    init {
        if (SESSION_ONLY_CHAT_LOG) {
            _messages.value = emptyList()
            historyHydrated = true
            purgePersistedAssistantHistoryAsync()
        } else {
            hydrateAssistantHistoryBlocking()
        }
        // Fetch HUD data on startup.
        refreshHudUpcomingCalendar(force = true)
        refreshHudTasks(force = true)
        refreshHudNews(force = true)
    }

    fun hydrateAssistantHistory() {
        if (SESSION_ONLY_CHAT_LOG) {
            if (!historyHydrated) {
                _messages.value = emptyList()
                historyHydrated = true
            }
            return
        }
        if (historyHydrated) return
        viewModelScope.launch {
            val restored = loadPersistedAssistantMessages()
            _messages.value = restored
            historyHydrated = true
        }
    }

    fun getAssistantCardsSnapshot(): List<ChatMessage> {
        return _messages.value
            .filterNot { it.fromUser }
            .let { cards ->
                if (cards.size > MAX_ASSISTANT_CHAT_CARDS) {
                    cards.takeLast(MAX_ASSISTANT_CHAT_CARDS)
                } else {
                    cards
                }
            }
    }

    // ── Previous chat context persistence ────────────────────────────
    // When a new chat starts or the app closes, the current conversation
    // is saved to SharedPreferences so Gemini can reference it in the
    // next session. This survives app restarts.

    private val chatContextPrefs = getApplication<android.app.Application>()
        .getSharedPreferences("chat_context", android.content.Context.MODE_PRIVATE)

    /**
     * Persist the current chat exchange as "previous conversation" context.
     * Called when a new chat starts, the app goes to background, or the
     * unipanel service closes a Live session.
     */
    fun saveChatContextForNextSession() {
        // Persist ASSISTANT cards ONLY — never the user's raw spoken commands.
        // This restores the hermes behavior: including the user's turns (e.g.
        // "play jazz", "open <x>", "research <y>") let the model graft those
        // stale command verbs into the NEXT session's tool arguments, which is
        // the cross-session contamination class of bug. Assistant-authored text
        // still supports anaphoric follow-ups ("tell me more about that")
        // without handing the model stale commands to re-execute.
        val cards = getAssistantCardsSnapshot()
        if (cards.isEmpty()) return
        val summary = cards.takeLast(10).joinToString("\n---\n") { card ->
            card.text.take(4000)
        }.take(MAX_PREVIOUS_CONTEXT_CHARS)
        chatContextPrefs.edit()
            .putString(KEY_PREVIOUS_CHAT, summary)
            .putLong(KEY_PREVIOUS_CHAT_MS, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Saved previous chat context: ${summary.length} chars, ${cards.size} cards")
    }

    /**
     * Wipe any persisted previous-conversation cache. Called on the explicit
     * "New Chat" gesture so the next session starts with a clean slate and
     * stale topics can't bleed into "that"/"it"/"the list" anaphora.
     */
    fun clearPreviousChatContext() {
        chatContextPrefs.edit()
            .remove(KEY_PREVIOUS_CHAT)
            .remove(KEY_PREVIOUS_CHAT_MS)
            .apply()
        Log.d(TAG, "Cleared previous chat context (explicit New Chat)")
    }

    /**
     * Get the saved previous conversation context (from the last session).
     * Returns null if there's no previous context, or if it's older than the
     * configured TTL — old cached cards (e.g. yesterday's Amiga 500 chat)
     * must not surface as "active context" for today's anaphoric tool calls.
     */
    fun getPreviousChatContext(): String? {
        val stored = chatContextPrefs.getString(KEY_PREVIOUS_CHAT, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val savedAt = chatContextPrefs.getLong(KEY_PREVIOUS_CHAT_MS, 0L)
        val now = System.currentTimeMillis()
        val ageMs = if (savedAt <= 0L) Long.MAX_VALUE else now - savedAt
        if (ageMs > PREVIOUS_CHAT_MAX_AGE_MS) {
            Log.d(
                TAG,
                "Skipping previous chat context: stale (${ageMs / 60000L} min old, " +
                    "TTL ${PREVIOUS_CHAT_MAX_AGE_MS / 60000L} min)"
            )
            return null
        }
        Log.d(
            TAG,
            "Previous chat context available: ${stored.length} chars, " +
                "${ageMs / 60000L} min old"
        )
        return stored
    }

    /**
     * Used by GeminiRouter logging so a cross-session contamination bug can
     * be confirmed from logcat without re-deriving age elsewhere.
     */
    fun getPreviousChatContextAgeMs(): Long {
        val savedAt = chatContextPrefs.getLong(KEY_PREVIOUS_CHAT_MS, 0L)
        if (savedAt <= 0L) return -1L
        return System.currentTimeMillis() - savedAt
    }

    // ── Calendar summary (StateFlow for HUD display) ─────────────────────
    private val _calendarSummary = MutableStateFlow("")
    val calendarSummary: StateFlow<String> = _calendarSummary.asStateFlow()

    // ── Tasks summary (StateFlow for HUD display) ─────────────────────────
    private val _tasksSummary = MutableStateFlow("")
    val tasksSummary: StateFlow<String> = _tasksSummary.asStateFlow()

    // ── News summary (StateFlow for HUD display) ──────────────────────────
    private val _newsSummary = MutableStateFlow("")
    val newsSummary: StateFlow<String> = _newsSummary.asStateFlow()
    data class AirQualityHudState(
        val text: String,
        val aqi: Int?
    )
    private val _airQualitySummary = MutableStateFlow<AirQualityHudState?>(null)
    val airQualitySummary: StateFlow<AirQualityHudState?> = _airQualitySummary.asStateFlow()

    data class RadioHudState(
        val stationName: String,
        val genre: String? = null,
        val playing: Boolean
    )
    private val _radioSummary = MutableStateFlow<RadioHudState?>(null)
    val radioSummary: StateFlow<RadioHudState?> = _radioSummary.asStateFlow()

    // ── API Key Required notification ────────────────────────────────────
    private val _apiKeyRequired = MutableLiveData<String?>()
    val apiKeyRequired: LiveData<String?> = _apiKeyRequired

    fun clearApiKeyRequired() {
        _apiKeyRequired.value = null
    }

    /** Central handler for missing API key — shows HUD notification without panel switching. */
    fun onApiKeyMissing(serviceName: String) {
        Log.w(TAG, "API key missing for: $serviceName")
        _apiKeyRequired.postValue("API Key Required ($serviceName)")
    }

    // ── Web navigation ───────────────────────────────────────────────────
    private val _webNavigationUrl = MutableLiveData<String?>()
    val webNavigationUrl: LiveData<String?> = _webNavigationUrl

    fun navigateWeb(url: String) {
        _webNavigationUrl.value = url
        _activePanelIndex.value = PANEL_WEB
    }

    fun clearWebNavigation() {
        _webNavigationUrl.value = null
    }

    /** Called by panel fragments to open a URL in the web panel. */
    fun openUrl(url: String) {
        // URLPipe diagnostic: this is the single funnel where every chat-card
        // tap and toolAssist auto-open lands before the WebView/TapBrowser
        // sees it. Logging the exact string here lets us diff against the
        // upstream `URLPipe/extract` and `URLPipe/tap` lines and confirm no
        // mutation happened on the way through.
        Log.d("MainViewModel", "URLPipe/open url='$url' len=${url.length}")
        navigateWeb(url)
    }

    // ── YouTube playlist playback (routed to TapBrowser) ────────────────
    data class YouTubePlaybackEvent(
        val query: String,
        val mode: String,
        val searchUrl: String,
        val responseText: String,
        val queue: List<String> = emptyList()
    )

    private val _youtubePlaybackEvent = MutableLiveData<YouTubePlaybackEvent?>()
    val youtubePlaybackEvent: LiveData<YouTubePlaybackEvent?> = _youtubePlaybackEvent

    fun clearYoutubePlaybackEvent() {
        _youtubePlaybackEvent.value = null
    }

    /**
     * Checks if [text] is a YouTube playback command (e.g. "play youtube drake",
     * "play youtube music by Taylor Swift"). If matched, emits a
     * [YouTubePlaybackEvent] for MainActivity to launch TapBrowser with the
     * proper autoplay extras, and returns true to short-circuit Gemini.
     */
    private fun maybeHandleYouTubePlayback(text: String): Boolean {
        val trimmed = text.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return false
        if (AssistantIntentParser.isYouTubeLookupRequest(text)) return false

        AssistantIntentParser.parseExplicitYouTubePlaybackRequest(trimmed)?.let { spec ->
            val first = spec.items.firstOrNull()?.trim().orEmpty()
            if (first.isNotBlank()) {
                val searchPhrase =
                    if (spec.mode == "music" && !Regex("(?i)\\b(?:music|songs?|audio|track)\\b").containsMatchIn(first)) {
                        "$first music"
                    } else {
                        first
                    }
                val encoded = URLEncoder.encode(searchPhrase, "UTF-8")
                val searchUrl = "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=${spec.mode}"
                val queue = spec.items.takeIf { it.size > 1 }.orEmpty()
                val msg = if (queue.isNotEmpty()) {
                    "Queuing ${queue.size} YouTube ${if (spec.mode == "music") "music picks" else "videos"} starting with $first."
                } else {
                    "Playing the newest YouTube ${if (spec.mode == "music") "music" else "videos"} for $first with captions enabled."
                }
                appendAssistantInteraction(msg)
                _chatResponse.postValue(msg)
                _youtubePlaybackEvent.postValue(
                    YouTubePlaybackEvent(first, spec.mode, searchUrl, msg, queue)
                )
                return true
            }
        }

        val lower = trimmed.lowercase(Locale.US)
        val explicitPlayback = AssistantIntentParser.hasExplicitYouTubePlaybackVerb(trimmed)
        val terseYouTubeCommand =
            lower.startsWith("youtube music ") ||
                lower.startsWith("youtube songs ") ||
                lower.startsWith("youtube videos ")

        // "play/open" is optional — Gemini Live often drops it in transcription
        // Music-specific patterns (highest priority)
        if (explicitPlayback || terseYouTubeCommand) {
            val musicPatterns = listOf(
                Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+music\\s+(?:by|from|about)\\s+(.+?)\\s*$"),
                Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+songs?\\s+(?:by|from|about)\\s+(.+?)\\s*$"),
                Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+music\\s+(.+?)\\s*$"),
                Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+songs?\\s+(.+?)\\s*$")
            )
            val musicTopic = musicPatterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.getOrNull(1) }
                ?.trim()?.takeIf { it.isNotBlank() }
            if (!musicTopic.isNullOrBlank()) {
                val encoded = URLEncoder.encode("$musicTopic music", "UTF-8")
                val searchUrl = "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=music"
                val msg = "Playing the newest YouTube music for $musicTopic with captions enabled."
                appendAssistantInteraction(msg)
                _chatResponse.postValue(msg)
                _youtubePlaybackEvent.postValue(YouTubePlaybackEvent(musicTopic, "music", searchUrl, msg))
                return true
            }
        }

        // Video-specific patterns
        if (explicitPlayback || terseYouTubeCommand) {
            val videoPatterns = listOf(
                Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+videos?\\s+(?:by|from|about|on)\\s+(.+?)\\s*$"),
                Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+videos?\\s+(.+?)\\s*$")
            )
            val videoTopic = videoPatterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.getOrNull(1) }
                ?.trim()?.takeIf { it.isNotBlank() }
            if (!videoTopic.isNullOrBlank()) {
                val encoded = URLEncoder.encode(videoTopic, "UTF-8")
                val searchUrl = "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=video"
                val msg = "Playing the newest YouTube videos for $videoTopic with captions enabled."
                appendAssistantInteraction(msg)
                _chatResponse.postValue(msg)
                _youtubePlaybackEvent.postValue(YouTubePlaybackEvent(videoTopic, "video", searchUrl, msg))
                return true
            }
        }

        // Subscriptions patterns
        val subscriptionsPatterns = listOf(
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?(?:youtube\s+)?subscribed\s+channels\s*$"""),
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?youtube\s+subscriptions?\s*$"""),
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?subscriptions?\s*$""")
        )
        if (subscriptionsPatterns.any { it.matches(trimmed) }) {
            val url = "https://www.youtube.com/feed/subscriptions?taplink_autoplay=subscriptions"
            val msg = "Playing the newest videos from your subscribed channels with captions enabled."
            appendAssistantInteraction(msg)
            _chatResponse.postValue(msg)
            _youtubePlaybackEvent.postValue(YouTubePlaybackEvent("subscriptions", "subscriptions", url, msg))
            return true
        }

        // History patterns — ANCHORED. The prior version used loose
        // `contains("youtube") && contains("history")` which falsely fired on
        // any conversation about the "history of X" that also happened to
        // mention YouTube (e.g. "are there YouTube videos about the history
        // of Pixar?" would hijack the turn and open the user's watch history
        // feed). Require "history" to be the actual object of a play/open
        // command, not just a co-occurring word.
        val historyPatterns = listOf(
            // "my youtube history", "open my youtube history", "play my youtube history"
            Regex("""(?i)^\s*(?:play|open|start|show|pull\s+up)?\s*(?:my\s+)?youtube\s+history\s*$"""),
            // "youtube watch history", "youtube viewing history"
            Regex("""(?i)^\s*(?:play|open|start|show|pull\s+up)?\s*(?:my\s+)?youtube\s+(?:watch|viewing)\s+history\s*$"""),
            // "watch history", "viewing history" (standalone command)
            Regex("""(?i)^\s*(?:play|open|start|show|pull\s+up)?\s*(?:my\s+)?(?:watch|viewing)\s+history\s*$"""),
            // "play my history", "open my history" — must have "my" so random
            // sentences containing "play" and "history" don't match.
            Regex("""(?i)^\s*(?:play|open|start|show|pull\s+up)\s+my\s+history\s*$""")
        )
        if (historyPatterns.any { it.matches(trimmed) }) {
            val url = "https://www.youtube.com/feed/history?taplink_autoplay=history"
            val msg = "Playing videos from your YouTube watch history with captions enabled."
            appendAssistantInteraction(msg)
            _chatResponse.postValue(msg)
            _youtubePlaybackEvent.postValue(YouTubePlaybackEvent("history", "history", url, msg))
            return true
        }

        // Catch-all: "[play] youtube <topic>" — must START with a play/open
        // verb to avoid hijacking lookup questions like "are there any YouTube
        // videos about X?" or statements like "youtube is a Google company".
        // Make the play/open verb MANDATORY for this catch-all (the earlier
        // topic-specific patterns above already handle "youtube videos about
        // X" without a verb). Also exclude question-shaped inputs and common
        // lookup prefixes.
        val looksLikeQuestion = trimmed.endsWith("?") ||
            Regex("""(?i)^\s*(?:are|is|do|does|did|can|could|what|who|when|where|why|how|any)\b""")
                .containsMatchIn(trimmed)
        val catchAll = Regex("""(?i)^\s*(?:play|open|start|pull\s+up|put\s+on)\s+youtube\s+(.+?)\s*$""")
        val catchTopic = catchAll.find(trimmed)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotBlank() }
        if (!catchTopic.isNullOrBlank() && !looksLikeQuestion) {
            val encoded = URLEncoder.encode(catchTopic, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encoded&taplink_autoplay=video"
            val msg = "Playing the newest YouTube videos for $catchTopic with captions enabled."
            appendAssistantInteraction(msg)
            _chatResponse.postValue(msg)
            _youtubePlaybackEvent.postValue(YouTubePlaybackEvent(catchTopic, "video", searchUrl, msg))
            return true
        }

        return false
    }

    fun updateDeviceLocationContext(context: DeviceLocationContext) {
        latestNearbyPlaceSnapshot?.let { snapshot ->
            val distance = FloatArray(1)
            Location.distanceBetween(
                snapshot.latitude,
                snapshot.longitude,
                context.latitude,
                context.longitude,
                distance
            )
            if (distance[0] > 250f) {
                latestNearbyPlaceSnapshot = null
            }
        }
        latestDeviceLocationContext = context
        persistLastKnownLocation(context)
        refreshHudAirQuality(force = true)
    }

    fun clearDeviceLocationContext() {
        latestDeviceLocationContext = null
        latestNearbyPlaceSnapshot = null
        _airQualitySummary.value = null
    }

    fun updateNearbyPlaceSnapshot(
        latitude: Double,
        longitude: Double,
        summary: String?
    ) {
        latestNearbyPlaceSnapshot = summary
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                NearbyPlaceSnapshot(
                    summary = it,
                    latitude = latitude,
                    longitude = longitude
                )
            }
    }

    fun clearNearbyPlaceSnapshot() {
        latestNearbyPlaceSnapshot = null
    }

    fun updateRadioHudState(stationName: String?, genre: String?, playing: Boolean) {
        val cleanName = stationName?.trim().orEmpty()
        _radioSummary.value =
            if (playing && cleanName.isNotBlank()) {
                RadioHudState(
                    stationName = cleanName,
                    genre = genre?.trim()?.takeIf { it.isNotBlank() },
                    playing = true
                )
            } else {
                null
            }
    }

    fun getDeviceLocationContext(): DeviceLocationContext? {
        return latestDeviceLocationContext
    }

    // Last-known device location, persisted across sessions/restarts so the
    // glasses ALWAYS have a location to ground "places near me" / routes — even
    // before a fresh GPS fix, or when GPS/permission/IP all fail this session.
    // Better a slightly-stale location (timestamp tells Gemini its age) than
    // "I don't know where you are".
    private val locationPrefs by lazy {
        getApplication<android.app.Application>()
            .getSharedPreferences("device_location", android.content.Context.MODE_PRIVATE)
    }

    private fun persistLastKnownLocation(ctx: DeviceLocationContext) {
        runCatching {
            locationPrefs.edit()
                .putString("lat", ctx.latitude.toString())
                .putString("lon", ctx.longitude.toString())
                .putFloat("acc", ctx.accuracyMeters ?: -1f)
                .putString("provider", ctx.provider ?: "")
                .putLong("ts", ctx.timestampMs)
                .apply()
        }
    }

    private fun loadLastKnownLocation(): DeviceLocationContext? = runCatching {
        val lat = locationPrefs.getString("lat", null)?.toDoubleOrNull() ?: return@runCatching null
        val lon = locationPrefs.getString("lon", null)?.toDoubleOrNull() ?: return@runCatching null
        DeviceLocationContext(
            latitude = lat,
            longitude = lon,
            accuracyMeters = locationPrefs.getFloat("acc", -1f).takeIf { it >= 0f },
            provider = locationPrefs.getString("provider", null)?.takeIf { it.isNotBlank() }
                ?: "last_known",
            timestampMs = locationPrefs.getLong("ts", 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )
    }.getOrNull()

    /**
     * Ensure a usable device location is published before a Live session
     * connects, so the router's CURRENT LOCATION block (built once at
     * connect time) is filled in and Gemini always knows where the user is.
     * Cheap when a recent fix is already cached (phone-bridge / GPS / IP);
     * only does a bounded resolve when nothing fresh is available. Best
     * effort — never throws.
     */
    suspend fun ensureDeviceLocationForLiveSession() {
        val current = latestDeviceLocationContext
        val freshEnough = current != null &&
            (System.currentTimeMillis() - current.timestampMs) <= 10 * 60 * 1000L
        if (freshEnough) return

        // FAST PATH ONLY — never block the Live connect on a fresh GPS/IP
        // resolve(). On a cold start that resolve loops up to ~4 providers at
        // ~4.5s each, then wifi triangulation, then IP geolocation — easily
        // 20-30s during which the user just stares at "Connecting…". Seed the
        // CURRENT LOCATION block from the cheap, synchronous sources only
        // (phone-bridge / in-memory cache via peekCached, else persisted
        // last-known) so Gemini still knows roughly where the user is, then
        // let the connect proceed immediately.
        val cached = runCatching {
            withContext(Dispatchers.IO) {
                deviceLocationResolver.peekCached(allowApproximate = true)
            }
        }.getOrNull()
        if (cached != null) {
            updateDeviceLocationContext(cached)
        } else if (latestDeviceLocationContext == null) {
            // No cached fix — fall back to the persisted last-known location so
            // 'places near me' / routes can still ground instead of Gemini
            // saying it has no idea. Set the field directly (don't re-persist
            // a stale value).
            loadLastKnownLocation()?.let { latestDeviceLocationContext = it }
        }

        // Warm a precise fix in the BACKGROUND — this does NOT gate the
        // current connect. When it lands it refreshes latestDeviceLocationContext
        // and the resolver cache, so the next turn / next session has an
        // accurate location (and AQI / places re-ground). Fire-and-forget;
        // viewModelScope is Application-scoped so it outlives this connect.
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = runCatching {
                deviceLocationResolver.resolve(
                    requirePrecise = false,
                    allowApproximateFallback = true
                )
            }.getOrNull()
            if (fresh != null) {
                withContext(Dispatchers.Main) { updateDeviceLocationContext(fresh) }
            }
        }
    }

    private fun hasFreshVisionLocationContext(context: DeviceLocationContext?): Boolean {
        if (context == null) return false
        val ageMs = System.currentTimeMillis() - context.timestampMs
        val accuracy = context.accuracyMeters ?: Float.MAX_VALUE
        return ageMs <= VISION_LOCATION_PRECISE_MAX_AGE_MS &&
            accuracy <= VISION_LOCATION_PRECISE_MAX_ACCURACY_METERS &&
            context.provider != "ip_geolocation"
    }

    private suspend fun ensureVisionLocationContext() {
        if (hasFreshVisionLocationContext(latestDeviceLocationContext)) {
            return
        }

        val resolved = withContext(Dispatchers.IO) {
            deviceLocationResolver.peekCached(
                maxAgeMs = VISION_LOCATION_PRECISE_MAX_AGE_MS,
                maxAccuracyMeters = VISION_LOCATION_PRECISE_MAX_ACCURACY_METERS,
                allowApproximate = false
            ) ?: deviceLocationResolver.resolveNavigationBlocking(timeoutMs = VISION_LOCATION_TIMEOUT_MS)
                ?: deviceLocationResolver.resolve(
                    maxAgeMs = VISION_LOCATION_FALLBACK_MAX_AGE_MS,
                    timeoutMs = VISION_LOCATION_TIMEOUT_MS,
                    requirePrecise = false,
                    allowApproximateFallback = true
                )
        }

        if (resolved != null) {
            updateDeviceLocationContext(resolved)
            Log.d(
                TAG,
                "Vision request location ready provider=${resolved.provider} lat=${resolved.latitude} lon=${resolved.longitude} acc=${resolved.accuracyMeters}"
            )
        } else {
            Log.w(TAG, "Vision request continuing without a fresh location context")
        }
    }

    // ── Chat / Gemini ────────────────────────────────────────────────────
    private val _chatResponse = MutableLiveData<String?>()
    val chatResponse: LiveData<String?> = _chatResponse

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /**
     * Called by ChatPanelFragment when the user submits text input.
     * Adds user message, sends to Gemini, and appends the response.
     * Sends the input to Gemini and adds the result to chat.
     */
    fun submitChatInput(text: String) {
        viewModelScope.launch {
            if (maybeHandleYouTubePlayback(text)) return@launch
            if (maybeHandleDirectAssistantIntent(text)) {
                return@launch
            }
            _isLoading.value = true
            when (val result = geminiRouter.sendPrompt(text)) {
                is GeminiRouter.GeminiResult.Success -> {
                    val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(result.text))
                    val fullLog = appendAssistantInteraction(rendered)
                    _chatResponse.postValue(fullLog)
                }
                is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                    onApiKeyMissing("Gemini")
                }
                is GeminiRouter.GeminiResult.Error -> {
                    val fullLog = appendAssistantInteraction("Error: ${result.message}")
                    _chatResponse.postValue(fullLog)
                    Log.e(TAG, "Gemini error: ${result.message}")
                }
            }
            _isLoading.postValue(false)
        }
    }

    fun sendChatMessage(message: String, systemPrompt: String? = null) {
        viewModelScope.launch {
            if (maybeHandleYouTubePlayback(message)) return@launch
            if (maybeHandleDirectAssistantIntent(message)) {
                return@launch
            }
            _isLoading.value = true
            when (val result = geminiRouter.sendPrompt(message, systemInstruction = systemPrompt)) {
                is GeminiRouter.GeminiResult.Success -> {
                    val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(result.text))
                    val fullLog = appendAssistantInteraction(rendered)
                    _chatResponse.postValue(fullLog)
                }
                is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                    onApiKeyMissing("Gemini")
                }
                is GeminiRouter.GeminiResult.Error -> {
                    val fullLog = appendAssistantInteraction("Error: ${result.message}")
                    _chatResponse.postValue(fullLog)
                    Log.e(TAG, "Gemini error: ${result.message}")
                }
            }
            _isLoading.postValue(false)
        }
    }

    /**
     * Appends a user message generated by the Gemini Live session.
     */
    fun appendLiveUserTranscript(text: String) {
        // Privacy requirement: never render user STT/input transcription in chat.
    }

    /**
     * Appends assistant text generated by Gemini Live output transcription.
     * Tool-call responses are handled natively by ToolDispatcher.
     */
    fun appendLiveAssistantTranscript(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        val rendered = ensureBottomRawUrls(safe)
        val fullLog = finalizeAssistantLiveTurn(rendered)
        _chatResponse.postValue(fullLog)
    }

    /**
     * Persist streaming Gemini output directly in chat. This keeps the chat log live-updated
     * without transient overlays while Gemini is still generating a turn.
     */
    fun appendLiveAssistantStreamChunk(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        val fullLog = appendLiveAssistantWorkingChunk(safe)
        _chatResponse.postValue(fullLog)
    }

    fun appendDirectAssistantResponse(text: String) {
        val safe = sanitizeAssistantDisplayText(text)
        if (safe.isBlank()) return
        val rendered = ensureBottomRawUrls(safe)
        val fullLog = appendAssistantInteraction(rendered)
        _chatResponse.postValue(fullLog)
    }

    fun commitLiveAssistantStreamIfNeeded() {
        val live = liveAssistantWorkingTurn.trim()
        val hasWorkingCard = findLiveAssistantWorkingIndex(_messages.value) >= 0
        if (live.isBlank() && !hasWorkingCard) return

        if (live.isNotBlank() && !hasWorkingCard) {
            appendAssistantInteraction(live)
            return
        }
        clearLiveAssistantWorkingCard(commitIfPopulated = true)
    }

    fun resetLiveAssistantStream() {
        commitLiveAssistantStreamIfNeeded()
        liveAssistantWorkingTurn = ""
    }

    private fun sanitizeAssistantDisplayText(text: String): String {
        return text
            .lineSequence()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("thought:", ignoreCase = true) ||
                    t.startsWith("reasoning:", ignoreCase = true) ||
                    t.startsWith("<thinking>", ignoreCase = true) ||
                    t.startsWith("</thinking>", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun ensureBottomRawUrls(text: String): String {
        val base = text.trim()
        if (base.isBlank()) return base

        val urls = LinkedHashSet<String>()
        val matcher = Patterns.WEB_URL.matcher(base)
        while (matcher.find()) {
            val raw = matcher.group().orEmpty().trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
            if (raw.isBlank()) continue
            val display = normalizeUrl(raw)
            urls.add(display)
        }
        if (urls.isEmpty()) return base

        val urlBlock = urls.joinToString("\n")
        if (base.endsWith(urlBlock)) return base
        return "$base\n\n$urlBlock"
    }

    private fun extractFirstUrl(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return null
        val raw = matcher.group().orEmpty().trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
        if (raw.isBlank()) return null
        return normalizeUrl(raw)
    }

    private fun normalizeUrl(raw: String): String {
        return if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
    }

    private fun mergeStreamText(existing: String, incoming: String): String {
        val prev = existing.trim()
        val next = incoming.trim()
        if (prev.isBlank()) return next
        if (next.isBlank()) return prev
        if (next.startsWith(prev)) return next
        if (prev.startsWith(next)) return prev
        if (next.contains(prev)) return next
        if (prev.contains(next)) return prev

        val maxOverlap = minOf(prev.length, next.length)
        for (n in maxOverlap downTo 1) {
            if (prev.endsWith(next.substring(0, n))) {
                return prev + next.substring(n)
            }
        }
        return "$prev $next"
    }

    fun sendVisionQuery(prompt: String, imageBase64: String) {
        viewModelScope.launch {
            _isLoading.value = true
            ensureVisionLocationContext()
            when (val result = geminiRouter.sendVisionPrompt(prompt, imageBase64)) {
                is GeminiRouter.GeminiResult.Success -> {
                    val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(result.text))
                    val fullLog = appendAssistantInteraction(rendered)
                    _chatResponse.postValue(fullLog)
                }
                is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                    onApiKeyMissing("Gemini")
                }
                is GeminiRouter.GeminiResult.Error -> {
                    val fullLog = appendAssistantInteraction("Error: ${result.message}")
                    _chatResponse.postValue(fullLog)
                }
            }
            _isLoading.postValue(false)
        }
    }

    /**
     * Full end-to-end routing pipeline with tool call support.
     * Sends text and optional frame to Gemini, parses tool calls,
     * Routes queries through Gemini with tool calling.
     */
    /**
     * TapInsight: Routes queries through Gemini with tool calling.
     * Tool calls are handled natively by ToolDispatcher in MainActivity.
     */
    fun routeWithToolCalls(text: String, frameBase64: String? = null) {
        viewModelScope.launch {
            if (maybeHandleYouTubePlayback(text)) return@launch
            if (maybeHandleDirectAssistantIntent(text)) {
                return@launch
            }
            _isLoading.value = true
            try {
                val geminiResult = if (frameBase64 != null) {
                    ensureVisionLocationContext()
                    geminiRouter.sendVisionPrompt(text, frameBase64)
                } else {
                    geminiRouter.sendPrompt(text)
                }

                when (geminiResult) {
                    is GeminiRouter.GeminiResult.Success -> {
                        val rendered = ensureBottomRawUrls(sanitizeAssistantDisplayText(geminiResult.text))
                        val fullLog = appendAssistantInteraction(rendered)
                        _chatResponse.postValue(fullLog)
                    }
                    is GeminiRouter.GeminiResult.ApiKeyMissing -> {
                        onApiKeyMissing("Gemini")
                    }
                    is GeminiRouter.GeminiResult.Error -> {
                        val fullLog = appendAssistantInteraction("Error: ${geminiResult.message}")
                        _chatResponse.postValue(fullLog)
                        Log.e(TAG, "Gemini error: ${geminiResult.message}")
                    }
                }
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun maybeHandleDirectAssistantIntent(text: String): Boolean {
        val intent = AssistantIntentParser.parse(text) ?: return false
        handleDirectAssistantIntent(intent)
        return true
    }

    fun handleDirectAssistantIntent(intent: AssistantIntent) {
        when (intent) {
            is AssistantIntent.OpenWeb -> {
                openUrl(intent.url)
                val fullLog = appendAssistantInteraction("Opening ${intent.displayLabel}.\n${intent.url}")
                _chatResponse.postValue(fullLog)
            }
            is AssistantIntent.Research -> {
                executeResearchIntent(intent.topic)
            }
            is AssistantIntent.Learn -> {
                executeLearnIntent(intent.prompt, intent.topicHint)
            }
        }
    }

    private fun executeResearchIntent(topic: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                when (val result = researchRouter.research(topic)) {
                    is ResearchRouter.ResearchResult.Success -> {
                        val rendered = ensureBottomRawUrls(
                            sanitizeAssistantDisplayText(
                                ResearchRouter.formatForDisplay(result)
                            )
                        )
                        val fullLog = appendAssistantInteraction(rendered)
                        _chatResponse.postValue(fullLog)
                    }
                    is ResearchRouter.ResearchResult.ApiKeyMissing -> {
                        onApiKeyMissing(
                            if (prefs.researchProvider.trim().equals("openai_codex", ignoreCase = true)) {
                                "OpenAI Codex Research"
                            } else {
                                "Gemini Research"
                            }
                        )
                    }
                    is ResearchRouter.ResearchResult.Error -> {
                        val fullLog = appendAssistantInteraction("Research unavailable right now.")
                        _chatResponse.postValue(fullLog)
                        Log.e(TAG, "Research error: ${result.message}")
                    }
                }
            } finally {
                _isLoading.postValue(false)
            }
        }
    }


    private fun executeLearnIntent(prompt: String, topicHint: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                when (val result = learnLmRouter.teach(prompt)) {
                    is LearnLmRouter.LearnResult.Success -> {
                        val rendered = ensureBottomRawUrls(
                            sanitizeAssistantDisplayText(
                                LearnLmRouter.formatForDisplay(result)
                            )
                        )
                        val fullLog = appendAssistantInteraction(rendered)
                        _chatResponse.postValue(fullLog)
                    }
                    is LearnLmRouter.LearnResult.ApiKeyMissing -> {
                        onApiKeyMissing("LearnLM Tutor")
                    }
                    is LearnLmRouter.LearnResult.Error -> {
                        val fallback = if (topicHint.isNotBlank()) {
                            "Tutor mode is unavailable right now for $topicHint."
                        } else {
                            "Tutor mode is unavailable right now."
                        }
                        val fullLog = appendAssistantInteraction(fallback)
                        _chatResponse.postValue(fullLog)
                        Log.e(TAG, "LearnLM error: ${result.message}")
                    }
                }
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * TapInsight: HUD calendar refresh. Placeholder until google_calendar tool is fully wired.
     */
    fun refreshHudUpcomingCalendar(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.hudRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudCalendarRefreshMs) < intervalMs) {
            return
        }
        lastHudCalendarRefreshMs = now
        // Use the real GoogleCalendarClient for live calendar data.
        fetchCalendarEvents()
    }

    private fun appendLiveAssistantWorkingChunk(chunk: String): String {
        // Gemini Live's output-transcription deltas arrive as whole-word
        // fragments WITHOUT boundary spaces (e.g. "Hello", "world", "this").
        // So they must be joined WITH a space — concatenating them verbatim
        // glues words together ("Helloworld"). We keep the cumulative / stale /
        // contains guards (a delta that already holds the whole turn), but we
        // deliberately DROP the old longest-suffix/prefix overlap heuristic:
        // it false-matched on coincidental overlaps (e.g. "I am" + "amazing" ->
        // "I amazing") and was the source of the dropped / merged words.
        val next = chunk.trim()
        if (next.isBlank()) return currentAssistantVisibleLog()
        val prev = liveAssistantWorkingTurn
        liveAssistantWorkingTurn = when {
            prev.isBlank() -> next
            next.startsWith(prev) -> next      // cumulative resend of the full turn
            prev.startsWith(next) -> prev      // stale / duplicate delta
            next.contains(prev) -> next        // cumulative (with leading context)
            prev.contains(next) -> prev        // already included
            else -> "$prev $next"              // distinct fragment — join with one space
        }
        upsertLiveAssistantWorkingCard(liveAssistantWorkingTurn)
        return currentAssistantVisibleLog()
    }

    private fun finalizeAssistantLiveTurn(finalChunk: String): String {
        val finalText = finalChunk.trim()
        val mergedTurn =
            when {
                liveAssistantWorkingTurn.isBlank() -> finalText
                finalText.isBlank() -> liveAssistantWorkingTurn
                else -> mergeStreamText(liveAssistantWorkingTurn, finalText)
            }.trim()
        liveAssistantWorkingTurn = ""
        if (mergedTurn.isBlank()) {
            clearLiveAssistantWorkingCard(commitIfPopulated = true)
            return currentAssistantVisibleLog()
        }
        return appendAssistantInteraction(mergedTurn)
    }

    /**
     * Phase 2 Step 2c.3.1: append a finalized user utterance as a
     * chat card so the unipanel mini-card stack (and any other
     * surface that observes [messages]) can render it.
     *
     * Idempotent: if the most-recent message already matches the
     * supplied text from the same speaker, the call is a no-op.
     * That lets MainActivity hook this into every "we just settled
     * a user turn" branch — settledLiveInputRunnable plus the
     * status / learnlm / youtube fast-path intercepts — without
     * worrying about a single transcript double-appending.
     *
     * Session-only: deliberately NOT persisted to the chat-history
     * DB or the previous-conversation prefs cache. The pre-2c.3
     * behaviour was to suppress user transcripts entirely for
     * privacy reasons; this restores them only to the in-memory
     * `_messages` StateFlow so they survive a panel swap but not a
     * process restart.
     *
     * Preserves oldest-first ordering — the new card is appended
     * to the end, and the list is then trimmed to the same
     * [MAX_ASSISTANT_CHAT_CARDS] cap that assistant cards use.
     * The unipanel renderer maps tail entries to the top slots, so
     * "newest at top" is upheld.
     */
    fun appendUserUtterance(text: String) {
        val entry = text.trim()
        if (entry.isBlank()) return
        val snapshot = _messages.value
        val last = snapshot.lastOrNull()
        if (last != null && last.fromUser && last.text == entry) {
            // Already on the end — keep the StateFlow quiet.
            return
        }
        val updated = snapshot.toMutableList().apply {
            add(ChatMessage(text = entry, fromUser = true))
        }
        _messages.value = updated.takeLast(MAX_ASSISTANT_CHAT_CARDS)
        // Intentionally no persistAssistantEntry() — see kdoc.
    }

    private fun appendAssistantInteraction(interactionText: String): String {
        val entry = interactionText.trim()
        if (entry.isBlank()) return currentAssistantVisibleLog()

        val current = _messages.value.toMutableList()
        val workingIndex = findLiveAssistantWorkingIndex(current)
        if (workingIndex >= 0) {
            current[workingIndex] = current[workingIndex].copy(text = entry, fromUser = false)
        } else {
            current += ChatMessage(text = entry, fromUser = false)
        }

        _messages.value = current.takeLast(MAX_ASSISTANT_CHAT_CARDS)
        liveAssistantWorkingTurn = ""
        liveAssistantWorkingCardIndex = -1
        persistAssistantEntry(entry)
        return entry
    }

    private fun currentAssistantVisibleLog(): String {
        val live = liveAssistantWorkingTurn.trim()
        if (live.isNotBlank()) return live
        return _messages.value.lastOrNull { !it.fromUser }?.text.orEmpty()
    }

    private fun hydrateAssistantHistoryBlocking() {
        val restored = runCatching {
            runBlocking {
                chatHistoryMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val existing = chatMessageDao.getAllMessages()
                        if (existing.isEmpty()) {
                            migrateLegacyPrefsHistoryLocked()
                        }
                        chatMessageDao.getAllMessages()
                    }
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to hydrate assistant history", error)
            emptyList()
        }
        _messages.value = restored.map { it.toModel() }
        historyHydrated = true
    }

    private fun purgePersistedAssistantHistoryAsync() {
        viewModelScope.launch {
            runCatching {
                chatHistoryMutex.withLock {
                    withContext(Dispatchers.IO) {
                        chatMessageDao.deleteAllMessages()
                    }
                }
                prefs.setAssistantCardHistory(emptyList())
            }.onFailure { error ->
                Log.w(TAG, "Failed to purge persisted assistant history", error)
            }
        }
    }

    private suspend fun loadPersistedAssistantMessages(): List<ChatMessage> {
        val restored = chatHistoryMutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = chatMessageDao.getAllMessages()
                if (existing.isNotEmpty()) {
                    existing
                } else {
                    migrateLegacyPrefsHistoryLocked()
                    chatMessageDao.getAllMessages()
                }
            }
        }
        return restored.map { it.toModel() }
    }

    private suspend fun migrateLegacyPrefsHistoryLocked() {
        val legacy = prefs.getAssistantCardHistory()
            .sortedBy { it.timestampMs }
            .filter { it.text.isNotBlank() }
            .takeLast(MAX_ASSISTANT_CHAT_CARDS)
        if (legacy.isEmpty()) return

        legacy.forEach { card ->
            chatMessageDao.insertAndTrim(
                ChatMessageEntity(
                    text = card.text.trim(),
                    url = card.url ?: extractFirstUrl(card.text),
                    timestamp = card.timestampMs
                ),
                maxItems = MAX_ASSISTANT_CHAT_CARDS
            )
        }
    }

    private fun persistAssistantEntry(text: String) {
        if (SESSION_ONLY_CHAT_LOG) return
        val clean = text.trim()
        if (clean.isBlank()) return
        val timestamp = System.currentTimeMillis()
        val url = extractFirstUrl(clean)

        viewModelScope.launch {
            val restored = runCatching {
                chatHistoryMutex.withLock {
                    withContext(Dispatchers.IO) {
                        chatMessageDao.insertAndTrim(
                            ChatMessageEntity(
                                text = clean,
                                url = url,
                                timestamp = timestamp
                            ),
                            maxItems = MAX_ASSISTANT_CHAT_CARDS
                        )
                        chatMessageDao.getAllMessages()
                    }
                }
            }.getOrElse { error ->
                Log.e(TAG, "Failed to persist assistant entry", error)
                return@launch
            }

            val rebuilt = restored.map { it.toModel() }.toMutableList()
            val live = liveAssistantWorkingTurn.trim()
            if (live.isNotBlank()) {
                rebuilt += ChatMessage(text = live, fromUser = false)
                liveAssistantWorkingCardIndex = rebuilt.lastIndex
            }
            _messages.value = rebuilt
        }
    }

    private fun upsertLiveAssistantWorkingCard(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val current = _messages.value.toMutableList()
        val index = findLiveAssistantWorkingIndex(current)
        if (index >= 0) {
            current[index] = current[index].copy(text = clean, fromUser = false)
        } else {
            if (current.size >= MAX_ASSISTANT_CHAT_CARDS + 1) {
                current.removeAt(0)
            }
            current += ChatMessage(text = clean, fromUser = false)
            liveAssistantWorkingCardIndex = current.lastIndex
        }
        _messages.value = current
        liveAssistantWorkingCardIndex = current.indexOfLast { !it.fromUser && it.text == clean }
    }

    private fun clearLiveAssistantWorkingCard(commitIfPopulated: Boolean = false) {
        val current = _messages.value.toMutableList()
        val index = findLiveAssistantWorkingIndex(current)
        if (index >= 0) {
            val candidate = current[index].text.trim()
            if (commitIfPopulated && candidate.isNotBlank()) {
                _messages.value = current.takeLast(MAX_ASSISTANT_CHAT_CARDS)
                liveAssistantWorkingCardIndex = -1
                liveAssistantWorkingTurn = ""
                persistAssistantEntry(candidate)
                return
            }
            current.removeAt(index)
            _messages.value = current
        }
        liveAssistantWorkingCardIndex = -1
    }

    private fun findLiveAssistantWorkingIndex(messages: List<ChatMessage>): Int {
        val index = liveAssistantWorkingCardIndex
        return if (index in messages.indices && !messages[index].fromUser) index else -1
    }

    private fun ChatMessageEntity.toModel(): ChatMessage {
        return ChatMessage(
            text = text,
            fromUser = false,
            timestampMs = timestamp
        )
    }

    // ── Calendar ─────────────────────────────────────────────────────────
    private val _calendarEvents = MutableLiveData<List<GoogleCalendarClient.CalendarEvent>>()
    val calendarEvents: LiveData<List<GoogleCalendarClient.CalendarEvent>> = _calendarEvents

    fun fetchCalendarEvents() {
        viewModelScope.launch {
            // Fetch from all enabled calendars
            val enabledIds = prefs.enabledCalendarIds
            val calendarIds = if (enabledIds.isEmpty()) {
                listOf(prefs.calendarId.ifBlank { "primary" })
            } else {
                enabledIds.toList()
            }

            val allEvents = mutableListOf<GoogleCalendarClient.CalendarEvent>()
            var anyApiKeyMissing = false

            for (calId in calendarIds) {
                val itemCount = prefs.getCalendarItemCount(calId)
                when (val result = calendarClient.fetchUpcomingEvents(calendarId = calId, maxResults = itemCount)) {
                    is GoogleCalendarClient.CalendarResult.Success -> {
                        allEvents.addAll(result.events)
                    }
                    is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> {
                        anyApiKeyMissing = true
                    }
                    is GoogleCalendarClient.CalendarResult.Error -> {
                        Log.e(TAG, "Calendar error for $calId: ${result.message}")
                    }
                }
            }

            // Sort all events by start time
            allEvents.sortBy { it.start?.time ?: Long.MAX_VALUE }

            // Use the freshest data we have. If THIS fetch returned events, cache
            // them; if it came back empty (e.g. a transient OAuth/API failure)
            // fall back to the last good set so we can still re-filter it against
            // the current time instead of leaving a stale summary in place.
            val gotFreshData = allEvents.isNotEmpty()
            if (gotFreshData) {
                lastCalendarEventsCache = allEvents.toList()
            }
            val sourceEvents = if (gotFreshData) allEvents else lastCalendarEventsCache
            _calendarEvents.postValue(sourceEvents)

            // The HUD should surface the NEXT event that hasn't started yet, not
            // one that's currently in progress. Keep only events with a CONFIRMED
            // future start — recomputed against the CURRENT clock on every refresh.
            // A null start (un-parseable time, or an all-day event whose midnight
            // is already past) is NOT a confirmed upcoming event, so it's excluded
            // too — otherwise an in-progress event would masquerade as next.
            val nowMs = System.currentTimeMillis()
            val upcomingEvents = sourceEvents.filter { ev ->
                val startMs = ev.start?.time ?: return@filter false
                startMs > nowMs
            }

            val summary = if (upcomingEvents.isEmpty()) {
                if (sourceEvents.isEmpty() && anyApiKeyMissing) {
                    onApiKeyMissing("Google Calendar")
                    return@launch
                }
                "No upcoming events"
            } else {
                val showTime = prefs.hudShowEventTime
                val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                val dateFormat = SimpleDateFormat("MMM d", Locale.US)
                upcomingEvents.take(3).joinToString("\n") { event ->
                    if (showTime && event.start != null) {
                        val time = timeFormat.format(event.start)
                        val date = dateFormat.format(event.start)
                        "\u2022 $date $time — ${event.summary}"
                    } else {
                        "\u2022 ${event.summary}"
                    }
                }
            }
            _calendarSummary.value = summary
        }
    }

    /** Convenience for forcing a calendar refresh after config changes. */
    fun refreshCalendarNow() {
        refreshHudUpcomingCalendar(force = true)
    }

    // ── Tasks ─────────────────────────────────────────────────────────────

    /**
     * Refresh HUD tasks display. Throttled by hudRefreshIntervalSeconds.
     */
    fun refreshHudTasks(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.hudRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudTasksRefreshMs) < intervalMs) return
        lastHudTasksRefreshMs = now
        fetchHudTasks()
    }

    private fun fetchHudTasks() {
        val client = tasksClient ?: return
        viewModelScope.launch {
            val maxItems = prefs.tasksItemCount
            when (val result = client.fetchTasks(maxResults = maxItems)) {
                is GoogleTasksClient.TasksResult.Success -> {
                    postDueSoonTaskNotifications(result.tasks)
                    val summary = if (result.tasks.isEmpty()) {
                        "No pending tasks"
                    } else {
                        val dateFormat = SimpleDateFormat("MMM d", Locale.US)
                        result.tasks.take(maxItems).joinToString("\n") { task ->
                            val dueStr = task.due?.let { " (${dateFormat.format(it)})" } ?: ""
                            "\u2022 ${task.title}$dueStr"
                        }
                    }
                    _tasksSummary.value = if (summary.contains("\n")) "TASKS\n$summary" else "TASKS: $summary"
                }
                is GoogleTasksClient.TasksResult.AuthRequired -> {
                    _tasksSummary.value = ""
                    Log.d(TAG, "Tasks: OAuth required")
                }
                is GoogleTasksClient.TasksResult.Error -> {
                    Log.e(TAG, "Tasks error: ${result.message}")
                }
            }
        }
    }

    /**
     * Ring the HUD bell for tasks coming due. Fires for incomplete tasks
     * whose due time falls inside [start-of-today .. now + 1h] — i.e. due
     * within the hour, or already due today (Google Tasks date-only tasks
     * land at midnight, so "due today" is the honest phrasing). The id
     * `task_<id>_<dueMs>` makes the 5-minute poll re-posts no-ops until
     * the task's due date changes.
     */
    private fun postDueSoonTaskNotifications(tasks: List<GoogleTasksClient.TaskItem>) {
        val nowMs = System.currentTimeMillis()
        val oneHourMs = 3_600_000L
        val startOfTodayMs = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        tasks.forEach { task ->
            if (task.status == "completed") return@forEach
            val due = task.due ?: return@forEach
            val dueMs = due.time
            if (dueMs < startOfTodayMs) return@forEach
            if (dueMs > nowMs + oneHourMs) return@forEach
            val message = if (dueMs > nowMs) {
                "${task.title} due at ${timeFormat.format(due)}"
            } else {
                "${task.title} due today"
            }
            NotificationCenter.post(
                NotificationCenter.HudNotification(
                    id = "task_${task.id}_$dueMs",
                    source = NotificationCenter.Source.TASK,
                    title = "Task due soon",
                    message = message
                )
            )
        }
    }

    // ── News ──────────────────────────────────────────────────────────────

    /**
     * Refresh HUD news headlines. Throttled by newsRefreshIntervalSeconds.
     */
    fun refreshHudNews(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.newsRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudNewsRefreshMs) < intervalMs) return
        lastHudNewsRefreshMs = now
        fetchHudNews()
    }

    fun refreshHudAirQuality(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val intervalMs = prefs.hudRefreshIntervalSeconds * 1000L
        if (!force && (now - lastHudAirQualityRefreshMs) < intervalMs) return
        lastHudAirQualityRefreshMs = now
        fetchHudAirQuality()
    }

    private fun fetchHudNews() {
        viewModelScope.launch {
            val maxItems = prefs.newsItemCount
            when (val result = newsClient.fetchHeadlines(maxResults = maxItems)) {
                is GoogleNewsClient.NewsResult.Success -> {
                    val summary = if (result.headlines.isEmpty()) {
                        "No headlines"
                    } else {
                        result.headlines.take(maxItems).joinToString("\n") { "\u2022 ${it.title}" }
                    }
                    _newsSummary.value = if (summary.contains("\n")) "NEWS\n$summary" else "NEWS: $summary"
                }
                is GoogleNewsClient.NewsResult.Error -> {
                    Log.e(TAG, "News error: ${result.message}")
                }
            }
        }
    }

    private fun fetchHudAirQuality() {
        val location = latestDeviceLocationContext ?: run {
            _airQualitySummary.value = null
            return
        }
        if (prefs.googleMapsApiKey.isBlank()) {
            _airQualitySummary.value = null
            return
        }

        viewModelScope.launch {
            when (
                val result = airQualityClient.fetchCurrentConditions(
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            ) {
                is GoogleAirQualityClient.AirQualityResult.Success -> {
                    _airQualitySummary.value = AirQualityHudState(
                        text = result.index.label,
                        aqi = result.index.aqi
                    )
                }
                is GoogleAirQualityClient.AirQualityResult.ApiKeyMissing -> {
                    _airQualitySummary.value = null
                }
                is GoogleAirQualityClient.AirQualityResult.Error -> {
                    Log.w(TAG, "Air quality error: ${result.message}")
                    _airQualitySummary.value = null
                }
            }
        }
    }

    // ── Voice assistant trigger ──────────────────────────────────────────
    private val _voiceAssistantActive = MutableLiveData(false)
    val voiceAssistantActive: LiveData<Boolean> = _voiceAssistantActive

    fun activateVoiceAssistant() {
        _voiceAssistantActive.value = true
    }

    fun deactivateVoiceAssistant() {
        _voiceAssistantActive.value = false
    }

    // ── HUD hold-progress (Siri-style ring) ──────────────────────────────
    private val _holdProgress = MutableLiveData(0f)
    val holdProgress: LiveData<Float> = _holdProgress

    fun updateHoldProgress(progress: Float) {
        _holdProgress.postValue(progress)
    }

    fun resetHoldProgress() {
        _holdProgress.postValue(0f)
    }

    // ── Multimodal readiness ─────────────────────────────────────────────
    private val _isCameraEnabled = MutableStateFlow(false)
    private val _isTextureViewReady = MutableStateFlow(false)

    fun setMultimodalCameraEnabled(enabled: Boolean) {
        _isCameraEnabled.value = enabled
    }

    fun setMultimodalTextureReady(ready: Boolean) {
        _isTextureViewReady.value = ready
    }

    fun canSendMultimodalFrame(): Boolean {
        return _isCameraEnabled.value && _isTextureViewReady.value
    }
}
