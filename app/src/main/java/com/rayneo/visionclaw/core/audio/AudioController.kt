package com.rayneo.visionclaw.core.audio

import android.content.Context
import android.media.AudioManager
import com.rayneo.visionclaw.core.storage.AppPreferences
import kotlin.math.roundToInt

class AudioController(
    context: Context,
    private val preferences: AppPreferences
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setMusicVolume(normalized: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val value = (normalized.coerceIn(0f, 1f) * max).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
    }

    fun getMusicVolumeNormalized(): Float {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return current.toFloat() / max.toFloat()
    }

    fun setMusicMuted(muted: Boolean) {
        preferences.musicMuted = muted
        if (muted) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }
}
