package com.callsub.translator.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket клиент для Gemini Multimodal Live API.
 * Позволяет передавать непрерывный сырой поток PCM 16kHz и получать текстовый перевод на лету.
 */
class GeminiLiveClient(private val apiKey: String) {

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        SETUP_ACTIVE,
        STREAMING,
        ERROR
    }

    private val _subtitlesFlow = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val subtitlesFlow: SharedFlow<String> = _subtitlesFlow

    private val _statusFlow = MutableSharedFlow<ConnectionStatus>(replay = 1, extraBufferCapacity = 8)
    val statusFlow: SharedFlow<ConnectionStatus> = _statusFlow

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    @Volatile
    private var currentStatus = ConnectionStatus.DISCONNECTED

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun connect() {
        if (currentStatus == ConnectionStatus.CONNECTING || currentStatus == ConnectionStatus.STREAMING) {
            return
        }

        updateStatus(ConnectionStatus.CONNECTING)
        val url = "$WS_BASE_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket сокет открыт. Отправляем initial setup...")
                sendSetupConfig(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Сбой соединения WebSocket: ${t.message}")
                updateStatus(ConnectionStatus.ERROR)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket закрыт сервером: $code / $reason")
                updateStatus(ConnectionStatus.DISCONNECTED)
            }
        })
    }

    private fun sendSetupConfig(ws: WebSocket) {
        val setupPayload = JsonObject().apply {
            add("setup", JsonObject().apply {
                addProperty("model", MODEL_NAME)
                add("generationConfig", JsonObject().apply {
                    add("responseModalities", gson.toJsonTree(listOf("TEXT")))
                    addProperty("temperature", 0.1)
                })
                add("systemInstruction", JsonObject().apply {
                    add("parts", gson.toJsonTree(listOf(mapOf("text" to SYSTEM_INSTRUCTION))))
                })
            })
        }

        ws.send(gson.toJson(setupPayload))
        updateStatus(ConnectionStatus.SETUP_ACTIVE)
        Log.d(TAG, "Конфигурация Setup отправлена")
    }

    private fun handleIncomingMessage(rawJson: String) {
        try {
            val json = JsonParser.parseString(rawJson).asJsonObject

            if (json.has("setupComplete")) {
                Log.i(TAG, "Google подтвердил сессию (setupComplete). Начинаем перевод!")
                updateStatus(ConnectionStatus.STREAMING)
                return
            }

            if (json.has("serverContent")) {
                val serverContent = json.getAsJsonObject("serverContent")
                val modelTurn = serverContent.getAsJsonObject("modelTurn")
                val parts = modelTurn?.getAsJsonArray("parts")

                parts?.forEach { element ->
                    val part = element.asJsonObject
                    if (part.has("text")) {
                        val text = part.get("text").asString
                        if (text.isNotBlank()) {
                            _subtitlesFlow.tryEmit(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка разбора входящего сообщения: ${e.message}")
        }
    }

    fun sendPcmChunk(pcmData: ByteArray) {
        if (currentStatus != ConnectionStatus.STREAMING) return

        val base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val payload = JsonObject().apply {
            add("realtimeInput", JsonObject().apply {
                add("mediaChunks", gson.toJsonTree(listOf(
                    mapOf(
                        "mimeType" to "audio/pcm",
                        "data" to base64
                    )
                )))
            })
        }

        webSocket?.send(gson.toJson(payload))
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "User call ended")
        } catch (_: Exception) {}
        webSocket = null
        updateStatus(ConnectionStatus.DISCONNECTED)
    }

    private fun updateStatus(status: ConnectionStatus) {
        currentStatus = status
        _statusFlow.tryEmit(status)
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val MODEL_NAME = "models/gemini-2.5-flash"
        private const val SYSTEM_INSTRUCTION =
            "Ты — мгновенный синхронный переводчик немецких телефонных звонков на русский язык. " +
            "На вход непрерывно поступает живое аудио немецкой речи собеседника. " +
            "Твоя задача — моментально возвращать в текстовом потоке ТОЛЬКО краткий и точный перевод услышанного " +
            "на русский язык в виде субтитров. Не добавляй никаких комментариев, вводных слов и рассуждений. " +
            "Только перевод на русский язык."
    }
}
