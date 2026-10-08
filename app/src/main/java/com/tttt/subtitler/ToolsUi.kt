package com.tttt.subtitler

import android.app.Activity
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** (v135) نوافذ أدوات الفيديو (🧰 في المشغّل): قص · صوت · GIF · ضغط · ترجمة ثابتة — كلها بتضيف مهمة لتوبيب «المهام» */
class ToolsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private fun col(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(4))
    }
    private fun label(t: String) = ui.text(t, 13f, th.muted, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) }
    private fun note(t: String) = ui.text(t, 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, 0) }
    private fun toast(m: String) = Notice.show(act, m, 3200L)
    private fun added() = toast("اتضافت لتوبيب «المهام» — هتتحفظ لوحدها لما تخلص")

    /** بيقبل 90 أو 1:30 أو 1:02:03 */
    private fun parse(s: String): Long? {
        val p = s.trim().split(":")
        if (p.isEmpty() || p.size > 3 || p.any { it.isBlank() }) return null
        var sec = 0.0
        for (x in p) { val v = x.trim().toDoubleOrNull() ?: return null; if (v < 0) return null; sec = sec * 60 + v }
        return (sec * 1000).toLong()
    }
    private fun qChips(get: () -> String, set: (String) -> Unit, extra: List<String> = emptyList()) =
        ui.chips(Tools.QUALS + extra, get, set)

    private fun timeRow(title: String, init: String, now: Long): Pair<LinearLayout, android.widget.EditText> {
        val et = ui.input(title, init).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER }
        val btn = ui.button("⏱ الوقت الحالي") { et.setText(Tools.clock(now)) }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, 0, 0) })
        return Pair(row, et)
    }

    fun trim(s: ToolSrc) {
        if (s.audioUri != null) { toast("الفيديو ده صورته وصوته منفصلين (زي يوتيوب) — حمّله من ⬇ الأول وبعدين اقصّه"); return }
        var q = "عالية"
        val c = col()
        val endDef = if (s.durMs > 0) minOf(s.durMs, s.curMs + 30_000L) else s.curMs + 30_000L
        c.addView(note("المدة: " + (if (s.durMs > 0) Tools.clock(s.durMs) else "؟") + "  ·  اكتب الوقت كده 1:30"))
        c.addView(label("من"))
        val (r1, a) = timeRow("البداية", Tools.clock(s.curMs), s.curMs); c.addView(r1)
        c.addView(label("إلى"))
        val (r2, b) = timeRow("النهاية", Tools.clock(endDef), s.curMs); c.addView(r2)
        c.addView(label("الجودة")); c.addView(qChips({ q }, { q = it }))
        GAlert(act).setTitle("✂ قص جزء من الفيديو").setView(c)
            .setPositiveButton("ابدأ القص") { _, _ ->
                val st = parse(a.text.toString()); val en = parse(b.text.toString())
                when {
                    st == null || en == null -> toast("الوقت مش مكتوب صح — مثال 1:30")
                    en <= st -> toast("النهاية لازم تكون بعد البداية")
                    s.durMs > 0 && st >= s.durMs -> toast("البداية بعد نهاية الفيديو")
                    else -> { Tools.trim(act, s, st, if (s.durMs > 0) minOf(en, s.durMs) else en, q); added() }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    fun audio(s: ToolSrc) {
        var q = "متوسطة"
        val c = col()
        c.addView(note("بيتحفظ M4A (AAC) في Music/Subtitler. «الأصلية» = نسخ الصوت زي ما هو من غير إعادة ترميز (أسرع، وبتشتغل لو الصوت AAC)."))
        c.addView(label("الجودة")); c.addView(qChips({ q }, { q = it }, listOf(Tools.ORIG)))
        GAlert(act).setTitle("🎧 تحويل الفيديو لصوت").setView(c)
            .setPositiveButton("ابدأ") { _, _ -> Tools.audio(act, s, q); added() }.setNegativeButton("إلغاء", null).show()
    }

    fun gif(s: ToolSrc) {
        var q = "متوسطة"; var len = "5 ثواني"
        val lens = mapOf("3 ثواني" to 3000L, "5 ثواني" to 5000L, "10 ثواني" to 10000L, "15 ثانية" to 15000L)
        val c = col()
        c.addView(label("بداية المقطع"))
        val (r1, a) = timeRow("البداية", Tools.clock(s.curMs), s.curMs); c.addView(r1)
        c.addView(label("طول المقطع")); c.addView(ui.chips(lens.keys.toList(), { len }, { len = it }))
        c.addView(label("الجودة")); c.addView(qChips({ q }, { q = it }))
        c.addView(note("الـ GIF بيطلع من غير صوت، والجودة العالية بتكبّر الحجم."))
        GAlert(act).setTitle("🎞 عمل GIF").setView(c)
            .setPositiveButton("ابدأ") { _, _ ->
                val st = parse(a.text.toString())
                if (st == null) toast("الوقت مش مكتوب صح — مثال 1:30")
                else { Tools.gif(act, s, st, lens[len] ?: 5000L, q); added() }
            }.setNegativeButton("إلغاء", null).show()
    }

    fun compress(s: ToolSrc) {
        if (s.audioUri != null) { toast("الفيديو ده صورته وصوته منفصلين (زي يوتيوب) — حمّله من ⬇ الأول وبعدين اضغطه"); return }
        var q = "متوسطة"; var res = "نفس الدقة"
        val c = col()
        c.addView(label("الدقة")); c.addView(ui.chips(listOf("نفس الدقة", "720p", "480p", "360p", "240p"), { res }, { res = it }))
        c.addView(label("الجودة")); c.addView(qChips({ q }, { q = it }))
        c.addView(note("«منخفضة» بتصغّر الحجم أكتر على حساب الوضوح. الضغط بيعيد ترميز الفيديو كله فبياخد وقت."))
        GAlert(act).setTitle("📦 ضغط الفيديو / تغيير الدقة").setView(c)
            .setPositiveButton("ابدأ") { _, _ ->
                val h = res.removeSuffix("p").toIntOrNull() ?: 0
                Tools.compress(act, s, h, q); added()
            }.setNegativeButton("إلغاء", null).show()
    }

    fun hardsub(s: ToolSrc) {
        if (s.audioUri != null) { toast("الفيديو ده صورته وصوته منفصلين (زي يوتيوب) — حمّله من ⬇ الأول"); return }
        if (s.subs.none { it.translated.isNotBlank() || it.original.isNotBlank() }) { toast("مفيش ترجمة للفيديو ده لسه — ترجمه الأول"); return }
        var q = "عالية"; var which = "الترجمة"; var size = "عادي"
        val sizes = mapOf("صغير" to 80, "عادي" to 100, "كبير" to 130)
        val c = col()
        c.addView(note("الترجمة هتتحرق في الصورة نفسها بخطك المختار في الإعدادات وبتفضل ظاهرة في أي مشغّل. بياخد وقت قد الفيديو تقريبًا، فسيب التطبيق شغّال."))
        c.addView(label("النص")); c.addView(ui.chips(listOf("الترجمة", "الأصلي"), { which }, { which = it }))
        c.addView(label("حجم الخط")); c.addView(ui.chips(sizes.keys.toList(), { size }, { size = it }))
        c.addView(label("الجودة")); c.addView(qChips({ q }, { q = it }))
        GAlert(act).setTitle("🎬 ترجمة ثابتة في الفيديو (هارد ساب)").setView(c)
            .setPositiveButton("ابدأ") { _, _ ->
                val st = SubStyle.load { k, d -> Cfg.str(k, d) }
                val file = SubStyle.fonts.firstOrNull { it.id == st.font }?.file
                Tools.hardsub(act, s, q, file, (sizes[size] ?: 100) * st.scale / 100, which == "الأصلي"); added()
            }.setNegativeButton("إلغاء", null).show()
    }
}
