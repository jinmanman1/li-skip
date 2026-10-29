package com.li.tiaotiao.core

import android.content.Context

object Settings {

    private const val PREF = "li_skip_cfg"

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 总开关 */
    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** 点击前二次确认（毫秒），0 表示不确认。默认 0：确认会让开屏慢半拍 */
    fun confirmDelay(c: Context) = sp(c).getLong("confirm_ms_v2", 0L)
    fun setConfirmDelay(c: Context, v: Long) = sp(c).edit().putLong("confirm_ms_v2", v).apply()

    /** 开屏判定窗口（毫秒）：应用切到前台后多久内算开屏 */
    fun splashWindow(c: Context) = sp(c).getLong("splash_ms", 9000L)

    fun verbose(c: Context) = sp(c).getBoolean("verbose", false)
    fun setVerbose(c: Context, v: Boolean) = sp(c).edit().putBoolean("verbose", v).apply()

    /** 跳过成功时显示悬浮提示 */
    fun toastOnSkip(c: Context) = sp(c).getBoolean("toast_skip", true)
    fun setToastOnSkip(c: Context, v: Boolean) = sp(c).edit().putBoolean("toast_skip", v).apply()

    /** 调试浮层：屏幕上实时显示扫描状态 */
    fun hud(c: Context) = sp(c).getBoolean("hud", false)
    fun setHud(c: Context, v: Boolean) = sp(c).edit().putBoolean("hud", v).apply()
}
