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

        // 1. Если была речь и собеседник сделал естественную паузу (400 мс)
        if (hasSpeechDetectedInCurrentPhrase && silenceDurationMs >= SILENCE_GAP_THRESHOLD_MS && totalDurationMs >= MIN_PHRASE_DURATION_MS) {
            flush()
            return
        }

        // 2. Если собеседник говорит непрерывно дольше 3.2 секунд — отсекаем порцию
        if (totalDurationMs >= MAX_PHRASE_DURATION_MS) {
            flush()
            return
        }

        // 3. Если речи не было и накапливается чистая тишина > 1 сек — очищаем буфер без отправки
        if (!hasSpeechDetectedInCurrentPhrase && totalDurationMs >= 1000) {
            accumulatedChunks.clear()
            totalDurationMs = 0
            silenceDurationMs = 0
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

            // Отправляем фразу на перевод
            onPhraseComplete(combined)
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
            val sample = ((high shl 8) or low).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / samples)
    }

    companion object {
        // Порог обнаружения речи из динамика (чувствительнее, чтобы ловить тихий голос автоответчика)
        private const val RMS_SPEECH_THRESHOLD = 11.0
        // Пауза между фразами: 650 мс для естественного разделения предложений
        private const val SILENCE_GAP_THRESHOLD_MS = 650
        // Максимальная длина фразы: 4.5 секунды (вмещает длинное немецкое предложение)
        private const val MAX_PHRASE_DURATION_MS = 4500
        // Минимальная длина: 1.2 секунды (рекомендация Музы: быстрее передает короткие фразы 'Ja', 'Guten Tag')
        private const val MIN_PHRASE_DURATION_MS = 1200
        private const val CHUNK_DURATION_MS = 100
    }
}
