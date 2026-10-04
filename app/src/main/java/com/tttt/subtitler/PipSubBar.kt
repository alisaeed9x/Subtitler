package com.tttt.subtitler

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** شريط الترجمة وقت PiP: نافذة overlay بتتحط **تحت** نافذة الـ PiP بنفس عرضها (ولو مفيش مكان تحت بتتحط فوقها)،
 *  وبتتابع مكان النافذة وحجمها (لما تسحبها أو تكبّرها/تصغّرها) كل ربع ثانية. */
class PipSubBar(private val ctx: Context, private val rect: () -> IntArray?) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private val h = Handler(Looper.getMainLooper())
    private var tv: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var added = false
    private var text = ""
    private val tick = object : Runnable { override fun run() { reposition(); if (added) h.postDelayed(this, 250) } }

    @Suppress("DEPRECATION")
    fun show() {
        if (added) return
        val t = TextView(ctx).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            layoutDirection = View.LAYOUT_DIRECTION_RTL; maxLines = 3
            setPadding((8 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = 10 * d }
            setShadowLayer(4f, 0f, 1f, Color.BLACK); visibility = View.GONE
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(ctx.resources.displayMetrics.widthPixels / 2, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        try { wm.addView(t, p); tv = t; lp = p; added = true; h.post(tick) } catch (_: Exception) { added = false }
    }

    fun set(s: String) {
        text = s
        val t = tv ?: return
        if (s.isBlank()) { t.visibility = View.GONE; return }
        t.text = s; t.visibility = View.VISIBLE
        reposition()
    }

    private fun reposition() {
        val t = tv ?: return; val p = lp ?: return
        if (!added || t.visibility != View.VISIBLE) return
        val sw = ctx.resources.displayMetrics.widthPixels; val sh = ctx.resources.displayMetrics.heightPixels
        val r = rect()
        val x: Int; val w: Int; var y: Int
        if (r == null || r[2] <= 0) { x = (sw * 0.03f).toInt(); w = (sw * 0.94f).toInt(); y = (60 * d).toInt() }
        else {
            x = r[0].coerceAtLeast(0); w = r[2].coerceAtMost(sw - x).coerceAtLeast((120 * d).toInt()); y = r[1] + r[3] + (4 * d).toInt()
            // الخط بيكبر ويصغر مع عرض نافذة الـ PiP
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, (w / d / 20f).coerceIn(10f, 22f))
        }
        if (r == null) t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val bh = if (t.height > 0) t.height else (60 * d).toInt()
        // مفيش مكان تحت النافذة؟ حطّه فوقها
        if (r != null && y + bh > sh - (8 * d).toInt()) y = (r[1] - bh - (4 * d).toInt()).coerceAtLeast(0)
        if (p.x != x || p.y != y || p.width != w) { p.x = x; p.y = y; p.width = w; try { wm.updateViewLayout(t, p) } catch (_: Exception) {} }
    }

    fun hide() {
        h.removeCallbacksAndMessages(null)
        val t = tv ?: return
        try { wm.removeView(t) } catch (_: Exception) {}
        tv = null; lp = null; added = false
    }
}
