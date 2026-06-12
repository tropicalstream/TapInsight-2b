package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GoogleNewsClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import com.rayneo.visionclaw.core.network.LearnLmRouter
import com.rayneo.visionclaw.core.network.OpenClawClient
import com.rayneo.visionclaw.core.network.OpenMeteoWeatherClient
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.storage.AppPreferences
import org.json.JSONObject

/**
 * Central dispatcher that routes Gemini tool calls to the appropriate
 * native tool handler. Each tool executes locally on the device —
 * no external bridge needed.
 */
class ToolDispatcher(
    private val context: Context,
    calendarClient: GoogleCalendarClient? = null,
    directionsClient: GoogleDirectionsClient? = null,
    tasksClient: GoogleTasksClient? = null,
    placesClient: GooglePlacesClient? = null,
    airQualityClient: GoogleAirQualityClient? = null,
    weatherClient: OpenMeteoWeatherClient? = null,
    researchRouter: ResearchRouter? = null,
    learnLmRouter: LearnLmRouter? = null,
    recentCardsProvider: (() -> List<String>)? = null,
    locationProvider: (() -> DeviceLocationContext?)? = null,
    openClawClient: OpenClawClient? = null,
    hermesClient: com.rayneo.visionclaw.core.network.HermesClient? = null,
    cameraFrameProvider: (() -> String?)? = null,
    /**
     * Returns a base64-encoded JPEG of the on-glasses TapBrowser
     * WebView's current viewport, or null if the browser isn't up.
     * Supplied by [com.TapLink.app.media.BrowserFrameHolder] in
     * production. Used by [BrowserVisionTool] for voice-triggered
     * "what does this say" / "summarize this page" prompts.
     */
    browserFrameProvider: (() -> String?)? = null,
    /**
     * Returns full DOM/body text for the active TapBrowser page.
     * Used for "read/summarize the web page" requests where OCR of
     * the visible viewport would miss off-screen content.
     */
    browserPageTextProvider: ((Int) -> com.TapLink.app.media.BrowserFrameHolder.PageTextResult?)? = null,
    batteryLevelProvider: (() -> Int)? = null,
    isChargingProvider: (() -> Boolean)? = null,
    toggleBatterySaver: ((Boolean) -> Unit)? = null
) {

    companion object {
        private const val TAG = "ToolDispatcher"
    }

    private val tools = mutableMapOf<String, AiTapTool>()

    init {
        val effectiveLocationProvider = locationProvider ?: { null }
        val effectiveRecentCardsProvider = recentCardsProvider ?: { emptyList() }
        val prefs = AppPreferences(context)
        val effectiveCalendarClient = calendarClient ?: GoogleCalendarClient({ null }, context = context)
        val effectiveDirectionsClient = directionsClient ?: GoogleDirectionsClient({ null }, context = context)
        val effectiveTasksClient = tasksClient ?: GoogleTasksClient({ null }, context = context)
        val effectivePlacesClient = placesClient ?: GooglePlacesClient({ null }, context = context)
        val effectiveAirQualityClient = airQualityClient ?: GoogleAirQualityClient({ null }, context = context)
        val effectiveWeatherClient = weatherClient ?: OpenMeteoWeatherClient(context = context)
        val effectiveResearchRouter = researchRouter ?: ResearchRouter(
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
                prefs.geminiApiKey.trim().takeIf { it.isNotBlank() }
                    ?: BuildConfig.GEMINI_API_KEY.trim().takeIf { it.isNotBlank() }
            },
            context = context,
            timeoutSecondsProvider = { prefs.timeoutResearchSeconds },
            customResearchPromptProvider = {
                prefs.researchPrompt.trim().takeIf { it.isNotBlank() }
            }
        )
        val effectiveLearnLmRouter = learnLmRouter ?: LearnLmRouter(
            apiKeyProvider = {
                prefs.geminiApiKey.trim().takeIf { it.isNotBlank() }
                    ?: BuildConfig.GEMINI_API_KEY.trim().takeIf { it.isNotBlank() }
            },
            modelProvider = {
                prefs.learnLmModel.trim().takeIf { it.isNotBlank() }
            },
            recentCardsProvider = effectiveRecentCardsProvider,
            context = context
        )
        // Register all built-in tools
        register(GoogleCalendarTool(context, effectiveCalendarClient))
        register(GoogleKeepTool(context))
        register(GoogleContactsTool(context))
        register(GoogleRoutesTool(context, effectiveDirectionsClient, effectiveLocationProvider))
        register(SpotifyTool(context))
        register(SonosTool(context))
        register(IdentifySongTool(context))
        register(CommunicationTool(context))
        register(CameraTool(context, frameProvider = cameraFrameProvider ?: { null }))
        register(BrowserVisionTool(
            context,
            frameProvider = browserFrameProvider ?: { null }
        ))
        register(BrowserPageTextTool(
            context,
            pageTextProvider = browserPageTextProvider ?: { null }
        ))
        // "Save this image" — files the current browser frame (or the live
        // camera frame) into the Media Library Photos folder. Reuses the
        // same frame providers the vision tools above already consume.
        register(SaveScreenImageTool(
            context,
            browserFrameProvider = browserFrameProvider ?: { null },
            cameraFrameProvider = cameraFrameProvider ?: { null }
        ))
        register(TapLinkTool(context))
        register(SendVideoListTool(context))
        register(SendLinkListTool(
            context,
            geminiApiKeyProvider = {
                prefs.geminiApiKey.trim().takeIf { it.isNotBlank() }
                    ?: BuildConfig.GEMINI_API_KEY.trim().takeIf { it.isNotBlank() }
            }
        ))
        register(ResearchTool(context, effectiveResearchRouter))
        register(LearnTool(effectiveLearnLmRouter))
        register(ContextCacheTool(context))
        // Google Tasks
        register(GoogleTasksTool(context, effectiveTasksClient))
        // Google News
        register(GoogleNewsTool(context, GoogleNewsClient()))
        // Google Places (Nearby Search)
        register(
            GooglePlacesTool(
                context,
                effectivePlacesClient,
                effectiveLocationProvider,
                effectiveDirectionsClient,
                effectiveWeatherClient
            )
        )
        register(GoogleAirQualityTool(context, effectiveAirQualityClient, effectiveLocationProvider))
        // Ask Maps — unified map intelligence (explore, 3D nav, landmarks)
        register(AskMapsTool(context, effectivePlacesClient, effectiveDirectionsClient, effectiveLocationProvider, apiKeyProvider = { prefs.googleMapsApiKey }))
        register(
            DailyBriefingTool(
                context = context,
                calendarClient = effectiveCalendarClient,
                directionsClient = effectiveDirectionsClient,
                placesClient = effectivePlacesClient,
                airQualityClient = effectiveAirQualityClient,
                weatherClient = effectiveWeatherClient,
                researchRouter = effectiveResearchRouter,
                locationProvider = effectiveLocationProvider
            )
        )
        // TapClaw personal AI agent — enable if toggled on OR if device pairing token exists
        val hasPairingToken = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
            .getString("openclaw_pair_device_token", null)?.isNotBlank() == true
        if (openClawClient != null && (prefs.openClawEnabled || hasPairingToken)) {
            register(OpenClawTool(
                context,
                openClawClient,
                effectiveLocationProvider,
                frameProvider = cameraFrameProvider ?: { null }
            ))
            Log.d(TAG, "TapClaw integration enabled (toggle=${prefs.openClawEnabled}, pairingToken=$hasPairingToken)")
        }

        // Hermes Agent — register the `hermes_agent` tool when enabled
        // and a non-blank endpoint + API key are configured. Separate
        // from the OpenClaw block above so both can be active at once
        // (one keyword routes to each).
        val hermesConfigured = prefs.hermesEndpoint.isNotBlank() && prefs.hermesApiKey.isNotBlank()
        if (hermesClient != null && prefs.hermesEnabled && hermesConfigured) {
            register(com.rayneo.visionclaw.core.tools.HermesTool(
                context,
                hermesClient,
                effectiveLocationProvider,
                frameProvider = cameraFrameProvider ?: { null }
            ))
            Log.d(TAG, "Hermes integration enabled (endpoint=${prefs.hermesEndpoint})")
        } else {
            Log.d(
                TAG,
                "Hermes integration NOT enabled " +
                    "(toggle=${prefs.hermesEnabled}, configured=$hermesConfigured)"
            )
        }

        // TapRadio — internet radio and podcast playback/search
        register(TapRadioTool(context))

        // Translation tool (always available — uses Gemini's multilingual capabilities)
        register(TranslateTool())

        // Battery Saver tool
        if (batteryLevelProvider != null && isChargingProvider != null && toggleBatterySaver != null) {
            register(BatterySaverTool(
                batteryLevelProvider = batteryLevelProvider,
                isChargingProvider = isChargingProvider,
                batterySaverActiveProvider = { prefs.batterySaverActive },
                toggleBatterySaver = { enabled ->
                    prefs.batterySaverActive = enabled
                    toggleBatterySaver(enabled)
                }
            ))
        }

        // Quick Actions / Voice Macros
        register(QuickActionTool(context, this))

        // Status briefing — TapClaw gateway state + now/next calendar events.
        // Triggered by Gemini when the user says "status".
        register(StatusTool(context, effectiveCalendarClient))
    }

    private fun register(tool: AiTapTool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name}")
    }

    /** Returns true if the tool name is recognized. */
    fun isSupported(name: String): Boolean = tools.containsKey(name.trim())

    /** Get all registered tool names. */
    fun registeredTools(): Set<String> = tools.keys.toSet()

    /**
     * Dispatch a tool call to the appropriate handler.
     * Parses the JSON args string into a Map and delegates to the tool.
     */
    suspend fun dispatch(name: String, argsJson: String): Result<String> {
        val toolName = name.trim()
        val tool = tools[toolName]
            ?: return Result.failure(IllegalArgumentException("Unknown tool: $toolName"))

        val args = parseArgs(argsJson)
        Log.d(TAG, "Dispatching $toolName with ${args.size} args: ${args.keys}")

        return try {
            val result = tool.execute(args)
            result.onSuccess {
                Log.d(TAG, "Tool $toolName succeeded: ${it.take(200)}")
            }.onFailure {
                Log.w(TAG, "Tool $toolName failed: ${it.message}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing tool $toolName", e)
            Result.failure(e)
        }
    }

    private fun parseArgs(argsJson: String): Map<String, String> {
        val trimmed = argsJson.trim()
        if (trimmed.isBlank() || trimmed == "{}") return emptyMap()
        return try {
            val json = JSONObject(trimmed)
            val map = mutableMapOf<String, String>()
            for (key in json.keys()) {
                val value = json.opt(key)
                if (value != null && value != JSONObject.NULL) {
                    map[key] = value.toString()
                }
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool args JSON: ${e.message}")
            mapOf("query" to trimmed)
        }
    }
}
