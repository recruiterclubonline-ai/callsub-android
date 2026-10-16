package com.callsub.translator.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
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
    private var statusIndicator: View? = null

    private val fontSizes = floatArrayOf(18f, 22f, 28f)
    private var fontIndex = 1

    fun show() {
        if (overlayView != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_subtitles, null)
        overlayView = view

        tvGerman = view.findViewById(R.id.tv_german)
        tvRussian = view.findViewById(R.id.tv_russian)
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

        windowManager?.addView(view, layoutParams)
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
        view.findViewById<View>(R.id.btn_font_size)?.setOnClickListener {
            fontIndex = (fontIndex + 1) % fontSizes.size
            tvRussian?.textSize = fontSizes[fontIndex]
        }

        view.findViewById<View>(R.id.btn_minimize)?.setOnClickListener {
            val russianTv = tvRussian ?: return@setOnClickListener
            val isShown = russianTv.visibility == View.VISIBLE
            russianTv.visibility = if (isShown) View.GONE else View.VISIBLE
            tvGerman?.visibility = if (isShown) View.GONE else View.VISIBLE
        }

        view.findViewById<View>(R.id.btn_langsamer)?.setOnClickListener {
            phrasesPlayer?.speakSpeakSlower()
        }

        view.findViewById<View>(R.id.btn_moment)?.setOnClickListener {
            phrasesPlayer?.speakOneMoment()
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

    fun setStatusColor(colorResId: Int) {
        statusIndicator?.post {
            statusIndicator?.background = ContextCompat.getDrawable(context, colorResId)
        }
    }

    fun hide() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        tvGerman = null
        tvRussian = null
        statusIndicator = null
    }
}
