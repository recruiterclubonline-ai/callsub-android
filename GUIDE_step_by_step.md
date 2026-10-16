# 📖 ПОШАГОВОЕ РУКОВОДСТВО ПО РАЗРАБОТКЕ «CallSub DE-RU»
## Подробная инструкция для каждого этапа — от создания проекта до рабочего APK

> [!NOTE]
> Это руководство является приложением к [Техническому заданию v2.0](file:///home/ubuntu/переводчик%20звонков/TZ_CallSub_Android.md). Каждый шаг расписан максимально подробно — копируй и делай.

---

## ЭТАП 1. Создание проекта и настройка разрешений

### Шаг 1.1. Создание нового проекта в Android Studio

1. Откройте Android Studio.
2. Нажмите **File → New → New Project**.
3. Выберите шаблон **«Empty Views Activity»** (не Compose — нам нужен классический XML для оверлея).
4. Заполните поля:
   * **Name:** `CallSub`
   * **Package name:** `com.callsub.translator`
   * **Save location:** выберите любую удобную папку
   * **Language:** `Kotlin`
   * **Minimum SDK:** `API 26 (Android 8.0 Oreo)`
   * **Build configuration language:** `Kotlin DSL (build.gradle.kts)`
5. Нажмите **Finish** и дождитесь окончания синхронизации Gradle.

### Шаг 1.2. Подключение зависимостей (библиотек)

Откройте файл `app/build.gradle.kts` и добавьте в блок `dependencies { }` следующие библиотеки:

```kotlin
dependencies {
    // Kotlin Coroutines — для асинхронной работы (чтобы сеть и запись звука не блокировали интерфейс)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp — HTTP-клиент и WebSocket-клиент для связи с Google AI Studio
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson — для парсинга JSON-ответов от Gemini
    implementation("com.google.code.gson:gson:2.11.0")

    // AndroidX Security — для безопасного хранения API-ключа (шифрование)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // AndroidX Core — утилиты для работы с уведомлениями и совместимостью версий
    implementation("androidx.core:core-ktx:1.13.1")

    // AndroidX AppCompat — базовая совместимость UI
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material Design — кнопки, поля ввода, переключатели
    implementation("com.google.android.material:material:1.12.0")
}
```

После добавления нажмите **Sync Now** (жёлтая полоска вверху редактора).

**Что делает каждая библиотека (объяснение для начинающих):**
* `kotlinx-coroutines` — позволяет выполнять тяжёлые операции (запись звука, отправка по сети) в фоновом потоке, не «замораживая» экран.
* `okhttp` — отправляет HTTP-запросы и поддерживает постоянное WebSocket-соединение с сервером Google.
* `gson` — превращает JSON-строку (текстовый ответ от Google) в объект Kotlin, из которого удобно доставать поля.
* `security-crypto` — шифрует API-ключ на диске телефона, чтобы его нельзя было украсть.
* `core-ktx`, `appcompat`, `material` — стандартные библиотеки Android для интерфейса.

### Шаг 1.3. Настройка AndroidManifest.xml (разрешения)

Откройте файл `app/src/main/AndroidManifest.xml`. Внутри тега `<manifest>`, **перед** тегом `<application>`, добавьте все необходимые разрешения:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- ═══════════════════════════════════════════════════════ -->
    <!-- РАЗРЕШЕНИЯ (Permissions)                                -->
    <!-- ═══════════════════════════════════════════════════════ -->

    <!-- 1. Запись звука с микрофона — без этого приложение не услышит собеседника -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- 2. Показ окна поверх других приложений — без этого не будет субтитров -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

    <!-- 3. Чтение состояния телефона — чтобы узнать, когда начался/закончился звонок -->
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />

    <!-- 4. Чтение номеров телефона (Android 13+) — замена READ_PHONE_STATE -->
    <uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />

    <!-- 5. Доступ в интернет — для отправки аудио в Google AI Studio -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- 6. Фоновый сервис — чтобы приложение работало во время звонка -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <!-- 7. Защита от принудительного завершения системой -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <!-- 8. Показ уведомлений (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="CallSub"
        android:supportsRtl="true"
        android:theme="@style/Theme.CallSub">

        <!-- Главный экран настроек -->
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Фоновый сервис перевода -->
        <service
            android:name=".service.CallTranslatorService"
            android:foregroundServiceType="microphone"
            android:exported="false" />

        <!-- Приёмник событий телефонии (автозапуск при звонке) -->
        <receiver
            android:name=".service.CallStateReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.PHONE_STATE" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

**Что значит каждый блок (объяснение для начинающих):**
* `<uses-permission>` — запрос разрешения у системы. Без этих строк Android просто не даст приложению доступ к микрофону, экрану и телефонии.
* `<activity>` — экран, который видит пользователь (настройки, ввод ключа).
* `<service>` — фоновый процесс, который работает, даже когда пользователь перешёл в другое приложение (звонилку). Атрибут `foregroundServiceType="microphone"` говорит Android: «Этот сервис использует микрофон, не убивай его».
* `<receiver>` — слушатель системных событий. Атрибут `android:exported="true"` говорит: «Система имеет право присылать нам события телефонии».

### Шаг 1.4. Создание структуры пакетов (папок)

В панели Project (слева в Android Studio) раскройте `app/src/main/java/com/callsub/translator/`. Кликните правой кнопкой → **New → Package** и создайте следующие пакеты:

```
com.callsub.translator.audio       ← Всё, что связано со звуком
com.callsub.translator.network     ← Всё, что связано с интернетом и Gemini
com.callsub.translator.service     ← Фоновые сервисы и приёмники событий
com.callsub.translator.ui          ← Экраны и оверлей
com.callsub.translator.tts         ← Озвучка немецких фраз
com.callsub.translator.storage     ← Хранение ключа и логов
```

**Зачем разделять на пакеты:** Чтобы не запутаться. Каждый пакет отвечает за одну область. Если что-то сломалось в звуке — ищите в `audio/`. Проблемы с сетью — в `network/`.

### Шаг 1.5. Запрос разрешений при первом запуске (Runtime Permissions)

Android не даёт разрешения автоматически только потому, что они записаны в манифесте. Для «опасных» разрешений (`RECORD_AUDIO`, `READ_PHONE_STATE`) нужно показать пользователю системный диалог «Разрешить?».

Создайте файл `app/src/main/java/com/callsub/translator/ui/PermissionHelper.kt`:

```kotlin
package com.callsub.translator.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Помощник для запроса всех необходимых разрешений.
 *
 * КАК ЭТО РАБОТАЕТ:
 * 1. Мы проверяем, есть ли уже разрешение (checkSelfPermission).
 * 2. Если нет — показываем системный диалог (requestPermissions).
 * 3. Если пользователь нажал "Не спрашивать больше" — открываем настройки приложения.
 */
object PermissionHelper {

    const val REQUEST_CODE_PERMISSIONS = 1001

    // Список всех разрешений, которые нам нужны
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        // На Android 13+ нужны дополнительные разрешения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    // Проверяем, все ли разрешения уже выданы
    fun hasAllPermissions(activity: Activity): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    // Запрашиваем недостающие разрешения
    fun requestMissingPermissions(activity: Activity) {
        val missing = getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(activity, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    // Проверяем разрешение на показ поверх других приложений (оверлей)
    // Это разрешение запрашивается отдельно — через системные настройки
    fun hasOverlayPermission(activity: Activity): Boolean {
        return Settings.canDrawOverlays(activity)
    }

    // Открываем настройки для выдачи разрешения на оверлей
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    // Открываем страницу настроек приложения (если пользователь нажал "Больше не спрашивать")
    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
        activity.startActivity(intent)
    }
}
```

---

## ЭТАП 2. Аудио-модуль (захват звука с микрофона)

### Шаг 2.1. Что мы делаем и зачем

Когда пользователь разговаривает по телефону на громкой связи, голос немецкого собеседника звучит из нижнего динамика. Микрофон телефона находится рядом и «слышит» этот голос. Наша задача — записать этот звук в цифровом виде (массив чисел) и отправить в Google для перевода.

### Шаг 2.2. Создание AudioSourceProbe (автоматический подбор источника звука)

Разные телефоны по-разному реагируют на запись звука во время звонка. На одних работает `AudioSource.MIC`, на других — `AudioSource.UNPROCESSED`. Мы попробуем каждый и выберем тот, который реально записывает звук.

Создайте файл `app/src/main/java/com/callsub/translator/audio/AudioSourceProbe.kt`:

```kotlin
package com.callsub.translator.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.util.Log
import kotlin.math.sqrt

/**
 * Автоматический подбор источника звука.
 *
 * ПРОБЛЕМА: На некоторых телефонах (Samsung, Huawei) при активном сотовом
 * звонке AudioSource.MIC отдаёт тишину. На других — работает отлично.
 * Заранее предсказать невозможно.
 *
 * РЕШЕНИЕ: Пробуем каждый источник по очереди. Записываем 500 мс.
 * Считаем громкость (RMS). Если громкость выше порога — значит,
 * этот источник слышит звук из динамика. Используем его.
 *
 * ПОРЯДОК ПЕРЕБОРА:
 * 1. MIC — стандартный микрофон, работает на большинстве устройств
 * 2. UNPROCESSED — «сырой» микрофон без обработки (API 24+)
 * 3. DEFAULT — системный источник по умолчанию
 */
object AudioSourceProbe {

    private const val TAG = "AudioSourceProbe"
    private const val SAMPLE_RATE = 16000           // 16 кГц — стандарт для распознавания речи
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private const val PROBE_DURATION_MS = 500        // Время проверки: 0.5 секунды
    private const val RMS_THRESHOLD = 200.0          // Порог: если RMS выше — значит есть звук

    /**
     * Перебирает источники звука и возвращает первый работающий.
     * Вызывайте эту функцию ПОСЛЕ включения громкой связи.
     *
     * @return ID источника (например, AudioSource.MIC) или AudioSource.DEFAULT как fallback.
     */
    fun findBestSource(): Int {
        // Список источников для перебора
        val candidates = mutableListOf(
            AudioSource.MIC,
            AudioSource.DEFAULT
        )

        // UNPROCESSED доступен только на Android 7.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            candidates.add(1, AudioSource.UNPROCESSED) // Вставляем вторым
        }

        for (source in candidates) {
            val sourceName = when (source) {
                AudioSource.MIC -> "MIC"
                AudioSource.UNPROCESSED -> "UNPROCESSED"
                AudioSource.DEFAULT -> "DEFAULT"
                else -> "UNKNOWN($source)"
            }

            try {
                val rms = measureRms(source)
                Log.d(TAG, "Источник $sourceName: RMS = ${"%.1f".format(rms)}")

                if (rms > RMS_THRESHOLD) {
                    Log.i(TAG, "✅ Выбран источник: $sourceName (RMS=${"%.1f".format(rms)})")
                    return source
                }
            } catch (e: Exception) {
                Log.w(TAG, "❌ Источник $sourceName недоступен: ${e.message}")
            }
        }

        // Если ни один не прошёл проверку — используем MIC как наиболее универсальный
        Log.w(TAG, "⚠️ Ни один источник не показал сигнал. Используем MIC по умолчанию.")
        return AudioSource.MIC
    }

    /**
     * Записывает звук из указанного источника в течение PROBE_DURATION_MS
     * и возвращает среднеквадратичную громкость (RMS).
     *
     * RMS (Root Mean Square) — это мера «средней громкости» звука.
     * Тишина ≈ 0–50, тихая речь ≈ 200–500, громкая речь ≈ 1000–5000.
     */
    private fun measureRms(audioSource: Int): Double {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val recorder = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord не удалось инициализировать")
            }

            recorder.startRecording()

            // Сколько сэмплов записать за PROBE_DURATION_MS
            val samplesToRead = (SAMPLE_RATE * PROBE_DURATION_MS) / 1000
            val buffer = ShortArray(samplesToRead)
            val read = recorder.read(buffer, 0, samplesToRead)

            if (read <= 0) return 0.0

            // Вычисляем RMS: корень из среднего квадратов всех значений
            var sumSquares = 0.0
            for (i in 0 until read) {
                sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
            }
            return sqrt(sumSquares / read)
        } finally {
            recorder.stop()
            recorder.release()
        }
    }
}
```

### Шаг 2.3. Создание основного аудио-движка (`AudioCaptureEngine`)

Этот класс непрерывно записывает звук с микрофона и передаёт порции аудиоданных в функцию-обработчик (callback), которая затем отправит их в Google.

Создайте файл `app/src/main/java/com/callsub/translator/audio/AudioCaptureEngine.kt`:

```kotlin
package com.callsub.translator.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Аудио-движок: непрерывно захватывает звук с микрофона и публикует
 * порции PCM-данных через Kotlin Flow.
 *
 * ВАЖНО: AcousticEchoCanceler (AEC) здесь НЕ используется!
 * AEC подавляет звук из динамика — а нам этот звук как раз НУЖЕН,
 * потому что это голос немецкого собеседника.
 *
 * ИСПОЛЬЗУЮТСЯ:
 * - NoiseSuppressor — убирает фоновый шум (шум улицы, вентилятор)
 * - AutomaticGainControl — нормализует громкость
 *
 * КАК ИСПОЛЬЗОВАТЬ:
 *   val engine = AudioCaptureEngine()
 *   engine.start()                    // Начать запись
 *   engine.audioFlow.collect { chunk -> // Получать порции звука
 *       sendToGemini(chunk)
 *   }
 *   engine.stop()                     // Остановить запись
 */
class AudioCaptureEngine {

    companion object {
        private const val TAG = "AudioCaptureEngine"
        const val SAMPLE_RATE = 16000                   // 16 кГц
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_DURATION_MS = 100        // Размер одной порции: 100 мс
    }

    // Flow — это «трубопровод» данных. Другие части программы подписываются
    // на этот Flow и получают каждую новую порцию аудио автоматически.
    private val _audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 50)
    val audioFlow: SharedFlow<ByteArray> = _audioFlow

    private var recorder: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    /**
     * Запускает непрерывную запись звука.
     *
     * ЧТО ПРОИСХОДИТ ВНУТРИ:
     * 1. AudioSourceProbe определяет лучший источник звука.
     * 2. Создаётся AudioRecord с параметрами 16kHz/Mono/16-bit.
     * 3. Включаются NoiseSuppressor и AutomaticGainControl.
     * 4. В фоновом потоке (Coroutine) каждые 100 мс считывается
     *    порция звука и публикуется в audioFlow.
     */
    fun start() {
        if (isRecording) {
            Log.w(TAG, "Запись уже идёт, повторный запуск проигнорирован")
            return
        }

        // Шаг 1: Определяем лучший источник звука для этого телефона
        val audioSource = AudioSourceProbe.findBestSource()

        // Шаг 2: Рассчитываем размер буфера
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = minBufferSize * 2  // Двойной буфер — защита от пропуска данных

        // Шаг 3: Создаём AudioRecord
        recorder = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize).also { rec ->
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "ОШИБКА: AudioRecord не инициализирован. Микрофон заблокирован?")
                return
            }

            val sessionId = rec.audioSessionId

            // Шаг 4: Включаем шумоподавление (если телефон поддерживает)
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                Log.d(TAG, "NoiseSuppressor включён ✅")
            } else {
                Log.d(TAG, "NoiseSuppressor недоступен на этом устройстве")
            }

            // Шаг 5: Включаем автоматическую регулировку громкости
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)
                Log.d(TAG, "AutomaticGainControl включён ✅")
            }
        }

        // Шаг 6: Запускаем запись в фоновом потоке
        isRecording = true
        recorder?.startRecording()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "🎙️ Запись звука начата")

            // Сколько байт в одной порции (100 мс звука)
            // Формула: sample_rate * bytes_per_sample * channels * duration_sec
            // 16000 * 2 * 1 * 0.1 = 3200 байт
            val chunkSize = (SAMPLE_RATE * 2 * CHUNK_DURATION_MS) / 1000
            val buffer = ByteArray(chunkSize)

            while (isActive && isRecording) {
                val bytesRead = recorder?.read(buffer, 0, chunkSize) ?: -1

                if (bytesRead > 0) {
                    // Копируем данные (чтобы буфер не перезаписался) и публикуем
                    val chunk = buffer.copyOf(bytesRead)
                    _audioFlow.emit(chunk)
                }
            }
        }
    }

    /**
     * Останавливает запись и освобождает ресурсы.
     * ОБЯЗАТЕЛЬНО вызывайте при завершении звонка!
     */
    fun stop() {
        isRecording = false
        recordingJob?.cancel()

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка при остановке записи: ${e.message}")
        }

        noiseSuppressor?.release()
        gainControl?.release()
        recorder?.release()

        noiseSuppressor = null
        gainControl = null
        recorder = null

        Log.i(TAG, "🔇 Запись звука остановлена")
    }
}
```

### Шаг 2.4. Создание VAD — детектора пауз (только для REST-режима)

Создайте файл `app/src/main/java/com/callsub/translator/audio/VoiceActivityDetector.kt`:

```kotlin
package com.callsub.translator.audio

import kotlin.math.sqrt

/**
 * Детектор речевой активности (Voice Activity Detector).
 *
 * ЗАЧЕМ: Когда WebSocket недоступен и мы отправляем аудио через REST,
 * нужно разбить непрерывный поток на осмысленные фразы.
 * Мы не хотим отправлять в Google тишину или обрезать фразу посередине.
 *
 * КАК РАБОТАЕТ:
 * 1. Получает порции аудио (ByteArray с PCM 16-bit данными).
 * 2. Вычисляет громкость (RMS) каждой порции.
 * 3. Если громкость выше порога — значит, кто-то говорит (накапливаем).
 * 4. Если громкость ниже порога дольше 400 мс — значит, пауза (отдаём фразу).
 * 5. Если накопилось больше 2.5 секунд речи — принудительно отдаём (защита от монолога).
 *
 * ИСПОЛЬЗУЕТСЯ ТОЛЬКО В РЕЖИМЕ REST FALLBACK.
 * В режиме WebSocket Live API этот класс НЕ нужен — Google определяет паузы сам.
 */
class VoiceActivityDetector(
    private val onPhraseReady: (ByteArray) -> Unit  // Callback: вызывается, когда фраза готова
) {
    companion object {
        private const val RMS_SPEECH_THRESHOLD = 250.0  // Порог: выше = речь, ниже = тишина
        private const val SILENCE_TIMEOUT_MS = 400       // Длительность паузы для отсечки фразы
        private const val MAX_PHRASE_DURATION_MS = 2500   // Максимальная длина фразы
        private const val MIN_PHRASE_DURATION_MS = 500    // Минимальная длина (защита от щелчков)
        private const val CHUNK_DURATION_MS = 100         // Длительность одной порции в мс
    }

    private val phraseBuffer = mutableListOf<ByteArray>()  // Накопитель порций текущей фразы
    private var silenceChunks = 0                          // Счётчик «тихих» порций подряд
    private var totalDurationMs = 0                        // Общая длительность накопленного

    /**
     * Подайте сюда каждую порцию аудио (100 мс PCM 16-bit).
     * Когда набирается целая фраза — вызовется onPhraseReady().
     */
    fun feed(chunk: ByteArray) {
        val rms = calculateRms(chunk)

        if (rms > RMS_SPEECH_THRESHOLD) {
            // Обнаружена речь — накапливаем
            silenceChunks = 0
            phraseBuffer.add(chunk)
            totalDurationMs += CHUNK_DURATION_MS

            // Если фраза слишком длинная — принудительно отдаём
            if (totalDurationMs >= MAX_PHRASE_DURATION_MS) {
                flush()
            }
        } else {
            // Тишина
            if (phraseBuffer.isNotEmpty()) {
                silenceChunks++

                // Если тишина длится дольше порога — фраза закончилась
                val silenceDurationMs = silenceChunks * CHUNK_DURATION_MS
                if (silenceDurationMs >= SILENCE_TIMEOUT_MS) {
                    flush()
                }
            }
        }
    }

    /**
     * Собирает все накопленные порции в один массив байт и отдаёт через callback.
     */
    private fun flush() {
        if (totalDurationMs < MIN_PHRASE_DURATION_MS) {
            // Слишком короткая «фраза» — скорее всего, щелчок или шорох. Выбрасываем.
            phraseBuffer.clear()
            silenceChunks = 0
            totalDurationMs = 0
            return
        }

        // Склеиваем все порции в один массив
        val totalBytes = phraseBuffer.sumOf { it.size }
        val result = ByteArray(totalBytes)
        var offset = 0
        for (part in phraseBuffer) {
            part.copyInto(result, offset)
            offset += part.size
        }

        // Очищаем буфер для следующей фразы
        phraseBuffer.clear()
        silenceChunks = 0
        totalDurationMs = 0

        // Отдаём готовую фразу
        onPhraseReady(result)
    }

    /**
     * Вычисляет RMS (среднеквадратичную громкость) массива PCM 16-bit данных.
     *
     * PCM 16-bit: каждые 2 байта = одно число (sample) от -32768 до +32767.
     * Тишина ≈ 0, шёпот ≈ 100–300, нормальная речь ≈ 500–3000.
     */
    private fun calculateRms(pcmBytes: ByteArray): Double {
        if (pcmBytes.size < 2) return 0.0

        var sumSquares = 0.0
        val sampleCount = pcmBytes.size / 2

        for (i in 0 until sampleCount) {
            // Декодируем 2 байта в одно 16-битное число (Little-Endian)
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt()
            val sample = (high shl 8) or low  // Число от -32768 до +32767
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        return sqrt(sumSquares / sampleCount)
    }
}
```

---

## ЭТАП 3. Сетевой модуль — подключение к Google AI Studio (WebSocket)

### Шаг 3.1. Получение API-ключа (инструкция для пользователя)

Прежде чем писать код, вам (или пользователю вашего приложения) нужен API-ключ:

1. Откройте в браузере: **https://aistudio.google.com**
2. Войдите с помощью Google-аккаунта (обычный Gmail).
3. Нажмите кнопку **«Get API key»** (или «Получить ключ API»).
4. Нажмите **«Create API key»**.
5. Скопируйте ключ — он выглядит так: `AIzaSyB1a2C3d4E5f6G7h8I9j0K...` (около 40 символов).
6. Банковская карта **НЕ требуется**. Бесплатного лимита хватает на десятки минут разговоров.

### Шаг 3.2. Безопасное хранение API-ключа

Создайте файл `app/src/main/java/com/callsub/translator/storage/SecureKeyStorage.kt`:

```kotlin
package com.callsub.translator.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Безопасное хранилище API-ключа.
 *
 * ЗАЧЕМ ШИФРОВАТЬ: Если хранить ключ в обычных SharedPreferences,
 * любое приложение с root-доступом сможет его прочитать и использовать
 * ваш лимит Google AI Studio. EncryptedSharedPreferences шифрует
 * данные с помощью аппаратного модуля безопасности (Android Keystore).
 */
class SecureKeyStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        // Создаём или получаем мастер-ключ из Android Keystore
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Создаём зашифрованные SharedPreferences
        prefs = EncryptedSharedPreferences.create(
            context,
            "callsub_secure_prefs",          // Имя файла
            masterKey,                        // Ключ шифрования
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Сохраняет API-ключ Google AI Studio */
    fun saveApiKey(apiKey: String) {
        prefs.edit().putString("gemini_api_key", apiKey).apply()
    }

    /** Возвращает API-ключ или null, если не сохранён */
    fun getApiKey(): String? {
        return prefs.getString("gemini_api_key", null)
    }

    /** Проверяет, похож ли ключ на настоящий (начинается с AIzaSy и длина > 30) */
    fun isApiKeyValid(): Boolean {
        val key = getApiKey() ?: return false
        return key.startsWith("AIzaSy") && key.length > 30
    }
}
```

### Шаг 3.3. WebSocket-клиент для Gemini Multimodal Live API

Это **самый важный файл** в проекте. Он устанавливает постоянное WebSocket-соединение с Google и гонит туда аудиопоток, а обратно получает текст перевода.

Создайте файл `app/src/main/java/com/callsub/translator/network/GeminiLiveClient.kt`:

```kotlin
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
 * WebSocket-клиент для Gemini Multimodal Live API.
 *
 * КАК ЭТО РАБОТАЕТ:
 * 1. При вызове connect() — устанавливается WebSocket-соединение с Google.
 * 2. Первым сообщением отправляется «setup» — конфигурация модели + системный промпт.
 * 3. Google отвечает подтверждением setup.
 * 4. После этого мы начинаем непрерывно отправлять порции аудио (метод sendAudio).
 * 5. Google в реальном времени присылает текстовые токены перевода.
 * 6. Мы публикуем текст через subtitleFlow — и оверлей его показывает.
 *
 * СОСТОЯНИЯ СОЕДИНЕНИЯ:
 * DISCONNECTED → CONNECTING → SETUP_SENT → ACTIVE → RECONNECTING → ...
 */
class GeminiLiveClient(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage" +
            ".v1alpha.GenerativeService.BidiGenerateContent"
        private const val MODEL = "models/gemini-3.1-flash-lite"
        private const val SYSTEM_PROMPT =
            "Ты — мгновенный синхронный переводчик немецких телефонных звонков " +
            "на русский язык. На вход поступает живое аудио немецкой речи собеседника. " +
            "Твоя задача — непрерывно возвращать в текстовом потоке ТОЛЬКО краткий и " +
            "точный перевод услышанного на русский язык в виде субтитров. " +
            "Не добавляй никаких комментариев, вводных слов и рассуждений. " +
            "Только перевод на русский."
    }

    // Текстовый поток субтитров (подписывайтесь на него из оверлея)
    private val _subtitleFlow = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val subtitleFlow: SharedFlow<String> = _subtitleFlow

    // Текущее состояние соединения
    enum class State { DISCONNECTED, CONNECTING, SETUP_SENT, ACTIVE, RECONNECTING }
    private val _stateFlow = MutableSharedFlow<State>(replay = 1, extraBufferCapacity = 5)
    val stateFlow: SharedFlow<State> = _stateFlow

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var currentState = State.DISCONNECTED

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)    // WebSocket не имеет таймаута чтения
        .pingInterval(15, TimeUnit.SECONDS)       // Отправляем ping каждые 15 сек
        .build()

    /**
     * Устанавливает WebSocket-соединение с Google AI Studio.
     */
    fun connect() {
        if (currentState != State.DISCONNECTED) {
            Log.w(TAG, "Уже подключен или подключаемся. Текущее состояние: $currentState")
            return
        }

        updateState(State.CONNECTING)

        val url = "$WS_ENDPOINT?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "✅ WebSocket подключён!")
                sendSetup(webSocket)
                updateState(State.SETUP_SENT)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket ошибка: ${t.message}")
                updateState(State.DISCONNECTED)
                // TODO: Здесь можно добавить автоматический реконнект
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket закрыт: $reason")
                updateState(State.DISCONNECTED)
            }
        })
    }

    /**
     * Отправляет конфигурацию модели (setup) — первое сообщение после подключения.
     * Пока setup не подтверждён, аудио отправлять нельзя.
     */
    private fun sendSetup(ws: WebSocket) {
        val setup = JsonObject().apply {
            add("setup", JsonObject().apply {
                addProperty("model", MODEL)
                add("generationConfig", JsonObject().apply {
                    add("responseModalities", gson.toJsonTree(listOf("TEXT")))
                    addProperty("temperature", 0.1)
                })
                add("systemInstruction", JsonObject().apply {
                    add("parts", gson.toJsonTree(listOf(mapOf("text" to SYSTEM_PROMPT))))
                })
            })
        }

        val json = gson.toJson(setup)
        ws.send(json)
        Log.d(TAG, "📤 Setup отправлен")
    }

    /**
     * Обрабатывает входящее сообщение от Google.
     *
     * Google присылает JSON-объекты. Нас интересуют:
     * - setupComplete — подтверждение setup, можно начинать стримить аудио
     * - serverContent.modelTurn.parts[].text — текст перевода (субтитры)
     */
    private fun handleServerMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject

            // Подтверждение setup — теперь можно стримить аудио
            if (json.has("setupComplete")) {
                Log.i(TAG, "✅ Setup подтверждён. Готов к стримингу аудио.")
                updateState(State.ACTIVE)
                return
            }

            // Текстовый ответ модели (субтитры)
            if (json.has("serverContent")) {
                val serverContent = json.getAsJsonObject("serverContent")
                val modelTurn = serverContent?.getAsJsonObject("modelTurn")
                val parts = modelTurn?.getAsJsonArray("parts")

                parts?.forEach { part ->
                    val partObj = part.asJsonObject
                    if (partObj.has("text")) {
                        val subtitle = partObj.get("text").asString
                        if (subtitle.isNotBlank()) {
                            _subtitleFlow.tryEmit(subtitle)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка парсинга ответа: ${e.message}")
        }
    }

    /**
     * Отправляет порцию аудиоданных (PCM 16-bit) в Google.
     *
     * ФОРМАТ СООБЩЕНИЯ:
     * {
     *   "realtimeInput": {
     *     "mediaChunks": [{
     *       "mimeType": "audio/pcm",
     *       "data": "<base64-encoded PCM bytes>"
     *     }]
     *   }
     * }
     *
     * @param pcmData — массив байт PCM 16-bit, 16 kHz, Mono
     */
    fun sendAudio(pcmData: ByteArray) {
        if (currentState != State.ACTIVE) return

        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)

        val message = JsonObject().apply {
            add("realtimeInput", JsonObject().apply {
                add("mediaChunks", gson.toJsonTree(listOf(
                    mapOf(
                        "mimeType" to "audio/pcm",
                        "data" to base64Audio
                    )
                )))
            })
        }

        webSocket?.send(gson.toJson(message))
    }

    /**
     * Закрывает WebSocket-соединение. Вызывайте при завершении звонка.
     */
    fun disconnect() {
        webSocket?.close(1000, "Call ended")
        webSocket = null
        updateState(State.DISCONNECTED)
        Log.i(TAG, "🔌 WebSocket отключён")
    }

    private fun updateState(newState: State) {
        currentState = newState
        _stateFlow.tryEmit(newState)
    }
}
```

---

## ЭТАП 4. Плавающий оверлей субтитров

### Шаг 4.1. Создание XML-разметки окна субтитров

Создайте файл `app/src/main/res/layout/overlay_subtitles.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
    Разметка плавающего окна субтитров.
    Это окно рисуется ПОВЕРХ экрана звонка (поверх стандартной звонилки).

    Структура:
    ┌──────────────────────────────────────┐
    │ 🟢  🇩🇪 Ihr Termin ist morgen...     │ ← серый мелкий шрифт
    │     🇷🇺 Ваш приём завтра...          │ ← крупный белый шрифт
    │     [Аа] [🔊 Langsamer] [—]         │ ← кнопки управления
    └──────────────────────────────────────┘
-->
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/overlay_root"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="#CC121212"
    android:padding="12dp"
    android:elevation="8dp">

    <!-- Индикатор состояния + Немецкий оригинал -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <!-- Цветной кружок состояния -->
        <View
            android:id="@+id/status_indicator"
            android:layout_width="10dp"
            android:layout_height="10dp"
            android:background="@drawable/circle_green"
            android:layout_marginEnd="8dp" />

        <!-- Немецкий текст (оригинал) -->
        <TextView
            android:id="@+id/tv_german"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textColor="#AAAAAA"
            android:textSize="13sp"
            android:maxLines="2"
            android:ellipsize="end"
            android:text="Warte auf Sprache..." />
    </LinearLayout>

    <!-- Русский перевод (главный текст, крупный шрифт) -->
    <TextView
        android:id="@+id/tv_russian"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:textColor="#FFFFFF"
        android:textSize="20sp"
        android:textStyle="bold"
        android:paddingTop="4dp"
        android:paddingBottom="8dp"
        android:maxLines="4"
        android:text="Ожидание речи собеседника..." />

    <!-- Панель кнопок -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="end">

        <!-- Кнопка смены размера шрифта -->
        <TextView
            android:id="@+id/btn_font_size"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Аа"
            android:textColor="#FFEB3B"
            android:textSize="14sp"
            android:padding="8dp"
            android:clickable="true"
            android:focusable="true" />

        <!-- Кнопка «Говорите медленнее» -->
        <TextView
            android:id="@+id/btn_langsamer"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="🔊 Langsamer"
            android:textColor="#FFEB3B"
            android:textSize="14sp"
            android:padding="8dp"
            android:layout_marginStart="8dp"
            android:clickable="true"
            android:focusable="true" />

        <!-- Кнопка сворачивания -->
        <TextView
            android:id="@+id/btn_minimize"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="—"
            android:textColor="#FFEB3B"
            android:textSize="18sp"
            android:padding="8dp"
            android:layout_marginStart="8dp"
            android:clickable="true"
            android:focusable="true" />
    </LinearLayout>
</LinearLayout>
```

### Шаг 4.2. Создание drawable для индикатора

Создайте файл `app/src/main/res/drawable/circle_green.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#4CAF50" />
    <size android:width="10dp" android:height="10dp" />
</shape>
```

### Шаг 4.3. Менеджер плавающего окна (`OverlayManager`)

Создайте файл `app/src/main/java/com/callsub/translator/ui/OverlayManager.kt`:

```kotlin
package com.callsub.translator.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.TextView
import com.callsub.translator.R

/**
 * Менеджер плавающего окна субтитров.
 *
 * ПОКАЗЫВАЕТ полупрозрачное окно с субтитрами ПОВЕРХ экрана звонка.
 * Окно можно перетаскивать пальцем (Drag & Drop).
 *
 * КАК ИСПОЛЬЗОВАТЬ:
 *   val overlay = OverlayManager(context)
 *   overlay.show()                             // Показать окно
 *   overlay.updateSubtitles("Немецкий", "Русский")  // Обновить текст
 *   overlay.hide()                             // Скрыть окно
 */
class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvGerman: TextView? = null
    private var tvRussian: TextView? = null
    private var statusIndicator: View? = null

    // Текущий масштаб шрифта (циклически: 18 → 24 → 30 → 18)
    private var currentFontSize = 20f
    private val fontSizes = floatArrayOf(18f, 22f, 28f)
    private var fontSizeIndex = 0

    /**
     * Показывает плавающее окно на экране.
     * Требует разрешения SYSTEM_ALERT_WINDOW.
     */
    fun show() {
        if (overlayView != null) return  // Уже показан

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // «Надуваем» (inflate) XML-разметку в реальный View-объект
        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_subtitles, null)

        // Находим элементы по ID
        tvGerman = overlayView?.findViewById(R.id.tv_german)
        tvRussian = overlayView?.findViewById(R.id.tv_russian)
        statusIndicator = overlayView?.findViewById(R.id.status_indicator)

        // Параметры окна: тип, размер, флаги
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,     // Ширина: во весь экран
            WindowManager.LayoutParams.WRAP_CONTENT,     // Высота: по содержимому
            // Тип окна: поверх всех приложений
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // Флаги:
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or  // Не перехватывать ввод
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or  // Поверх статус-бара
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,     // Клики «проходят сквозь»
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 200  // Отступ от нижнего края (чтобы не перекрывать кнопку «Завершить»)
        }

        // Добавляем Drag & Drop (перетаскивание пальцем)
        setupDragAndDrop(params)

        // Настраиваем кнопки
        setupButtons()

        // Добавляем окно на экран
        windowManager?.addView(overlayView, params)
    }

    /**
     * Обновляет текст субтитров на экране.
     *
     * @param germanText — оригинальная немецкая фраза (или пустая строка)
     * @param russianText — русский перевод
     */
    fun updateSubtitles(germanText: String, russianText: String) {
        tvGerman?.post {  // post {} гарантирует выполнение в UI-потоке
            if (germanText.isNotBlank()) {
                tvGerman?.text = "🇩🇪 $germanText"
            }
            tvRussian?.text = russianText
        }
    }

    /** Обновляет только русский перевод (для потокового режима) */
    fun appendTranslation(text: String) {
        tvRussian?.post {
            val current = tvRussian?.text?.toString() ?: ""
            tvRussian?.text = if (current == "Ожидание речи собеседника...") text
                              else "$current$text"
        }
    }

    /** Очищает текст для новой фразы */
    fun clearForNewPhrase() {
        tvGerman?.post { tvGerman?.text = "" }
        tvRussian?.post { tvRussian?.text = "" }
    }

    /**
     * Устанавливает цвет индикатора состояния.
     * @param color — например, 0xFF4CAF50 (зелёный) или 0xFFFF5722 (красный)
     */
    fun setStatusColor(color: Int) {
        statusIndicator?.post {
            statusIndicator?.setBackgroundColor(color)
        }
    }

    /** Скрывает окно с экрана и освобождает ресурсы */
    fun hide() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        tvGerman = null
        tvRussian = null
        statusIndicator = null
    }

    // ─── Вспомогательные методы ────────────────────────────────────

    /**
     * Настраивает перетаскивание окна пальцем.
     * При отпускании окно «прилипает» к ближайшему краю.
     */
    private fun setupDragAndDrop(params: WindowManager.LayoutParams) {
        var initialY = 0
        var touchY = 0f

        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.y = initialY - (event.rawY - touchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
    }

    /** Настраивает обработчики кнопок */
    private fun setupButtons() {
        // Кнопка [Аа] — смена размера шрифта
        overlayView?.findViewById<View>(R.id.btn_font_size)?.setOnClickListener {
            fontSizeIndex = (fontSizeIndex + 1) % fontSizes.size
            currentFontSize = fontSizes[fontSizeIndex]
            tvRussian?.textSize = currentFontSize
        }

        // Кнопка [—] — сворачивание (скрытие текста, оставляем только индикатор)
        overlayView?.findViewById<View>(R.id.btn_minimize)?.setOnClickListener {
            val isVisible = tvRussian?.visibility == View.VISIBLE
            tvRussian?.visibility = if (isVisible) View.GONE else View.VISIBLE
            tvGerman?.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Кнопка [Langsamer] — настраивается отдельно через setLangsamCallback()
    }

    /** Устанавливает callback для кнопки «Langsamer» */
    fun setLangsamCallback(callback: () -> Unit) {
        overlayView?.findViewById<View>(R.id.btn_langsamer)?.setOnClickListener {
            callback()
        }
    }
}
```

---

## ЭТАП 5. Главный сервис — сердце приложения

### Шаг 5.1. Foreground Service, объединяющий все модули

Создайте файл `app/src/main/java/com/callsub/translator/service/CallTranslatorService.kt`:

```kotlin
package com.callsub.translator.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callsub.translator.R
import com.callsub.translator.audio.AudioCaptureEngine
import com.callsub.translator.network.GeminiLiveClient
import com.callsub.translator.storage.SecureKeyStorage
import com.callsub.translator.ui.OverlayManager
import kotlinx.coroutines.*

/**
 * Главный фоновый сервис приложения.
 *
 * ОБЪЕДИНЯЕТ ВСЕ МОДУЛИ:
 * 1. AudioCaptureEngine — записывает звук.
 * 2. GeminiLiveClient — отправляет звук в Google и получает перевод.
 * 3. OverlayManager — показывает перевод на экране.
 *
 * ЖИЗНЕННЫЙ ЦИКЛ:
 * - Запускается автоматически при начале звонка (через CallStateReceiver).
 * - Работает как Foreground Service (с уведомлением в шторке).
 * - Останавливается при завершении звонка.
 */
class CallTranslatorService : Service() {

    companion object {
        private const val TAG = "CallTranslatorService"
        private const val NOTIFICATION_CHANNEL_ID = "callsub_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var audioEngine: AudioCaptureEngine? = null
    private var geminiClient: GeminiLiveClient? = null
    private var overlayManager: OverlayManager? = null
    private var serviceScope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null  // Мы не используем bound service

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "═══ Сервис создан ═══")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "═══ Сервис запущен ═══")

        // Шаг 1: Показываем уведомление (обязательно для Foreground Service!)
        startForeground(NOTIFICATION_ID, createNotification())

        // Шаг 2: Получаем API-ключ
        val keyStorage = SecureKeyStorage(this)
        val apiKey = keyStorage.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "API-ключ не найден! Остановка сервиса.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Шаг 3: Инициализируем все модули
        audioEngine = AudioCaptureEngine()
        geminiClient = GeminiLiveClient(apiKey)
        overlayManager = OverlayManager(this)

        // Шаг 4: Показываем оверлей
        overlayManager?.show()
        overlayManager?.setStatusColor(0xFFFFEB3B.toInt()) // Жёлтый: подключаемся

        // Шаг 5: Подключаемся к Google
        geminiClient?.connect()

        // Шаг 6: Запускаем конвейер в фоновом потоке
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        startPipeline()

        return START_STICKY  // Если система убьёт сервис — перезапустить
    }

    /**
     * Запускает главный конвейер обработки:
     * Микрофон → [аудиопорции] → Google → [текст] → Оверлей
     */
    private fun startPipeline() {
        // Поток 1: Слушаем состояние соединения
        serviceScope?.launch {
            geminiClient?.stateFlow?.collect { state ->
                when (state) {
                    GeminiLiveClient.State.ACTIVE -> {
                        Log.i(TAG, "🟢 Соединение активно — запускаем запись")
                        overlayManager?.setStatusColor(0xFF4CAF50.toInt()) // Зелёный
                        audioEngine?.start()
                    }
                    GeminiLiveClient.State.DISCONNECTED -> {
                        overlayManager?.setStatusColor(0xFFFF5722.toInt()) // Красный
                    }
                    else -> {
                        overlayManager?.setStatusColor(0xFFFFEB3B.toInt()) // Жёлтый
                    }
                }
            }
        }

        // Поток 2: Аудио из микрофона → Google
        serviceScope?.launch {
            audioEngine?.audioFlow?.collect { audioChunk ->
                geminiClient?.sendAudio(audioChunk)
            }
        }

        // Поток 3: Субтитры из Google → Оверлей
        serviceScope?.launch {
            geminiClient?.subtitleFlow?.collect { subtitle ->
                overlayManager?.appendTranslation(subtitle)
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "═══ Сервис остановлен ═══")

        // Останавливаем всё в обратном порядке
        serviceScope?.cancel()
        audioEngine?.stop()
        geminiClient?.disconnect()
        overlayManager?.hide()

        audioEngine = null
        geminiClient = null
        overlayManager = null
        serviceScope = null

        super.onDestroy()
    }

    /**
     * Создаёт уведомление для Foreground Service.
     * Без этого уведомления Android убьёт сервис через 5 секунд.
     */
    private fun createNotification(): Notification {
        // Создаём канал уведомлений (обязательно для Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Перевод звонков",
                NotificationManager.IMPORTANCE_LOW  // LOW = без звука
            ).apply {
                description = "CallSub переводит разговор в реальном времени"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CallSub: Перевод активен")
            .setContentText("Идёт синхронный перевод звонка DE → RU")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)  // Нельзя смахнуть
            .build()
    }
}
```

---

## ЭТАП 6. Автозапуск при звонке

### Шаг 6.1. BroadcastReceiver для перехвата событий телефонии

Создайте файл `app/src/main/java/com/callsub/translator/service/CallStateReceiver.kt`:

```kotlin
package com.callsub.translator.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Перехватчик событий телефонии.
 *
 * КОГДА СРАБАТЫВАЕТ:
 * - Входящий звонок: RINGING → OFFHOOK (трубка снята) → IDLE (повесили)
 * - Исходящий звонок: OFFHOOK → IDLE
 *
 * ПРИ OFFHOOK → запускаем CallTranslatorService (перевод начинается)
 * ПРИ IDLE → останавливаем CallTranslatorService (перевод заканчивается)
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Трубка снята — разговор начался!
                Log.i(TAG, "📞 Звонок начался — запускаем перевод")

                val serviceIntent = Intent(context, CallTranslatorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Трубку повесили — разговор окончен
                Log.i(TAG, "📵 Звонок завершён — останавливаем перевод")

                val serviceIntent = Intent(context, CallTranslatorService::class.java)
                context.stopService(serviceIntent)
            }

            TelephonyManager.EXTRA_STATE_RINGING -> {
                Log.d(TAG, "🔔 Входящий звонок (ещё не снята трубка)")
            }
        }
    }
}
```

---

## ЭТАП 7. Экран настроек (MainActivity)

### Шаг 7.1. Простой экран с полем ввода ключа и кнопкой теста

Замените содержимое `app/src/main/java/com/callsub/translator/ui/MainActivity.kt`:

```kotlin
package com.callsub.translator.ui

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.callsub.translator.R
import com.callsub.translator.service.CallTranslatorService
import com.callsub.translator.storage.SecureKeyStorage

class MainActivity : AppCompatActivity() {

    private lateinit var keyStorage: SecureKeyStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        keyStorage = SecureKeyStorage(this)

        val etApiKey = findViewById<EditText>(R.id.et_api_key)
        val btnSave = findViewById<Button>(R.id.btn_save_key)
        val btnTest = findViewById<Button>(R.id.btn_test)
        val tvStatus = findViewById<TextView>(R.id.tv_status)

        // Показываем сохранённый ключ (маскированный)
        keyStorage.getApiKey()?.let { key ->
            etApiKey.setText(key)
            tvStatus.text = "✅ Ключ сохранён"
        }

        // Кнопка «Сохранить ключ»
        btnSave.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.startsWith("AIzaSy") && key.length > 30) {
                keyStorage.saveApiKey(key)
                tvStatus.text = "✅ Ключ сохранён!"
                Toast.makeText(this, "API-ключ сохранён", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "❌ Неверный формат ключа"
                Toast.makeText(this, "Ключ должен начинаться с AIzaSy", Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка «Тест» — запуск сервиса вручную
        btnTest.setOnClickListener {
            if (!keyStorage.isApiKeyValid()) {
                Toast.makeText(this, "Сначала сохраните API-ключ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Проверяем разрешения
            if (!PermissionHelper.hasAllPermissions(this)) {
                PermissionHelper.requestMissingPermissions(this)
                return@setOnClickListener
            }
            if (!PermissionHelper.hasOverlayPermission(this)) {
                PermissionHelper.requestOverlayPermission(this)
                return@setOnClickListener
            }

            // Запускаем сервис перевода
            val intent = Intent(this, CallTranslatorService::class.java)
            startForegroundService(intent)
            Toast.makeText(this, "🎙️ Перевод запущен! Включите громкую связь.", Toast.LENGTH_LONG).show()
        }

        // Запрашиваем разрешения при первом запуске
        if (!PermissionHelper.hasAllPermissions(this)) {
            PermissionHelper.requestMissingPermissions(this)
        }
    }
}
```

### Шаг 7.2. Разметка экрана настроек

Замените содержимое `app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center_horizontal">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="CallSub DE→RU"
        android:textSize="28sp"
        android:textStyle="bold"
        android:layout_marginBottom="8dp" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Синхронный переводчик звонков"
        android:textSize="16sp"
        android:textColor="#888888"
        android:layout_marginBottom="32dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="API-ключ Google AI Studio">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/et_api_key"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword"
            android:singleLine="true" />
    </com.google.android.material.textfield.TextInputLayout>

    <Button
        android:id="@+id/btn_save_key"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="💾 Сохранить ключ"
        android:layout_marginTop="16dp" />

    <TextView
        android:id="@+id/tv_status"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Ключ не сохранён"
        android:textSize="14sp"
        android:layout_marginTop="8dp" />

    <Button
        android:id="@+id/btn_test"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="🎙️ Запустить перевод (тест)"
        android:layout_marginTop="32dp" />

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="📌 Инструкция:\n1. Вставьте ключ из aistudio.google.com\n2. Нажмите «Сохранить»\n3. Позвоните кому-нибудь\n4. Включите громкую связь\n5. Субтитры появятся автоматически"
        android:textSize="14sp"
        android:lineSpacingExtra="4dp"
        android:layout_marginTop="32dp"
        android:padding="16dp"
        android:background="#F5F5F5" />

</LinearLayout>
```

---

## ЭТАП 8. Сборка и тестирование APK

### Шаг 8.1. Компиляция

1. В Android Studio нажмите **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
2. Дождитесь окончания сборки (1–2 минуты).
3. Готовый APK появится в папке: `app/build/outputs/apk/debug/app-debug.apk`.

### Шаг 8.2. Установка на смартфон

**Вариант A (через USB):**
1. Подключите смартфон USB-кабелем к компьютеру.
2. На смартфоне включите **режим разработчика** (Настройки → О телефоне → 7 раз нажмите на «Номер сборки»).
3. Включите **Отладка по USB** (Настройки → Для разработчиков → Отладка по USB).
4. В Android Studio нажмите зелёную кнопку ▶️ (Run).

**Вариант B (через файл):**
1. Скопируйте `app-debug.apk` на телефон (через Telegram, email или файловый менеджер).
2. Откройте APK на телефоне и разрешите установку из неизвестных источников.

### Шаг 8.3. Первый запуск и тестирование

1. Откройте приложение **CallSub**.
2. Выдайте все запрошенные разрешения (микрофон, телефон, оверлей).
3. Вставьте API-ключ из Google AI Studio и нажмите «Сохранить».
4. Нажмите **«🎙️ Запустить перевод (тест)»**.
5. Появится плавающее окно внизу экрана.
6. Включите на YouTube или в браузере любое **видео на немецком языке** на громкой связи.
7. Через 1–2 секунды в окне должен появиться русский перевод.

### Шаг 8.4. Тестирование на реальном звонке

1. Позвоните на любой немецкий автоответчик:
   * **Deutsche Bahn (Справочная):** +49 30 2970
   * **ADAC (Автоклуб):** +49 89 76 76 0
2. Нажмите кнопку **«Громкая связь»** на экране звонка.
3. Наблюдайте субтитры в плавающем окне.
