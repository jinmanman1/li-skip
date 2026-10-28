package com.li.tiaotiao.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 核心无障碍服务。
 *
 * 事件流：窗口切换 / 内容变化 → 去抖 → 扫一遍节点树 → 多因子打分 → 二次确认 → 点击 → 冷却。
 */
class SkipService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SkipService? = null

        private const val MAX_NODES = 900
        private const val MAX_DEPTH = 40
    }

    private val handler = Handler(Looper.getMainLooper())
    private var rules: List<Rule> = emptyList()

    var pkg: String = ""
        private set
    var activityName: String = ""
        private set

    private var launchAt = 0L
    private val lastFire = HashMap<String, Long>()
    private var pending: Pending? = null
    private var picker: Picker? = null

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
            if (!v) handler.postDelayed(scanRunnable, 250)
        }

    private val scanRunnable = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        reloadRules()
        // 服务启动时先把当前前台包名记下来，否则第一次扫描会因为 pkg 为空而直接返回
        runCatching {
            val r = rootInActiveWindow
            val p = r?.packageName?.toString().orEmpty()
            if (p.isNotEmpty() && p != packageName) {
                pkg = p
                activityName = r?.className?.toString().orEmpty()
                launchAt = SystemClock.uptimeMillis()
            }
        }
        LogBus.log("无障碍服务已连接，已加载 ${rules.size} 条规则")
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
        if (paused) return
        if (!Settings.enabled(this)) return

        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val p = ev.packageName?.toString().orEmpty()
                val c = ev.className?.toString().orEmpty()
                if (p.isNotEmpty()) onWindowChanged(p, c)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> schedule(150)

            else -> Unit
        }
    }

    private fun onWindowChanged(p: String, c: String) {
        // 自己的界面不参与自动跳过，避免误点自己的开关
        if (p == packageName) {
            pkg = p
            activityName = c
            return
        }
        if (p != pkg) {
            if (pkg.isNotEmpty()) LogBus.log("前台切换 → $p")
            pkg = p
            activityName = c
            launchAt = SystemClock.uptimeMillis()
            pending = null
            lastFire.clear()
            reloadRules()
        } else if (c.isNotEmpty() && c != activityName) {
            activityName = c
        }

        val splash = SystemClock.uptimeMillis() - launchAt <= Settings.splashWindow(this)
        tryActivityRules(p, c, splash)
        schedule(160)
    }

    /** 一类特殊规则：不看节点树，命中广告 SDK 的 Activity 就直接动作 */
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
            doAction(r.action, null)
            return
        }
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delay)
    }

    private fun scan() {
        if (paused || !Settings.enabled(this)) return
        if (pkg.isEmpty() || pkg == packageName) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        if (rules.isEmpty()) reloadRules()

        val (sw, sh) = screenSize()
        val splash = SystemClock.uptimeMillis() - launchAt <= Settings.splashWindow(this)
        val ctx = MatchEngine.Ctx(sw, sh, splash, pkg, activityName)

        // 第一步：如果上一轮留了「待确认」候选，这一轮只做校验，确认没变才点
        pending?.let { pd ->
            pending = null
            val nodes = ArrayList<AccessibilityNodeInfo>(256)
            collect(root, 0, nodes)
            val node = findSameNode(nodes, pd)
            if (node != null) {
                val r = rules.firstOrNull { it.id == pd.ruleId }
                if (r != null && r.enabled) {
                    LogBus.log("二次确认通过 → 点击「${r.name}」(${pd.score})")
                    fire(r, node, pd.score)
                    return
                }
            }
            schedule(180)
            return
        }

        val nodes = ArrayList<AccessibilityNodeInfo>(256)
        collect(root, 0, nodes)

        val cands = ArrayList<Cand>()
        for (r in rules) {
            if (!r.enabled || r.activityOnly) continue
            if (!r.appliesTo(pkg)) continue
            if (r.onlySplash && !splash) continue
            if (!cooldownOk(r)) continue
            for (n in nodes) {
                val s = MatchEngine.score(n, r, ctx)
                if (s >= r.minScore) cands.add(Cand(n, r, s))
            }
        }
        if (cands.isEmpty()) return

        cands.sortByDescending { it.score }
        val best = cands.first()
        if (Settings.verbose(this)) {
            LogBus.log("候选 ${cands.size} 个，最高分 ${best.score}「${best.rule.name}」")
        }

        // 有像素探针的，先截图取色校验
        val probe = best.rule.probe
        if (probe != null) {
            Screenshotter.capture(this) { bmp ->
                val ok = bmp != null && Screenshotter.pixelMatches(bmp, probe)
                if (ok) {
                    handler.post { startConfirmOrFire(best) }
                } else {
                    LogBus.log("像素探针不匹配，跳过「${best.rule.name}」")
                }
            }
        } else {
            startConfirmOrFire(best)
        }
    }

    private fun startConfirmOrFire(c: Cand) {
        if (paused) return
        val delay = Settings.confirmDelay(this)
        if (delay > 0) {
            val b = Rect()
            c.node.getBoundsInScreen(b)
            pending = Pending(c.rule.id, b.left, b.top, b.right, b.bottom, c.score)
            handler.postDelayed(scanRunnable, delay)
        } else {
            fire(c.rule, c.node, c.score)
        }
    }

    private fun fire(rule: Rule, node: AccessibilityNodeInfo, score: Int) {
        lastFire[rule.id] = SystemClock.uptimeMillis()
        LogBus.log("执行「${rule.name}」score=$score action=${rule.action.name}")
        doAction(rule.action, node)
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

    private fun collect(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        if (node.isVisibleToUser) out.add(node)
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), depth + 1, out)
            if (out.size >= MAX_NODES) return
        }
    }

    private fun findSameNode(nodes: List<AccessibilityNodeInfo>, pd: Pending): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestD = Int.MAX_VALUE
        for (n in nodes) {
            val b = Rect()
            n.getBoundsInScreen(b)
            val d = kotlin.math.abs(b.left - pd.left) + kotlin.math.abs(b.top - pd.top) +
                kotlin.math.abs(b.right - pd.right) + kotlin.math.abs(b.bottom - pd.bottom)
            if (d < bestD && d <= 24) {
                bestD = d
                best = n
            }
        }
        return best
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

    /** 唤起「点一下就学会」的拾取器 */
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
                LogBus.log("已生成规则：${rule.name}｜${rule.describe()}")
            }
            onDone()
        }
    }
}
