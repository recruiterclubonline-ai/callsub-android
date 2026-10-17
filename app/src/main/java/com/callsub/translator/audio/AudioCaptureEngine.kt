package com.callsub.translator.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.media.audiofx.AutomaticGainControl
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Непрерывный низколатентный аудиозахват с микрофона (16 кГц, 16-бит Mono PCM).
 * Оптимизирован для захвата голоса собеседника из динамика громкой связи.
 */
class AudioCaptureEngine {

    private val _audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioFlow: SharedFlow<ByteArray> = _audioFlow

    private var recorder: AudioRecord? = null
    private var gainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null

    @Volatile
    private var isRecording = false

    fun start() {
        if (isRecording) return

        val sources = listOf(
            AudioSource.VOICE_COMMUNICATION,
            AudioSource.MIC,
            AudioSource.DEFAULT
        )

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(CHUNK_SIZE_BYTES * 4)

        var selectedRecord: AudioRecord? = null
        var selectedSource = AudioSource.MIC

        for (source in sources) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    selectedRecord = record
                    selectedSource = source
                    Log.i(TAG, "AudioRecord инициализирован с источником: $source")
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Источник $source недоступен: ${e.message}")
            }
        }

        if (selectedRecord == null) {
            Log.e(TAG, "Не удалось инициализировать AudioRecord ни с одним источником!")
            return
        }

        try {
            val sessionId = selectedRecord.audioSessionId

            // Включаем автоматическое усиление звука (AGC), чтобы слышать динамик
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                Log.d(TAG, "AutomaticGainControl активирован")
            }

            selectedRecord.startRecording()
            recorder = selectedRecord
            isRecording = true

            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                Log.i(TAG, "Потоковая аудиозапись начата (16kHz PCM, source=$selectedSource)")
                val buffer = ByteArray(CHUNK_SIZE_BYTES)

                while (isActive && isRecording) {
                    val read = recorder?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        _audioFlow.emit(buffer.copyOf(read))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска AudioCaptureEngine: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            recorder?.stop()
        } catch (_: Exception) {}

        gainControl?.release()
        gainControl = null

        recorder?.release()
        recorder = null

        Log.i(TAG, "Потоковая аудиозапись остановлена")
    }

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 100 мс порция PCM: 16000 сэмплов/сек * 2 байта * 0.1 сек = 3200 байт
        const val CHUNK_SIZE_BYTES = 3200
    }
}
