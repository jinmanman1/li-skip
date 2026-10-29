package com.li.tiaotiao.core

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 调试浮层：一行小字显示「扫了几次 / 哪个 App / 几个节点 / 命中几次」。
 * 打开后你在广告页上就能直接看到服务到底有没有在工作，不用来回切 App 看日志。
 */
class SkipHud(private val svc: SkipService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var tv: TextView? = null

    fun show(text: String) {
        val v = tv ?: create()
        v.text = text
    }

    fun hide() {
        tv?.let { runCatching { wm.removeView(it) } }
        tv = null
    }

    private fun create(): TextView {
        val ctx: Context = svc
        val v = TextView(ctx).apply {
            setTextColor(0xFFFFD79A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xCC000000.toInt())
                setStroke(dp(1), 0x66D9A54A.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(60)
        }
        runCatching { wm.addView(v, lp) }
        tv = v
        return v
    }

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()
}
