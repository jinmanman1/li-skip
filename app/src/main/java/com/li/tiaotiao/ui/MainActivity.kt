package com.li.tiaotiao.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.li.tiaotiao.R
import com.li.tiaotiao.core.LogBus
import com.li.tiaotiao.core.Rule
import com.li.tiaotiao.core.RuleStore
import com.li.tiaotiao.core.SkipService
import com.li.tiaotiao.core.Settings as AppSettings

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var rulesContainer: LinearLayout
    private lateinit var rulesHeader: TextView
    private lateinit var logText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    private val logListener: (String) -> Unit = { line ->
        handler.post {
            logLines.addLast(line)
            while (logLines.size > 40) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")
        }
    }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(RuleStore.exportJson().toByteArray())
                }
                toast("规则已导出")
            }.onFailure { toast("导出失败：${it.message}") }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val n = RuleStore.importJson(text)
                refreshRules()
                SkipService.instance?.reloadRules()
                toast("已导入 $n 条新规则")
            }.onFailure { toast("导入失败：${it.message}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        rulesContainer = findViewById(R.id.rulesContainer)
        rulesHeader = findViewById(R.id.rulesHeader)
        logText = findViewById(R.id.logText)

        findViewById<MaterialButton>(R.id.btnOpenService).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure { toast("打不开无障碍设置") }
            toast("在列表里找到「Li 跳过」并开启")
        }

        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { toast("请手动在系统设置里关闭电池优化") }
        }

        val swGlobal = findViewById<MaterialSwitch>(R.id.swGlobal)
        swGlobal.isChecked = AppSettings.enabled(this)
        swGlobal.setOnCheckedChangeListener { _, v ->
            AppSettings.setEnabled(this, v)
            toast(if (v) "已开启自动跳过" else "已暂停")
        }

        val swVerbose = findViewById<MaterialSwitch>(R.id.swVerbose)
        swVerbose.isChecked = AppSettings.verbose(this)
        swVerbose.setOnCheckedChangeListener { _, v -> AppSettings.setVerbose(this, v) }

        findViewById<MaterialButton>(R.id.btnPick).setOnClickListener {
            val svc = SkipService.instance
            if (svc == null) {
                toast("请先开启无障碍服务")
                return@setOnClickListener
            }
            svc.startPicker {
                runOnUiThread { refreshRules() }
            }
            toast("小球已出现，切到目标 App，走到广告页，再点小球")
        }

        findViewById<MaterialButton>(R.id.btnNew).setOnClickListener {
            startActivity(Intent(this, RuleEditorActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            runCatching { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
                .onFailure { toast("无法打开文件选择器") }
        }

        findViewById<MaterialButton>(R.id.btnExport).setOnClickListener {
            runCatching { exportLauncher.launch("li-skip-rules.json") }
                .onFailure { toast("无法创建文件") }
        }

        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复默认规则？")
                .setMessage("会覆盖你现在的所有规则。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    RuleStore.resetToDefault()
                    refreshRules()
                    SkipService.instance?.reloadRules()
                    toast("已恢复默认规则")
                }
                .show()
        }

        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            LogBus.clear()
            logLines.clear()
            logText.text = ""
        }

        logLines.addAll(LogBus.snapshot().takeLast(40))
        logText.text = logLines.joinToString("\n")

        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        LogBus.addListener(logListener)
    }

    override fun onPause() {
        LogBus.removeListener(logListener)
        super.onPause()
    }

    private fun refreshAll() {
        statusText.text = if (isAccessibilityOn()) {
            "无障碍服务：已开启"
        } else {
            "无障碍服务：未开启（不开就完全不工作）"
        }
        refreshRules()
    }

    private fun refreshRules() {
        val rules = RuleStore.load()
        rulesHeader.text = "规则（${rules.count { it.enabled }}/${rules.size} 条启用）"
        rulesContainer.removeAllViews()

        if (rules.isEmpty()) {
            val tv = TextView(this)
            tv.text = "还没有规则，点上面「拾取规则」试试"
            tv.alpha = 0.6f
            rulesContainer.addView(tv)
            return
        }

        for (r in rules) {
            rulesContainer.addView(buildRuleItem(r))
        }
    }

    private fun buildRuleItem(r: Rule): View {
        val item = layoutInflater.inflate(R.layout.item_rule, rulesContainer, false)
        item.findViewById<TextView>(R.id.ruleName).text = r.name
        item.findViewById<TextView>(R.id.ruleSummary).text = r.describe()

        val sw = item.findViewById<MaterialSwitch>(R.id.ruleSwitch)
        sw.isChecked = r.enabled
        sw.setOnCheckedChangeListener { _, checked ->
            RuleStore.toggle(r.id, checked)
            SkipService.instance?.reloadRules()
            refreshRules()
        }

        item.setOnClickListener {
            startActivity(
                Intent(this, RuleEditorActivity::class.java).putExtra("ruleId", r.id)
            )
        }

        item.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除「${r.name}」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    RuleStore.delete(r.id)
                    SkipService.instance?.reloadRules()
                    refreshRules()
                }
                .show()
            true
        }
        return item
    }

    private fun isAccessibilityOn(): Boolean {
        val expected = ComponentName(this, SkipService::class.java)
        val raw = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        if (raw.isEmpty()) return false
        return raw.split(':').any {
            it.equals(expected.flattenToString(), true) ||
                it.equals(expected.flattenToShortString(), true)
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
