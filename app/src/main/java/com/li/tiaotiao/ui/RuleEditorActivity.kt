package com.li.tiaotiao.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.li.tiaotiao.R
import com.li.tiaotiao.core.MatchMode
import com.li.tiaotiao.core.PixelProbe
import com.li.tiaotiao.core.Region
import com.li.tiaotiao.core.Rule
import com.li.tiaotiao.core.RuleAction
import com.li.tiaotiao.core.RuleStore
import com.li.tiaotiao.core.SkipService

class RuleEditorActivity : AppCompatActivity() {

    private var rule: Rule = Rule()

    private lateinit var etName: EditText
    private lateinit var etPackages: EditText
    private lateinit var cbOnlySplash: CheckBox
    private lateinit var cbActivityOnly: CheckBox
    private lateinit var etActivityRegex: EditText
    private lateinit var etKeywords: EditText
    private lateinit var spMode: Spinner
    private lateinit var etTextRegex: EditText
    private lateinit var etViewId: EditText
    private lateinit var etDesc: EditText
    private lateinit var etClass: EditText
    private lateinit var etRegion: EditText
    private lateinit var etMaxW: EditText
    private lateinit var etMaxH: EditText
    private lateinit var spAction: Spinner
    private lateinit var etMinScore: EditText
    private lateinit var etCooldown: EditText
    private lateinit var cbMustClickable: CheckBox
    private lateinit var cbAncestor: CheckBox
    private lateinit var cbPositional: CheckBox
    private lateinit var etProbeX: EditText
    private lateinit var etProbeY: EditText
    private lateinit var etProbeColor: EditText
    private lateinit var etTolerance: EditText
    private lateinit var etNote: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        RuleStore.find(intent.getStringExtra("ruleId"))?.let { rule = it }

        bindViews()
        setupSpinners()
        fill()

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnDelete).setOnClickListener {
            if (rule.id.isBlank() || RuleStore.find(rule.id) == null) {
                finish()
                return@setOnClickListener
            }
            RuleStore.delete(rule.id)
            SkipService.instance?.reloadRules()
            toast("已删除")
            finish()
        }
    }

    private fun bindViews() {
        etName = findViewById(R.id.etName)
        etPackages = findViewById(R.id.etPackages)
        cbOnlySplash = findViewById(R.id.cbOnlySplash)
        cbActivityOnly = findViewById(R.id.cbActivityOnly)
        etActivityRegex = findViewById(R.id.etActivityRegex)
        etKeywords = findViewById(R.id.etKeywords)
        spMode = findViewById(R.id.spMode)
        etTextRegex = findViewById(R.id.etTextRegex)
        etViewId = findViewById(R.id.etViewId)
        etDesc = findViewById(R.id.etDesc)
        etClass = findViewById(R.id.etClass)
        etRegion = findViewById(R.id.etRegion)
        etMaxW = findViewById(R.id.etMaxW)
        etMaxH = findViewById(R.id.etMaxH)
        spAction = findViewById(R.id.spAction)
        etMinScore = findViewById(R.id.etMinScore)
        etCooldown = findViewById(R.id.etCooldown)
        cbMustClickable = findViewById(R.id.cbMustClickable)
        cbAncestor = findViewById(R.id.cbAncestor)
        cbPositional = findViewById(R.id.cbPositional)
        etProbeX = findViewById(R.id.etProbeX)
        etProbeY = findViewById(R.id.etProbeY)
        etProbeColor = findViewById(R.id.etProbeColor)
        etTolerance = findViewById(R.id.etTolerance)
        etNote = findViewById(R.id.etNote)
    }

    private fun setupSpinners() {
        spMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("包含", "完全相等", "正则")
        )
        spAction.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("点击", "点击父节点", "返回键", "上滑")
        )
    }

    private fun fill() {
        etName.setText(rule.name)
        etPackages.setText(rule.packages.joinToString(","))
        cbOnlySplash.isChecked = rule.onlySplash
        cbActivityOnly.isChecked = rule.activityOnly
        etActivityRegex.setText(rule.activityRegex)
        etKeywords.setText(rule.keywords.joinToString(","))
        spMode.setSelection(rule.keywordMode.ordinal)
        etTextRegex.setText(rule.textRegex)
        etViewId.setText(rule.viewIdRegex)
        etDesc.setText(rule.descRegex)
        etClass.setText(rule.classNameContains)
        etRegion.setText(rule.region?.toString() ?: "")
        etMaxW.setText(rule.maxWidthFrac.toString())
        etMaxH.setText(rule.maxHeightFrac.toString())
        spAction.setSelection(rule.action.ordinal)
        etMinScore.setText(rule.minScore.toString())
        etCooldown.setText(rule.cooldownMs.toString())
        cbMustClickable.isChecked = rule.mustClickable
        cbAncestor.isChecked = rule.allowAncestorClickable
        cbPositional.isChecked = rule.allowPositional
        rule.probe?.let {
            etProbeX.setText(it.x.toString())
            etProbeY.setText(it.y.toString())
            etProbeColor.setText("#%06X".format(0xFFFFFF and it.color))
            etTolerance.setText(it.tolerance.toString())
        }
        etNote.setText(rule.note)
    }

    private fun save() {
        val regionText = etRegion.text.toString().trim()
        val region = if (regionText.isEmpty()) null else Region.parse(regionText)
        if (regionText.isNotEmpty() && region == null) {
            toast("区域格式不对，应为 0.4,0,1,0.35")
            return
        }

        val probeColor = etProbeColor.text.toString().trim()
        var probe: PixelProbe? = null
        if (probeColor.isNotEmpty()) {
            val hex = probeColor.removePrefix("#")
            val color = hex.toLongOrNull(16)?.toInt()
            if (color == null) {
                toast("颜色格式不对，应为 #RRGGBB")
                return
            }
            probe = PixelProbe(
                x = etProbeX.text.toString().toFloatOrNull() ?: 0.5f,
                y = etProbeY.text.toString().toFloatOrNull() ?: 0.5f,
                color = color,
                tolerance = etTolerance.text.toString().toIntOrNull() ?: 48
            )
        }

        rule.name = etName.text.toString().trim().ifBlank { "未命名规则" }
        rule.packages = splitList(etPackages.text.toString())
        rule.onlySplash = cbOnlySplash.isChecked
        rule.activityOnly = cbActivityOnly.isChecked
        rule.activityRegex = etActivityRegex.text.toString().trim()
        rule.keywords = splitList(etKeywords.text.toString())
        rule.keywordMode = MatchMode.values()[spMode.selectedItemPosition]
        rule.textRegex = etTextRegex.text.toString().trim()
        rule.viewIdRegex = etViewId.text.toString().trim()
        rule.descRegex = etDesc.text.toString().trim()
        rule.classNameContains = etClass.text.toString().trim()
        rule.region = region
        rule.maxWidthFrac = etMaxW.text.toString().toFloatOrNull() ?: 0.45f
        rule.maxHeightFrac = etMaxH.text.toString().toFloatOrNull() ?: 0.16f
        rule.action = RuleAction.values()[spAction.selectedItemPosition]
        rule.minScore = (etMinScore.text.toString().toIntOrNull() ?: 75).coerceIn(1, 100)
        rule.cooldownMs = (etCooldown.text.toString().toLongOrNull() ?: 1500L).coerceAtLeast(100L)
        rule.mustClickable = cbMustClickable.isChecked
        rule.allowAncestorClickable = cbAncestor.isChecked
        rule.allowPositional = cbPositional.isChecked
        rule.probe = probe
        rule.note = etNote.text.toString().trim()

        RuleStore.upsert(rule)
        SkipService.instance?.reloadRules()
        toast("已保存")
        finish()
    }

    private fun splitList(s: String): MutableList<String> =
        s.split(',', '，', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
