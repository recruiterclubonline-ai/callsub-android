package com.callsub.translator.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.callsub.translator.R
import com.callsub.translator.service.CallTranslatorService
import com.callsub.translator.storage.SecureKeyStorage
import com.callsub.translator.test.MicTestEngine
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/**
 * Главный экран приложения: настройка API-ключа, диагностика микрофона и доступ к истории звонков.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyStorage: SecureKeyStorage
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверка первого запуска
        val prefs = getSharedPreferences("callsub_app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("onboarding_completed", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        keyStorage = SecureKeyStorage(this)

        val etApiKey = findViewById<TextInputEditText>(R.id.et_api_key)
        val btnSaveKey = findViewById<Button>(R.id.btn_save_key)
        val tvKeyStatus = findViewById<TextView>(R.id.tv_key_status)
        val btnToggle = findViewById<Button>(R.id.btn_toggle_service)
        val btnOpenHistory = findViewById<Button>(R.id.btn_open_history)
        val btnRunMicTest = findViewById<Button>(R.id.btn_run_mic_test)
        val tvMicTestStatus = findViewById<TextView>(R.id.tv_mic_test_status)
        val btnReopenOnboarding = findViewById<Button>(R.id.btn_reopen_onboarding)

        val existingKey = keyStorage.getApiKey()
        if (!existingKey.isNullOrBlank()) {
            etApiKey.setText(existingKey)
            tvKeyStatus.text = getString(R.string.status_key_saved)
            tvKeyStatus.setTextColor(getColor(R.color.status_green))
        }

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text?.toString()?.trim() ?: ""
            if (key.startsWith("AIzaSy") && key.length >= 35) {
                keyStorage.saveApiKey(key)
                tvKeyStatus.text = getString(R.string.status_key_saved)
                tvKeyStatus.setTextColor(getColor(R.color.status_green))
                Toast.makeText(this, "API-ключ надёжно сохранён!", Toast.LENGTH_SHORT).show()
            } else {
                tvKeyStatus.text = "Ключ должен начинаться с AIzaSy... (длина от 35 символов)"
                tvKeyStatus.setTextColor(getColor(R.color.status_red))
            }
        }

        btnOpenHistory.setOnClickListener {
            startActivity(Intent(this, TranscriptViewActivity::class.java))
        }

        btnReopenOnboarding.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        btnRunMicTest.setOnClickListener {
            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                PermissionHelper.requestMissingPermissions(this)
                return@setOnClickListener
            }

            val tester = MicTestEngine(this)
            btnRunMicTest.isEnabled = false

            lifecycleScope.launch {
                val result = tester.runMicAndApiTest { progressMsg ->
                    runOnUiThread {
                        tvMicTestStatus.text = progressMsg
                    }
                }

                btnRunMicTest.isEnabled = true
                when (result) {
                    is MicTestEngine.TestResult.Success -> {
                        tvMicTestStatus.text = "✅ Тест пройден! Микрофон и Google AI работают идеально."
                        tvMicTestStatus.setTextColor(getColor(R.color.status_green))
                    }
                    is MicTestEngine.TestResult.LowSignal -> {
                        tvMicTestStatus.text = "⚠️ Звук слишком тихий (RMS: ${"%.1f".format(result.rms)}). Говорите громче или используйте гарнитуру."
                        tvMicTestStatus.setTextColor(getColor(R.color.accent_yellow))
                    }
                    is MicTestEngine.TestResult.ApiKeyMissing -> {
                        tvMicTestStatus.text = "❌ Сохраните сначала API-ключ Gemini выше."
                        tvMicTestStatus.setTextColor(getColor(R.color.status_red))
                    }
                    is MicTestEngine.TestResult.ApiError -> {
                        tvMicTestStatus.text = "❌ Ошибка API: ${result.message}"
                        tvMicTestStatus.setTextColor(getColor(R.color.status_red))
                    }
                    is MicTestEngine.TestResult.Error -> {
                        tvMicTestStatus.text = "❌ Сбой: ${result.exception.message}"
                        tvMicTestStatus.setTextColor(getColor(R.color.status_red))
                    }
                }
            }
        }

        btnToggle.setOnClickListener {
            if (!keyStorage.isApiKeyConfigured()) {
                Toast.makeText(this, "Сначала сохраните корректный API-ключ", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                PermissionHelper.requestMissingPermissions(this)
                return@setOnClickListener
            }

            if (!PermissionHelper.hasOverlayPermission(this)) {
                Toast.makeText(this, "Разрешите показ поверх других приложений для субтитров", Toast.LENGTH_LONG).show()
                PermissionHelper.requestOverlayPermission(this)
                return@setOnClickListener
            }

            val serviceIntent = Intent(this, CallTranslatorService::class.java)

            if (!CallTranslatorService.isServiceActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                btnToggle.text = getString(R.string.btn_stop_test)
                Toast.makeText(this, "Перевод запущен! Включите немецкое аудио на динамике.", Toast.LENGTH_LONG).show()
            } else {
                stopService(serviceIntent)
                btnToggle.text = getString(R.string.btn_start_test)
                Toast.makeText(this, "Перевод остановлен.", Toast.LENGTH_SHORT).show()
            }
        }

        checkInitialPermissions()
    }

    override fun onResume() {
        super.onResume()
        findViewById<Button>(R.id.btn_toggle_service)?.text =
            if (CallTranslatorService.isServiceActive)
                getString(R.string.btn_stop_test)
            else
                getString(R.string.btn_start_test)
    }

    private fun checkInitialPermissions() {
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            PermissionHelper.requestMissingPermissions(this)
        }
    }
}
