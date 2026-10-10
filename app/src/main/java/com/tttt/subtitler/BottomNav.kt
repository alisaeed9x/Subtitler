package com.tttt.subtitler

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** (v117) شريط البوابات السفلي: 🎞 الفيديوهات · 🎵 الموسيقى · 🌐 المتصفح (v189) — بيظهر في الشاشة الرئيسية وفي المتصفح */
class BottomNav(act: Activity, private val ui: Ui, private val th: Theme, private var active: Int, private val onPick: (Int) -> Unit) {
    private val icons = ArrayList<IconTextView>()
    private val labels = ArrayList<IconTextView>()
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private var rowV: View? = null
    private var lineV: View? = null
    private var tinted = false

    init {
        val line = View(act).apply { setBackgroundColor(th.border) }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(th.card)
        }
        val defs = listOf("🎞" to "الفيديوهات", "🎵" to "الموسيقى", "🌐" to "المتصفح")
        defs.forEachIndexed { idx, d ->
            val icon = IconTextView(act).apply { text = d.first; textSize = 20f; gravity = Gravity.CENTER; includeFontPadding = false }
            val lab = ui.text(d.second, 11f, th.muted, true).apply { gravity = Gravity.CENTER; setSingleLine() }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(icon, LinearLayout.LayoutParams(ui.dp(30), ui.dp(26)))
                addView(lab, LinearLayout.LayoutParams(-2, -2))
                Glass.pressable(this)
                setOnClickListener { onPick(idx) }
            }
            icons.add(icon); labels.add(lab)
            row.addView(cell, LinearLayout.LayoutParams(0, ui.dp(58), 1f))
        }
        rowV = row; lineV = line
        view.addView(line, LinearLayout.LayoutParams(-1, ui.dp(1)))
        view.addView(row, LinearLayout.LayoutParams(-1, -2))
        paint()
    }

    fun set(i: Int) { active = i; paint() }
    /** (v135) نص تحت أيقونة البوابة (بيتغيّر لـ «المهام (2)» لما فيه مهام شغّالة) */
    fun label(i: Int, t: String) { if (i in labels.indices) labels[i].text = t }
    /** (v198) لون الشريط وهو مشغّل الموسيقى مفتوح (بيتبع لون الغلاف)؛ null = رجّعه للثيم */
    fun tint(c: Int?) {
        tinted = c != null
        rowV?.setBackgroundColor(c ?: th.card)
        lineV?.setBackgroundColor(if (c != null) 0x22FFFFFF else th.border)
        paint()
    }
    private fun paint() {
        for (i in icons.indices) {
            val on = i == active
            val off = if (tinted) 0xAAFFFFFF.toInt() else th.muted
            icons[i].setTextColor(if (on) th.primary else off)
            labels[i].setTextColor(if (on) th.primary else off)
        }
    }
}
