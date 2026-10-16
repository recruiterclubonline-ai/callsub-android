package com.callsub.translator.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Озвучивает спасительные фразы на чистом немецком языке прямо в динамик громкой связи.
 */
class QuickPhrasesPlayer(context: Context) {

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
    }

    fun speakSpeakSlower() {
        speak(PHRASE_LANGSAMER)
    }

    fun speakOneMoment() {
        speak(PHRASE_MOMENT)
    }

    private fun speak(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS еще не готов к озвучке")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quick_phrase_id")
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
    }
}
