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
import java.util.concurrent.TimeUnit

/**
 * Резервный REST-клиент для отправки отдельных аудиочанков (2-3 сек) при обрыве WebSocket.
 */
class GeminiRestClient(private val apiKey: String) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun translateAudioChunk(pcmData: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
            val jsonPayload = JsonObject().apply {
                add("contents", gson.toJsonTree(listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to "Переведи немецкую речь из этого аудио на русский язык кратко в виде субтитра:"),
                            mapOf(
                                "inlineData" to mapOf(
                                    "mimeType" to "audio/pcm;rate=16000",
                                    "data" to base64Data
                                )
                            )
                        )
                    )
                )))
                add("generationConfig", JsonObject().apply {
                    addProperty("temperature", 0.1)
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

    companion object {
        private const val TAG = "GeminiRestClient"
        private const val REST_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    }
}
