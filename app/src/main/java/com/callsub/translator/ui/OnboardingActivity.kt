package com.callsub.translator.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.callsub.translator.R

/**
 * Обучающий экран первого запуска (Onboarding Wizard).
 */
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val btnPermissions = findViewById<Button>(R.id.btn_grant_permissions)
        val btnFinish = findViewById<Button>(R.id.btn_finish_onboarding)

        btnPermissions.setOnClickListener {
            if (!PermissionHelper.hasAllRuntimePermissions(this)) {
                PermissionHelper.requestMissingPermissions(this)
            }
            if (!PermissionHelper.hasOverlayPermission(this)) {
                PermissionHelper.requestOverlayPermission(this)
            }
        }

        btnFinish.setOnClickListener {
            val prefs = getSharedPreferences("callsub_app_prefs", MODE_PRIVATE)
            prefs.edit().putBoolean("onboarding_completed", true).apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
