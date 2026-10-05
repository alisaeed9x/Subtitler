package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * إشعار صغير فوق الشاشة (بديل التوست بالكامل). مفيش Toast في أي حتة في البرنامج.
 * - show: سطر صغير بيختفي لوحده (أو بلمسة).
 * - ask: إشعار بسؤال (نعم / لا) وشريط رفيع بيعدّ تنازلي، ولو ما حدش ردّ في المهلة (5 ثواني) بيختفي.
 * بيتفتح كنافذة فوق كل حاجة (حتى فوق الدايلوجات) بدون ما ياخد الفوكس.
 */
object Notice {
    private val main = Handler(Looper.getMainLooper())
    private var removeCur: (() -> Unit)? = null
    private var timeout: Runnable? = null

    private fun activityOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
        return null
    }

    private fun theme(c: Context): Theme = try { Themes.byId(c.getSharedPreferences("p", 0).getString("theme", "mx")) } catch (_: Throwable) { Themes.all[0] }

    private fun statusBarH(a: Activity): Int {
        val id = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) a.resources.getDimensionPixelSize(id) else 0
    }

    private fun removeNow() {
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        val r = removeCur; removeCur = null
        try { r?.invoke() } catch (_: Throwable) {}
    }

    fun dismiss() { if (Looper.myLooper() == Looper.getMainLooper()) removeNow() else main.post { removeNow() } }

    /** إشعار عادي صغير */
    fun show(c: Context, msg: String, ms: Long = 2400L) { present(c, msg, null, null, ms, null, null) }

    /** إشعار بسؤال. onNo بتتنادي لو دوس «لا» أو عدّت المهلة من غير رد */
    fun ask(c: Context, msg: String, yes: String, no: String, ms: Long = 5000L, onYes: () -> Unit, onNo: () -> Unit) {
        present(c, msg, yes, no, ms, onYes, onNo)
    }

    private fun present(c: Context, msg: String, yes: String?, no: String?, ms: Long, onYes: (() -> Unit)?, onNo: (() -> Unit)?) {
        val act = activityOf(c)
        if (act == null) { onNo?.let { main.post { it() } }; return }
        main.post {
            if (act.isFinishing || act.isDestroyed) { onNo?.invoke(); return@post }
            try { if (act.isInPictureInPictureMode) { onNo?.invoke(); return@post } } catch (_: Throwable) {}
            removeNow()
            val th = theme(act); val ui = Ui(act, th)
            val d = act.resources.displayMetrics.density
            fun dp(v: Int) = (v * d).toInt()
            val onPrimary = if (th.isLight) Color.WHITE else Color.BLACK

            val pill = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                background = ui.box(th.card, th.border, 12)
                setPadding(dp(12), dp(8), dp(12), dp(if (yes != null) 6 else 8)); elevation = dp(10).toFloat()
            }
            pill.addView(TextView(act).apply {
                text = msg; textSize = 12.5f; setTextColor(th.text); maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            })
            var done = false
            fun finish(cb: (() -> Unit)?) { if (done) return; done = true; removeNow(); cb?.invoke() }

            if (yes != null && no != null) {
                fun chip(t: String, primary: Boolean, f: () -> Unit) = TextView(act).apply {
                    text = t; textSize = 12.5f; gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setTextColor(if (primary) onPrimary else th.text)
                    background = ui.box(if (primary) th.primary else th.surface, if (primary) th.primary else th.border, 16)
                    setOnClickListener { f() }
                }
                val row = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(7), 0, dp(6)) }
                row.addView(chip(yes, true) { finish(onYes) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                row.addView(chip(no, false) { finish(onNo) }, LinearLayout.LayoutParams(0, -2, 1f))
                pill.addView(row)
                val bar = View(act).apply { setBackgroundColor(th.primary) }
                pill.addView(bar, LinearLayout.LayoutParams(-1, dp(2)))
                bar.post {
                    bar.pivotX = bar.width.toFloat()
                    bar.animate().scaleX(0f).setDuration(ms).setInterpolator(LinearInterpolator()).start()
                }
            } else {
                pill.setOnClickListener { finish(null) }
            }

            val wrap = FrameLayout(act).apply { setPadding(dp(10), dp(6), dp(10), 0); addView(pill, FrameLayout.LayoutParams(-1, -2)) }
            wrap.alpha = 0f; wrap.translationY = -dp(16).toFloat()

            val tok = try { act.window.decorView.windowToken } catch (_: Throwable) { null }
            var added = false
            if (tok != null) {
                try {
                    val wm = act.windowManager
                    val lp = WindowManager.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP; token = tok; y = statusBarH(act) }
                    wm.addView(wrap, lp)
                    removeCur = { try { wm.removeViewImmediate(wrap) } catch (_: Throwable) {} }
                    added = true
                } catch (_: Throwable) {}
            }
            if (!added) {
                try {
                    val content = act.findViewById<ViewGroup>(android.R.id.content)
                    content.addView(wrap, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                    removeCur = { try { content.removeView(wrap) } catch (_: Throwable) {} }
                } catch (_: Throwable) { onNo?.invoke(); return@post }
            }
            wrap.animate().alpha(1f).translationY(0f).setDuration(180).start()
            val t = Runnable { finish(onNo) }
            timeout = t; main.postDelayed(t, ms)
        }
    }
}

/** بديل Toast بنفس الشكل: Toast.makeText(ctx, "..", Toast.LENGTH_SHORT).show() بيطلع إشعار صغير فوق بدل التوست */
object Toast {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1
    class T(private val c: Context, private val m: CharSequence, private val d: Int) {
        fun show() { Notice.show(c, m.toString(), if (d == LENGTH_LONG) 3600L else 2300L) }
    }
    fun makeText(c: Context, m: CharSequence, d: Int): T = T(c, m, d)
}
