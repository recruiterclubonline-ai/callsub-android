package com.callsub.translator.test

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.callsub.translator.audio.AudioCaptureEngine
import com.callsub.translator.audio.AudioSourceProbe
import com.callsub.translator.network.GeminiRestClient
import com.callsub.translator.storage.SecureKeyStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Модуль экспресс-тестирования микрофона и работоспособности ключа Gemini.
 * Позволяет убедиться, что телефон захватывает звук и Google возвращает перевод.
 */
class MicTestEngine(private val context: Context) {

    sealed class TestResult {
        object Success : TestResult()
        data class LowSignal(val rms: Double) : TestResult()
        object ApiKeyMissing : TestResult()
        data class ApiError(val message: String) : TestResult()
        data class Error(val exception: Throwable) : TestResult()
    }

    suspend fun runMicAndApiTest(onProgress: (String) -> Unit): TestResult = withContext(Dispatchers.IO) {
        val storage = SecureKeyStorage(context)
        val apiKey = storage.getApiKey()

        if (apiKey.isNullOrBlank()) {
            return@withContext TestResult.ApiKeyMissing
        }

        try {
            onProgress("Проверка микрофона...")
            val source = AudioSourceProbe.findWorkingSource()
            val minBufferSize = AudioRecord.getMinBufferSize(
                AudioCaptureEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val recorder = AudioRecord(
                source,
                AudioCaptureEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext TestResult.LowSignal(0.0)
            }

            recorder.startRecording()
            onProgress("Говорите что-нибудь по-немецки (например 'Guten Tag')...")

            val totalSamples = AudioCaptureEngine.SAMPLE_RATE * TEST_DURATION_SECONDS
            val buffer = ShortArray(totalSamples)
            var readSamples = 0

            while (readSamples < totalSamples) {
                val read = recorder.read(buffer, readSamples, totalSamples - readSamples)
                if (read > 0) readSamples += read
                else break
            }

            recorder.stop()
            recorder.release()

            // Проверка RMS
            var sumSquares = 0.0
            for (i in 0 until readSamples) {
                sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
            }
            val rms = if (readSamples > 0) sqrt(sumSquares / readSamples) else 0.0

            if (rms < RMS_MIN_THRESHOLD) {
                return@withContext TestResult.LowSignal(rms)
            }

            onProgress("Отправка тестового фрагмента в Gemini AI...")
            val byteBuffer = ByteArray(readSamples * 2)
            for (i in 0 until readSamples) {
                val s = buffer[i].toInt()
                byteBuffer[i * 2] = (s and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }

            val restClient = GeminiRestClient(apiKey)
            val translatedText = restClient.translateAudioChunk(byteBuffer)

            if (!translatedText.isNullOrBlank()) {
                onProgress("Перевод получен: $translatedText")
                return@withContext TestResult.Success
            } else {
                return@withContext TestResult.ApiError("Ответ от модели пуст или не получен")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Сбой теста: ${e.message}", e)
            return@withContext TestResult.Error(e)
        }
    }

    companion object {
        private const val TAG = "MicTestEngine"
        private const val TEST_DURATION_SECONDS = 3
        private const val RMS_MIN_THRESHOLD = 120.0
    }
}
