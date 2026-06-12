package com.rayneo.visionclaw.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.util.Log
import com.rayneo.visionclaw.core.storage.AppPreferences
import java.util.Locale
import java.util.concurrent.Executors

class TtsController(
    context: Context,
    private val preferences: AppPreferences
) {

    companion object {
        private const val TAG = "TtsController"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var pendingUtterance: String? = null
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TtsController").apply { isDaemon = true }
    }
    @Volatile
    private var activeSpeechGeneration: Long = 0L
    @Volatile
    private var remainingUtteranceCount: Int = 0
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= AudioManager.AUDIOFOCUS_LOSS) {
            stop()
            abandonAudioFocus()
        }
    }

    init {
        initTts()
    }

    /**
     * Speak text aloud. Set [force] to true for voice-session audio that should
     * always play regardless of the auto-read preference. Set [ignoreMute] to
     * true to also bypass the global `ttsMuted` preference — use this only for
     * readouts the user explicitly requested (e.g., "read the research report"),
     * since silently obeying the mute flag is indistinguishable from the app
     * hanging.
     */
    fun speak(text: String, force: Boolean = false, ignoreMute: Boolean = false) {
        if (text.isBlank()) return
        if (!ignoreMute && preferences.ttsMuted) return
        if (!force && !preferences.ttsAutoRead) return
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus not granted for TTS")
            return
        }
        if (!ready || tts == null) {
            pendingUtterance = text
            initTts()
            return
        }
        speakInternal(text)
    }

    private fun speakInternal(text: String) {
        // Apply user-configured speech rate (0 or negative = default 1.0)
        val rate = preferences.ttsSpeechRate
        tts?.setSpeechRate(if (rate > 0f) rate else 1.0f)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.ttsVolume)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        val generation = activeSpeechGeneration + 1L
        activeSpeechGeneration = generation
        val chunks = splitTextForSpeech(text)
        remainingUtteranceCount = chunks.size

        var hadFailure = false
        chunks.forEachIndexed { index, chunk ->
            val utteranceId = "panel_readout_${generation}_$index"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts?.speak(chunk, queueMode, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                hadFailure = true
            }
        }
        if (hadFailure) {
            Log.w(TAG, "tts.speak failed; reinitializing")
            pendingUtterance = text
            remainingUtteranceCount = 0
            initTts(force = true)
        }
    }

    fun stop() {
        activeSpeechGeneration += 1L
        remainingUtteranceCount = 0
        val engine = tts
        runCatching {
            controlExecutor.execute {
                runCatching { engine?.stop() }
                abandonAudioFocus()
            }
        }.onFailure {
            runCatching { engine?.stop() }
            abandonAudioFocus()
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        val engine = tts
        tts = null
        ready = false
        pendingUtterance = null
        initializing = false
        activeSpeechGeneration += 1L
        remainingUtteranceCount = 0
        runCatching {
            controlExecutor.execute {
                runCatching { engine?.stop() }
                runCatching { engine?.shutdown() }
                abandonAudioFocus()
                controlExecutor.shutdown()
            }
        }.onFailure {
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
            abandonAudioFocus()
            controlExecutor.shutdown()
        }
    }

    private fun initTts(force: Boolean = false) {
        if (initializing) return
        if (!force && tts != null && ready) return
        initializing = true
        if (force) {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ready = false
        }

        tts = TextToSpeech(appContext) { status ->
            initializing = false
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "TTS init failed")
                return@TextToSpeech
            }
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        maybeFinishSpeech(utteranceId)
                    }
                    override fun onError(utteranceId: String?) {
                        maybeFinishSpeech(utteranceId)
                    }
                }
            )
            pendingUtterance?.let { queued ->
                pendingUtterance = null
                speakInternal(queued)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req =
                    focusRequest
                        ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setOnAudioFocusChangeListener(focusChangeListener)
                            .build()
                            .also { focusRequest = it }
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun maybeFinishSpeech(utteranceId: String?) {
        val generation = activeSpeechGeneration
        val prefix = "panel_readout_${generation}_"
        if (utteranceId == null || !utteranceId.startsWith(prefix)) return
        val remaining = (remainingUtteranceCount - 1).coerceAtLeast(0)
        remainingUtteranceCount = remaining
        if (remaining == 0) {
            abandonAudioFocus()
        }
    }

    private fun splitTextForSpeech(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()
        val maxLen = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1000)
        if (normalized.length <= maxLen) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var remaining = normalized
        while (remaining.isNotBlank()) {
            if (remaining.length <= maxLen) {
                chunks.add(remaining.trim())
                break
            }
            var splitAt = remaining.lastIndexOf('\n', maxLen)
            if (splitAt < maxLen / 2) {
                splitAt = remaining.lastIndexOf(". ", maxLen)
                if (splitAt >= 0) splitAt += 1
            }
            if (splitAt < maxLen / 2) {
                splitAt = remaining.lastIndexOf(' ', maxLen)
            }
            if (splitAt <= 0) splitAt = maxLen
            chunks.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trimStart()
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }
}
