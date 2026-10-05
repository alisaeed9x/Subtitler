package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** مجلد الترجمة في الخلفية: الفيديوهات بالترتيب (اللي فوق بيترجم الأول)، وكل مستني ليه ⬆ فوق · ⬇ تحت · ⏫ أول واحد · ▶ ابدأ دلوقتي */
class QueueUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private var dlg: Dialog? = null
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private var sig = ""
    val showing: Boolean get() = dlg?.isShowing == true

    private fun chip(t: String, f: () -> Unit) = TextView(act).apply {
        text = t; textSize = 12f; setTextColor(th.text); gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) }; setOnClickListener { f() }
    }
    private fun stateText(j: BgJob): String = when {
        j.state == "queued" -> if (j.paused) "⏸ مؤجّل" else "⏳ مستني دوره"
        j.state == "running" -> if (j.paused) "⏸ متوقف مؤقتًا — ${j.pct}%" else "🌙 بيترجم ${j.pct}%" + BgJobs.fmtRemain(j.remainSec).let { if (it.isEmpty()) "" else " · باقي $it" }
        j.state == "done" -> "✅ خلصت" + (if (j.srt.isNotEmpty()) " · اتحفظ SRT جنب الفيديو" else "") + (if (j.err.isNotEmpty()) " — " + j.err else "")
        j.state == "failed" -> "⚠ وقفت: " + j.err
        else -> "⏹ اتوقفت (${j.pct}%)"
    }

    fun refresh(force: Boolean = false) {
        val s = BgJobs.jobs.joinToString("|") { it.vid + it.state + it.pct + it.paused + (it.remainSec / 30) }
        if (!force && s == sig) return
        sig = s
        col.removeAllViews()
        val jobs = BgJobs.jobs.toList()
        val top = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        top.addView(chip("⏹ إيقاف الكل") { BgJobs.stopAll() }); top.addView(chip("🧹 مسح المنتهي") { BgJobs.clearFinished() })
        top.addView(chip("📊 الاستهلاك") { StatsUi(act, ui, th).show() })
        col.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        if (jobs.isEmpty()) { col.addView(ui.text("مجلد الترجمة في الخلفية فاضي.\nدوس ⋮ أو اضغط ضغطة مطولة على أي فيديو واختار «نقل لمجلد الترجمة في الخلفية».", 13f, th.muted).apply { setPadding(0, ui.dp(16), 0, ui.dp(16)) }); return }
        for (j in jobs) {
            val pos = if (j.state == "queued") BgJobs.position(j) else 0
            val title = ui.text((if (pos > 0) "#$pos  " else if (j.state == "running") "🌙  " else "") + j.title, 14f, th.text, true).apply { maxLines = 2 }
            val st = ui.text(stateText(j), 12f, th.primary)
            val fill = View(act).apply { setBackgroundColor(th.primary) }; val rest = View(act)
            val bar = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; background = ui.box(th.border, android.graphics.Color.TRANSPARENT, 2); clipToOutline = true
                addView(fill, LinearLayout.LayoutParams(0, -1, j.pct.coerceIn(0, 100).toFloat())); addView(rest, LinearLayout.LayoutParams(0, -1, (100 - j.pct).coerceIn(0, 100).toFloat())) }
            val btns = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val btnsScroll = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(btns) }
            if (j.active) {
                btns.addView(chip(if (j.paused) "▶ كمّل" else "⏸ إيقاف مؤقت") { if (j.paused) BgJobs.resume(act, j.vid) else BgJobs.pause(j.vid) })
                if (j.state == "queued") {
                    btns.addView(chip("⬆") { BgJobs.moveUp(j) }); btns.addView(chip("⬇") { BgJobs.moveDown(j) })
                    btns.addView(chip("⏫ أول واحد") { BgJobs.moveTop(j) }); btns.addView(chip("▶ ابدأ دلوقتي") { BgJobs.startNow(act, j) })
                }
            } else if (j.state != "done" || j.err.isNotEmpty()) btns.addView(chip("🔁 إعادة المحاولة") { if (!BgJobs.retry(act, j)) Toast.makeText(act, "بيترجم بالفعل", Toast.LENGTH_SHORT).show() })
            btns.addView(chip("✕ إزالة") { BgJobs.remove(j) })
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.box(th.card, th.border, 12)
                addView(title); addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) })
                addView(bar, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(6) })
                addView(btnsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            }
            col.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        }
    }

    fun show() {
        refresh(true)
        (col.parent as? android.view.ViewGroup)?.removeView(col)
        val sv = ScrollView(act).apply { addView(col) }
        dlg = ui.sheet(act, "📋 طابور الترجمة في الخلفية", listOf<View>(sv), true) { dlg = null }
        dlg!!.show()
    }
}
