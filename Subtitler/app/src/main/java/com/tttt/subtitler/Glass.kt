package com.tttt.subtitler

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** أدوات الشكل الزجاجي (Glass UI) + أنيميشن الضغط — موحّدة لكل الشاشات */
object Glass {
    fun theme(): Theme = Themes.byId(Cfg.str("theme", "mx"))

    fun alpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    fun blend(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(m(Color.alpha(a), Color.alpha(b)), m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
    }

    /** كارت زجاجي: تدرّج خفيف من فوق لتحت + حد. الفاتح بيميل لون الثيم، الغامق بيلمّع من فوق */
    fun drawable(th: Theme, radiusPx: Float, strokePx: Int, stroke: Int, a: Int = 255): GradientDrawable {
        val cols = if (th.isLight) intArrayOf(alpha(th.card, a), alpha(blend(th.card, th.primary, 0.05f), a))
        else intArrayOf(alpha(blend(th.card, Color.WHITE, 0.08f), a), alpha(th.card, a))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, cols).apply { cornerRadius = radiusPx; setStroke(strokePx, stroke) }
    }

    /** زرار أساسي بتدرّج لون الثيم */
    fun primaryFill(th: Theme, radiusPx: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(th.primary, blend(th.primary, Color.BLACK, 0.22f))).apply { cornerRadius = radiusPx }

    /** أي نافذة جذرها له خلفية مدوّرة (box) بيتحوّل لزجاجي — بيتنده تلقائي من GDialog */
    fun restyle(v: View) {
        val bg = v.background as? GradientDrawable ?: return
        val r = try { bg.cornerRadius } catch (_: Throwable) { 0f }
        if (r < 1f) return
        val th = theme(); val d = v.resources.displayMetrics.density
        v.background = drawable(th, r, maxOf(1, d.toInt()), if (th.isLight) th.border else alpha(Color.WHITE, 0x2E), 0xF2)
    }

    /** تعتيم خفيف + بلور للخلفية (أندرويد 12+). على الأقدم: تعتيم بس */
    fun styleWindow(d: Dialog) {
        val w = d.window ?: return
        try {
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.40f)
            if (Build.VERSION.SDK_INT >= 31) {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val lp = w.attributes; lp.blurBehindRadius = 26; w.attributes = lp
            }
        } catch (_: Throwable) {}
    }

    private fun content(d: Dialog): View? = d.window?.decorView?.findViewById(android.R.id.content)

    fun enter(d: Dialog) {
        val c = content(d) ?: return
        val g = d.window?.attributes?.gravity ?: 0
        val bottom = ((g and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM)
        val dens = c.resources.displayMetrics.density
        c.animate().cancel()
        c.alpha = 0f; c.scaleX = 0.93f; c.scaleY = 0.93f; c.translationY = (if (bottom) 56f else 14f) * dens
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    fun exit(d: Dialog, done: () -> Unit) {
        val c = content(d)
        if (c == null) { done(); return }
        c.animate().cancel()
        c.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(140).withEndAction { done() }.start()
    }

    /** تأثير ضغطة: بيصغّر العنصر وهو مضغوط ويرجّعه بنطّة خفيفة. مابيعترضش الـ click */
    fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    fun hasEdit(v: View?): Boolean {
        if (v is EditText) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (hasEdit(v.getChildAt(i))) return true
        return false
    }
}

/**
 * Dialog بتصميم التطبيق: خلفية زجاجية + بلور + أنيميشن دخول/خروج،
 * ورجوع (زرار/سويب) بيتعالج في onBackPressed — ده اللي بيشتغل مع إيماءة الرجوع (القديم setOnKeyListener ماكانش بيوصله السويب).
 * onBack بترجّع true لو استهلكت الرجوع (مثلاً رجوع خطوة جوه الإعدادات) وإلا بتتقفل النافذة.
 */
open class GDialog(ctx: Context, theme: Int = 0) : Dialog(ctx, theme) {
    var onBack: (() -> Boolean)? = null
    var plain = false
    private var closing = false
    private var seq = 0

    init { requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun setContentView(view: View) {
        super.setContentView(view)
        if (!plain) Glass.restyle(view)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (onBack?.invoke() == true) return
        super.onBackPressed()
    }

    override fun show() {
        seq++; closing = false
        if (!plain) Glass.styleWindow(this)
        super.show()
        try { Glass.enter(this) } catch (_: Throwable) {}
    }

    private fun realDismiss() { try { super.dismiss() } catch (_: Throwable) {} }

    override fun dismiss() {
        val v = window?.decorView
        if (closing) return
        if (!isShowing || v == null || Looper.myLooper() != Looper.getMainLooper()) { realDismiss(); return }
        closing = true
        val my = seq
        try { Glass.exit(this) { if (closing && my == seq) { closing = false; realDismiss() } } } catch (_: Throwable) { closing = false; realDismiss(); return }
        // احتياطي لو الأنيميشن اتلغى
        v.postDelayed({ if (closing && my == seq) { closing = false; realDismiss() } }, 400)
    }
}

/** بديل AlertDialog.Builder بنفس الـ API لكن بشكل زجاجي بلون الثيم (مش شكل النظام) */
class GAlert(private val ctx: Context) {
    private var title: CharSequence? = null
    private var msg: CharSequence? = null
    private var view: View? = null
    private var items: Array<out CharSequence>? = null
    private var itemsCb: DialogInterface.OnClickListener? = null
    private var pos: CharSequence? = null; private var posCb: DialogInterface.OnClickListener? = null
    private var neg: CharSequence? = null; private var negCb: DialogInterface.OnClickListener? = null
    private var neu: CharSequence? = null; private var neuCb: DialogInterface.OnClickListener? = null
    private var cancelable = true
    private var cancelCb: DialogInterface.OnCancelListener? = null

    fun setTitle(t: CharSequence?): GAlert { title = t; return this }
    fun setMessage(m: CharSequence?): GAlert { msg = m; return this }
    fun setView(v: View?): GAlert { view = v; return this }
    fun setItems(a: Array<out CharSequence>, l: DialogInterface.OnClickListener?): GAlert { items = a; itemsCb = l; return this }
    fun setPositiveButton(t: CharSequence, l: DialogInterface.OnClickListener?): GAlert { pos = t; posCb = l; return this }
    fun setNegativeButton(t: CharSequence, l: DialogInterface.OnClickListener?): GAlert { neg = t; negCb = l; return this }
    fun setNeutralButton(t: CharSequence, l: DialogInterface.OnClickListener?): GAlert { neu = t; neuCb = l; return this }
    fun setCancelable(b: Boolean): GAlert { cancelable = b; return this }
    fun setOnCancelListener(l: DialogInterface.OnCancelListener?): GAlert { cancelCb = l; return this }

    fun show(): Dialog {
        val th = Glass.theme(); val ui = Ui(ctx, th); val dm = ctx.resources.displayMetrics
        val d = GDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(14)); background = ui.box(th.card, th.border, 22)
        }
        title?.let { root.addView(ui.text(it.toString(), 18f, th.primary, true), LinearLayout.LayoutParams(-1, -2)) }
        msg?.let { m ->
            val tv = ui.text(m.toString(), 14f, th.text).apply { setLineSpacing(0f, 1.2f); setPadding(0, ui.dp(10), 0, ui.dp(4)); setTextIsSelectable(true) }
            root.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.5f).toInt()).apply { addView(tv) }, LinearLayout.LayoutParams(-1, -2))
        }
        view?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            // (v125) أي محتوى طويل جوه الدايلوج لازم يتسكرول (كان بيتقص من غير ما يتحرك)
            val scrollable = v is android.widget.ScrollView || v is android.widget.AbsListView || v is android.widget.HorizontalScrollView
            val wrapped: View = if (scrollable) v else MaxHeightScroll(ctx, (dm.heightPixels * 0.6f).toInt()).apply { addView(v) }
            root.addView(wrapped, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        items?.let { arr ->
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            arr.forEachIndexed { i, s ->
                val tv = ui.text(s.toString(), 15f, th.text).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = ui.box(th.surface, th.border, 12) }
                Glass.pressable(tv)
                tv.setOnClickListener { d.dismiss(); itemsCb?.onClick(d, i) }
                col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
            }
            root.addView(MaxHeightScroll(ctx, (dm.heightPixels * 0.6f).toInt()).apply { addView(col) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        }
        val btns = ArrayList<Triple<CharSequence, DialogInterface.OnClickListener?, Int>>()
        pos?.let { btns.add(Triple(it, posCb, DialogInterface.BUTTON_POSITIVE)) }
        neu?.let { btns.add(Triple(it, neuCb, DialogInterface.BUTTON_NEUTRAL)) }
        neg?.let { btns.add(Triple(it, negCb, DialogInterface.BUTTON_NEGATIVE)) }
        if (btns.isNotEmpty()) {
            val row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
            for ((t, cb, which) in btns) {
                val main = which == DialogInterface.BUTTON_POSITIVE
                val b = IconTextView(ctx).apply {
                    text = t; textSize = 14f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                    setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
                    setTextColor(if (main) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
                    background = if (main) Glass.primaryFill(th, ui.dp(12).toFloat()) else ui.box(th.surface, th.border, 12)
                }
                Glass.pressable(b)
                b.setOnClickListener { d.dismiss(); cb?.onClick(d, which) }
                row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            }
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14) })
        }
        d.setContentView(root)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf((dm.widthPixels * 0.9f).toInt(), ui.dp(420)), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or (if (Glass.hasEdit(view)) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0))
        }
        d.setCancelable(cancelable); d.setCanceledOnTouchOutside(cancelable)
        cancelCb?.let { d.setOnCancelListener(it) }
        d.show()
        return d
    }
}
