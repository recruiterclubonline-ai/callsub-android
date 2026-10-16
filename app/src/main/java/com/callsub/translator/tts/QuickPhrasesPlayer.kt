package com.callsub.translator.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Озвучивает спасительные фразы на чистом немецком языке прямо в динамик громкой связи.
 * Предоставляет слушатель onSpeechPlaybackState, чтобы микрофон не записывал собственный голос телефона.
 */
class QuickPhrasesPlayer(
    context: Context,
    private val onPlaybackActive: ((Boolean) -> Unit)? = null
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.GERMANY)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Немецкий языковой пакет TTS не установлен")
                } else {
                    isInitialized = true
                    Log.i(TAG, "German TTS успешно инициализирован")
                }
            } else {
                Log.e(TAG, "Сбой инициализации TextToSpeech: $status")
            }
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onPlaybackActive?.invoke(true)
            }

            override fun onDone(utteranceId: String?) {
                onPlaybackActive?.invoke(false)
            }

            override fun onError(utteranceId: String?) {
                onPlaybackActive?.invoke(false)
            }
        })
    }

    fun speakSpeakSlower() {
        speak(PHRASE_LANGSAMER)
    }

    fun speakOneMoment() {
        speak(PHRASE_MOMENT)
    }

    fun speakJaGenau() {
        speak(PHRASE_JA)
    }

    private fun speak(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS еще не готов к озвучке")
            return
        }
        val utteranceId = "quick_phrase_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "QuickPhrasesPlayer"
        const val PHRASE_LANGSAMER = "Entschuldigung, könnten Sie bitte etwas langsamer sprechen? Die Verbindung ist etwas schlecht."
        const val PHRASE_MOMENT = "Einen kleinen Moment bitte, ich notiere mir das kurz."
        const val PHRASE_JA = "Ja, genau, vielen Dank."
    }
}
