package com.li.tiaotiao.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * 匹配引擎：多因子打分。
 *
 * 硬条件（任一命中）：关键词 / 文字正则 / viewId / 描述 / 类名
 * 软条件：尺寸不超限（超了直接否）、可点击加分、位置区域加减分、开屏时机加分
 *
 * 一次扫描里每个节点只读一次属性（Snap），避免在双重循环里反复跨进程 IPC ——
 * 这是之前扫描慢、经常扫不到的直接原因之一。
 */
object MatchEngine {

    data class Ctx(
        val screenW: Int,
        val screenH: Int,
        val isSplash: Boolean,
        val pkg: String,
        val activity: String
    )

    /** score < 0 表示硬条件不匹配；score == 0 且有 reason 表示被软约束否决 */
    data class Eval(val score: Int, val reason: String = "")

    /** 节点属性快照 */
    class Snap(val node: AccessibilityNodeInfo) {
        val text: String = node.text?.toString()?.trim().orEmpty()
        val desc: String = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId: String = node.viewIdResourceName.orEmpty()
        val cls: String = node.className?.toString().orEmpty()
        val pkg: String? = runCatching { node.packageName?.toString() }.getOrNull()
        val clickable: Boolean = node.isClickable
        val bounds: Rect = Rect().also { node.getBoundsInScreen(it) }

        private var ancestorCache: Boolean? = null

        /** 往上找可点击的父节点（最多 6 层），找到就缓存 */
        fun ancestorClickable(): Boolean {
            ancestorCache?.let { return it }
            var p = runCatching { node.parent }.getOrNull()
            var i = 0
            var ok = false
            while (p != null && i < 6) {
                if (p.isClickable && p.isVisibleToUser) {
                    ok = true
                    break
                }
                p = runCatching { p?.parent }.getOrNull()
                i++
            }
            ancestorCache = ok
            return ok
        }
    }

    fun evaluate(s: Snap, rule: Rule, ctx: Ctx): Eval {
        val b = s.bounds
        if (b.width() <= 2 || b.height() <= 2) return Eval(-1)

        // packageName 为 null 的节点（WebView / 自绘控件）不要一棍子打死
        if (s.pkg != null && s.pkg != ctx.pkg) return Eval(-1)

        var base = 0
        var hard = false

        if (rule.keywords.isNotEmpty()) {
            if (!textMatches(s.text, rule)) return Eval(-1)
            base = maxOf(base, 50); hard = true
        }
        if (rule.textRegex.isNotBlank()) {
            if (!regex(rule.textRegex).containsMatchIn(s.text)) return Eval(-1)
            base = maxOf(base, 50); hard = true
        }
        if (rule.viewIdRegex.isNotBlank()) {
            if (!regex(rule.viewIdRegex).containsMatchIn(s.viewId)) return Eval(-1)
            base = maxOf(base, 45); hard = true
        }
        if (rule.descRegex.isNotBlank()) {
            if (!regex(rule.descRegex).containsMatchIn(s.desc)) return Eval(-1)
            base = maxOf(base, 40); hard = true
        }
        if (rule.classNameContains.isNotBlank()) {
            if (!s.cls.contains(rule.classNameContains, true)) return Eval(-1)
            base = maxOf(base, 30); hard = true
        }

        if (!hard) {
            if (!rule.allowPositional) return Eval(-1)
            base = 40
        }

        var score = base

        val wFrac = b.width().toFloat() / ctx.screenW.coerceAtLeast(1)
        val hFrac = b.height().toFloat() / ctx.screenH.coerceAtLeast(1)
        if (wFrac > rule.maxWidthFrac || hFrac > rule.maxHeightFrac) {
            return Eval(
                0,
                String.format(Locale.US, "尺寸超限 %.2f/%.2f > %.2f/%.2f", wFrac, hFrac, rule.maxWidthFrac, rule.maxHeightFrac)
            )
        }
        score += 15

        val clickable = s.clickable || s.ancestorClickable()
        if (clickable) {
            score += 10
        } else if (rule.mustClickable) {
            return Eval(0, "不可点击")
        }

        rule.region?.let { r ->
            if (r.contains(b, ctx.screenW, ctx.screenH)) score += 25 else score -= 20
        }

        if (ctx.isSplash) score += 5
        if (s.text.length > 8) score -= 10

        return Eval(score.coerceIn(0, 100))
    }

    fun textMatches(text: String, rule: Rule): Boolean {
        val kws = rule.keywords.filter { it.isNotBlank() }
        if (kws.isEmpty()) return false
        return kws.any { kw ->
            when (rule.keywordMode) {
                MatchMode.CONTAINS -> text.contains(kw, ignoreCase = true)
                MatchMode.EXACT -> text.equals(kw, ignoreCase = true)
                MatchMode.REGEX -> regex(kw).containsMatchIn(text)
            }
        }
    }

    fun clickableAncestor(node: AccessibilityNodeInfo, maxUp: Int = 6): AccessibilityNodeInfo? {
        var cur = runCatching { node.parent }.getOrNull()
        var i = 0
        while (cur != null && i < maxUp) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = runCatching { cur?.parent }.getOrNull()
            i++
        }
        return null
    }

    fun clickTarget(node: AccessibilityNodeInfo, preferParent: Boolean): AccessibilityNodeInfo {
        if (preferParent) return clickableAncestor(node, 6) ?: node
        if (node.isClickable) return node
        return clickableAncestor(node, 6) ?: node
    }

    private fun regex(p: String): Regex = runCatching { Regex(p) }.getOrElse { Regex("(?!)") }
}
