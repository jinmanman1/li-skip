package com.li.tiaotiao.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 跳过次数与节省时间统计 */
object Stats {

    private const val PREF = "li_skip_stats"

    private fun sp(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun todayKey() = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())

    fun record(c: Context, savedMs: Long) {
        val s = sp(c)
        val totalMs = s.getLong("total_ms", 0) + savedMs
        val count = s.getLong("count", 0) + 1
        val today = todayKey()
        val todayCount = (if (s.getString("day", "") == today) s.getLong("today_count", 0) else 0) + 1
        s.edit()
            .putLong("total_ms", totalMs)
            .putLong("count", count)
            .putString("day", today)
            .putLong("today_count", todayCount)
            .apply()
    }

    fun count(c: Context): Long = sp(c).getLong("count", 0)

    fun totalMs(c: Context): Long = sp(c).getLong("total_ms", 0)

    fun todayCount(c: Context): Long {
        val s = sp(c)
        return if (s.getString("day", "") == todayKey()) s.getLong("today_count", 0) else 0
    }

    fun reset(c: Context) = sp(c).edit().clear().apply()

    fun human(ms: Long): String {
        if (ms < 1000) return "0 秒"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分 $s 秒"
            else -> "$s 秒"
        }
    }
}
