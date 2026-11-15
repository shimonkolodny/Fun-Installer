package com.funinstaller.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The small floating card shown while a job runs: what Claude is doing, the
 * running cost, questions for the user, and a Stop button. It's drawn as an
 * accessibility overlay, so it needs no extra permission. Drag it by its title.
 */
class Overlay(private val service: AccessibilityService) {
    data class Button(val text: String, val onClick: () -> Unit)

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var buttonRow: LinearLayout
    private val params = WindowManager.LayoutParams(
        dp(260), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.START or Gravity.TOP
        x = dp(8)
        y = dp(120)
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): LinearLayout {
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(238, 28, 27, 34))
            }
            elevation = dp(6).toFloat()
        }
        title = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        body = TextView(service).apply {
            setTextColor(Color.argb(230, 235, 235, 240))
            textSize = 13f
            maxLines = 10
            setPadding(0, dp(4), 0, dp(4))
        }
        buttonRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        card.addView(title)
        card.addView(body)
        card.addView(buttonRow)

        // Drag the card by its title so it never blocks what you need to see.
        var startY = 0f
        var startParamY = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startParamY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (startParamY + (e.rawY - startY)).toInt().coerceAtLeast(0)
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                else -> false
            }
        }
        return card
    }

    private fun button(b: Button, primary: Boolean): TextView = TextView(service).apply {
        text = b.text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.WHITE else Color.argb(255, 200, 190, 255))
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (primary) Color.argb(255, 109, 74, 255) else Color.TRANSPARENT)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.marginStart = dp(6)
        layoutParams = lp
        setOnClickListener { b.onClick() }
    }

    /** Show or update the card. The last button is drawn as the main one. */
    fun show(heading: String, message: String, buttons: List<Button>) = main.post {
        if (root == null) {
            root = build()
            runCatching { wm.addView(root, params) }
        }
        title.text = heading
        body.text = message
        body.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        buttonRow.removeAllViews()
        buttons.forEachIndexed { i, b -> buttonRow.addView(button(b, i == buttons.lastIndex)) }
        root?.visibility = View.VISIBLE
    }

    fun hide() = main.post {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    /** Temporarily hide (for screenshots, or taps that land under the card). */
    fun setHidden(hidden: Boolean) = main.post {
        root?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** Where the card is on screen, or an empty rect when it isn't shown. */
    fun bounds(): Rect {
        val r = Rect()
        val v = root ?: return r
        if (v.visibility != View.VISIBLE) return r
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        r.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        return r
    }
}
