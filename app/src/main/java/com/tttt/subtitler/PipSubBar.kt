package com.tttt.subtitler

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** شريط الترجمة وقت PiP: نافذة overlay صغيرة أعلى الشاشة تحت الستاتس بار، بعيد عن نافذة الفيديو الصغيرة */
class PipSubBar(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private var tv: TextView? = null
    private var added = false

    @Suppress("DEPRECATION")
    fun show() {
        if (added) return
        val t = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            layoutDirection = View.LAYOUT_DIRECTION_RTL; maxLines = 3
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = 10 * d }
            setShadowLayer(4f, 0f, 1f, Color.BLACK); visibility = View.GONE
        }
        val sbId = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbH = if (sbId > 0) ctx.resources.getDimensionPixelSize(sbId) else (24 * d).toInt()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = sbH + (4 * d).toInt()
        }
        try { wm.addView(t, lp); tv = t; added = true } catch (_: Exception) { added = false }
    }

    fun set(text: String) {
        val t = tv ?: return
        if (text.isBlank()) { t.visibility = View.GONE; return }
        t.maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.94f).toInt()
        t.text = text; t.visibility = View.VISIBLE
    }

    fun hide() {
        val t = tv ?: return
        try { wm.removeView(t) } catch (_: Exception) {}
        tv = null; added = false
    }
}
