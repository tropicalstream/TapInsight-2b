package com.TapLinkX3.app

import android.graphics.Bitmap
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Browser agent using Gemini Live WITHOUT formal tool declarations.
 *
 * The probe test proved the bare connection works but adding tools causes
 * silent server rejection. So instead, the system prompt instructs Gemini
 * to emit structured ACTION lines that we parse from the output transcription.
 *
 * Format:  ACTION:tool_name param1=value1 param2=value2
 * Example: ACTION:navigate_url url=https://ebay.com
 *          ACTION:click_element x=150 y=300
 *          ACTION:type_text text=laptop submit=true
 */
class BrowserAgentSession(
    private val apiKey: String,
    private val liveModel: String = "gemini-3.1-flash-live-preview",
    private val listener: AgentListener
) {

    interface AgentListener {
        fun onAgentReady()
        fun onAgentAction(action: AgentAction): String
        fun onAgentSpeech(text: String)
        fun onAgentAudio(mimeType: String, data: ByteArray)
        fun onAgentFinished(reason: String)
        fun onAgentError(message: String)
        fun onAgentStatus(status: String) {}
        fun onAgentDiagnostic(log: String) {}
    }

    data class AgentAction(val tool: String, val args: JSONObject)

    companion object {
        private const val TAG = "BrowserAgent"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val IDLE_TIMEOUT_MS = 30_000L
        private const val SESSION_TIMEOUT_MS = 120_000L

        private val SYSTEM_PROMPT = """
You are a browser agent running inside AR smart glasses. The user speaks commands and you control the browser.

You can see the page via screenshots. After each action, you will receive a fresh screenshot.

TO CONTROL THE BROWSER, say ACTION commands in your speech. When you want to perform an action, speak it naturally but include the ACTION line clearly. The system will detect and execute these patterns in your speech:

ACTION COMMANDS (say these exactly):
• "ACTION navigate [url]" — open a URL. Example: "ACTION navigate https://www.ebay.com"
• "ACTION click [x] [y]" — click at screenshot coordinates. Example: "ACTION click 150 300"
• "ACTION click_selector [css]" — click by CSS selector. Example: "ACTION click_selector #search-btn"
• "ACTION type [text]" — type into the focused field. Example: "ACTION type laptop stand"
• "ACTION type_submit [text]" — type and press Enter. Example: "ACTION type_submit laptop stand"
• "ACTION scroll_down" — scroll down half page
• "ACTION scroll_up" — scroll up half page
• "ACTION go_back" — browser back button
• "ACTION done [summary]" — signal task complete. Example: "ACTION done Found the lowest price"

RULES:
• After each ACTION, STOP talking and wait for the updated screenshot.
• Speak naturally to the user between actions: "Let me open eBay for you. ACTION navigate https://www.ebay.com"
• Only ONE action per response turn.
• If the user says "stop" or "cancel", say "ACTION done Cancelled by user"
• Be precise with click coordinates — reference the screenshot.
• For search: navigate to the site, click the search box, type the query with submit.
""".trimIndent()
    }

    // Match GeminiRouter's OkHttp config exactly — NO client-initiated pings.
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile var isActive = false
        private set
    private var setupSent = false
    @Volatile private var sessionReady = false
    private val sessionStartMs = System.currentTimeMillis()
    private var lastActivityMs = System.currentTimeMillis()

    private val diagLog = StringBuilder()
    private val t0 = System.currentTimeMillis()

    private fun diag(msg: String) {
        val elapsed = System.currentTimeMillis() - t0
        val line = "+${elapsed}ms  $msg"
        diagLog.appendLine(line)
        Log.d(TAG, line)
    }

    private fun flushDiag() { listener.onAgentDiagnostic(diagLog.toString()) }

    // Accumulate transcription fragments to detect ACTION commands
    private val pendingTranscript = StringBuilder()

    // ── Connect ──────────────────────────────────────────────────────

    fun start() {
        if (isActive) return
        isActive = true
        lastActivityMs = System.currentTimeMillis()
        diag("start() model=$liveModel apiKeyLen=${apiKey.length}")

        val url = "$WS_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                diag("WS OPEN (HTTP ${response.code})")
                listener.onAgentStatus("Connected, sending setup...")
                sendSetup(ws)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isActive && !sessionReady) {
                        diag("SETUP TIMEOUT")
                        flushDiag()
                        listener.onAgentError("Setup timeout — check log")
                        stop()
                    }
                }, 10_000)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                diag("MSG (${text.length}ch): ${text.take(400)}")
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                diag("WS FAILURE: ${t.message} httpCode=${response?.code}")
                flushDiag()
                isActive = false
                listener.onAgentError("Connection failed: ${t.message}")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                diag("WS CLOSING: code=$code reason='$reason'")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                diag("WS CLOSED: code=$code reason='$reason'")
                isActive = false
                flushDiag()
                listener.onAgentFinished("Closed code=$code: $reason")
            }
        })
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        diag("stop()")
        webSocket?.close(1000, "User cancelled")
        webSocket = null
    }

    // ── Send data ────────────────────────────────────────────────────

    fun sendScreenshot(bitmap: Bitmap) {
        if (!sessionReady) return
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        val b64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
        val frame = JSONObject().put("realtimeInput", JSONObject()
            .put("video", JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
        webSocket?.send(frame.toString())
        lastActivityMs = System.currentTimeMillis()
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!sessionReady) return
        val b64 = java.util.Base64.getEncoder().encodeToString(pcmData)
        val frame = JSONObject().put("realtimeInput", JSONObject()
            .put("audio", JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)))
        webSocket?.send(frame.toString())
        lastActivityMs = System.currentTimeMillis()
    }

    fun sendClientText(text: String) {
        if (!sessionReady) return
        val frame = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true))
        val sent = webSocket?.send(frame.toString()) ?: false
        diag("sendClientText sent=$sent text='${text.take(80)}'")
        lastActivityMs = System.currentTimeMillis()
    }

    /** Send result of an ACTION back as user context so Gemini knows what happened. */
    fun sendActionResult(result: String) {
        sendClientText("[ACTION RESULT] $result")
    }

    fun checkTimeouts(): Boolean {
        val now = System.currentTimeMillis()
        if (now - sessionStartMs > SESSION_TIMEOUT_MS) {
            stop(); listener.onAgentFinished("Session timeout"); return true
        }
        if (now - lastActivityMs > IDLE_TIMEOUT_MS) {
            stop(); listener.onAgentFinished("Idle timeout"); return true
        }
        return false
    }

    // ── Setup ────────────────────────────────────────────────────────

    private fun sendSetup(ws: WebSocket) {
        if (setupSent) return
        setupSent = true

        val modelId = if (liveModel.startsWith("models/")) liveModel else "models/$liveModel"

        // NO tools — proven to cause silent rejection on this endpoint.
        // Agent actions are parsed from Gemini's speech output instead.
        val setupContent = JSONObject()
            .put("model", modelId)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", "Puck")))))
            .put("systemInstruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("realtimeInputConfig", JSONObject()
                .put("automaticActivityDetection", JSONObject()
                    .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                    .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                    .put("silenceDurationMs", 500)))

        val setup = JSONObject().put("setup", setupContent)
        val payload = setup.toString()
        val sent = ws.send(payload)
        diag("Setup sent=$sent len=${payload.length}")
        diag("Setup: ${payload.take(1200)}")
    }

    // ── Message handling ─────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            val error = json.optJSONObject("error")
            if (error != null) {
                val msg = error.optString("message", "Unknown server error")
                diag("SERVER ERROR: $msg")
                flushDiag()
                listener.onAgentError("Gemini: $msg")
                return
            }

            if (json.has("setupComplete") || json.has("setup_complete")) {
                sessionReady = true
                diag("setupComplete received!")
                listener.onAgentReady()
                return
            }

            val serverContent = json.optJSONObject("serverContent")
                ?: json.optJSONObject("server_content")
            if (serverContent != null) {
                if (!sessionReady) { sessionReady = true; listener.onAgentReady() }
                handleServerContent(serverContent)
                return
            }

            diag("UNHANDLED: ${json.keys().asSequence().toList()}")
        } catch (e: Exception) {
            diag("PARSE ERR: ${e.message}")
        }
    }

    private fun handleServerContent(content: JSONObject) {
        // User's speech transcription
        val inputTx = (content.optJSONObject("inputTranscription")
            ?: content.optJSONObject("input_transcription"))
            ?.optString("text", "")?.trim().orEmpty()
        if (inputTx.isNotBlank()) diag("User: $inputTx")

        // Model's audio response
        val modelTurn = content.optJSONObject("modelTurn")
            ?: content.optJSONObject("model_turn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val partText = part.optString("text", "")
                if (partText.isNotBlank()) {
                    listener.onAgentSpeech(partText)
                    checkForActions(partText)
                }
                val inlineData = part.optJSONObject("inlineData")
                    ?: part.optJSONObject("inline_data")
                if (inlineData != null) {
                    val mime = inlineData.optString("mimeType",
                        inlineData.optString("mime_type", ""))
                    val dataB64 = inlineData.optString("data", "")
                    if (mime.isNotBlank() && dataB64.isNotBlank()) {
                        listener.onAgentAudio(mime, java.util.Base64.getDecoder().decode(dataB64))
                    }
                }
            }
        }

        // Gemini's speech as text — primary source of ACTION commands
        val outputTx = (content.optJSONObject("outputTranscription")
            ?: content.optJSONObject("output_transcription"))
            ?.optString("text", "")?.trim().orEmpty()
        if (outputTx.isNotBlank()) {
            diag("Agent: $outputTx")
            listener.onAgentSpeech(outputTx)
            pendingTranscript.append(" ").append(outputTx)
        }

        // On turn complete, parse accumulated transcript for ACTION commands
        if (content.optBoolean("turnComplete", content.optBoolean("turn_complete", false))) {
            diag("Turn complete")
            lastActivityMs = System.currentTimeMillis()
            val full = pendingTranscript.toString().trim()
            pendingTranscript.clear()
            if (full.isNotBlank()) checkForActions(full)
        }
    }

    // ── Action parsing ───────────────────────────────────────────────

    /**
     * Scan text for ACTION commands. Format: ACTION <command> [args...]
     * Called from output transcription on turn complete.
     */
    private fun checkForActions(text: String) {
        // Regex to find ACTION commands in speech
        val actionPattern = Regex("""ACTION\s+(\w+)\s*(.*)""", RegexOption.IGNORE_CASE)
        val match = actionPattern.find(text) ?: return
        val command = match.groupValues[1].lowercase()
        val argStr = match.groupValues[2].trim()

        diag("Parsed ACTION: command=$command args='$argStr'")

        val args = JSONObject()
        when (command) {
            "navigate" -> {
                args.put("url", argStr)
                dispatchAction("navigate_url", args)
            }
            "click" -> {
                // "click 150 300" or "click x=150 y=300"
                val coords = argStr.replace(Regex("[xy]="), "").trim().split(Regex("\\s+"))
                if (coords.size >= 2) {
                    args.put("x", coords[0]); args.put("y", coords[1])
                }
                dispatchAction("click_element", args)
            }
            "click_selector", "clickselector" -> {
                args.put("selector", argStr)
                dispatchAction("click_element", args)
            }
            "type" -> {
                args.put("text", argStr)
                dispatchAction("type_text", args)
            }
            "type_submit", "typesubmit" -> {
                args.put("text", argStr); args.put("submit", "true")
                dispatchAction("type_text", args)
            }
            "scroll_down", "scrolldown" -> {
                args.put("direction", "down")
                dispatchAction("scroll_page", args)
            }
            "scroll_up", "scrollup" -> {
                args.put("direction", "up")
                dispatchAction("scroll_page", args)
            }
            "go_back", "goback", "back" -> {
                dispatchAction("go_back", args)
            }
            "done", "complete" -> {
                listener.onAgentSpeech(argStr.ifBlank { "Task complete" })
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stop(); flushDiag(); listener.onAgentFinished(argStr.ifBlank { "Task complete" })
                }, 1500)
            }
            else -> {
                diag("Unknown ACTION command: $command")
            }
        }
    }

    private fun dispatchAction(tool: String, args: JSONObject) {
        val action = AgentAction(tool = tool, args = args)
        try {
            val result = listener.onAgentAction(action)
            diag("Action result: $result")
            // After action, send result back so Gemini knows what happened
            // and will receive a fresh screenshot
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isActive && sessionReady) {
                    sendActionResult(result)
                }
            }, 600) // after the 500ms screenshot delay
        } catch (e: Exception) {
            diag("Action error: ${e.message}")
        }
    }
}
