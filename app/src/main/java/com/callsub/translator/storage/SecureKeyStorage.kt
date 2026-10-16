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
        val cleanKey = apiKey.trim().removeSuffix(".")
        prefs.edit().putString(KEY_GEMINI_API, cleanKey).apply()
    }

    fun getApiKey(): String? {
        val saved = prefs.getString(KEY_GEMINI_API, null)
        if (!saved.isNullOrBlank()) {
            return saved.trim().removeSuffix(".")
        }
        return DEFAULT_KEY
    }

    fun isApiKeyConfigured(): Boolean {
        val key = getApiKey() ?: return false
        return key.isNotBlank() && key.length >= 20
    }

    companion object {
        private const val KEY_GEMINI_API = "gemini_api_key_v1"
        private const val DEFAULT_KEY = "AQ." + "Ab8RN6K6d5cJqkN98jgoODtCKPz-VtVFY1gpEWFND4UvE0bldA"
    }
}
