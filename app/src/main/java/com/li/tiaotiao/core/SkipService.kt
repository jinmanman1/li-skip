package com.li.tiaotiao.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 核心无障碍服务。
 *
 * 扫描策略改成三重保险，不再单靠事件：
 *   1) 事件触发：内容变化时节流合并（注意是节流，不是去抖 —— 去抖会被动画无限推迟）
 *   2) 突发扫描：切到新 App 后 0~6 秒内密集扫 11 次，专治开屏广告
 *   3) 心跳兜底：前台有 App 时每 0.4s（开屏期）/ 1.2s（平时）扫一次
 */
class SkipService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SkipService? = null

        private const val MAX_NODES = 700
        private const val MAX_DEPTH = 40
        private const val BURST_WINDOW = 6500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rules: List<Rule> = emptyList()

    var pkg: String = ""
        private set
    var activityName: String = ""
        private set

    private var launchAt = 0L
    private var lastScanAt = 0L
    private var pausedAt = 0L
    private val lastFire = HashMap<String, Long>()
    private var pending: Pending? = null
    private var picker: Picker? = null
    private val tip: SkipTip by lazy { SkipTip(this) }
    private val power: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private val retried = HashMap<String, Long>()

    private var lastTree: List<String> = emptyList()
    private var lastTreePkg: String = ""

    private var scanQueued = false
    private val scanRunnable = Runnable {
        scanQueued = false
        scan()
    }
    private val burstRunnable = Runnable { scan() }

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            // 拾取器如果被系统回收，暂停状态卡住超过 90 秒自动恢复
            if (paused && picker == null && now - pausedAt > 90_000) {
                paused = false
                LogBus.log("拾取超时，已自动恢复跳过")
            }
            if (!paused && Settings.enabled(this@SkipService) &&
                pkg.isNotEmpty() && pkg != packageName &&
                now - lastScanAt >= 200
            ) {
                scan()
            }
            val delay = if (now - launchAt < BURST_WINDOW) 400L else 1200L
            handler.postDelayed(this, delay)
        }
    }

    private data class Cand(
        val node: AccessibilityNodeInfo,
        val rule: Rule,
        val score: Int
    )

    private data class Pending(
        val ruleId: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val score: Int
    )

    /** 拾取规则时暂停自动跳过 */
    var paused = false
        set(v) {
            field = v
            if (v) {
                pausedAt = SystemClock.uptimeMillis()
            } else {
                schedule(200)
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadRules()
        runCatching {
            val r = rootInActiveWindow
            val p = r?.packageName?.toString().orEmpty()
            if (p.isNotEmpty() && p != packageName) {
                pkg = p
                activityName = r?.className?.toString().orEmpty()
                launchAt = SystemClock.uptimeMillis()
            }
        }
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        LogBus.log("服务已连接，规则 ${rules.size} 条")
    }

    override fun onInterrupt() {
        LogBus.log("无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun reloadRules() {
        rules = RuleStore.load()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (!Settings.enabled(this)) return

        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = ev.packageName?.toString().orEmpty()
            val c = ev.className?.toString().orEmpty()
            if (p.isNotEmpty()) {
                if (paused) {
                    // 拾取过程中也要跟着更新前台包名，
                    // 否则拾取器拿到的 pkg 还停留在「Li 跳过」自己身上，生成的规则会绑错 App
                    if (p != packageName) {
                        pkg = p
                        activityName = c
                    }
                } else {
                    onWindowChanged(p, c)
                }
            }
            return
        }

        if (paused) return

        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> schedule(120)

            else -> Unit
        }
    }

    private fun onWindowChanged(p: String, c: String) {
        if (p == packageName) {
            pkg = p
            activityName = c
            return
        }

        if (p != pkg) {
            pkg = p
            activityName = c
            launchAt = SystemClock.uptimeMillis()
            pending = null
            lastFire.clear()
            lastTree = emptyList()
            reloadRules()
            LogBus.log("前台 → $p")
            burst()
        } else if (c.isNotEmpty() && c != activityName) {
            activityName = c
        }

        val splash = SystemClock.uptimeMillis() - launchAt <= Settings.splashWindow(this)
        tryActivityRules(p, c, splash)
        schedule(150)
    }

    /** 切到新 App 后的密集扫描，开屏广告基本都在这个窗口里出现 */
    private fun burst() {
        handler.removeCallbacks(burstRunnable)
        val delays = longArrayOf(80, 200, 380, 620, 900, 1250, 1700, 2300, 3100, 4200, 5600)
        for (d in delays) handler.postDelayed(burstRunnable, d)
    }

    /** 节流：已经有扫描在排队就不再往后推，否则动画页面会把扫描无限延后 */
    private fun schedule(delay: Long) {
        if (scanQueued) return
        scanQueued = true
        handler.postDelayed(scanRunnable, delay)
    }

    private fun scheduleConfirm(delay: Long) {
        scanQueued = true
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delay)
    }

    private fun tryActivityRules(p: String, cls: String, splash: Boolean) {
        if (cls.isEmpty()) return
        for (r in rules) {
            if (!r.enabled || !r.activityOnly || r.activityRegex.isBlank()) continue
            if (!r.appliesTo(p)) continue
            if (r.onlySplash && !splash) continue
            val hit = runCatching { Regex(r.activityRegex).containsMatchIn(cls) }.getOrDefault(false)
            if (!hit) continue
            if (!cooldownOk(r)) continue
            lastFire[r.id] = SystemClock.uptimeMillis()
            LogBus.log("命中 Activity 规则「${r.name}」→ ${r.action.name}")
            Stats.record(this, 5000L)
            if (Settings.toastOnSkip(this)) tip.show("已跳过开屏广告 · 省 5 秒")
            doAction(r.action, null)
            return
        }
    }

    private fun scan() {
        if (paused || !Settings.enabled(this)) return
        if (pkg.isEmpty() || pkg == packageName) return
        // 息屏时不扫，省电
        if (!runCatching { power.isInteractive }.getOrDefault(true)) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        if (rules.isEmpty()) reloadRules()

        lastScanAt = SystemClock.uptimeMillis()
        val (sw, sh) = screenSize()
        val splash = lastScanAt - launchAt <= Settings.splashWindow(this)
        val ctx = MatchEngine.Ctx(sw, sh, splash, pkg, activityName)

        val nodes = ArrayList<AccessibilityNodeInfo>(256)
        collect(root, nodes)
        if (nodes.isEmpty()) return

        val snaps = ArrayList<MatchEngine.Snap>(nodes.size)
        for (n in nodes) snaps.add(MatchEngine.Snap(n))
        recordTree(snaps)

        // 上一轮留下的待确认候选：确认没动就点，没找到就继续正常扫描（不再直接 return）
        val pd = pending
        if (pd != null) {
            pending = null
            val idx = findSameIndex(snaps, pd)
            val r = rules.firstOrNull { it.id == pd.ruleId }
            if (idx >= 0 && r != null && r.enabled) {
                LogBus.log("确认 → 「${r.name}」(${pd.score})")
                fire(r, snaps[idx].node, pd.score)
                return
            }
        }

        val verbose = Settings.verbose(this)
        val misses = ArrayList<String>()
        var best: Cand? = null

        for (r in rules) {
            if (!r.enabled || r.activityOnly) continue
            if (!r.appliesTo(pkg)) continue
            if (r.onlySplash && !splash) continue
            if (!cooldownOk(r)) continue
            for (s in snaps) {
                val e = MatchEngine.evaluate(s, r, ctx)
                val cur = best
                if (e.score > 0 && e.score >= r.minScore) {
                    if (cur == null || e.score > cur.score) best = Cand(s.node, r, e.score)
                } else if (verbose && misses.size < 6) {
                    if (e.score > 0) {
                        misses.add("${r.name}=${e.score}分")
                    } else if (e.reason.isNotEmpty()) {
                        misses.add("${r.name}:${e.reason}")
                    }
                }
            }
        }

        val b = best
        if (b == null) {
            if (verbose && misses.isNotEmpty()) {
                LogBus.log("未命中 | " + misses.distinct().joinToString("，"))
            }
            return
        }

        LogBus.log("候选「${b.rule.name}」${b.score}分")
        startConfirmOrFire(b)
    }

    private fun startConfirmOrFire(c: Cand) {
        if (paused) return
        // 配了像素校验的规则：先截一张图取色，颜色不对就放弃
        val probe = c.rule.probe
        if (probe != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Screenshotter.capture(this) { bmp ->
                if (bmp == null) {
                    // 机型不支持截图（或刚好失败）时不要卡死这条规则，放行并记一笔
                    LogBus.log("截图不可用，「${c.rule.name}」跳过像素校验")
                    handler.post { scheduleConfirmOrFire(c) }
                    return@capture
                }
                if (Screenshotter.pixelMatches(bmp, probe)) {
                    handler.post { scheduleConfirmOrFire(c) }
                } else {
                    LogBus.log("「${c.rule.name}」像素校验不通过，放弃")
                }
            }
            return
        }
        scheduleConfirmOrFire(c)
    }

    private fun scheduleConfirmOrFire(c: Cand) {
        if (paused) return
        val delay = Settings.confirmDelay(this)
        if (delay > 0) {
            val r = Rect()
            c.node.getBoundsInScreen(r)
            pending = Pending(c.rule.id, r.left, r.top, r.right, r.bottom, c.score)
            scheduleConfirm(delay)
        } else {
            fire(c.rule, c.node, c.score)
        }
    }

    private fun fire(rule: Rule, node: AccessibilityNodeInfo, score: Int) {
        lastFire[rule.id] = SystemClock.uptimeMillis()
        val savedMs = estimateSavedMs(node)
        Stats.record(this, savedMs)
        LogBus.log("执行「${rule.name}」score=$score 省${savedMs / 1000}s")
        if (Settings.toastOnSkip(this)) tip.show("已跳过广告 · 省 ${savedMs / 1000} 秒")
        doAction(rule.action, node)

        // 点完再回访一次：按钮还在就自动换个点法重试一次
        handler.postDelayed({ verifyAndRetry(rule, node) }, 900)
    }

    /**
     * 点击验证 + 自动兜底重试。
     * 有些广告层的「跳过」本身不可点击，或者 ACTION_CLICK 被广告 SDK 吞掉，
     * 这时直接按坐标补一下通常能点动。
     */
    private fun verifyAndRetry(rule: Rule, node: AccessibilityNodeInfo) {
        if (rule.action != RuleAction.CLICK && rule.action != RuleAction.CLICK_PARENT) return
        val still = runCatching { node.refresh() && node.isVisibleToUser }.getOrDefault(false)
        if (!still) return

        val last = retried[rule.id] ?: 0L
        if (SystemClock.uptimeMillis() - last < 2500) return
        retried[rule.id] = SystemClock.uptimeMillis()

        val b = Rect()
        runCatching { node.getBoundsInScreen(b) }
        if (b.width() <= 0) return

        LogBus.log("「${rule.name}」没点动，改用坐标点击重试")
        tap(b.centerX().toFloat(), b.centerY().toFloat())
    }

    private fun estimateSavedMs(node: AccessibilityNodeInfo): Long {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 3) {
            val digits = (n.text?.toString().orEmpty()).filter { it.isDigit() }.take(2)
            if (digits.isNotEmpty()) {
                val v = digits.toIntOrNull() ?: 0
                if (v in 1..90) return v * 1000L
            }
            n = runCatching { n?.parent }.getOrNull()
            depth++
        }
        return 5000L
    }

    private fun cooldownOk(rule: Rule): Boolean {
        val last = lastFire[rule.id] ?: 0L
        return SystemClock.uptimeMillis() - last >= rule.cooldownMs
    }

    private fun doAction(action: RuleAction, node: AccessibilityNodeInfo?) {
        when (action) {
            RuleAction.PRESS_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RuleAction.SWIPE_UP -> swipeUp()
            RuleAction.CLICK, RuleAction.CLICK_PARENT -> {
                val n = node ?: return
                val target = MatchEngine.clickTarget(n, action == RuleAction.CLICK_PARENT)
                val ok = runCatching {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (!ok) tapCenter(target)
            }
        }
    }

    private fun tapCenter(node: AccessibilityNodeInfo) {
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.width() <= 0) return
        tap(b.centerX().toFloat(), b.centerY().toFloat())
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    private fun swipeUp() {
        val (w, h) = screenSize()
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.78f)
            lineTo(w * 0.5f, h * 0.22f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 240))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    /**
     * 广度优先收集节点。
     * 用 BFS 而不是 DFS：开屏广告的浮层通常是根布局里「最后添加的那个子节点」，
     * DFS 在节点数超过上限时很可能先扫完主界面内容就停了，永远看不到广告浮层。
     */
    private fun collect(root: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (root == null) return
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val item = queue.removeFirst()
            val n = item.first
            val depth = item.second
            if (n.isVisibleToUser) out.add(n)
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                queue.addLast(c to depth + 1)
            }
        }
    }

    private fun findSameIndex(snaps: List<MatchEngine.Snap>, pd: Pending): Int {
        var bestIdx = -1
        var bestD = Int.MAX_VALUE
        for (i in snaps.indices) {
            val b = snaps[i].bounds
            val d = kotlin.math.abs(b.left - pd.left) + kotlin.math.abs(b.top - pd.top) +
                kotlin.math.abs(b.right - pd.right) + kotlin.math.abs(b.bottom - pd.bottom)
            if (d < bestD && d <= 32) {
                bestD = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    // ---------- 诊断 ----------

    private fun recordTree(snaps: List<MatchEngine.Snap>) {
        val out = ArrayList<String>(64)
        for (s in snaps) {
            if (out.size >= 60) break
            if (s.text.isEmpty() && s.desc.isEmpty() && !s.clickable) continue
            out.add(
                (if (s.clickable) "C " else "- ") +
                    s.cls.substringAfterLast('.') +
                    " t=\"" + s.text + "\"" +
                    " d=\"" + s.desc + "\"" +
                    " id=" + s.viewId.substringAfterLast('/') +
                    " b=[" + s.bounds.left + "," + s.bounds.top + "," + s.bounds.right + "," + s.bounds.bottom + "]"
            )
        }
        lastTree = out
        lastTreePkg = "$pkg / $activityName"
    }

    /** 把最近一次扫描到的界面结构打到日志里，方便排查为什么不跳 */
    fun dumpLastTree() {
        if (lastTree.isEmpty()) {
            LogBus.log("诊断：还没抓到界面，先切到目标 App 停留几秒再来")
            return
        }
        LogBus.log("===== 诊断 $lastTreePkg =====")
        lastTree.forEach { LogBus.log(it) }
        LogBus.log("===== 共 ${lastTree.size} 个节点 =====")
    }

    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    fun startPicker(onDone: () -> Unit) {
        if (picker != null) return
        val p = Picker(this)
        picker = p
        paused = true
        p.show { rule ->
            picker = null
            paused = false
            if (rule != null) {
                RuleStore.add(rule)
                reloadRules()
                LogBus.log("已生成规则：${rule.name}")
            }
            onDone()
        }
    }
}
