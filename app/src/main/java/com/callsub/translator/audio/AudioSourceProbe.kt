package com.callsub.translator.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

/**
 * Динамический тестер и селектор источника захвата звука.
 * Находит тот AudioSource, который физически слышит динамик громкой связи.
 */
object AudioSourceProbe {

    private const val TAG = "AudioSourceProbe"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val PROBE_DURATION_MS = 300
    private const val SIGNAL_RMS_THRESHOLD = 150.0

    fun findWorkingSource(): Int {
        val candidates = mutableListOf(
            AudioSource.MIC,
            AudioSource.DEFAULT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            candidates.add(1, AudioSource.UNPROCESSED)
        }

        for (source in candidates) {
            try {
                val rms = measureEnergy(source)
                Log.d(TAG, "AudioSource $source RMS: $rms")
                if (rms >= SIGNAL_RMS_THRESHOLD) {
                    Log.i(TAG, "Выбран рабочий AudioSource: $source (RMS: $rms)")
                    return source
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source недоступен: ${e.message}")
            }
        }

        Log.i(TAG, "Используем AudioSource.MIC по умолчанию")
        return AudioSource.MIC
    }

    private fun measureEnergy(source: Int): Double {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (bufferSize <= 0) return 0.0

        val recorder = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return 0.0
        }

        try {
            recorder.startRecording()
            val samplesToRead = (SAMPLE_RATE * PROBE_DURATION_MS) / 1000
            val buffer = ShortArray(samplesToRead)
            val read = recorder.read(buffer, 0, samplesToRead)
            if (read <= 0) return 0.0

            var sumSquares = 0.0
            for (i in 0 until read) {
                sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
            }
            return sqrt(sumSquares / read)
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {}
            recorder.release()
        }
    }
}
