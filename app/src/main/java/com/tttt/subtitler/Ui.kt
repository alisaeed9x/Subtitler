package com.tttt.subtitler

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** مكوّنات واجهة بنفس شكل الأصل (radius 14/8، حدود، أمبر كلون أساسي) */
class Ui(val ctx: Context, var th: Theme) {
    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun box(fill: Int, stroke: Int, r: Int, sw: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(sw), stroke)
    }
    private val plex: android.graphics.Typeface? by lazy { try { android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/ibm_plex.ttf") } catch (_: Exception) { null } }
    fun text(t: String, size: Float = 14f, color: Int = th.text, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) { plex?.let { typeface = it } ?: setTypeface(typeface, android.graphics.Typeface.BOLD) }
    }
    fun slider(label: String, init: Int, lo: Int, hi: Int, unit: String, onChange: (Int) -> Unit): LinearLayout {
        val tv = text("$label: ${init.coerceIn(lo, hi)}$unit", 13f, th.text)
        val sb = SeekBar(ctx).apply {
            max = hi - lo; progress = init.coerceIn(lo, hi) - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = "$label: ${p + lo}$unit"; if (u) onChange(p + lo) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sb) }
    }
    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(14))
        background = box(th.card, th.border, 14)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    }
    fun input(hint: String, value: String, lines: Int = 1) = EditText(ctx).apply {
        this.hint = hint; setText(value); setTextColor(th.text); setHintTextColor(th.muted); textSize = 14f
        background = box(th.surface, th.border, 8); setPadding(dp(10), dp(8), dp(10), dp(8))
        if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START } else setSingleLine()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
    }
    fun button(t: String, primary: Boolean = false, f: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; setTextColor(if (primary) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
        background = box(if (primary) th.primary else th.surface, if (primary) th.primary else th.border, 8)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
        setOnClickListener { f() }
    }
    fun switchRow(t: String, on: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(ctx).apply {
        text = t; isChecked = on; setTextColor(th.text); textSize = 14f; setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    /** صف chips بيلف على أكتر من سطر؛ بيرجّع الـ container، والاختيار بيتبلّغ بـ onPick */
    fun chips(items: List<String>, selected: () -> String, onPick: (String) -> Unit): LinearLayout {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val all = ArrayList<Pair<String, TextView>>()
        fun paint() = all.forEach { (k, v) ->
            val on = k == selected()
            v.setTextColor(if (on) (if (th.isLight) Color.WHITE else Color.BLACK) else th.text)
            v.background = box(if (on) th.primary else th.surface, if (on) th.primary else th.border, 20)
        }
        var row: LinearLayout? = null; var w = 0
        val max = ctx.resources.displayMetrics.widthPixels - dp(70)
        for (label in items) {
            val tv = TextView(ctx).apply { text = label; textSize = 13f; setPadding(dp(12), dp(7), dp(12), dp(7)) }
            tv.measure(0, 0); val tw = tv.measuredWidth + dp(8)
            if (row == null || w + tw > max) { row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }; col.addView(row); w = 0 }
            w += tw
            row.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            all.add(label to tv)
            tv.setOnClickListener { onPick(label); paint() }
        }
        paint(); return col
    }
    /** قسم قابل للطي (الموبايل صغير) */
    fun section(title: String, open: Boolean, vararg views: View): LinearLayout {
        val c = card(); val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
        views.forEach { body.addView(it) }
        val head = text((if (open) "▾ " else "▸ ") + title, 16f, th.primary, true).apply { setPadding(0, dp(2), 0, dp(2)) }
        head.setOnClickListener {
            val o = body.visibility != View.VISIBLE
            body.visibility = if (o) View.VISIBLE else View.GONE
            head.text = (if (o) "▾ " else "▸ ") + title
        }
        c.addView(head); c.addView(body); return c
    }
}

/** شريط الحالة والتنقل بلون الثيم، وأيقونات غامقة لو الثيم فاتح (عشان تبان على الأبيض) */
@Suppress("DEPRECATION")
fun android.app.Activity.applyBars(th: Theme) {
    window.statusBarColor = th.bg; window.navigationBarColor = th.bg
    if (android.os.Build.VERSION.SDK_INT >= 23 && th.isLight) {
        var f = window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (android.os.Build.VERSION.SDK_INT >= 26) f = f or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
}
