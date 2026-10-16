package com.callsub.translator.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Безопасное хранилище API-ключа Google AI Studio.
 * Использует аппаратное шифрование Android Keystore (AES-256).
 */
class SecureKeyStorage(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "callsub_secure_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API, apiKey.trim()).apply()
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_GEMINI_API, null)
    }

    fun isApiKeyConfigured(): Boolean {
        val key = getApiKey() ?: return false
        return key.startsWith("AIzaSy") && key.length >= 35
    }

    companion object {
        private const val KEY_GEMINI_API = "gemini_api_key_v1"
    }
}
