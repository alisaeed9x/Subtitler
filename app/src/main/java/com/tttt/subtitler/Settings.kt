package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** مرجع للمحرك الشغّال حاليًا (بيستخدمه تبويب الشخصيات عشان يستورد المكتشفة تلقائيًا) */
object Live { @Volatile var engine: Engine? = null }

class TabDef(val id: String, val label: String, val content: List<View>, val preview: Boolean = false)

/** نافذة إعدادات واحدة منبثقة (popup) بتبويبات. preview = معاينة الترجمة بتظهر فوق المحتوى في التبويبات اللي preview=true */
class TabbedDialog(val act: Activity, val ui: Ui, val title: String, val tabs: List<TabDef>, val preview: View?, onClose: () -> Unit) {
    val dialog = Dialog(act)
    private val bodies = ArrayList<LinearLayout>()
    private val pills = ArrayList<TextView>()
    private var cur = -1
    private val previewBox = FrameLayout(act)

    init {
        val th = ui.th
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dm = act.resources.displayMetrics
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12)); background = ui.box(th.card, th.border, 18)
        }
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, ui.dp(6)) }
        head.addView(ui.text(title, 17f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(act).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val strip = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        tabs.forEachIndexed { i, t ->
            val p = TextView(act).apply {
                text = t.label; textSize = 13f; gravity = Gravity.CENTER; setSingleLine()
                setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); setOnClickListener { select(i) }
            }
            pills.add(p); strip.addView(p, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(strip) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })

        if (preview != null) { previewBox.addView(preview); root.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }) }

        val frame = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        tabs.forEach { t ->
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
            t.content.forEach { b.addView(it) }
            bodies.add(b); frame.addView(b, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(MaxHeightScroll(act, (dm.heightPixels * 0.62f).toInt()).apply { addView(frame) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.95f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.setOnDismissListener { onClose() }
        select(0)
    }

    fun select(i: Int) {
        if (i !in tabs.indices) return
        cur = i
        val th = ui.th
        tabs.indices.forEach { k ->
            bodies[k].visibility = if (k == i) View.VISIBLE else View.GONE
            pills[k].apply {
                val on = k == i
                setTextColor(if (on) ui.onPrimary() else th.text)
                background = ui.box(if (on) th.primary else th.surface, if (on) th.primary else th.border, 16)
            }
        }
        previewBox.visibility = if (preview != null && tabs[i].preview) View.VISIBLE else View.GONE
    }

    fun show(tabId: String? = null) {
        tabId?.let { id -> tabs.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { select(it) } }
        dialog.show()
    }
}

/** محرر الشخصيات: صفوف (اسم · ذكر/أنثى · وصف) بتتكتب في roster بصيغة name:male:role */
fun Ui.charactersEditor(act: Activity, roster: EditText, gloss: EditText): LinearLayout {
    val th = th
    val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    class Row(var name: String, var gender: String, var role: String)
    val list = ArrayList<Row>()
    fun parse() {
        list.clear()
        roster.text.toString().lines().forEach { ln ->
            val a = ln.split(":")
            if (a.size >= 2 && a[0].isNotBlank()) list.add(Row(a[0].trim(), if (a[1].trim().lowercase().startsWith("f") || a[1].contains("أنث")) "female" else "male", a.drop(2).joinToString(":").trim()))
        }
    }
    fun flush() { roster.setText(list.filter { it.name.isNotBlank() }.joinToString("\n") { "${it.name.replace(":", " ")}:${it.gender}:${it.role.replace("\n", " ")}" }) }
    lateinit var rebuild: () -> Unit
    fun watch(e: EditText, f: (String) -> Unit) = e.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) { f(s.toString()); flush() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    rebuild = {
        rows.removeAllViews()
        if (list.isEmpty()) rows.addView(text("مفيش شخصيات لسه — ضيف شخصية، أو سيب التحليل التلقائي يعبّيها وبعدين استوردها", 12f, th.muted))
        list.forEach { r ->
            val name = input("الاسم", r.name).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1.1f) }
            val role = input("وصف قصير", r.role).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f) }
            watch(name) { r.name = it }; watch(role) { r.role = it }
            val g = TextView(act).apply {
                textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8)); setTextColor(th.text)
                fun paint() { text = if (r.gender == "female") "♀ أنثى" else "♂ ذكر"; background = box(th.surface, if (r.gender == "female") SubStyle.FEMALE else SubStyle.MALE, 10) }
                paint(); setOnClickListener { r.gender = if (r.gender == "female") "male" else "female"; paint(); flush() }
            }
            val del = TextView(act).apply { text = "🗑"; textSize = 17f; setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { list.remove(r); flush(); rebuild() } }
            rows.addView(LinearLayout(act).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                addView(name); addView(g, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) }); addView(role); addView(del)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }
    parse(); rebuild()
    box.addView(text("الشخصيات (الجنس هنا هو المرجع النهائي لتصحيح الضمائر)", 13f, th.muted))
    box.addView(rows)
    box.addView(button("➕ إضافة شخصية") { list.add(Row("", "male", "")); rebuild() })
    box.addView(button("📥 استيراد الشخصيات المكتشفة تلقائيًا") {
        val found = Live.engine?.charactersNow().orEmpty()
        if (found.isEmpty()) Toast.makeText(act, "لسه مفيش شخصيات متحللة (شغّل ترجمة فيديو لحد ما يتحلل أول 12 جملة)", Toast.LENGTH_LONG).show()
        else {
            parse()
            found.forEach { c -> val ix = list.indexOfFirst { it.name == c.name }; if (ix >= 0) { list[ix].gender = c.gender; if (c.role.isNotBlank()) list[ix].role = c.role } else list.add(Row(c.name, c.gender, c.role)) }
            flush(); rebuild(); Toast.makeText(act, "اتستوردت ${found.size} شخصية", Toast.LENGTH_SHORT).show()
        }
    })
    box.addView(text("مسرد المصطلحات (كل سطر: الكلمة = ترجمتها)", 13f, th.muted).apply { setPadding(0, dp(12), 0, 0) })
    box.addView(gloss)
    return box
}
