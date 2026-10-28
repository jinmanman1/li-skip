package com.li.tiaotiao.core

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 跳过成功的悬浮提示。用无障碍悬浮窗画，比 Toast 可靠（后台也能显示），
 * 配色跟 App 统一：深底 + 金边框 + 奶白字。
 */
class SkipTip(private val svc: SkipService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideTask = Runnable { hide() }

    fun show(text: String) {
        val tv = view ?: createView()
        tv.text = text
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, 1700)
    }

    private fun createView(): TextView {
        val ctx: Context = svc
        val tv = TextView(ctx).apply {
            setTextColor(0xFFF4EDE4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xF516100C.toInt())
                setStroke(dp(1), 0xFFD9A54A.toInt())
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(150)
        }
        runCatching { wm.addView(tv, lp) }
        view = tv
        return tv
    }

    private fun hide() {
        view?.let { v -> runCatching { wm.removeView(v) } }
        view = null
    }

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()
}
