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

// مفاتيح جوجل: القديم AIza… والجديد (AI Studio دلوقتي) AQ.… وفيه نقط وشرطات
private val KEY_RE = Regex("AIza[0-9A-Za-z_\\-]{30,}|AQ\\.[0-9A-Za-z_.\\-]{20,}")

/** أول مفتاح Gemini (AIza… أو AQ.…) جوه نص، أو null */
fun findGeminiKey(t: String): String? = KEY_RE.find(t)?.value?.trimEnd('.')

/** أول مفتاح Gemini (AIza… أو AQ.…) موجود في الكليبورد، أو null */
fun Activity.clipboardKey(): String? = try {
    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
    findGeminiKey(t)
} catch (_: Exception) { null }

/** شاشة «إزاي أجيب مفتاح Gemini؟»: خطوات + زرار يفتح Google AI Studio + زرار يلصق المفتاح من الكليبورد */
fun Activity.showKeyGuide(onKey: (String) -> Unit) {
    val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(this, th)
    val d = GDialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    var auto = false
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
    }
    box.addView(ui.text("❓ إزاي أجيب مفتاح Gemini؟", 17f, th.primary, true))
    box.addView(ui.text("المفتاح بيتعمل من موقع جوجل نفسه، ببلاش، وبياخد دقيقة:", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
    val steps = listOf(
        "١) دوس «🌐 افتح صفحة المفتاح هنا» تحت وسجّل دخول بحساب جوجل بتاعك.",
        "٢) لو طلب منك توافق على الشروط، وافق.",
        "٣) دوس على Create API key (لو سألك عن مشروع، اختار «مشروع جديد» أو أي مشروع موجود).",
        "٤) دوس على أيقونة النسخ جنب المفتاح (اللي بيبدأ بـ AQ. أو AIza) — البرنامج هيلقطه ويحفظه لوحده وتقفل الصفحة.",
        "لو جوجل رفضت تسجيل الدخول جوه البرنامج، دوس «Chrome» فوق في الصفحة (أو الزرار اللي تحت) وانسخ المفتاح من هناك وارجع."
    )
    for (s in steps) box.addView(ui.text(s, 13f, th.text).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) })
    box.addView(ui.text("• كل مفتاح ليه حد استخدام يومي مجاني. لو عايز ترجمة أسرع أو أكتر، اعمل أكتر من مفتاح (من حسابات/مشاريع مختلفة) وضيفهم كلهم.\n• ماتشاركش مفتاحك مع حد.\n• لو الحساب تبع مدرسة أو شركة ممكن يمنع إنشاء المفاتيح — جرّب حساب شخصي.", 11f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
    fun pill(t: String, bg: Int, fg: Int, f: () -> Unit) = IconTextView(this).apply {
        text = t; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = ui.box(bg, th.border, 12); setOnClickListener { f() }
    }
    fun paste() {
        val k = clipboardKey()
        if (k == null) Notice.show(this, ("مفيش مفتاح (بيبدأ بـ AQ. أو AIza) في الكليبورد — انسخه الأول").toString(), 3600L)
        else { onKey(k); Notice.show(this, ("✓ اتحط المفتاح").toString(), 2300L); d.dismiss() }
    }
    box.addView(pill("🌐 افتح صفحة المفتاح هنا (جوه البرنامج)", th.primary, Color.BLACK) {
        d.dismiss(); showKeyBrowser(onKey)
    }, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(8) })
    box.addView(pill("افتح في Chrome", th.surface, th.text) {
        auto = true
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KEY_PAGE_URL))) }
        catch (_: Exception) { Notice.show(this, ("مفيش متصفح — افتح aistudio.google.com/apikey يدويًا").toString(), 3600L) }
    }, LinearLayout.LayoutParams(-1, ui.dp(42)).apply { topMargin = ui.dp(6) })
    box.addView(pill("📋 الصق من الكليبورد", th.surface, th.text) { paste() }, LinearLayout.LayoutParams(-1, ui.dp(44)).apply { topMargin = ui.dp(6) })
    box.addView(pill("إغلاق", th.surface, th.muted) { d.dismiss() }, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(6) })
    // أول ما ترجع من المتصفح والمفتاح منسوخ: يتحط تلقائي
    d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { f -> if (f && auto && clipboardKey() != null) { auto = false; paste() } }
    d.setContentView(ScrollView(this).apply { addView(box) })
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.show()
    d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
}

/** لو مفيش أي مفتاح Gemini متسجّل: نافذة منبثقة تطلب المفتاح (+ لإضافة شريط مفتاح جديد). بعد الحفظ أو "بعدين" بينفّذ onDone */
private fun hasAnyKeyNow() = Cfg.keys("keys").isNotEmpty() || Cfg.keys("backup").isNotEmpty() || Cfg.keys("extra").isNotEmpty()
fun Activity.ensureKeys(onDone: () -> Unit) {
    Cfg.init(this)
    if (hasAnyKeyNow()) { onDone(); return }
    // مفيش مفاتيح: دوّر على النسخة المخفية بعد ما الصلاحيات تتمنح (تثبيت جديد) — على خيط خلفي عشان قراءة التخزين ما توقفش الواجهة
    val app = applicationContext
    Thread {
        try { KeyVault.restore(app, "kv_full"); KeyVault.save(app) } catch (_: Throwable) {}
        runOnUiThread { if (!isFinishing && !isDestroyed) ensureKeysUi(onDone) }
    }.apply { isDaemon = true }.start()
}
private fun Activity.ensureKeysUi(onDone: () -> Unit) {
    if (hasAnyKeyNow()) { onDone(); return }
    val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(this, th)
    val d = GDialog(this); d.requestWindowFeature(Window.FEATURE_NO_TITLE)
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
    fun pill(t: String, bg: Int, fg: Int, f: () -> Unit) = IconTextView(this).apply {
        text = t; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = ui.box(bg, th.border, 12); setOnClickListener { f() }
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)); background = ui.box(th.card, th.border, 18)
    }
    box.addView(ui.text("🔑 ضيف مفتاح Gemini", 17f, th.primary, true))
    box.addView(ui.text("محتاج مفتاح واحد على الأقل عشان الترجمة تشتغل. تقدر تزوّد مفاتيح تانية بزرار +.", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) })
    box.addView(pill("❓ معييش مفتاح — إزاي أجيبه؟", th.surface, th.primary) {
        showKeyGuide { k ->
            val all = (fields.map { it.text.toString().trim() }.filter { it.length > 10 } + k).distinct()
            Cfg.p.edit().putString("keys", all.joinToString("\n")).apply(); d.dismiss()
        }
    }, LinearLayout.LayoutParams(-1, ui.dp(42)).apply { bottomMargin = ui.dp(4) })
    addField()
    box.addView(col)
    box.addView(pill("＋  مفتاح تاني", th.surface, th.text) { addField() }, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(4) })
    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    btns.addView(pill("حفظ ✓", th.primary, Color.BLACK) {
        val ks = fields.map { it.text.toString().trim() }.filter { it.length > 10 }.distinct()
        if (ks.isEmpty()) Notice.show(this, ("اكتب مفتاح صالح").toString(), 2300L)
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
