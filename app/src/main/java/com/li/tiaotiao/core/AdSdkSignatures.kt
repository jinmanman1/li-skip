package com.li.tiaotiao.core

/**
 * 广告 SDK 的 Activity / Class 特征库。
 * 开屏广告绝大多数跑在 SDK 自己的 Activity 里，类名里必然带 SDK 的包名，
 * 这比去节点树里找「跳过」按钮更早、更准。
 */
object AdSdkSignatures {

    private val activityMarkers = listOf(
        "openadsdk",            // 穿山甲 / Pangle
        "pangle",
        "bytedance.sdk",
        "com.qq.e",             // 优量汇 GDT
        "com.qq.e.comm",
        "gdt",
        "com.kwad",             // 快手
        "ksad",
        "kwad",
        "mintegral",
        "applovin",
        "unityads",
        "ironsource",
        "vungle",
        "chartboost",
        "adcolony",
        "inmobi",
        "sigmob",
        "com.mimo",
        "topon",
        "anythink",
        "com.baidu.mobads",
        "mobads",
        "adview",
        "gromore",              // 穿山甲聚合
        "beizi",
        "reone",
        "interstitial"
    )

    fun isAdActivity(className: String): Boolean =
        activityMarkers.any { className.contains(it, ignoreCase = true) }

    /** 常见「跳过」文案，用于打分时判断节点像不像广告按钮 */
    private val skipWords = listOf("跳过", "关闭", "skip", "close", "跳过广告", "关闭广告", "×", "✕")

    fun looksLikeSkipText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 10) return false
        return skipWords.any { t.contains(it, ignoreCase = true) }
    }
}
