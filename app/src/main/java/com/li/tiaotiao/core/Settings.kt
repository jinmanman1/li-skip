package com.li.tiaotiao.core

import android.content.Context

object Settings {

    private const val PREF = "li_skip_cfg"

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 总开关 */
    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** 点击前二次确认（毫秒），0 表示不确认 */
    fun confirmDelay(c: Context) = sp(c).getLong("confirm_ms", 120L)
    fun setConfirmDelay(c: Context, v: Long) = sp(c).edit().putLong("confirm_ms", v).apply()

    /** 开屏判定窗口（毫秒）：应用切到前台后多久内算开屏 */
    fun splashWindow(c: Context) = sp(c).getLong("splash_ms", 9000L)

    fun verbose(c: Context) = sp(c).getBoolean("verbose", false)
    fun setVerbose(c: Context, v: Boolean) = sp(c).edit().putBoolean("verbose", v).apply()
}
