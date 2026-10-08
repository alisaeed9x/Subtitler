package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** (v135) توبيب «المهام»: كل عمليات القص والصوت والـ GIF والضغط والترجمة الثابتة، بتقدّمها وناتجها */
class TasksUi(private val act: Activity, private val ui: Ui, private val th: Theme, private val onTool: (String) -> Unit = {}) {
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }
    private val empty = ui.text("مفيش مهام لسه.\nدوس على أي أداة فوق واختار الفيديو، أو افتح فيديو في المشغّل وادوس 🧰 أدوات. التحميلات (⬇) بتظهر هنا كمان.", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(60), ui.dp(24), 0) }

    private class Row(val card: LinearLayout, val title: TextView, val status: TextView, val bar: ProgressBar, val btns: LinearLayout)
    private val rows = HashMap<Int, Row>()

    init {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(6)) }
        head.addView(ui.text("⏳ المهام", 18f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.button("🗑 مسح المنتهي") { TaskCenter.clearFinished() }, LinearLayout.LayoutParams(-2, -2))
        val sv = ScrollView(act).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        // (v137) الأدوات مباشرة من هنا: دوس الأداة → اختار الفيديو → نافذة الإعدادات → تبدأ
        fun toolRow(vararg p: Pair<String, String>): LinearLayout {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2)) }
            for ((k, lab) in p) r.addView(ui.button(lab) { onTool(k) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            return r
        }
        root.addView(toolRow("trim" to "✂ قص", "audio" to "🎧 صوت", "gif" to "🎞 GIF"), LinearLayout.LayoutParams(-1, -2))
        root.addView(toolRow("compress" to "📦 ضغط / دقة", "hardsub" to "🎬 ترجمة ثابتة"), LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh()
    }

    private fun statusOf(t: TaskItem): String = when (t.state) {
        0 -> "⏳ في الانتظار"
        1 -> (if (t.paused) "⏸ واقف مؤقتًا… " else "⚙ شغّال… ") + t.pct + "%" + (if (t.msg.isNotBlank()) "  ·  " + t.msg else "")
        2 -> "✅ خلصت وتحفظت" + (if (t.outPath.isNotBlank()) "\n" + t.outPath else "") + (if (t.outSize > 0) "  ·  " + Sniff.fmtSize(t.outSize) else "")
        3 -> "❌ فشلت: " + t.msg
        else -> "اتلغت"
    }

    private fun open(t: TaskItem, share: Boolean) {
        val u = t.outUri ?: return
        try {
            val i = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(t.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "مشاركة")
                    else Intent(Intent.ACTION_VIEW).setDataAndType(u, t.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(i)
        } catch (_: Throwable) { Notice.show(act, "مفيش تطبيق يفتح الملف ده", 2400L) }
    }

    private fun makeRow(t: TaskItem): Row {
        val card = ui.card()
        val title = ui.text(t.title, 14f, th.text, true)
        val status = ui.text("", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(th.primary)
        }
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        card.addView(title); card.addView(status); card.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(8))); card.addView(btns)
        return Row(card, title, status, bar, btns)
    }

    private fun paint(t: TaskItem, r: Row) {
        r.status.text = statusOf(t)
        r.bar.visibility = if (t.state == 1 || t.state == 0) View.VISIBLE else View.GONE
        r.bar.progress = t.pct
        r.btns.removeAllViews()
        fun add(label: String, f: () -> Unit) = r.btns.addView(ui.button(label) { f() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        when (t.state) {
            0, 1 -> {
                if (t.kind == "download" && t.state == 1) add(if (t.paused) "▶ استكمال" else "⏸ إيقاف") { TaskCenter.pause(t, !t.paused) }
                add("✕ إلغاء") { TaskCenter.cancel(t) }
            }
            2 -> { if (t.outUri != null) { add("▶ فتح") { open(t, false) }; add("📤 مشاركة") { open(t, true) } }; add("🗑 حذف") { TaskCenter.remove(t) } }
            else -> { if (t.workKeep != null) add("🔁 إعادة") { TaskCenter.retry(t) }; add("🗑 حذف") { TaskCenter.remove(t) } }
        }
    }

    fun refresh() {
        val items = TaskCenter.items.toList()
        val ids = items.map { it.id }.toSet()
        val gone = rows.keys.filter { it !in ids }
        for (k in gone) { rows.remove(k)?.let { list.removeView(it.card) } }
        if (items.isEmpty()) { if (empty.parent == null) list.addView(empty) } else if (empty.parent != null) list.removeView(empty)
        items.forEachIndexed { idx, t ->
            var r = rows[t.id]
            if (r == null) { r = makeRow(t); rows[t.id] = r; list.addView(r.card, minOf(idx, list.childCount)); r.card.tag = -1 }
            // الأزرار بتتبني من جديد بس لما حالة المهمة تتغيّر (عشان الدوسة ما تضيعش وسط تحديث التقدم)
            val sig = t.state * 2 + (if (t.paused) 1 else 0)
            if (r.card.tag != sig) { r.card.tag = sig; paint(t, r) } else { r.status.text = statusOf(t); r.bar.progress = t.pct }
        }
    }
}
