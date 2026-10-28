package com.li.tiaotiao.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.li.tiaotiao.R
import com.li.tiaotiao.core.AppPicker
import com.li.tiaotiao.core.MatchMode
import com.li.tiaotiao.core.PixelProbe
import com.li.tiaotiao.core.Region
import com.li.tiaotiao.core.Rule
import com.li.tiaotiao.core.RuleAction
import com.li.tiaotiao.core.RuleStore
import com.li.tiaotiao.core.SkipService

class RuleEditorActivity : AppCompatActivity() {

    private var rule = Rule()
    private var apps: MutableList<String> = mutableListOf()
    private var posCell = -1

    private lateinit var cells: List<Button>
    private lateinit var blockText: View
    private lateinit var blockId: View
    private lateinit var blockPos: View
    private lateinit var blockAdvanced: View
    private lateinit var btnApps: MaterialButton
    private lateinit var rgMode: RadioGroup
    private lateinit var cbAdvanced: CheckBox

    private lateinit var etName: EditText
    private lateinit var etKeywords: EditText
    private lateinit var etViewId: EditText
    private lateinit var spTiming: Spinner
    private lateinit var spSize: Spinner
    private lateinit var spAction: Spinner
    private lateinit var spSens: Spinner

    private lateinit var etTextRegex: EditText
    private lateinit var etDesc: EditText
    private lateinit var etClass: EditText
    private lateinit var etRegion: EditText
    private lateinit var etMaxW: EditText
    private lateinit var etMaxH: EditText
    private lateinit var etMinScore: EditText
    private lateinit var etCooldown: EditText
    private lateinit var cbMustClickable: CheckBox
    private lateinit var cbAncestor: CheckBox
    private lateinit var cbPositional: CheckBox
    private lateinit var cbFastTap: CheckBox
    private lateinit var etProbeX: EditText
    private lateinit var etProbeY: EditText
    private lateinit var etProbeColor: EditText
    private lateinit var etNote: EditText

    private val actions = arrayOf(
        RuleAction.CLICK, RuleAction.CLICK_PARENT, RuleAction.PRESS_BACK, RuleAction.SWIPE_UP
    )
    private val sensScore = intArrayOf(90, 80, 68)
    private val sizeW = floatArrayOf(0.15f, 0.30f, 0.45f, 0.70f, 1.0f)
    private val sizeH = floatArrayOf(0.08f, 0.12f, 0.18f, 0.30f, 1.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        RuleStore.find(intent.getStringExtra("ruleId"))?.let { rule = it }

        bind()
        setupSpinners()
        setupListeners()
        fill()
    }

    private fun bind() {
        cells = listOf(
            findViewById<Button>(R.id.cell0), findViewById<Button>(R.id.cell1), findViewById<Button>(R.id.cell2),
            findViewById<Button>(R.id.cell3), findViewById<Button>(R.id.cell4), findViewById<Button>(R.id.cell5),
            findViewById<Button>(R.id.cell6), findViewById<Button>(R.id.cell7), findViewById<Button>(R.id.cell8)
        )
        blockText = findViewById(R.id.blockText)
        blockId = findViewById(R.id.blockId)
        blockPos = findViewById(R.id.blockPos)
        blockAdvanced = findViewById(R.id.blockAdvanced)
        btnApps = findViewById(R.id.btnApps)
        rgMode = findViewById(R.id.rgMode)
        cbAdvanced = findViewById(R.id.cbAdvanced)

        etName = findViewById(R.id.etName)
        etKeywords = findViewById(R.id.etKeywords)
        etViewId = findViewById(R.id.etViewId)
        spTiming = findViewById(R.id.spTiming)
        spSize = findViewById(R.id.spSize)
        spAction = findViewById(R.id.spAction)
        spSens = findViewById(R.id.spSens)

        etTextRegex = findViewById(R.id.etTextRegex)
        etDesc = findViewById(R.id.etDesc)
        etClass = findViewById(R.id.etClass)
        etRegion = findViewById(R.id.etRegion)
        etMaxW = findViewById(R.id.etMaxW)
        etMaxH = findViewById(R.id.etMaxH)
        etMinScore = findViewById(R.id.etMinScore)
        etCooldown = findViewById(R.id.etCooldown)
        cbMustClickable = findViewById(R.id.cbMustClickable)
        cbAncestor = findViewById(R.id.cbAncestor)
        cbPositional = findViewById(R.id.cbPositional)
        cbFastTap = findViewById(R.id.cbFastTap)
        etProbeX = findViewById(R.id.etProbeX)
        etProbeY = findViewById(R.id.etProbeY)
        etProbeColor = findViewById(R.id.etProbeColor)
        etNote = findViewById(R.id.etNote)
    }

    private fun setupSpinners() {
        val simple = android.R.layout.simple_spinner_dropdown_item
        spTiming.adapter = ArrayAdapter(this, simple, arrayOf("只在应用刚启动（开屏）", "任何时候"))
        spSize.adapter = ArrayAdapter(
            this, simple, arrayOf("很小（图标大小）", "小（一小块）", "中（普通按钮）", "大", "整块区域")
        )
        spAction.adapter = ArrayAdapter(
            this, simple, arrayOf("点击这个按钮", "点击它的父控件", "按返回键", "向上滑")
        )
        spSens.adapter = ArrayAdapter(
            this, simple, arrayOf("保守（宁可漏点）", "标准", "激进（容易点到）")
        )
    }

    private fun setupListeners() {
        rgMode.setOnCheckedChangeListener { _, _ -> updateModeBlocks() }

        cbAdvanced.setOnCheckedChangeListener { _, v ->
            blockAdvanced.visibility = if (v) View.VISIBLE else View.GONE
        }

        cells.forEachIndexed { index, b ->
            b.setOnClickListener {
                posCell = if (posCell == index) -1 else index
                updateCellVisuals()
            }
        }

        btnApps.setOnClickListener {
            AppPicker.show(this, apps) { picked ->
                apps = picked.toMutableList()
                updateAppsLabel()
            }
        }

        findViewById<MaterialButton>(R.id.btnKwSkip).setOnClickListener { addKeyword("跳过") }
        findViewById<MaterialButton>(R.id.btnKwClose).setOnClickListener { addKeyword("关闭") }
        findViewById<MaterialButton>(R.id.btnKwAd).setOnClickListener { addKeyword("广告") }
        findViewById<MaterialButton>(R.id.btnKwX).setOnClickListener { addKeyword("×") }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            if (RuleStore.find(rule.id) == null) {
                finish()
                return@setOnClickListener
            }
            RuleStore.delete(rule.id)
            SkipService.instance?.reloadRules()
            toast("已删除")
            finish()
        }
    }

    private fun fill() {
        etName.setText(rule.name)
        apps = rule.packages.toMutableList()
        updateAppsLabel()

        spTiming.setSelection(if (rule.onlySplash) 0 else 1)
        spAction.setSelection(rule.action.ordinal)
        spSens.setSelection(
            when {
                rule.minScore >= 88 -> 0
                rule.minScore >= 76 -> 1
                else -> 2
            }
        )

        etKeywords.setText(rule.keywords.joinToString(","))
        etViewId.setText(rule.viewIdRegex)
        etTextRegex.setText(rule.textRegex)
        etDesc.setText(rule.descRegex)
        etClass.setText(rule.classNameContains)
        etRegion.setText(rule.region?.toString() ?: "")
        etNote.setText(rule.note)
        cbMustClickable.isChecked = rule.mustClickable
        cbAncestor.isChecked = rule.allowAncestorClickable
        cbPositional.isChecked = rule.allowPositional
        cbFastTap.isChecked = rule.fastTap
        rule.probe?.let {
            etProbeX.setText(it.x.toString())
            etProbeY.setText(it.y.toString())
            etProbeColor.setText("#%06X".format(0xFFFFFF and it.color))
        }

        posCell = rule.region?.let { r ->
            val col = (((r.l + r.r) / 2f) * 3f).toInt().coerceIn(0, 2)
            val row = (((r.t + r.b) / 2f) * 3f).toInt().coerceIn(0, 2)
            row * 3 + col
        } ?: -1
        spSize.setSelection(nearestSize(rule.maxWidthFrac))

        val mode = when {
            rule.keywords.isNotEmpty() || rule.textRegex.isNotBlank() ||
                rule.descRegex.isNotBlank() || rule.classNameContains.isNotBlank() -> R.id.rbText
            rule.viewIdRegex.isNotBlank() -> R.id.rbId
            rule.allowPositional || rule.region != null -> R.id.rbPos
            else -> R.id.rbText
        }
        rgMode.check(mode)
        updateModeBlocks()

        val adv = rule.textRegex.isNotBlank() || rule.descRegex.isNotBlank() ||
            rule.classNameContains.isNotBlank() || rule.probe != null || rule.fastTap
        cbAdvanced.isChecked = adv
        blockAdvanced.visibility = if (adv) View.VISIBLE else View.GONE

        updateCellVisuals()
    }

    private fun updateAppsLabel() {
        btnApps.text = when {
            apps.isEmpty() -> "所有应用"
            apps.size == 1 -> "已选：" + appLabel(apps[0])
            else -> "已选 ${apps.size} 个应用"
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun updateModeBlocks() {
        blockText.visibility = if (rgMode.checkedRadioButtonId == R.id.rbText) View.VISIBLE else View.GONE
        blockId.visibility = if (rgMode.checkedRadioButtonId == R.id.rbId) View.VISIBLE else View.GONE
        blockPos.visibility = if (rgMode.checkedRadioButtonId == R.id.rbPos) View.VISIBLE else View.GONE
    }

    private fun updateCellVisuals() {
        val on = ContextCompat.getColor(this, R.color.primary)
        val off = ContextCompat.getColor(this, R.color.card_alt)
        cells.forEachIndexed { i, b ->
            val selected = i == posCell
            b.backgroundTintList = ColorStateList.valueOf(if (selected) on else off)
            b.setTextColor(
                if (selected) ContextCompat.getColor(this, R.color.on_primary)
                else ContextCompat.getColor(this, R.color.text)
            )
        }
    }

    private fun addKeyword(kw: String) {
        val cur = etKeywords.text.toString().trim()
        if (cur.isEmpty()) {
            etKeywords.setText(kw)
            return
        }
        val list = splitList(cur)
        if (list.none { it == kw }) list.add(kw)
        etKeywords.setText(list.joinToString(","))
    }

    private fun nearestSize(w: Float): Int {
        var best = 2
        var diff = Float.MAX_VALUE
        sizeW.forEachIndexed { i, v ->
            val d = kotlin.math.abs(v - w)
            if (d < diff) {
                diff = d
                best = i
            }
        }
        return best
    }

    private fun cellRegion(idx: Int): Region {
        val col = idx % 3
        val row = idx / 3
        return Region(col / 3f, row / 3f, (col + 1) / 3f, (row + 1) / 3f)
    }

    private fun save() {
        val mode = rgMode.checkedRadioButtonId

        rule.name = etName.text.toString().trim().ifBlank { "未命名规则" }
        rule.packages = apps
        rule.onlySplash = spTiming.selectedItemPosition == 0
        rule.action = actions[spAction.selectedItemPosition]
        rule.keywordMode = MatchMode.CONTAINS
        rule.textRegex = etTextRegex.text.toString().trim()
        rule.descRegex = etDesc.text.toString().trim()
        rule.classNameContains = etClass.text.toString().trim()
        rule.allowAncestorClickable = cbAncestor.isChecked
        rule.mustClickable = cbMustClickable.isChecked || mode == R.id.rbPos
        rule.cooldownMs = (etCooldown.text.toString().toLongOrNull() ?: 1500L).coerceAtLeast(200L)
        rule.minScore = etMinScore.text.toString().toIntOrNull()
            ?: sensScore[spSens.selectedItemPosition]

        when (mode) {
            R.id.rbId -> {
                val v = etViewId.text.toString().trim()
                if (v.isEmpty()) {
                    toast("填一下控件 ID，比如 skip_btn")
                    return
                }
                rule.viewIdRegex = v
                rule.keywords = mutableListOf()
                rule.allowPositional = false
            }

            R.id.rbPos -> {
                if (posCell < 0 && etRegion.text.toString().isBlank()) {
                    toast("先在九宫格里点一个大概位置")
                    return
                }
                rule.keywords = mutableListOf()
                rule.viewIdRegex = ""
                rule.allowPositional = true
            }

            else -> {
                val kws = splitList(etKeywords.text.toString())
                if (kws.isEmpty() && rule.textRegex.isBlank() && rule.descRegex.isBlank()) {
                    toast("写一下按钮上的文字，或者切换到别的识别方式")
                    return
                }
                rule.keywords = kws
                rule.viewIdRegex = ""
                rule.allowPositional = false
            }
        }

        val regionText = etRegion.text.toString().trim()
        if (regionText.isNotEmpty()) {
            val r = Region.parse(regionText)
            if (r == null) {
                toast("区域格式不对，应该是 0.4,0,1,0.35")
                return
            }
            rule.region = r
        } else if (mode == R.id.rbPos && posCell >= 0) {
            rule.region = cellRegion(posCell)
        }

        val wText = etMaxW.text.toString().trim()
        val hText = etMaxH.text.toString().trim()
        rule.maxWidthFrac = when {
            wText.isNotEmpty() -> wText.toFloatOrNull() ?: rule.maxWidthFrac
            mode == R.id.rbPos -> sizeW[spSize.selectedItemPosition]
            else -> rule.maxWidthFrac
        }
        rule.maxHeightFrac = when {
            hText.isNotEmpty() -> hText.toFloatOrNull() ?: rule.maxHeightFrac
            mode == R.id.rbPos -> sizeH[spSize.selectedItemPosition]
            else -> rule.maxHeightFrac
        }

        val colorText = etProbeColor.text.toString().trim()
        if (colorText.isEmpty()) {
            rule.probe = null
        } else {
            val c = colorText.removePrefix("#").toLongOrNull(16)?.toInt()
            if (c == null) {
                toast("颜色写成 #RRGGBB，比如 #FFFFFF")
                return
            }
            rule.probe = PixelProbe(
                x = etProbeX.text.toString().toFloatOrNull() ?: 0.5f,
                y = etProbeY.text.toString().toFloatOrNull() ?: 0.5f,
                color = c,
                tolerance = 48
            )
        }

        rule.note = etNote.text.toString().trim()
        rule.allowPositional = if (mode == R.id.rbPos) true else cbPositional.isChecked

        // 极速模式：没有记录坐标就退化成「区域中心」
        rule.fastTap = cbFastTap.isChecked
        if (rule.fastTap && (rule.tapX == null || rule.tapY == null)) {
            rule.region?.let { g ->
                rule.tapX = (g.l + g.r) / 2f
                rule.tapY = (g.t + g.b) / 2f
            }
        }

        RuleStore.upsert(rule)
        SkipService.instance?.reloadRules()
        toast("已保存")
        finish()
    }

    private fun splitList(s: String): MutableList<String> =
        s.split(',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
