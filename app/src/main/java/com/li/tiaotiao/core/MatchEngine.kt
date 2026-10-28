package com.li.tiaotiao.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 匹配引擎：多因子打分。
 *
 * 硬条件（必须全部满足，至少命中一个才算候选）：
 *   keywords / textRegex / viewIdRegex / descRegex / classNameContains
 *
 * 软加分：
 *   base 50（硬条件命中） / 位置区域 +25 / 尺寸合规 +15 / 可点击 +10 / 开屏时机 +5 / 像素探针 +20
 *
 * 任何一项明确的尺寸或可点击性约束不满足，直接判负，避免点到大块容器。
 */
object MatchEngine {

    data class Ctx(
        val screenW: Int,
        val screenH: Int,
        val isSplash: Boolean,
        val pkg: String,
        val activity: String
    )

    /** 返回 0~100 的分值，-1 表示不合格 */
    fun score(node: AccessibilityNodeInfo, rule: Rule, ctx: Ctx): Int {
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.width() <= 2 || b.height() <= 2) return -1
        if (node.packageName?.toString() != ctx.pkg) return -1

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val vid = node.viewIdResourceName.orEmpty()
        val cls = node.className?.toString().orEmpty()

        var base = 0
        var hard = false

        if (rule.keywords.isNotEmpty()) {
            if (!textMatches(text, rule)) return -1
            base = maxOf(base, 50); hard = true
        }
        if (rule.textRegex.isNotBlank()) {
            val ok = regex(rule.textRegex).containsMatchIn(text)
            if (!ok) return -1
            base = maxOf(base, 50); hard = true
        }
        if (rule.viewIdRegex.isNotBlank()) {
            val ok = regex(rule.viewIdRegex).containsMatchIn(vid)
            if (!ok) return -1
            base = maxOf(base, 45); hard = true
        }
        if (rule.descRegex.isNotBlank()) {
            val ok = regex(rule.descRegex).containsMatchIn(desc)
            if (!ok) return -1
            base = maxOf(base, 40); hard = true
        }
        if (rule.classNameContains.isNotBlank()) {
            if (!cls.contains(rule.classNameContains, true)) return -1
            base = maxOf(base, 30); hard = true
        }

        if (!hard) {
            if (!rule.allowPositional) return -1
            base = 40
        }

        var s = base

        // 尺寸约束：宁可漏点，不可乱点
        val wFrac = b.width().toFloat() / ctx.screenW.coerceAtLeast(1)
        val hFrac = b.height().toFloat() / ctx.screenH.coerceAtLeast(1)
        if (wFrac > rule.maxWidthFrac || hFrac > rule.maxHeightFrac) return -1
        s += 15

        // 可点击性
        val selfClickable = node.isClickable
        val ancestorClickable = !selfClickable && rule.allowAncestorClickable && clickableAncestor(node) != null
        if (!selfClickable && !ancestorClickable) {
            if (rule.mustClickable) return -1
        } else {
            s += 10
        }

        // 位置约束
        rule.region?.let { r ->
            if (r.contains(b, ctx.screenW, ctx.screenH)) s += 25 else s -= 20
        }

        if (ctx.isSplash) s += 5

        // 文案越长越不像「跳过」按钮，轻微惩罚
        if (text.length > 8) s -= 10

        return s.coerceIn(0, 100)
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

    fun clickableAncestor(node: AccessibilityNodeInfo, maxUp: Int = 4): AccessibilityNodeInfo? {
        var cur = node.parent
        var i = 0
        while (cur != null && i < maxUp) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
            i++
        }
        return null
    }

    /** 真正要执行点击的节点：自己可点就自己，否则向上找可点祖先 */
    fun clickTarget(node: AccessibilityNodeInfo, preferParent: Boolean): AccessibilityNodeInfo {
        if (preferParent) return clickableAncestor(node, 5) ?: node
        if (node.isClickable) return node
        return clickableAncestor(node, 4) ?: node
    }

    private fun regex(p: String): Regex = runCatching { Regex(p) }.getOrElse { Regex("(?!)") }
}
