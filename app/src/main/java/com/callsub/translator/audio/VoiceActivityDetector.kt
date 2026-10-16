package com.callsub.translator.audio

import kotlin.math.sqrt

/**
 * Детектор пауз речи (Voice Activity Detector) на базе энергии RMS.
 * Используется в режиме REST Chunking Fallback для нарезки естественных смысловых фраз.
 */
class VoiceActivityDetector(
    private val onPhraseComplete: (ByteArray) -> Unit
) {
    private val accumulatedChunks = mutableListOf<ByteArray>()
    private var silenceDurationMs = 0
    private var speechDurationMs = 0

    fun processChunk(pcmBytes: ByteArray) {
        val rms = computeRms(pcmBytes)

        if (rms >= RMS_SPEECH_THRESHOLD) {
            silenceDurationMs = 0
            accumulatedChunks.add(pcmBytes)
            speechDurationMs += CHUNK_DURATION_MS

            if (speechDurationMs >= MAX_PHRASE_DURATION_MS) {
                flush()
            }
        } else {
            if (accumulatedChunks.isNotEmpty()) {
                silenceDurationMs += CHUNK_DURATION_MS
                if (silenceDurationMs >= SILENCE_GAP_THRESHOLD_MS) {
                    flush()
                }
            }
        }
    }

    private fun flush() {
        if (speechDurationMs >= MIN_PHRASE_DURATION_MS) {
            val totalSize = accumulatedChunks.sumOf { it.size }
            val combined = ByteArray(totalSize)
            var offset = 0
            for (chunk in accumulatedChunks) {
                chunk.copyInto(combined, offset)
                offset += chunk.size
            }
            onPhraseComplete(combined)
        }
        accumulatedChunks.clear()
        silenceDurationMs = 0
        speechDurationMs = 0
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
        private const val RMS_SPEECH_THRESHOLD = 220.0
        private const val SILENCE_GAP_THRESHOLD_MS = 400
        private const val MAX_PHRASE_DURATION_MS = 2500
        private const val MIN_PHRASE_DURATION_MS = 500
        private const val CHUNK_DURATION_MS = 100
    }
}
