package com.li.tiaotiao.core

import android.content.Context
import org.json.JSONArray

/**
 * 规则持久化：SharedPreferences 里存一个 JSON 数组，便于导入导出。
 */
object RuleStore {

    private const val PREF = "li_skip"
    private const val KEY_RULES = "rules_json"
    private const val KEY_SEEDED = "seeded_v2"
    private const val KEY_MIGRATED = "migrated_v3"

    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!sp.getBoolean(KEY_SEEDED, false)) {
            val old = load()
            if (old.isEmpty()) {
                save(seed())
            } else {
                mergeDefaults(old)
            }
            sp.edit().putBoolean(KEY_SEEDED, true).apply()
        }
        // 只迁移一次：否则用户手调过的默认规则会被反复覆盖
        if (!sp.getBoolean(KEY_MIGRATED, false)) {
            migrate()
            sp.edit().putBoolean(KEY_MIGRATED, true).apply()
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

    /**
     * 升级时把内置规则的判定参数刷新成最新版（保留用户自己的启停开关和自建规则）。
     * 这样修了默认规则的 bug 之后，老用户不用手动「恢复默认」也能享受到。
     */
    private fun migrate() {
        val cur = load()
        if (cur.isEmpty()) return
        var changed = false
        val defaults = seed()
        val byName = cur.associateBy { it.name }

        for (d in defaults) {
            val old = byName[d.name]
            if (old == null) continue
            val dirty = old.keywords != d.keywords ||
                old.textRegex != d.textRegex ||
                old.region != d.region ||
                old.maxWidthFrac != d.maxWidthFrac ||
                old.maxHeightFrac != d.maxHeightFrac ||
                old.minScore != d.minScore ||
                old.mustClickable != d.mustClickable ||
                old.allowPositional != d.allowPositional
            if (!dirty) continue
            old.keywords = d.keywords.toMutableList()
            old.textRegex = d.textRegex
            old.region = d.region
            old.maxWidthFrac = d.maxWidthFrac
            old.maxHeightFrac = d.maxHeightFrac
            old.minScore = d.minScore
            old.mustClickable = d.mustClickable
            old.allowPositional = d.allowPositional
            changed = true
        }
        if (changed) save(cur)
    }

    private fun mergeDefaults(cur: MutableList<Rule>) {
        val names = cur.map { it.name }.toHashSet()
        var changed = false
        for (d in seed()) {
            if (!names.contains(d.name)) {
                cur.add(d)
                changed = true
            }
        }
        if (changed) save(cur)
    }

    /**
     * 内置规则。
     * 注意 mustClickable = false：很多开屏广告的「跳过」是自绘控件，文字节点和父节点都不可点击
     * （点击由整页触摸监听处理），如果强制要求可点击就会永远匹配不上。靠尺寸和区域约束兜住误触。
     */
    private fun seed(): List<Rule> = listOf(
        Rule(
            name = "通用 · 跳过文字",
            keywords = mutableListOf("跳过", "跳过广告", "跳过此广告", "skip", "Skip", "SKIP"),
            region = Region(0.25f, 0f, 1f, 0.55f),
            maxWidthFrac = 0.62f,
            maxHeightFrac = 0.22f,
            mustClickable = false,
            minScore = 65,
            cooldownMs = 1200,
            note = "最常见的开屏广告按钮"
        ),
        Rule(
            name = "通用 · 关闭文字",
            keywords = mutableListOf("关闭广告", "关闭", "close", "×", "✕"),
            region = Region(0.30f, 0f, 1f, 0.75f),
            maxWidthFrac = 0.5f,
            maxHeightFrac = 0.2f,
            mustClickable = false,
            minScore = 72,
            cooldownMs = 1500,
            note = "插屏/弹窗广告的关闭按钮"
        ),
        Rule(
            name = "通用 · 倒计时跳过",
            textRegex = "(跳过|Skip|SKIP)\\s*\\d{0,2}\\s*(s|S|秒)?|^\\s*\\d{1,2}\\s*(s|秒)\\s*$",
            region = Region(0.18f, 0f, 1f, 0.5f),
            maxWidthFrac = 0.6f,
            maxHeightFrac = 0.2f,
            mustClickable = false,
            minScore = 68,
            note = "「跳过 3」「3s」这类倒计时样式"
        ),
        Rule(
            name = "宽松 · 右上角小按钮",
            region = Region(0.55f, 0f, 1f, 0.30f),
            maxWidthFrac = 0.25f,
            maxHeightFrac = 0.10f,
            mustClickable = false,
            allowPositional = true,
            minScore = 60,
            cooldownMs = 2000,
            enabled = false,
            note = "完全没有文字线索时的兜底。比较激进，容易误触就保持关闭"
        ),
        Rule(
            name = "开屏 · 广告SDK页面直接返回",
            activityOnly = true,
            onlySplash = true,
            activityRegex = "(openadsdk|pangle|bytedance|qq\\.e\\.|gdt|ksad|kwad|mintegral|applovin|unityads|topon|anythink|sigmob|mimo|vungle|chartboost)",
            action = RuleAction.PRESS_BACK,
            cooldownMs = 800,
            enabled = false,
            note = "命中广告 SDK 的 Activity 直接按返回键。默认关闭，个别应用可能因此退回桌面"
        )
    )
}
