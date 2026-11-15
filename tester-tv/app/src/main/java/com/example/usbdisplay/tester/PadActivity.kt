package com.example.usbdisplay.tester

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.usbdisplay.pad.PadProtocol
import com.example.usbdisplay.pad.PadServer

/**
 * Режим телефона: чёрный экран, который работает как тачпад для курсора на ТВ. Геймпад телефона остаётся
 * на телефоне (на ТВ всё управляется только пультом). Экран тёмный (минимальная яркость), поэтому на AMOLED он «как выключенный».
 *
 * Жесты: один палец — двигать курсор; короткое касание — клик; удержание ≈0,45 с и движение — перетаскивание;
 * два пальца — прокрутка; быстрое касание тремя пальцами — «Назад».
 */
class PadActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var hint: TextView

    private var maxPtr = 0
    private var downT = 0L
    private var sx = 0f
    private var sy = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastAvgY = 0f
    private var moved = false
    private var dragging = false

    private val longPress = Runnable {
        if (!moved && maxPtr == 1) {
            dragging = true
            PadServer.send(PadProtocol.DOWN)
            buzz()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
        hideSystemUi()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        hint = TextView(this).apply {
            setTextColor(Color.parseColor("#555B63"))
            textSize = 15f
            gravity = Gravity.CENTER
            text = "ДопЭкран · тачпад\nЖду подключения к ТВ…"
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)

        PadServer.start()
        PadServer.listener = { on ->
            h.post {
                hint.text = if (on) "Подключено к ТВ" else "ТВ отключился.\nЖду подключения…"
                hint.alpha = 1f
                if (on) hint.animate().alpha(0f).setStartDelay(2500).setDuration(600).start()
            }
        }
        if (PadServer.connected) hint.alpha = 0f
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onDestroy() {
        PadServer.listener = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // нет вибромотора
        }
    }

    // ------------------------------------------------------------ тачпад

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private fun move(dx: Float, dy: Float) {
        val ix = (dx * 8).toInt().coerceIn(-32768, 32767)
        val iy = (dy * 8).toInt().coerceIn(-32768, 32767)
        if (ix == 0 && iy == 0) return
        PadServer.send(PadProtocol.MOVE, PadProtocol.i16(ix), PadProtocol.i16(iy))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                maxPtr = 1
                downT = e.eventTime
                sx = e.x
                sy = e.y
                lastX = e.x
                lastY = e.y
                moved = false
                dragging = false
                h.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPtr = maxOf(maxPtr, e.pointerCount)
                h.removeCallbacks(longPress)
                lastAvgY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && maxPtr == 1) {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    if (!moved && Math.hypot((e.x - sx).toDouble(), (e.y - sy).toDouble()) > dp(10f)) {
                        moved = true
                        h.removeCallbacks(longPress)
                    }
                    move(dx, dy)
                } else if (e.pointerCount == 2) {
                    val a = avgY(e)
                    val d = a - lastAvgY
                    lastAvgY = a
                    moved = true
                    val id = (d * 8).toInt().coerceIn(-32768, 32767)
                    if (id != 0) PadServer.send(PadProtocol.SCROLL, PadProtocol.i16(id))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // оставшийся палец не должен «прыгать» курсором
                val idx = if (e.actionIndex == 0) 1 else 0
                if (idx < e.pointerCount) {
                    lastX = e.getX(idx)
                    lastY = e.getY(idx)
                    lastAvgY = lastY
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longPress)
                if (dragging) {
                    PadServer.send(PadProtocol.UP)
                } else if (!moved && e.eventTime - downT < 300) {
                    when (maxPtr) {
                        1 -> PadServer.send(PadProtocol.TAP)
                        3 -> PadServer.send(PadProtocol.BACK)
                    }
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longPress)
                if (dragging) PadServer.send(PadProtocol.UP)
                dragging = false
            }
        }
        return true
    }
}
