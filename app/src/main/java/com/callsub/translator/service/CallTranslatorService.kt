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
import com.callsub.translator.network.GeminiLiveClient
import com.callsub.translator.storage.SecureKeyStorage
import com.callsub.translator.storage.TranscriptLogger
import com.callsub.translator.tts.QuickPhrasesPlayer
import com.callsub.translator.ui.OverlayManager
import kotlinx.coroutines.*

/**
 * Неубиваемый Foreground Service, связывающий микрофон, Gemini AI, оверлей и сохранение логов.
 */
class CallTranslatorService : Service() {

    private var audioEngine: AudioCaptureEngine? = null
    private var geminiClient: GeminiLiveClient? = null
    private var overlayManager: OverlayManager? = null
    private var phrasesPlayer: QuickPhrasesPlayer? = null
    private var transcriptLogger: TranscriptLogger? = null
    private var serviceScope: CoroutineScope? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @Volatile
    private var isTtsSpeaking = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CallTranslatorService создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "CallTranslatorService запущен")

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

        phrasesPlayer = QuickPhrasesPlayer(this) { isSpeaking ->
            isTtsSpeaking = isSpeaking
            overlayManager?.setSpeakerActive(!isSpeaking)
        }

        overlayManager = OverlayManager(this, phrasesPlayer).apply { show() }
        overlayManager?.setStatusColor(R.drawable.circle_yellow)

        geminiClient = GeminiLiveClient(apiKey).apply { connect() }
        audioEngine = AudioCaptureEngine()

        bindPipelines()

        return START_STICKY
    }

    private fun bindPipelines() {
        val scope = serviceScope ?: return
        val liveClient = geminiClient ?: return
        val audio = audioEngine ?: return
        val overlay = overlayManager ?: return
        val logger = transcriptLogger

        // 1. Мониторинг статуса соединения
        scope.launch {
            liveClient.statusFlow.collect { status ->
                when (status) {
                    GeminiLiveClient.ConnectionStatus.STREAMING -> {
                        overlay.setStatusColor(R.drawable.circle_green)
                        audio.start()
                    }
                    GeminiLiveClient.ConnectionStatus.CONNECTING,
                    GeminiLiveClient.ConnectionStatus.SETUP_ACTIVE -> {
                        overlay.setStatusColor(R.drawable.circle_yellow)
                    }
                    GeminiLiveClient.ConnectionStatus.ERROR,
                    GeminiLiveClient.ConnectionStatus.DISCONNECTED -> {
                        overlay.setStatusColor(R.drawable.circle_red)
                    }
                }
            }
        }

        // 2. Передача аудио с микрофона в Google AI Studio (с пропуском, когда говорит собственный TTS)
        scope.launch {
            audio.audioFlow.collect { pcmChunk ->
                if (!isTtsSpeaking) {
                    liveClient.sendPcmChunk(pcmChunk)
                }
            }
        }

        // 3. Вывод полученных субтитров на экран и сохранение в историю
        scope.launch {
            liveClient.subtitlesFlow.collect { textToken ->
                overlay.appendStreamingTranslation(textToken)
                logger?.logEntry(germanText = "", russianText = textToken)
            }
        }
    }

    override fun onDestroy() {
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
    }
}
