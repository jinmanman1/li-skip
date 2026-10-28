package com.li.tiaotiao.core

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.li.tiaotiao.R

/** 应用选择器：列出已安装的桌面应用，勾选而不是手打包名 */
object AppPicker {

    data class AppEntry(val pkg: String, val label: String, val icon: Drawable)

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrNull().orEmpty()
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            val icon = runCatching { ri.loadIcon(pm) }.getOrNull() ?: continue
            out.add(AppEntry(pkg, label, icon))
        }
        out.sortBy { it.label }
        return out
    }

    fun show(ctx: Context, preselect: List<String>, onDone: (List<String>) -> Unit) {
        val apps = load(ctx)
        if (apps.isEmpty()) {
            onDone(preselect)
            return
        }
        val checked = BooleanArray(apps.size)
        apps.forEachIndexed { i, a -> checked[i] = preselect.contains(a.pkg) }

        val listView = ListView(ctx)
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val v = convertView
                    ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
                val a = apps[position]
                v.findViewById<TextView>(R.id.appLabel).text = a.label
                v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(a.icon)
                v.findViewById<CheckBox>(R.id.appCheck).isChecked = checked[position]
                v.setOnClickListener {
                    checked[position] = !checked[position]
                    v.findViewById<CheckBox>(R.id.appCheck).isChecked = checked[position]
                }
                return v
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("选择要生效的应用")
            .setView(listView)
            .setPositiveButton("确定") { _, _ ->
                onDone(apps.filterIndexed { i, _ -> checked[i] }.map { it.pkg })
            }
            .setNeutralButton("所有应用") { _, _ -> onDone(emptyList()) }
            .setNegativeButton("取消", null)
            .show()
    }
}
