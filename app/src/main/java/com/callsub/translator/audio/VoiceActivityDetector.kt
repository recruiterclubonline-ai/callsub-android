package com.callsub.translator.audio

import kotlin.math.sqrt

/**
 * Детектор речевой активности (VAD) и нарезки аудиопотока для синхронного перевода.
 * Отправляет фразы каждые 1.5–1.8 секунды, либо при паузах между словами (300 мс).
 */
class VoiceActivityDetector(
    private val onEnergyUpdate: ((Double) -> Unit)? = null,
    private val onPhraseComplete: (ByteArray) -> Unit
) {
    private val accumulatedChunks = mutableListOf<ByteArray>()
    private var silenceDurationMs = 0
    private var totalDurationMs = 0
    private var hasSpeechDetectedInCurrentPhrase = false

    fun processChunk(pcmBytes: ByteArray) {
        val rms = computeRms(pcmBytes)
        onEnergyUpdate?.invoke(rms)

        accumulatedChunks.add(pcmBytes)
        totalDurationMs += CHUNK_DURATION_MS

        if (rms >= RMS_SPEECH_THRESHOLD) {
            hasSpeechDetectedInCurrentPhrase = true
            silenceDurationMs = 0
        } else {
            silenceDurationMs += CHUNK_DURATION_MS
        }

        // Если собеседник сделал короткую естественную паузу (350 мс) и накоплено хотя бы 600 мс речи
        if (silenceDurationMs >= SILENCE_GAP_THRESHOLD_MS && totalDurationMs >= MIN_PHRASE_DURATION_MS) {
            flush()
            return
        }

        // Если собеседник говорит непрерывно, отсекаем каждые 2.2 секунды для синхронности
        if (totalDurationMs >= MAX_PHRASE_DURATION_MS) {
            flush()
        }
    }

    private fun flush() {
        val hadSpeech = hasSpeechDetectedInCurrentPhrase
        if (accumulatedChunks.isNotEmpty() && hadSpeech) {
            val totalSize = accumulatedChunks.sumOf { it.size }
            val combined = ByteArray(totalSize)
            var offset = 0
            for (chunk in accumulatedChunks) {
                chunk.copyInto(combined, offset)
                offset += chunk.size
            }

            // Отправляем только если в аудио реально есть энергия речи
            val phraseRms = computeRms(combined)
            if (phraseRms >= RMS_MIN_SEND_THRESHOLD) {
                onPhraseComplete(combined)
            }
        }
        accumulatedChunks.clear()
        silenceDurationMs = 0
        totalDurationMs = 0
        hasSpeechDetectedInCurrentPhrase = false
    }

    private fun computeRms(pcmBytes: ByteArray): Double {
        if (pcmBytes.size < 2) return 0.0
        var sumSquares = 0.0
        val samples = pcmBytes.size / 2

        for (i in 0 until samples) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / samples)
    }

    companion object {
        // Минимальный порог, отличающий реальный звук/голос от фонового шума комнаты
        private const val RMS_SPEECH_THRESHOLD = 26.0
        private const val RMS_MIN_SEND_THRESHOLD = 20.0
        private const val SILENCE_GAP_THRESHOLD_MS = 550
        private const val MAX_PHRASE_DURATION_MS = 3800
        private const val MIN_PHRASE_DURATION_MS = 1600
        private const val CHUNK_DURATION_MS = 100
    }
}
