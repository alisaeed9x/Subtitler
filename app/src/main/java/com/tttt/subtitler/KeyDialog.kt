package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** لو مفيش أي مفتاح Gemini متسجّل: نافذة منبثقة تطلب المفتاح (+ لإضافة شريط مفتاح جديد). بعد الحفظ أو "بعدين" بينفّذ onDone */
fun Activity.ensureKeys(onDone: () -> Unit) {
    Cfg.init(this)
    if (Cfg.keys("keys").isNotEmpty() || Cfg.keys("backup").isNotEmpty() || Cfg.keys("extra").isNotEmpty()) { onDone(); return }
    val th = Themes.byId(Cfg.str("theme", "default")); val ui = Ui(this, th)
    val d = Dialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    var finished = false
    fun done() { if (!finished) { finished = true; onDone() } }
    val fields = mutableListOf<EditText>()
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    fun addField() {
        val e = EditText(this).apply {
            hint = "مفتاح Gemini API"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 13f
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), 0, ui.dp(12), 0); background = ui.box(th.surface, th.border, 10)
        }
        fields.add(e)
        col.addView(e, LinearLayout.LayoutParams(-1, ui.dp(44)).apply { setMargins(0, ui.dp(5), 0, ui.dp(5)) })
        e.requestFocus()
    }
    fun pill(t: String, bg: Int, fg: Int, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = ui.box(bg, th.border, 12); setOnClickListener { f() }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
    }
    box.addView(ui.text("🔑 ضيف مفتاح Gemini", 17f, th.primary, true))
    box.addView(ui.text("محتاج مفتاح واحد على الأقل عشان الترجمة تشتغل. تقدر تزوّد مفاتيح تانية بزرار +.", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
    addField()
    box.addView(col)
    box.addView(pill("＋  مفتاح تاني", th.surface, th.text) { addField() }, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(4) })
    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    btns.addView(pill("حفظ ✓", th.primary, Color.BLACK) {
        val ks = fields.map { it.text.toString().trim() }.filter { it.length > 10 }.distinct()
        if (ks.isEmpty()) Toast.makeText(this, "اكتب مفتاح صالح", Toast.LENGTH_SHORT).show()
        else { Cfg.p.edit().putString("keys", ks.joinToString("\n")).apply(); d.dismiss() }
    }, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { setMargins(0, 0, ui.dp(4), 0) })
    btns.addView(pill("بعدين", th.surface, th.muted) { d.dismiss() }, LinearLayout.LayoutParams(0, ui.dp(44), 1f).apply { setMargins(ui.dp(4), 0, 0, 0) })
    box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
    d.setContentView(ScrollView(this).apply { addView(box) })
    d.setOnDismissListener { done() }
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
}
