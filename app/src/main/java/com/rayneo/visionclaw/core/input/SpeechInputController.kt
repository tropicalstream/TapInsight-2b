package com.rayneo.visionclaw.core.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechInputController(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onSpeechResult(text: String)
        fun onSpeechPartial(text: String) = Unit
        fun onSpeechStatus(status: String) = Unit
        fun onSpeechError(errorCode: Int)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var didEmitResult = false
    private var sawSpeech = false
    private var reconnectAttempted = false
    private var pendingStop = false
    private var latestPartialText = ""
    private var callbackSeenThisSession = false
    private var startupRetryDone = false

    private val forceFinalizeRunnable = Runnable {
        if (isListening && !didEmitResult) {
            requestStopWithGrace("force_finalize")
        }
    }

    private val gracefulStopRunnable = Runnable {
        if (!isListening || didEmitResult) return@Runnable
        pendingStop = true
        Log.i(TAG, "Grace elapsed → stopListening() (sawSpeech=$sawSpeech)")
        runCatching { recognizer?.stopListening() }
        mainHandler.postDelayed(postStopTimeoutRunnable, POST_STOP_CALLBACK_TIMEOUT_MS)
    }

    private val postStopTimeoutRunnable = Runnable {
        if (isListening && pendingStop && !didEmitResult) {
            if (latestPartialText.isNotBlank()) {
                Log.w(TAG, "No final callback after stop; using latest partial transcript")
                emitSpeechResultOnce(latestPartialText)
                return@Runnable
            }
            Log.w(TAG, "No callback after stopListening() — cancelling recognizer")
            isListening = false
            pendingStop = false
            runCatching { recognizer?.cancel() }
            listener.onSpeechError(SpeechRecognizer.ERROR_NO_MATCH)
        }
    }

    private val startupCallbackTimeoutRunnable = Runnable {
        if (!isListening || didEmitResult || callbackSeenThisSession) return@Runnable

        Log.w(TAG, "No speech callbacks after startListening()")
        runCatching { recognizer?.cancel() }
        isListening = false
        pendingStop = false

        if (!startupRetryDone) {
            startupRetryDone = true
            listener.onSpeechStatus("Microphone warmup… retrying")
            recreateRecognizer()
            mainHandler.postDelayed({ startListeningInternal() }, STARTUP_RETRY_DELAY_MS)
        } else {
            listener.onSpeechError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private val listeningTimeoutRunnable = Runnable {
        if (isListening) {
            if (latestPartialText.isNotBlank()) {
                Log.w(TAG, "Listen timeout with partial transcript; emitting latest partial")
                emitSpeechResultOnce(latestPartialText)
                return@Runnable
            }
            isListening = false
            runCatching { recognizer?.cancel() }
            listener.onSpeechError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            Log.w(TAG, "Speech listen timeout fired (sawSpeech=$sawSpeech)")
        }
    }

    init {
        ensureRecognizer()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return

        val rayneoStt = ComponentName(
            RAYNEO_RECOGNIZER_PACKAGE,
            RAYNEO_RECOGNIZER_CLASS
        )
        recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext).also {
                Log.i(TAG, "Using default device STT service")
            }
        }.getOrElse {
            Log.w(TAG, "Default STT bind failed; trying explicit RayNeo service", it)
            runCatching {
                SpeechRecognizer.createSpeechRecognizer(appContext, rayneoStt).also {
                    Log.i(TAG, "Using explicit RayNeo STT service: ${rayneoStt.flattenToShortString()}")
                }
            }.getOrNull()
        }

        recognizer?.also { r ->
            r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                callbackSeenThisSession = true
                pendingStop = false
                Log.d(TAG, "onReadyForSpeech")
                listener.onSpeechStatus("Ready. Speak now…")
            }

            override fun onBeginningOfSpeech() {
                callbackSeenThisSession = true
                sawSpeech = true
                Log.d(TAG, "onBeginningOfSpeech")
                listener.onSpeechStatus("Speech detected…")
            }

            override fun onRmsChanged(rmsdB: Float) {
                callbackSeenThisSession = true
                sawSpeech = true
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                callbackSeenThisSession = true
                val partial = extractFirstText(partialResults)
                if (partial.isNotBlank()) {
                    sawSpeech = true
                    latestPartialText = partial
                    Log.d(TAG, "onPartialResults: $partial")
                    listener.onSpeechPartial(partial)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                callbackSeenThisSession = true
                val text = extractFirstText(results)
                Log.d(TAG, "onResults: $text")
                if (!emitSpeechResultOnce(text)) {
                    listener.onSpeechError(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }

            override fun onError(error: Int) {
                callbackSeenThisSession = true
                isListening = false
                didEmitResult = false
                pendingStop = false
                mainHandler.removeCallbacks(listeningTimeoutRunnable)
                mainHandler.removeCallbacks(forceFinalizeRunnable)
                mainHandler.removeCallbacks(gracefulStopRunnable)
                mainHandler.removeCallbacks(postStopTimeoutRunnable)
                mainHandler.removeCallbacks(startupCallbackTimeoutRunnable)
                Log.e(TAG, "onError: $error")

                if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !reconnectAttempted) {
                    reconnectAttempted = true
                    Log.w(TAG, "Speech service disconnected - recreating recognizer and retrying")
                    recreateRecognizer()
                    mainHandler.postDelayed({ startListeningInternal() }, RECONNECT_RETRY_DELAY_MS)
                    return
                }

                listener.onSpeechError(error)
            }
            })
        }
    }

    private fun recreateRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        ensureRecognizer()
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onSpeechError(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        reconnectAttempted = false
        startupRetryDone = false
        startListeningInternal()
    }

    private fun startListeningInternal() {
        ensureRecognizer()

        if (recognizer == null) {
            listener.onSpeechError(SpeechRecognizer.ERROR_CLIENT)
            Log.e(TAG, "Speech recognizer not available, cannot start listening.")
            return
        }

        if (isListening) {
            runCatching { recognizer?.cancel() }
            isListening = false
        }

        // Fresh recognizer per session avoids stale binder states on X3 Pro.
        recreateRecognizer()
        didEmitResult = false
        sawSpeech = false
        pendingStop = false
        latestPartialText = ""
        callbackSeenThisSession = false

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        runCatching {
            listener.onSpeechStatus("Starting microphone…")
            recognizer!!.startListening(intent)
            isListening = true
            mainHandler.removeCallbacks(listeningTimeoutRunnable)
            mainHandler.removeCallbacks(forceFinalizeRunnable)
            mainHandler.removeCallbacks(gracefulStopRunnable)
            mainHandler.removeCallbacks(postStopTimeoutRunnable)
            mainHandler.removeCallbacks(startupCallbackTimeoutRunnable)
            mainHandler.postDelayed(forceFinalizeRunnable, FORCE_FINALIZE_MS)
            mainHandler.postDelayed(listeningTimeoutRunnable, LISTEN_TIMEOUT_MS)
            mainHandler.postDelayed(startupCallbackTimeoutRunnable, START_CALLBACK_TIMEOUT_MS)
        }.onFailure {
            isListening = false
            pendingStop = false
            mainHandler.removeCallbacks(listeningTimeoutRunnable)
            mainHandler.removeCallbacks(forceFinalizeRunnable)
            mainHandler.removeCallbacks(gracefulStopRunnable)
            mainHandler.removeCallbacks(postStopTimeoutRunnable)
            mainHandler.removeCallbacks(startupCallbackTimeoutRunnable)
            listener.onSpeechError(SpeechRecognizer.ERROR_CLIENT)
            Log.e(TAG, "startListening failed", it)
        }
    }

    private fun requestStopWithGrace(reason: String) {
        if (!isListening || didEmitResult || pendingStop) return
        Log.i(TAG, "Scheduling stopListening() with grace ($reason)")
        mainHandler.removeCallbacks(gracefulStopRunnable)
        mainHandler.postDelayed(gracefulStopRunnable, STOP_GRACE_MS)
    }

    fun stopListening() {
        requestStopWithGrace("manual_stop")
    }

    fun destroy() {
        isListening = false
        didEmitResult = false
        reconnectAttempted = false
        pendingStop = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun extractFirstText(bundle: Bundle?): String {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
            .trim()
    }

    private fun emitSpeechResultOnce(text: String): Boolean {
        if (text.isBlank() || didEmitResult) return false
        didEmitResult = true
        isListening = false
        pendingStop = false
        mainHandler.removeCallbacks(listeningTimeoutRunnable)
        mainHandler.removeCallbacks(forceFinalizeRunnable)
        mainHandler.removeCallbacks(gracefulStopRunnable)
        mainHandler.removeCallbacks(postStopTimeoutRunnable)
        mainHandler.removeCallbacks(startupCallbackTimeoutRunnable)
        listener.onSpeechResult(text)
        return true
    }

    companion object {
        private const val TAG = "SpeechInputController"
        private const val RAYNEO_RECOGNIZER_PACKAGE = "com.rayneo.live.ai"
        private const val RAYNEO_RECOGNIZER_CLASS = "com.rayneo.live.ai.wakeup.RayNeoRecognitionService"
        private const val FORCE_FINALIZE_MS = 9_000L
        private const val LISTEN_TIMEOUT_MS = 18_000L
        private const val STOP_GRACE_MS = 2_000L
        private const val POST_STOP_CALLBACK_TIMEOUT_MS = 2_000L
        private const val RECONNECT_RETRY_DELAY_MS = 350L
        private const val START_CALLBACK_TIMEOUT_MS = 1_800L
        private const val STARTUP_RETRY_DELAY_MS = 300L
    }
}
