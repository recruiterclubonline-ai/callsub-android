package com.callsub.translator.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Резервный REST-клиент для отправки отдельных аудиочанков (2-3 сек) при обрыве WebSocket.
 * Оборачивает сырой PCM в каноничный 44-байтный WAV-контейнер для гарантированной совместимости с Google AI Studio.
 */
class GeminiRestClient(private val apiKey: String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun translateAudioChunk(pcmData: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val wavData = wrapPcmToWav(pcmData, sampleRate = 16000)
            val base64Data = Base64.encodeToString(wavData, Base64.NO_WRAP)

            val jsonPayload = JsonObject().apply {
                add("contents", gson.toJsonTree(listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to "Ты — синхронный переводчик. Моментально переведи немецкую речь из аудио на русский язык кратко и ёмко в виде субтитра. Выводи ТОЛЬКО русский перевод:"),
                            mapOf(
                                "inlineData" to mapOf(
                                    "mimeType" to "audio/wav",
                                    "data" to base64Data
                                )
                            )
                        )
                    )
                )))
                add("generationConfig", JsonObject().apply {
                    addProperty("temperature", 0.0)
                })
            }

            val body = gson.toJson(jsonPayload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$REST_URL?key=$apiKey")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "REST ошибка: ${response.code}")
                return@withContext null
            }

            val responseString = response.body?.string() ?: return@withContext null
            val root = JsonParser.parseString(responseString).asJsonObject
            val candidates = root.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val content = candidates[0].asJsonObject.getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    return@withContext parts[0].asJsonObject.get("text")?.asString
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка REST перевода: ${e.message}")
        }
        return@withContext null
    }

    private fun wrapPcmToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono = 2 bytes per sample

        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // "fmt " sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format = 1
        header[21] = 0
        header[22] = 1 // 1 channel (mono)
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2 // block align (1 channel * 2 bytes)
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0

        // "data" sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        val out = ByteArrayOutputStream(header.size + pcmData.size)
        out.write(header)
        out.write(pcmData)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "GeminiRestClient"
        private const val REST_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }
}
