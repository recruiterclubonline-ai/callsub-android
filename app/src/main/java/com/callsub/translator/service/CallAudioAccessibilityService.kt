package com.callsub.translator.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Служба специальных возможностей (AccessibilityService) для приоритетного захвата
 * звука микрофона во время телефонного звонка на Android 10-15 без блокировки системой.
 */
class CallAudioAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var telephonyManager: TelephonyManager? = null
    private var audioManager: AudioManager? = null

    @Volatile
    private var isRecording = false

    // Для Android 12+ (API 31+)
    private var callStateCallbackApi31: Any? = null

    // Для Android 10-11 (API 29-30)
    private var legacyPhoneStateListener: PhoneStateListener? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События UI не обрабатываются, сервис используется для обхода ограничений аудиозахвата
    }

    override fun onInterrupt() {
        Log.w(TAG, "CallAudioAccessibilityService прерван системой")
        stopAudioCapture()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "CallAudioAccessibilityService успешно подключен")
        isAccessibilityServiceActive = true

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        registerCallStateListener()
    }

    /**
     * Регистрация слушателя состояния вызова с поддержкой всех версий Android 10-15.
     */
    private fun registerCallStateListener() {
        val tm = telephonyManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state)
                }
            }
            callStateCallbackApi31 = callback
            tm.registerTelephonyCallback(mainExecutor, callback)
            Log.i(TAG, "TelephonyCallback зарегистрирован (Android 12+)")
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state)
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "PhoneStateListener зарегистрирован (Android 10-11)")
        }
    }

    /**
     * Обработка состояний звонка: RINGING, OFFHOOK, IDLE.
     */
    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.i(TAG, "Звонок начат (OFFHOOK). Включаем громкую связь и запись звука...")
                enableSpeakerphone()
                ensureTranslatorServiceStarted()
                startAudioCapture()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.i(TAG, "Звонок завершен (IDLE). Останавливаем запись...")
                stopAudioCapture()
                restoreAudioSettings()
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.i(TAG, "Входящий вызов (RINGING)...")
            }
        }
    }

    /**
     * Запуск плавающего окна перевода, если оно еще не открыто.
     */
    private fun ensureTranslatorServiceStarted() {
        try {
            if (!CallTranslatorService.isServiceActive) {
                val intent = Intent(this, CallTranslatorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить CallTranslatorService: ${e.message}")
        }
    }

    /**
     * Принудительное включение громкой связи для акустического захвата голоса собеседника.
     */
    private fun enableSpeakerphone() {
        try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_IN_CALL
                am.isSpeakerphoneOn = true
                Log.i(TAG, "Громкая связь (Speakerphone) включена")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка активации громкой связи: ${e.message}", e)
        }
    }

    /**
     * Восстановление стандартного аудиорежима.
     */
    private fun restoreAudioSettings() {
        try {
            audioManager?.let { am ->
                am.isSpeakerphoneOn = false
                am.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "Громкая связь отключена, аудиорежим: NORMAL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка восстановления аудиорежима: ${e.message}", e)
        }
    }

    /**
     * Инициализация AudioRecord с источником VOICE_RECOGNITION.
     */
    private fun startAudioCapture() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        val bufferSize = (minBufferSize * 2).coerceAtLeast(CHUNK_SIZE_BYTES * 4)

        try {
            val record = AudioRecord(
                AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord не смог инициализироваться в VOICE_RECOGNITION")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()
            isRecording = true
            isCapturingAudio = true
            CallTranslatorService.instance?.pauseLocalAudio()
            Log.i(TAG, "AudioRecord запущен (16000 Hz, Mono, 16-bit PCM, VOICE_RECOGNITION)")

            recordingJob = serviceScope.launch(Dispatchers.IO) {
                val readBuffer = ByteArray(CHUNK_SIZE_BYTES)

                while (isActive && isRecording) {
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)

                    if (bytesRead > 0) {
                        val amplified = amplifyPcm(readBuffer, bytesRead, 2.5f)

                        // 1. Отправка в реактивный Flow
                        _audioChunkFlow.tryEmit(amplified)

                        // 2. Вызов прямого слушателя
                        onAudioChunkReady?.invoke(amplified)
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord.read вернул ошибку: $bytesRead")
                        delay(20)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Отсутствует RECORD_AUDIO: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска захвата аудио: ${e.message}", e)
            stopAudioCapture()
        }
    }

    /**
     * Программное усиление слабого звука из динамика громкой связи с защитой от клиппинга.
     */
    private fun amplifyPcm(buffer: ByteArray, read: Int, gain: Float = 2.5f): ByteArray {
        val out = ByteArray(read)
        val samples = read / 2
        for (i in 0 until samples) {
            val low = buffer[i * 2].toInt() and 0xFF
            val high = buffer[i * 2 + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val amplified = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[i * 2] = (amplified.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((amplified.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Остановка записи и освобождение ресурсов.
     */
    private fun stopAudioCapture() {
        isRecording = false
        isCapturingAudio = false
        CallTranslatorService.instance?.resumeLocalAudio()

        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
            Log.i(TAG, "Аудиозахват остановлен, ресурсы освобождены")
        }
    }

    override fun onDestroy() {
        isAccessibilityServiceActive = false
        stopAudioCapture()
        restoreAudioSettings()

        val tm = telephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = callStateCallbackApi31 as? TelephonyCallback
            if (tm != null && callback != null) {
                tm.unregisterTelephonyCallback(callback)
            }
        } else {
            @Suppress("DEPRECATION")
            legacyPhoneStateListener?.let { listener ->
                tm?.listen(listener, PhoneStateListener.LISTEN_NONE)
            }
        }

        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "CallAudioAccessibilityService уничтожен")
    }

    companion object {
        private const val TAG = "CallAudioAccService"

        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 3200

        @Volatile
        var isAccessibilityServiceActive = false

        @Volatile
        var isCapturingAudio = false
            private set

        /**
         * Прямой коллбэк для интеграции с сервисом перевода.
         */
        @Volatile
        var onAudioChunkReady: ((ByteArray) -> Unit)? = null

        /**
         * Реактивный Kotlin SharedFlow с аудиобуфером.
         */
        private val _audioChunkFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        val audioChunkFlow: SharedFlow<ByteArray> = _audioChunkFlow
    }
}
