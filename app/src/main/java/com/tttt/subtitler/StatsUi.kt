package com.tttt.subtitler

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** صفحة إحصائية الاستهلاك: دقائق اترجمت (اليوم/الأسبوع/الشهر) + رسم آخر 7 أيام + طلبات كل مفتاح + الكوتة الباقية */
class StatsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private fun mins(sec: Double) = String.format(Locale.US, "%.1f د", sec / 60.0)
    private fun head(t: String) = ui.text(t, 13f, th.primary, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(4)) }
    private fun line(t: String) = ui.text(t, 13f, th.text).apply { setPadding(0, ui.dp(2), 0, ui.dp(2)) }

    fun show() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val d1 = Stats.range(1); val d7 = Stats.range(7); val d30 = Stats.range(30)
        col.addView(head("⏱ اللي اترجم"))
        col.addView(line("النهارده: ${mins(d1.sec)}   ·   آخر 7 أيام: ${mins(d7.sec)}   ·   آخر 30 يوم: ${mins(d30.sec)}"))
        col.addView(head("📡 عدد الطلبات"))
        col.addView(line("النهارده: ${d1.req}   ·   آخر 7 أيام: ${d7.req}   ·   آخر 30 يوم: ${d30.req}"))

        // رسم آخر 7 أيام
        col.addView(head("📈 دقائق كل يوم (آخر 7 أيام)"))
        val days = Stats.daily(7); val mx = (days.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1.0)
        for ((name, m) in days) {
            val fill = View(act).apply { setBackgroundColor(th.primary) }; val rest = View(act)
            val bar = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; background = ui.box(th.border, android.graphics.Color.TRANSPARENT, 3); clipToOutline = true
                addView(fill, LinearLayout.LayoutParams(0, -1, (m / mx).toFloat().coerceIn(0f, 1f) * 100f)); addView(rest, LinearLayout.LayoutParams(0, -1, (1f - (m / mx).toFloat().coerceIn(0f, 1f)) * 100f)) }
            val row = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(name, 11f, th.muted), LinearLayout.LayoutParams(ui.dp(48), -2))
                addView(bar, LinearLayout.LayoutParams(0, ui.dp(10), 1f))
                addView(ui.text(String.format(Locale.US, "%.1f", m), 11f, th.text).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(ui.dp(46), -2)) }
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        }

        // كل مفتاح
        col.addView(head("🔑 الطلبات لكل مفتاح (النهارده · آخر 7 أيام)"))
        val main = Cfg.allMainKeys(); val bk = Cfg.keys("backup")
        val all = (main.map { it to "أساسي" } + bk.filter { it !in main }.map { it to "احتياطي" })
        if (all.isEmpty()) col.addView(line("مفيش مفاتيح متسجلة."))
        all.forEach { (k, role) -> col.addView(line("…${k.takeLast(4)}  ($role):  ${d1.keys[k.takeLast(4)] ?: 0}  ·  ${d7.keys[k.takeLast(4)] ?: 0}")) }

        // الكوتة
        col.addView(head("📊 الكوتة اليومية (تقريبي، بتتصفّر 00:00 بتوقيت المحيط الهادي)"))
        val nKeys = maxOf(1, all.size)
        val models = (listOf(Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }) + d1.models.keys).distinct()
        models.forEach { m ->
            val used = Quota.used(m); val perKey = Models.quotaOf(m); val total = perKey * nKeys
            col.addView(line("$m:  اتستخدم $used من $total  ·  فاضل حوالي ${maxOf(0, total - used)}"))
        }
        col.addView(ui.text("الإجمالي = كوتة المفتاح الواحد × عدد المفاتيح ($nKeys)، وده صحيح لو كل مفتاح من مشروع Google مختلف. العدّاد محلي على الجهاز ومش من جوجل.", 11f, th.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
        col.addView(ui.button("🗑 تصفير الإحصائيات") { Stats.reset(); Notice.show(act, ("اتصفّرت").toString(), 2300L) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        ui.sheet(act, "📊 إحصائية الاستهلاك", listOf<View>(col)).show()
    }
}
