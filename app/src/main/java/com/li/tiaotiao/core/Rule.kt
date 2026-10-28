package com.li.tiaotiao.core

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MatchMode { CONTAINS, EXACT, REGEX }

enum class RuleAction { CLICK, CLICK_PARENT, PRESS_BACK, SWIPE_UP }

/** 归一化矩形，0~1，相对屏幕 */
data class Region(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun contains(rect: Rect, screenW: Int, screenH: Int): Boolean {
        if (screenW <= 0 || screenH <= 0) return false
        val tol = 4f
        return rect.left >= l * screenW - tol &&
            rect.top >= t * screenH - tol &&
            rect.right <= r * screenW + tol &&
            rect.bottom <= b * screenH + tol
    }

    fun toJson(): JSONObject = JSONObject()
        .put("l", l.toDouble()).put("t", t.toDouble())
        .put("r", r.toDouble()).put("b", b.toDouble())

    override fun toString() =
        String.format(java.util.Locale.US, "%.2f,%.2f,%.2f,%.2f", l, t, r, b)

    companion object {
        fun from(o: JSONObject?): Region? {
            if (o == null || o == JSONObject.NULL) return null
            return Region(
                o.optDouble("l", 0.0).toFloat(),
                o.optDouble("t", 0.0).toFloat(),
                o.optDouble("r", 1.0).toFloat(),
                o.optDouble("b", 1.0).toFloat()
            )
        }
        fun parse(s: String): Region? {
            val p = s.split(",").map { it.trim().toFloatOrNull() ?: return null }
            if (p.size != 4) return null
            return Region(p[0], p[1], p[2], p[3])
        }
    }
}

/** 像素探针：点击前校验某个点的颜色，用来区分「真的关闭按钮」和「长得像的装饰元素」 */
data class PixelProbe(val x: Float, val y: Float, val color: Int, val tolerance: Int = 48) {
    fun toJson(): JSONObject = JSONObject()
        .put("x", x.toDouble()).put("y", y.toDouble())
        .put("color", color).put("tol", tolerance)

    companion object {
        fun from(o: JSONObject?): PixelProbe? {
            if (o == null || o == JSONObject.NULL) return null
            return PixelProbe(
                o.optDouble("x", 0.5).toFloat(),
                o.optDouble("y", 0.5).toFloat(),
                o.optInt("color", 0),
                o.optInt("tol", 48)
            )
        }
    }
}

/**
 * 一条跳过规则。规则是「多因子」的：文本 / id / 描述 / 类名 为硬条件，
 * 位置、尺寸、可点击性、开屏时机、像素探针 为软加分项，最终打分 >= minScore 才动手。
 */
data class Rule(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "未命名规则",
    var enabled: Boolean = true,
    /** 为空表示对所有应用生效 */
    var packages: MutableList<String> = mutableListOf(),
    /** 只在应用刚启动的开屏期间生效 */
    var onlySplash: Boolean = false,
    /** Activity 正则（配合 activityOnly 使用） */
    var activityRegex: String = "",
    /** 只按 Activity 判断，不看节点树（用于广告 SDK 页面直接返回） */
    var activityOnly: Boolean = false,
    var keywords: MutableList<String> = mutableListOf(),
    var keywordMode: MatchMode = MatchMode.CONTAINS,
    var textRegex: String = "",
    var viewIdRegex: String = "",
    var descRegex: String = "",
    var classNameContains: String = "",
    var region: Region? = null,
    var maxWidthFrac: Float = 0.5f,
    var maxHeightFrac: Float = 0.2f,
    var mustClickable: Boolean = true,
    var allowAncestorClickable: Boolean = true,
    /** 允许纯位置规则（没有文本/id 等硬条件） */
    var allowPositional: Boolean = false,
    var minScore: Int = 75,
    var action: RuleAction = RuleAction.CLICK,
    var cooldownMs: Long = 1500,
    var probe: PixelProbe? = null,
    var note: String = ""
) {

    fun appliesTo(pkg: String) = packages.isEmpty() || packages.any { it.equals(pkg, true) }

    fun describe(): String {
        val sb = StringBuilder()
        sb.append(if (packages.isEmpty()) "全局" else packages.joinToString("/"))
        if (onlySplash) sb.append(" · 仅开屏")
        if (activityOnly) sb.append(" · Activity")
        if (keywords.isNotEmpty()) sb.append(" · 文字:").append(keywords.take(3).joinToString("|"))
        if (textRegex.isNotBlank()) sb.append(" · 正则")
        if (viewIdRegex.isNotBlank()) sb.append(" · id")
        if (descRegex.isNotBlank()) sb.append(" · desc")
        if (allowPositional) sb.append(" · 位置")
        if (region != null) sb.append(" · 区域").append(region.toString())
        sb.append(" · ≥").append(minScore)
        sb.append(" · ").append(action.name)
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("packages", JSONArray(packages))
        put("onlySplash", onlySplash)
        put("activityRegex", activityRegex)
        put("activityOnly", activityOnly)
        put("keywords", JSONArray(keywords))
        put("keywordMode", keywordMode.name)
        put("textRegex", textRegex)
        put("viewIdRegex", viewIdRegex)
        put("descRegex", descRegex)
        put("classNameContains", classNameContains)
        put("region", region?.toJson() ?: JSONObject.NULL)
        put("maxWidthFrac", maxWidthFrac.toDouble())
        put("maxHeightFrac", maxHeightFrac.toDouble())
        put("mustClickable", mustClickable)
        put("allowAncestorClickable", allowAncestorClickable)
        put("allowPositional", allowPositional)
        put("minScore", minScore)
        put("action", action.name)
        put("cooldownMs", cooldownMs)
        put("probe", probe?.toJson() ?: JSONObject.NULL)
        put("note", note)
    }

    fun copyOf() = fromJson(toJson())

    companion object {
        private fun strList(a: JSONArray?): MutableList<String> {
            if (a == null) return mutableListOf()
            val out = mutableListOf<String>()
            for (i in 0 until a.length()) out.add(a.optString(i))
            return out
        }

        fun fromJson(o: JSONObject): Rule = Rule(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "未命名规则"),
            enabled = o.optBoolean("enabled", true),
            packages = strList(o.optJSONArray("packages")),
            onlySplash = o.optBoolean("onlySplash", false),
            activityRegex = o.optString("activityRegex", ""),
            activityOnly = o.optBoolean("activityOnly", false),
            keywords = strList(o.optJSONArray("keywords")),
            keywordMode = runCatching { MatchMode.valueOf(o.optString("keywordMode", "CONTAINS")) }
                .getOrDefault(MatchMode.CONTAINS),
            textRegex = o.optString("textRegex", ""),
            viewIdRegex = o.optString("viewIdRegex", ""),
            descRegex = o.optString("descRegex", ""),
            classNameContains = o.optString("classNameContains", ""),
            region = Region.from(o.optJSONObject("region")),
            maxWidthFrac = o.optDouble("maxWidthFrac", 0.5).toFloat(),
            maxHeightFrac = o.optDouble("maxHeightFrac", 0.2).toFloat(),
            mustClickable = o.optBoolean("mustClickable", true),
            allowAncestorClickable = o.optBoolean("allowAncestorClickable", true),
            allowPositional = o.optBoolean("allowPositional", false),
            minScore = o.optInt("minScore", 75),
            action = runCatching { RuleAction.valueOf(o.optString("action", "CLICK")) }
                .getOrDefault(RuleAction.CLICK),
            cooldownMs = o.optLong("cooldownMs", 1500),
            probe = PixelProbe.from(o.optJSONObject("probe")),
            note = o.optString("note", "")
        )
    }
}
