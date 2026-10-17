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
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    // Хранение контекста последних переведённых фраз для связности перевода
    private val translationHistory = mutableListOf<String>()
    private val maxHistorySize = 5

    sealed class Result {
        data class Success(val translation: String) : Result()
        object NoSpeech : Result()
        data class Error(val code: Int, val message: String) : Result()
    }

    suspend fun translateAudioChunk(pcmData: ByteArray): Result = withContext(Dispatchers.IO) {
        try {
            val wavData = wrapPcmToWav(pcmData, sampleRate = 16000)
            val base64Data = Base64.encodeToString(wavData, Base64.NO_WRAP)

            // Формируем контекст предыдущих фраз для связности
            val contextBlock = if (translationHistory.isNotEmpty()) {
                "\nПредыдущие фразы разговора (для контекста):\n" +
                translationHistory.joinToString("\n") { "— $it" } + "\n"
            } else ""

            val systemPrompt = """Ты — синхронный переводчик телефонного звонка с немецкого на русский.
Аудио записано через громкую связь, качество может быть низким.
$contextBlock
СТРОГИЕ ПРАВИЛА:
1. Выведи ТОЛЬКО русский перевод произнесённых слов, без кавычек и пояснений.
2. Переводи ВСЮ услышанную речь целиком, не сокращай и не пропускай слова.
3. Если собеседник назвал имя, адрес, номер или дату — передай их точно как есть.
4. Если слово непонятно — переведи по контексту, не пиши «неразборчиво».
5. Если в аудио ТОЛЬКО шум, тишина или нет речи — выведи одно слово: НЕТ_РЕЧИ"""

            val jsonPayload = JsonObject().apply {
                add("contents", gson.toJsonTree(listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to systemPrompt),
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
                    addProperty("temperature", 0.1)
                    addProperty("maxOutputTokens", 200)
                })
            }

            val body = gson.toJson(jsonPayload).toRequestBody("application/json".toMediaType())
            
            // Пробуем сначала gemini-2.5-flash-lite (быстрый, без жесткого лимита 20/день)
            var request = Request.Builder()
                .url("$PRIMARY_URL?key=$apiKey")
                .post(body)
                .build()

            var response = client.newCall(request).execute()

            // Если вернулся 429 — автоматически переключаемся на резервную модель gemini-flash-latest
            if (response.code == 429) {
                Log.w(TAG, "Лимит на primary модели, переключаемся на fallback gemini-flash-latest...")
                val fallbackRequest = Request.Builder()
                    .url("$FALLBACK_URL?key=$apiKey")
                    .post(body)
                    .build()
                response = client.newCall(fallbackRequest).execute()
            }

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.w(TAG, "REST ошибка: ${response.code} - $errBody")
                val errMsg = when (response.code) {
                    429 -> "Превышен лимит запросов Google API (429). Подождите пару минут."
                    403 -> "Неверный или отозванный API-ключ (403)"
                    400 -> "Ошибка формата аудио (400)"
                    else -> "Сбой Google API (${response.code})"
                }
                return@withContext Result.Error(response.code, errMsg)
            }

            val responseString = response.body?.string() ?: return@withContext Result.NoSpeech
            val root = JsonParser.parseString(responseString).asJsonObject
            val candidates = root.getAsJsonArray("candidates")
            if (candidates != null && candidates.size() > 0) {
                val content = candidates[0].asJsonObject.getAsJsonObject("content")
                val parts = content?.getAsJsonArray("parts")
                if (parts != null && parts.size() > 0) {
                    var text = parts[0].asJsonObject.get("text")?.asString?.trim() ?: ""
                    // Очистка от возможных артефактов модели
                    text = text.removePrefix("Вот перевод:").removePrefix("Перевод:").trim()
                    if (text.isNotBlank() && !text.contains("НЕТ_РЕЧИ", ignoreCase = true)) {
                        synchronized(translationHistory) {
                            translationHistory.add(text)
                            if (translationHistory.size > maxHistorySize) {
                                translationHistory.removeAt(0)
                            }
                        }
                        return@withContext Result.Success(text)
                    } else {
                        return@withContext Result.NoSpeech
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка REST перевода: ${e.message}")
            return@withContext Result.Error(-1, e.message ?: "Сетевая ошибка")
        }
        return@withContext Result.NoSpeech
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
        private const val PRIMARY_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"
        private const val FALLBACK_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
    }
}
