package com.callsub.translator.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.callsub.translator.R
import com.callsub.translator.tts.QuickPhrasesPlayer

/**
 * Управляет плавающим полупрозрачным окном субтитров через Android WindowManager.
 */
class OverlayManager(
    private val context: Context,
    private val phrasesPlayer: QuickPhrasesPlayer? = null
) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private var tvGerman: TextView? = null
    private var tvRussian: TextView? = null
    private var tvSpeakerIcon: TextView? = null
    private var statusIndicator: View? = null

    private val fontSizes = floatArrayOf(18f, 22f, 28f)
    private var fontIndex = 1

    fun show() {
        synchronized(OverlayManager::class.java) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Очищаем любое ранее открытое окно
            activeOverlayView?.let { oldView ->
                try {
                    windowManager?.removeView(oldView)
                } catch (_: Exception) {}
            }
            activeOverlayView = null

            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.overlay_subtitles, null)
            overlayView = view
            activeOverlayView = view

            tvGerman = view.findViewById(R.id.tv_german)
            tvRussian = view.findViewById(R.id.tv_russian)
            tvSpeakerIcon = view.findViewById(R.id.tv_speaker_icon)
            statusIndicator = view.findViewById(R.id.status_indicator)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 180
            }

            setupDragAndDrop(view, layoutParams)
            setupControls(view)

            try {
                windowManager?.addView(view, layoutParams)
            } catch (e: Exception) {
                Log.e("OverlayManager", "Ошибка добавления оверлея: ${e.message}")
            }
        }
    }

    private fun setupDragAndDrop(view: View, params: WindowManager.LayoutParams) {
        var startY = 0
        var touchDownY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    touchDownY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (touchDownY - event.rawY).toInt()
                    params.y = (startY + delta).coerceAtLeast(50)
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControls(view: View) {
        val btnFontSize = view.findViewById<View>(R.id.btn_font_size)
        val btnMinimize = view.findViewById<View>(R.id.btn_minimize)
        val btnLangsamer = view.findViewById<View>(R.id.btn_langsamer)
        val btnMoment = view.findViewById<View>(R.id.btn_moment)
        val btnJa = view.findViewById<View>(R.id.btn_ja)

        btnFontSize?.let { btn ->
            attachTactileSpringTouch(btn) {
                fontIndex = (fontIndex + 1) % fontSizes.size
                tvRussian?.textSize = fontSizes[fontIndex]
            }
        }

        btnMinimize?.let { btn ->
            attachTactileSpringTouch(btn) {
                val russianTv = tvRussian ?: return@attachTactileSpringTouch
                val isShown = russianTv.visibility == View.VISIBLE
                russianTv.visibility = if (isShown) View.GONE else View.VISIBLE
                tvGerman?.visibility = if (isShown) View.GONE else View.VISIBLE
                (btn as? TextView)?.text = if (isShown) "＋" else "—"
            }
        }

        btnLangsamer?.let { btn ->
            attachTactileSpringTouch(btn) {
                phrasesPlayer?.speakSpeakSlower()
            }
        }

        btnMoment?.let { btn ->
            attachTactileSpringTouch(btn) {
                phrasesPlayer?.speakOneMoment()
            }
        }

        btnJa?.let { btn ->
            attachTactileSpringTouch(btn) {
                phrasesPlayer?.speakJaGenau()
            }
        }

        val btnClose = view.findViewById<View>(R.id.btn_close)
        btnClose?.let { btn ->
            attachTactileSpringTouch(btn) {
                hide()
                try {
                    val serviceIntent = android.content.Intent(context, com.callsub.translator.service.CallTranslatorService::class.java)
                    context.stopService(serviceIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun attachTactileSpringTouch(view: View, action: () -> Unit) {
        view.setOnClickListener {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (_: Exception) {}
            view.animate()
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setDuration(70)
                .withEndAction {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(120)
                        .start()
                    action()
                }
                .start()
        }
    }

    fun updateSubtitles(germanText: String, russianText: String) {
        tvGerman?.post {
            if (germanText.isNotBlank()) tvGerman?.text = germanText
            tvRussian?.text = russianText
        }
    }

    fun appendStreamingTranslation(chunkText: String) {
        tvRussian?.post {
            val current = tvRussian?.text?.toString() ?: ""
            if (current == "Слушаю немецкую речь собеседника...") {
                tvRussian?.text = chunkText
            } else {
                tvRussian?.text = "$current$chunkText"
            }
        }
    }

    fun setSpeakerActive(isRemoteSpeaker: Boolean) {
        tvSpeakerIcon?.post {
            tvSpeakerIcon?.text = if (isRemoteSpeaker) "🔊" else "🎤"
        }
    }

    fun setStatusColor(colorResId: Int) {
        statusIndicator?.post {
            statusIndicator?.background = ContextCompat.getDrawable(context, colorResId)
        }
    }

    fun hide() {
        synchronized(OverlayManager::class.java) {
            val targetView = overlayView ?: activeOverlayView
            targetView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) {}
            }
            activeOverlayView = null
            overlayView = null
            tvGerman = null
            tvRussian = null
            tvSpeakerIcon = null
            statusIndicator = null
        }
    }

    companion object {
        @Volatile
        private var activeOverlayView: View? = null
    }
}
