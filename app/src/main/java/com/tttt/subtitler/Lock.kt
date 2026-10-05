package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout

/** شبكة 3×3 لرسم النمط */
class PatternView(ctx: Context, private val th: Theme) : View(ctx) {
    var onDone: (List<Int>) -> Unit = {}
    private val sel = ArrayList<Int>()
    private var fx = 0f; private var fy = 0f
    private var drawing = false
    private var error = false
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f * ctx.resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s) }
    private fun cx(i: Int) = width * ((i % 3) + 0.5f) / 3f
    private fun cy(i: Int) = height * ((i / 3) + 0.5f) / 3f

    fun reset() { sel.clear(); error = false; drawing = false; invalidate() }
    fun flashError() { error = true; invalidate(); postDelayed({ error = false; sel.clear(); invalidate() }, 700) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { removeCallbacks(null); sel.clear(); error = false; drawing = true; hit(e.x, e.y); fx = e.x; fy = e.y }
            MotionEvent.ACTION_MOVE -> if (drawing) { hit(e.x, e.y); fx = e.x; fy = e.y }
            MotionEvent.ACTION_UP -> if (drawing) { drawing = false; if (sel.isNotEmpty()) onDone(sel.toList()) }
            MotionEvent.ACTION_CANCEL -> { drawing = false; sel.clear() }
        }
        invalidate(); return true
    }
    private fun hit(x: Float, y: Float) {
        val r = width / 8f
        for (i in 0..8) {
            if (i in sel) continue
            val dx = x - cx(i); val dy = y - cy(i)
            if (dx * dx + dy * dy <= r * r) {
                if (sel.isNotEmpty()) { val m = LockCore.between(sel.last(), i); if (m != null && m !in sel) sel.add(m) }
                sel.add(i); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); break
            }
        }
    }
    override fun onDraw(c: Canvas) {
        val col = if (error) th.danger else th.primary
        line.color = col
        for (k in 1 until sel.size) c.drawLine(cx(sel[k - 1]), cy(sel[k - 1]), cx(sel[k]), cy(sel[k]), line)
        if (drawing && sel.isNotEmpty()) c.drawLine(cx(sel.last()), cy(sel.last()), fx, fy, line)
        val rad = width / 22f
        for (i in 0..8) {
            val on = i in sel
            dot.color = if (on) col else th.muted
            c.drawCircle(cx(i), cy(i), if (on) rad * 1.5f else rad, dot)
            if (on) { dot.color = (col and 0x00FFFFFF) or 0x44000000; c.drawCircle(cx(i), cy(i), rad * 3f, dot) }
        }
    }
}

/** قفل المجلد المخفي: نمط (بيتعمل أول مرة وبيتحفظ كهاش) + بصمة الجهاز (اختياري) + نسيت النمط ← تأكيد بقفل شاشة الجهاز */
class LockUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val REQ = 21
    private var pendingReset: (() -> Unit)? = null

    val isSet: Boolean get() = Cfg.str("lock_hash").isNotEmpty()
    val bioOn: Boolean get() = Cfg.bool("lock_bio", false)
    val bioAvailable: Boolean get() = Build.VERSION.SDK_INT >= 28 && act.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_LONG).show()

    private fun save(p: List<Int>) { val salt = LockCore.newSalt(); Cfg.put("lock_salt", salt); Cfg.put("lock_hash", LockCore.hash(salt, p)); Cfg.put("lock_fails", "0"); Cfg.put("lock_until", "0") }
    private fun check(p: List<Int>) = isSet && LockCore.hash(Cfg.str("lock_salt"), p) == Cfg.str("lock_hash")
    private fun clear() { Cfg.put("lock_hash", ""); Cfg.put("lock_salt", ""); Cfg.put("lock_bio", "0"); Cfg.put("lock_fails", "0"); Cfg.put("lock_until", "0") }

    /** البوابة: أول مرة بتعمل نمط، وبعد كده بصمة (لو مفعّلة) أو نمط */
    fun gate(open: () -> Unit) {
        if (!isSet) { setup { open() }; return }
        if (bioOn && bioAvailable) bio({ open() }, { askPattern(open) }) else askPattern(open)
    }

    private class Dlg(val d: Dialog, val pv: PatternView, val hint: android.widget.TextView, val title: android.widget.TextView)
    private fun dialog(title: String, hintText: String, extra: List<Pair<String, () -> Unit>>): Dlg {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(14)); background = ui.box(th.card, th.border, 18)
        }
        val t = ui.text(title, 17f, th.primary, true).apply { gravity = Gravity.CENTER }
        val h = ui.text(hintText, 13f, th.muted).apply { gravity = Gravity.CENTER }
        val pv = PatternView(act, th)
        root.addView(t, LinearLayout.LayoutParams(-1, -2))
        root.addView(h, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        root.addView(pv, LinearLayout.LayoutParams(ui.dp(260), ui.dp(260)).apply { topMargin = ui.dp(10) })
        val row = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        (extra + ("إلغاء" to { d.dismiss() })).forEach { (label, f) ->
            row.addView(ui.text(label, 13f, th.text).apply {
                setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)); background = ui.box(th.surface, th.border, 10); setOnClickListener { f() }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }
        root.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(10) })
        d.setContentView(root)
        d.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout((act.resources.displayMetrics.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        return Dlg(d, pv, h, t)
    }

    /** اطلب النمط (مع تأخير بعد 5 محاولات غلط) */
    fun askPattern(ok: () -> Unit) {
        lateinit var dl: Dlg
        dl = dialog("🔒 المجلد المخفي", "ارسم النمط", listOf("نسيت النمط؟" to { dl.d.dismiss(); forgot { setup { ok() } } }))
        dl.pv.onDone = { p ->
            val now = System.currentTimeMillis(); val until = Cfg.str("lock_until", "0").toLongOrNull() ?: 0L
            if (now < until) { dl.hint.text = "استنى ${(until - now) / 1000 + 1} ثانية وحاول تاني"; dl.pv.flashError() }
            else if (check(p)) { Cfg.put("lock_fails", "0"); dl.d.dismiss(); ok() }
            else {
                val f = (Cfg.str("lock_fails", "0").toIntOrNull() ?: 0) + 1
                Cfg.put("lock_fails", f.toString())
                val wait = LockCore.lockoutMs(f); if (wait > 0) Cfg.put("lock_until", (now + wait).toString())
                dl.hint.text = if (wait > 0) "نمط غلط — استنى ${wait / 1000} ثانية" else "نمط غلط ($f/5)"
                dl.pv.flashError()
            }
        }
        dl.d.show()
    }

    /** إنشاء نمط جديد: ارسمه مرتين للتأكيد، وبعدها اعرض تفعيل البصمة */
    fun setup(done: () -> Unit) {
        val dl = dialog("🔑 نمط جديد", "ارسم نمط (٤ نقط على الأقل)", emptyList())
        var first: List<Int>? = null
        dl.pv.onDone = { p ->
            val f = first
            if (p.size < LockCore.MIN_DOTS) { dl.hint.text = "لازم ${LockCore.MIN_DOTS} نقط على الأقل — جرّب تاني"; dl.pv.flashError() }
            else if (f == null) { first = p; dl.hint.text = "ارسمه تاني للتأكيد"; dl.title.text = "🔑 أكّد النمط"; dl.pv.postDelayed({ dl.pv.reset() }, 250) }
            else if (f == p) {
                save(p); dl.d.dismiss(); toast("✅ اتحفظ النمط")
                if (bioAvailable && !bioOn) {
                    android.app.AlertDialog.Builder(act).setTitle("👆 البصمة").setMessage("تفتح المجلد المخفي ببصمة الجهاز كمان؟ (النمط يفضل بديل)")
                        .setPositiveButton("فعّل") { _, _ -> enableBio { done() } }.setNegativeButton("لا، النمط بس") { _, _ -> done() }.setOnCancelListener { done() }.show()
                } else done()
            } else { first = null; dl.hint.text = "النمطين مش زي بعض — ابدأ من الأول"; dl.title.text = "🔑 نمط جديد"; dl.pv.flashError() }
        }
        dl.d.show()
    }

    fun change(done: () -> Unit = {}) { if (isSet) askPattern { setup(done) } else setup(done) }
    fun remove(done: () -> Unit = {}) { if (!isSet) { done(); return }; askPattern { clear(); toast("اتشال القفل"); done() } }

    /** تفعيل البصمة بعد ما نتأكد إنها شغالة فعلًا */
    fun enableBio(done: () -> Unit) {
        if (!bioAvailable) { toast("جهازك مفيهوش مستشعر بصمة"); done(); return }
        bio({ Cfg.put("lock_bio", "1"); toast("✅ البصمة اتفعّلت"); done() }, { toast("ماتفعّلتش — تأكد إن فيه بصمة متسجّلة في إعدادات الجهاز"); done() })
    }
    fun disableBio() { Cfg.put("lock_bio", "0") }

    /** بصمة الجهاز (BiometricPrompt بتاع النظام): بتتحقق من البصمات المسجّلة في الجهاز، والتطبيق مابيحفظش بصمة نفسه */
    private fun bio(ok: () -> Unit, fallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { fallback(); return }
        var handled = false
        fun once(f: () -> Unit) { if (!handled) { handled = true; f() } }
        try {
            val ex = act.mainExecutor
            val p = BiometricPrompt.Builder(act).setTitle("🔒 المجلد المخفي").setSubtitle("المس مستشعر البصمة")
                .setNegativeButton("استخدم النمط", ex) { _, _ -> once(fallback) }.build()
            p.authenticate(CancellationSignal(), ex, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) { once(ok) }
                override fun onAuthenticationError(code: Int, msg: CharSequence?) {
                    if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) return
                    once(fallback)
                }
            })
        } catch (_: Throwable) { once(fallback) }
    }

    /** نسيت النمط: أكّد بقفل شاشة الجهاز (PIN / نمط / بصمة النظام) وبعدها اعمل نمط جديد */
    fun forgot(then: () -> Unit) {
        val km = act.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT < 23 || !km.isDeviceSecure) { toast("جهازك مفيهوش قفل شاشة — مفيش طريقة استرجاع غير مسح بيانات التطبيق"); return }
        val i = km.createConfirmDeviceCredentialIntent("إعادة تعيين نمط المخفي", "أكّد قفل الجهاز عشان تعمل نمط جديد")
        if (i == null) { toast("مقدرتش أفتح تأكيد قفل الجهاز"); return }
        pendingReset = then
        try { act.startActivityForResult(i, REQ) } catch (_: Exception) { pendingReset = null }
    }
    fun onResult(req: Int, ok: Boolean): Boolean {
        if (req != REQ) return false
        val t = pendingReset; pendingReset = null
        if (ok) { clear(); t?.invoke() }
        return true
    }
}
