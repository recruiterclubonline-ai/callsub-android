package com.callsub.translator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callsub.translator.R
import com.callsub.translator.audio.AudioCaptureEngine
import com.callsub.translator.audio.VoiceActivityDetector
import com.callsub.translator.network.GeminiLiveClient
import com.callsub.translator.network.GeminiRestClient
import com.callsub.translator.storage.SecureKeyStorage
import com.callsub.translator.storage.TranscriptLogger
import com.callsub.translator.tts.QuickPhrasesPlayer
import com.callsub.translator.ui.OverlayManager
import kotlinx.coroutines.*

/**
 * Неубиваемый Foreground Service, связывающий микрофон, Gemini AI, оверлей и сохранение логов.
 * Реализует Dual-Engine: WebSocket Live API + автоматический REST VAD Fallback.
 */
class CallTranslatorService : Service() {

    private var audioEngine: AudioCaptureEngine? = null
    private var geminiClient: GeminiLiveClient? = null
    private var restClient: GeminiRestClient? = null
    private var overlayManager: OverlayManager? = null
    private var phrasesPlayer: QuickPhrasesPlayer? = null
    private var transcriptLogger: TranscriptLogger? = null
    private var serviceScope: CoroutineScope? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var vadDetector: VoiceActivityDetector? = null

    @Volatile
    private var isTtsSpeaking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "CallTranslatorService создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "CallTranslatorService onStartCommand")

        if (isServiceActive) {
            Log.i(TAG, "Сервис уже активен, повторная инициализация пропущена")
            return START_STICKY
        }
        isServiceActive = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        val storage = SecureKeyStorage(this)
        val apiKey = storage.getApiKey()

        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "Ключ Gemini API не настроен! Завершение сервиса.")
            isServiceActive = false
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CallSub:TranslatorWakeLock").apply {
                acquire(60 * 60 * 1000L) // 1 hour safety limit
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось захватить WakeLock: ${e.message}")
        }

        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        transcriptLogger = TranscriptLogger(this).apply { startNewSession() }
        restClient = GeminiRestClient(apiKey)

        phrasesPlayer = QuickPhrasesPlayer(this) { isSpeaking ->
            isTtsSpeaking = isSpeaking
            overlayManager?.setSpeakerActive(!isSpeaking)
        }

        overlayManager = OverlayManager(this, phrasesPlayer).apply { show() }
        overlayManager?.setStatusColor(R.drawable.circle_yellow)

        geminiClient = GeminiLiveClient(apiKey).apply { connect() }
        audioEngine = AudioCaptureEngine()

        bindPipelines()

        // Запускаем локальный захват только если сервис специальных возможностей еще не пишет звонок
        if (!CallAudioAccessibilityService.isCapturingAudio) {
            audioEngine?.start()
        }

        return START_STICKY
    }

    private fun bindPipelines() {
        val scope = serviceScope ?: return
        val liveClient = geminiClient ?: return
        val audio = audioEngine ?: return
        val overlay = overlayManager ?: return
        val rest = restClient
        val logger = transcriptLogger

        // 1. Мониторинг статуса WebSocket соединения
        scope.launch {
            liveClient.statusFlow.collect { status ->
                when (status) {
                    GeminiLiveClient.ConnectionStatus.STREAMING -> {
                        overlay.setStatusColor(R.drawable.circle_green)
                    }
                    GeminiLiveClient.ConnectionStatus.CONNECTING,
                    GeminiLiveClient.ConnectionStatus.SETUP_ACTIVE -> {
                        overlay.setStatusColor(R.drawable.circle_yellow)
                    }
                    GeminiLiveClient.ConnectionStatus.ERROR,
                    GeminiLiveClient.ConnectionStatus.DISCONNECTED -> {
                        // При сбое сокета индикатор остается желтым, так как работает VAD REST fallback
                        overlay.setStatusColor(R.drawable.circle_yellow)
                    }
                }
            }
        }

        // 2. VAD Детектор для нарезки естественных фраз и отправки через быстрый REST
        val phraseQueue = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 16)

        // Фоновый последовательный воркер: обрабатывает каждую фразу без потерь и без спама квоты
        scope.launch {
            for (phrasePcm in phraseQueue) {
                if (rest != null && !isTtsSpeaking) {
                    try {
                        when (val result = rest.translateAudioChunk(phrasePcm)) {
                            is GeminiRestClient.Result.Success -> {
                                overlay.updateSubtitles(
                                    germanText = "🇩🇪 Немецкая речь",
                                    russianText = result.translation
                                )
                                overlay.setStatusColor(R.drawable.circle_green)
                                logger?.logEntry(germanText = "", russianText = result.translation)
                            }
                            is GeminiRestClient.Result.Error -> {
                                overlay.updateSubtitles(
                                    germanText = "⚠️ " + result.message,
                                    russianText = "Код ошибки: ${result.code}. Проверьте соединение/ключ"
                                )
                                overlay.setStatusColor(R.drawable.circle_red)
                            }
                            is GeminiRestClient.Result.NoSpeech -> {
                                // Тишина между фразами
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Ошибка перевода чанка через REST: ${e.message}")
                    }
                }
            }
        }

        var continuousSilenceCount = 0

        vadDetector = VoiceActivityDetector(
            onEnergyUpdate = { rms ->
                val rounded = rms.toInt()
                if (rms >= 11.0) {
                    continuousSilenceCount = 0
                    overlay.setSpeakerActive(true)
                    overlay.setStatusColor(R.drawable.circle_green)
                    overlay.updateStatus("🟢 Слышу речь (сигнал: $rounded)")
                } else {
                    continuousSilenceCount++
                    if (continuousSilenceCount > 30) { // 3 сек тишины
                        overlay.setSpeakerActive(false)
                        overlay.setStatusColor(R.drawable.circle_yellow)
                        overlay.updateStatus("🇩🇪 Слушаю речь... ($rounded)")
                    }
                }
            },
            onPhraseComplete = { phrasePcm ->
                overlay.updateStatus("⚡ Перевожу...")
                // Отправляем в очередь — ни одна фраза собеседника не теряется!
                phraseQueue.trySend(phrasePcm)
            }
        )

        // 3. Передача аудио в VAD-детектор и Live сокет
        // А) Поток из CallAudioAccessibilityService (приоритетный захват сотового звонка)
        scope.launch {
            CallAudioAccessibilityService.audioChunkFlow.collect { pcmChunk ->
                if (!isTtsSpeaking) {
                    vadDetector?.processChunk(pcmChunk)
                    liveClient.sendPcmChunk(pcmChunk)
                }
            }
        }

        // Б) Поток из локального AudioCaptureEngine (резерв/ручной тест/когда Accessibility не активен)
        scope.launch {
            audio.audioFlow.collect { pcmChunk ->
                if (!CallAudioAccessibilityService.isCapturingAudio && !isTtsSpeaking) {
                    vadDetector?.processChunk(pcmChunk)
                    liveClient.sendPcmChunk(pcmChunk)
                }
            }
        }

        // 4. Прием потоковых токенов от Live сокета (только если Live активен и генерирует текст)
        scope.launch {
            liveClient.subtitlesFlow.collect { textToken ->
                if (textToken.isNotBlank()) {
                    overlay.appendStreamingTranslation(textToken)
                    overlay.setStatusColor(R.drawable.circle_green)
                    logger?.logEntry(germanText = "", russianText = textToken)
                }
            }
        }
    }

    /**
     * Приостановка локального аудиозахвата при перехвате звука AccessibilityService.
     */
    fun pauseLocalAudio() {
        audioEngine?.stop()
        Log.i(TAG, "Локальный микрофон временно приостановлен в пользу CallAudioAccessibilityService")
    }

    /**
     * Возобновление локального аудиозахвата после завершения звонка.
     */
    fun resumeLocalAudio() {
        if (isServiceActive && audioEngine?.isCapturing != true) {
            audioEngine?.start()
            Log.i(TAG, "Локальный микрофон возобновлен")
        }
    }

    override fun onDestroy() {
        isServiceActive = false
        if (instance == this) {
            instance = null
        }

        serviceScope?.cancel()
        serviceScope = null

        audioEngine?.stop()
        audioEngine = null

        geminiClient?.disconnect()
        geminiClient = null

        overlayManager?.hide()
        overlayManager = null

        phrasesPlayer?.release()
        phrasesPlayer = null

        transcriptLogger = null
        vadDetector = null
        restClient = null

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        Log.i(TAG, "CallTranslatorService остановлен и ресурсы освобождены")
        super.onDestroy()
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "CallTranslatorService"
        private const val NOTIFICATION_ID = 4040
        private const val CHANNEL_ID = "callsub_service_channel"

        @Volatile
        var isServiceActive = false
            private set

        @Volatile
        var instance: CallTranslatorService? = null
            private set
    }
}
