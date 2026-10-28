package com.li.tiaotiao.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则持久化：SharedPreferences 里存一个 JSON 数组，便于导入导出。
 */
object RuleStore {

    private const val PREF = "li_skip"
    private const val KEY_RULES = "rules_json"
    private const val KEY_SEEDED = "seeded_v1"

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!sp.getBoolean(KEY_SEEDED, false)) {
            save(seed())
            sp.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    private val sp get() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(): MutableList<Rule> {
        val raw = sp.getString(KEY_RULES, null) ?: return mutableListOf()
        val out = mutableListOf<Rule>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Rule.fromJson(it)) }
            }
        }
        return out
    }

    fun save(list: List<Rule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    fun add(rule: Rule) {
        val list = load()
        list.add(rule)
        save(list)
    }

    fun upsert(rule: Rule) {
        val list = load()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        save(list)
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun find(id: String?): Rule? = load().firstOrNull { it.id == id }

    fun exportJson(): String = sp.getString(KEY_RULES, "[]") ?: "[]"

    /** 合并导入；id 相同则覆盖，否则追加。返回导入条数 */
    fun importJson(text: String): Int {
        val cur = load()
        val arr = JSONArray(text)
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = Rule.fromJson(o)
            val idx = cur.indexOfFirst { it.id == r.id }
            if (idx >= 0) cur[idx] = r else {
                cur.add(r); n++
            }
        }
        save(cur)
        return n
    }

    fun resetToDefault() {
        save(seed())
    }

    fun toggle(id: String, enabled: Boolean) {
        val list = load()
        list.firstOrNull { it.id == id }?.enabled = enabled
        save(list)
    }

    private fun seed(): List<Rule> = listOf(
        Rule(
            name = "通用 · 跳过文字",
            keywords = mutableListOf("跳过", "跳过广告", "跳过此广告", "skip", "Skip", "SKIP"),
            region = Region(0.40f, 0f, 1f, 0.35f),
            maxWidthFrac = 0.45f,
            maxHeightFrac = 0.16f,
            minScore = 70,
            cooldownMs = 1200,
            note = "最常见的开屏广告按钮：右上角、小尺寸、带「跳过」字样"
        ),
        Rule(
            name = "通用 · 关闭文字",
            keywords = mutableListOf("关闭广告", "关闭", "close", "×", "✕"),
            region = Region(0.45f, 0f, 1f, 0.55f),
            maxWidthFrac = 0.35f,
            maxHeightFrac = 0.12f,
            minScore = 78,
            cooldownMs = 1500,
            note = "插屏/弹窗广告的关闭按钮"
        ),
        Rule(
            name = "通用 · 倒计时跳过",
            textRegex = "(跳过|Skip|SKIP)\\s*\\d{0,2}\\s*(s|S|秒)?|^\\s*\\d{1,2}\\s*(s|秒)\\s*$",
            region = Region(0.35f, 0f, 1f, 0.3f),
            maxWidthFrac = 0.4f,
            maxHeightFrac = 0.12f,
            minScore = 72,
            note = "「跳过 3」「3s」这类倒计时样式"
        ),
        Rule(
            name = "开屏 · 广告SDK页面直接返回",
            activityOnly = true,
            onlySplash = true,
            activityRegex = "(openadsdk|pangle|bytedance|qq\\.e\\.|gdt|ksad|kwad|mintegral|applovin|unityads|topon|anythink|sigmob|mimo|vungle|chartboost)",
            action = RuleAction.PRESS_BACK,
            cooldownMs = 800,
            enabled = false,
            note = "命中广告 SDK 的 Activity 直接按返回键。默认关闭，个别应用可能因此退回桌面，请按需开启"
        )
    )
}
