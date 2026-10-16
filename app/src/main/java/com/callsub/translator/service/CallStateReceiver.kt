package com.callsub.translator.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.callsub.translator.storage.SecureKeyStorage

/**
 * Перехватывает системные события звонков.
 * Автоматически запускает CallTranslatorService при снятии трубки и останавливает при отбое.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val storage = SecureKeyStorage(context)

        if (!storage.isApiKeyConfigured()) {
            Log.d(TAG, "API ключ не настроен, автозапуск пропущен")
            return
        }

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                Log.i(TAG, "Трубка снята (OFFHOOK) -> Запуск сервиса перевода")
                val serviceIntent = Intent(context, CallTranslatorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Звонок завершен (IDLE) -> Остановка сервиса перевода")
                val serviceIntent = Intent(context, CallTranslatorService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }

    companion object {
        private const val TAG = "CallStateReceiver"
    }
}
