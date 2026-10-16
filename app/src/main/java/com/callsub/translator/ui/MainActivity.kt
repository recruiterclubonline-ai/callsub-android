package com.callsub.translator.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.callsub.translator.R
import com.callsub.translator.service.CallTranslatorService
import com.callsub.translator.storage.SecureKeyStorage
import com.google.android.material.textfield.TextInputEditText

/**
 * Экран управления и первичной настройки приложения CallSub DE-RU.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var keyStorage: SecureKeyStorage
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        keyStorage = SecureKeyStorage(this)

        val etApiKey = findViewById<TextInputEditText>(R.id.et_api_key)
        val btnSaveKey = findViewById<Button>(R.id.btn_save_key)
        val tvKeyStatus = findViewById<TextView>(R.id.tv_key_status)
        val btnToggle = findViewById<Button>(R.id.btn_toggle_service)

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

            if (!isServiceRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                isServiceRunning = true
                btnToggle.text = getString(R.string.btn_stop_test)
                Toast.makeText(this, "Перевод запущен! Включите немецкое аудио на динамике.", Toast.LENGTH_LONG).show()
            } else {
                stopService(serviceIntent)
                isServiceRunning = false
                btnToggle.text = getString(R.string.btn_start_test)
                Toast.makeText(this, "Перевод остановлен.", Toast.LENGTH_SHORT).show()
            }
        }

        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        if (!PermissionHelper.hasAllRuntimePermissions(this)) {
            PermissionHelper.requestMissingPermissions(this)
        }
    }
}
