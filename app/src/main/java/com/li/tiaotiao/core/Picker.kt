package com.li.tiaotiao.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 「拾取器」：应对冷门 App / 新 App 最省事的办法，不用写正则、不用知道 viewId。
 *
 * 流程：屏幕上浮出一个小球 → 切到目标 App 走到广告页 → 轻点小球 → 再轻点那个
 * 「跳过 / 关闭」按钮 → 自动生成一条规则（文本、id、相对位置、尺寸约束全部填好）。
 *
 * 小球只占一小块区域，其余手势穿透给下面的 App，所以不会挡住操作。
 */
class Picker(private val svc: SkipService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubble: View? = null
    private var mask: View? = null
    private var done = false

    fun show(onDone: (Rule?) -> Unit) {
        showBubble(onDone)
        toast("拖动可移动 · 轻点进入拾取 · 长按取消")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble(onDone: (Rule?) -> Unit) {
        val ctx: Context = svc
        val size = dp(56)
        val v = TextView(ctx).apply {
            text = "选"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE64C9AFF.toInt())
                setStroke(dp(2), 0x99FFFFFF.toInt())
            }
        }

        val (sw, sh) = svc.screenSize()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw - size - dp(8)).coerceAtLeast(0)
            y = sh / 3
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        var consumed = false

        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    consumed = false
                    v.postDelayed({
                        if (!dragged && !consumed) {
                            consumed = true
                            removeBubble()
                            finish(null, onDone)
                            toast("已取消拾取")
                        }
                    }, 700)
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragged = true
                    if (dragged) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged && !consumed) {
                        consumed = true
                        removeBubble()
                        showMask(onDone)
                    }
                }
            }
            true
        }

        runCatching { wm.addView(v, lp) }.onFailure {
            LogBus.log("悬浮球添加失败：${it.message}")
            finish(null, onDone)
            return
        }
        bubble = v
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showMask(onDone: (Rule?) -> Unit) {
        val ctx: Context = svc
        val container = FrameLayout(ctx)
        container.setBackgroundColor(0x4D000000)

        val tip = TextView(ctx).apply {
            text = "轻点要自动跳过的按钮"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        // 放到屏幕下方：开屏广告的「跳过」几乎都在右上角，提示文字压在上面会挡住你点
        val tipLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        tipLp.bottomMargin = dp(150)
        container.addView(tip, tipLp)

        val cancel = Button(ctx).apply {
            text = "取消"
            setOnClickListener { removeMask(); finish(null, onDone) }
        }
        val cancelLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        cancelLp.bottomMargin = dp(96)
        container.addView(cancel, cancelLp)

        container.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val rule = hitTest(e.rawX, e.rawY)
                removeMask()
                finish(rule, onDone)
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { wm.addView(container, lp) }.onFailure {
            LogBus.log("拾取遮罩添加失败：${it.message}")
            finish(null, onDone)
            return
        }
        mask = container
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun removeMask() {
        mask?.let { runCatching { wm.removeView(it) } }
        mask = null
    }

    private fun finish(rule: Rule?, onDone: (Rule?) -> Unit) {
        if (done) return
        done = true
        removeBubble()
        removeMask()
        if (rule != null) {
            val pkg = rule.packages.firstOrNull()
            val who = if (pkg == null) "所有应用" else labelOf(pkg)
            toast("已生成规则「${rule.name}」\n生效于 $who")
        }
        onDone(rule)
    }

    private fun labelOf(pkg: String): String = runCatching {
        val pm = svc.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** 找出点击位置下「最深、最小」的节点，通常就是那个按钮本身 */
    private fun hitTest(x: Float, y: Float): Rule? {
        val hits = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        val windows = runCatching { svc.windows }.getOrNull().orEmpty()
        for (w in windows) {
            val r = w.root ?: continue
            walk(r, 0, x, y, hits)
        }
        if (hits.isEmpty()) {
            toast("没抓到节点，请对准按钮中心再点一次")
            return null
        }

        val sorted = hits.sortedWith(
            compareByDescending<Pair<AccessibilityNodeInfo, Int>> { it.second }
                .thenBy { area(it.first) }
        )
        val node = sorted.first().first

        val (sw, sh) = svc.screenSize()
        if (sw <= 0 || sh <= 0) return null
        val b = Rect()
        node.getBoundsInScreen(b)
        if (b.width() <= 0 || b.height() <= 0) return null

        val text = node.text?.toString()?.trim().orEmpty()
        val vid = node.viewIdResourceName.orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = text.ifBlank {
            vid.substringAfterLast('/').ifBlank { desc.ifBlank { "节点" } }
        }

        val rule = Rule(name = "拾取 · $label")

        // 关键：包名取「这个节点所属的 App」，而不是 svc.pkg。
        // 拾取时服务是暂停状态，pkg 很可能还停在「Li 跳过」自己身上，
        // 那样生成的规则只会对自己生效，下次开屏当然不会动。
        val nodePkg = runCatching { node.packageName?.toString() }.getOrNull()
        val targetPkg = when {
            !nodePkg.isNullOrBlank() -> nodePkg
            svc.pkg.isNotBlank() -> svc.pkg
            else -> ""
        }
        if (targetPkg.isNotBlank() && targetPkg != svc.packageName) {
            rule.packages = mutableListOf(targetPkg)
        }

        // 区域往外扩一点，避免下次字体宽度、倒计时位数变化导致「有一点点出界」就判负
        val padX = sw * 0.02f
        val padY = sh * 0.02f
        rule.region = Region(
            ((b.left - padX) / sw).coerceIn(0f, 1f),
            ((b.top - padY) / sh).coerceIn(0f, 1f),
            ((b.right + padX) / sw).coerceIn(0f, 1f),
            ((b.bottom + padY) / sh).coerceIn(0f, 1f)
        )
        // 文案里带倒计时（「跳过 5」「3s」）时，必须把数字部分剥掉，
        // 否则下次数字一变就匹配不上 —— 这是「当时能用、下次失效」的另一个原因
        val baseText = stripCountdown(text)
        when {
            baseText.isNotEmpty() -> rule.keywords = mutableListOf(baseText)
            text.isNotEmpty() -> rule.textRegex = digitRegex(text)
            vid.isNotEmpty() -> rule.viewIdRegex = Regex.escape(vid)
            desc.isNotEmpty() -> rule.descRegex = Regex.escape(desc)
            else -> rule.allowPositional = true
        }
        rule.maxWidthFrac = (b.width().toFloat() / sw * 1.5f).coerceIn(0.15f, 0.95f)
        rule.maxHeightFrac = (b.height().toFloat() / sh * 1.5f).coerceIn(0.06f, 0.95f)
        // 拾取到的节点可能只是文字，真正响应点击的是整页触摸 —— 不强制要求可点击，靠区域锁死误触
        rule.mustClickable = false
        rule.minScore = 80
        rule.cooldownMs = 1200
        // 记下按钮中心的归一化坐标，用于「极速通道」：下次开屏不用等渲染直接点
        rule.tapX = b.centerX().toFloat() / sw
        rule.tapY = b.centerY().toFloat() / sh
        rule.fastTap = true
        rule.note = "拾取于 $targetPkg / ${svc.activityName}".trim()
        return rule
    }

    private fun walk(
        n: AccessibilityNodeInfo?,
        depth: Int,
        x: Float,
        y: Float,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>
    ) {
        if (n == null || depth > 60) return
        val b = Rect()
        n.getBoundsInScreen(b)
        if (!b.contains(x.toInt(), y.toInt())) return
        // 跳过拾取器自己的遮罩节点，否则会抓到提示文字，生成一条对自己生效的废规则
        val p = runCatching { n.packageName?.toString() }.getOrNull()
        if (p == null || p != svc.packageName) {
            if (n.isVisibleToUser) out.add(n to depth)
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, x, y, out)
    }

    private fun area(n: AccessibilityNodeInfo): Int {
        val b = Rect()
        n.getBoundsInScreen(b)
        return b.width() * b.height()
    }

    /**
     * 把「跳过 5」「跳过广告 3s」里的倒计时尾巴剥掉，留下稳定部分「跳过」「跳过广告」。
     * 纯数字文案（如「5s」）会被剥成空串，交给 digitRegex 处理。
     */
    private fun stripCountdown(text: String): String =
        text.trim().replace(Regex("""[\s:：]*\d{1,3}\s*(s|S|秒)?$"""), "").trim()

    /**
     * 把「跳过 5」这类文案转成「跳过 \d{1,3}」的正则，
     * 这样下次倒计时变成「跳过 4」也照样命中。
     */
    private fun digitRegex(text: String): String {
        val sb = StringBuilder()
        var inDigit = false
        for (ch in text.trim()) {
            if (ch.isDigit()) {
                if (!inDigit) {
                    sb.append("\\d{1,3}")
                    inDigit = true
                }
            } else {
                inDigit = false
                sb.append(Regex.escape(ch.toString()))
            }
        }
        return sb.toString()
    }

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show()
}
