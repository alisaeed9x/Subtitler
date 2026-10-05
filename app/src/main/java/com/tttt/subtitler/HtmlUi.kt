package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** مكوّنات الواجهة بنفس شكل نسخة الـ HTML (دوائر علوية، كروت سودا، كبسولة تحكم، شبكة أزرار، شريط الشاشة الكاملة) */

/** شريط التقدم: أزرق = موضع التشغيل، كهرماني = المترجم (زي fs-progress-played / fs-progress-translated) */
class DualProgress(ctx: Context, val th: Theme) : View(ctx) {
    var played = 0f
    /** (قديم) نسبة المترجم المتصل — الشريط الأخضر بقى بيتحسب من الجمل الفعلية (cov) */
    var translated = 0f
    var dragging = false
    /** mini = شكل شريط التقدم الرئيسي في الأصل (.progress-bar-bg: خط 4dp بتدرج كهرماني→سماوي + خريطة التغطية تحته) */
    var mini = false
    var onSeek: ((Float) -> Unit)? = null
    /** أثناء السحب (قبل رفع الصباع): الفيديو بيتحرك معاك */
    var onScrub: ((Float) -> Unit)? = null
    var onDrag: ((Boolean) -> Unit)? = null
    /** مدة الفيديو بالثواني (لتحويل أزمنة الجمل لنسب على الشريط) */
    var durSec = 0.0
    /** مجالات الترجمة الفعلية [بداية,نهاية] بالثواني — الشريط الأخضر بيتقطع بين الجمل مش بين الباتشات */
    var cov: List<DoubleArray> = emptyList()
    /** مجالات الجمل اللي لسه هتتغير (لهجة / إعادة صياغة / ضمائر) — شريط تالت فوق، بيختفي لما كلها تتغير */
    var pend: List<DoubleArray> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), ((if (mini) 30 else 34) * d).toInt())
    }

    private fun strip(c: Canvas, w: Float, y0: Float, hh: Float, segs: List<DoubleArray>, color: Int, trackColor: Int?) {
        val rad = hh / 2f
        if (trackColor != null) { p.shader = null; p.color = trackColor; c.drawRoundRect(RectF(0f, y0, w, y0 + hh), rad, rad, p) }
        if (durSec <= 0.0 || segs.isEmpty()) return
        p.shader = null; p.color = color
        for (s in segs) {
            val a = (w * (s[0] / durSec)).toFloat().coerceIn(0f, w)
            val b = (w * (s[1] / durSec)).toFloat().coerceIn(0f, w)
            if (b <= a && a >= w) continue
            c.drawRoundRect(RectF(a, y0, maxOf(b, a + 1.5f * d).coerceAtMost(w), y0 + hh), rad, rad, p)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        p.style = Paint.Style.FILL; p.shader = null
        if (mini) {
            val cy = height / 2f - 3f * d
            val tr = 2f * d
            // الشريط التالت (فوق): الجمل اللي لسه هتتغير — بيظهر بس لما فيه تغيير شغال
            if (pend.isNotEmpty()) strip(c, w, 3f * d, 3f * d, pend, 0xFFF59E0B.toInt(), 0x33F59E0B)
            p.color = th.border
            c.drawRoundRect(RectF(0f, cy - tr, w, cy + tr), 3f * d, 3f * d, p)
            val px = w * played.coerceIn(0f, 1f)
            if (px > 0f) {
                p.shader = LinearGradient(0f, 0f, px.coerceAtLeast(1f), 0f, th.primary, th.accent, Shader.TileMode.CLAMP)
                c.drawRoundRect(RectF(0f, cy - tr, px, cy + tr), 3f * d, 3f * d, p)
                p.shader = null
            }
            // الشريط التاني (تحت): أخضر = فيه جملة ترجمة فعلًا (بيتقطع عند الفجوات)
            strip(c, w, cy + tr + 3f * d, 4f * d, cov, 0xFF22C55E.toInt(), th.border)
            val r = (if (dragging) 5.5f else 3.5f) * d
            val tcx = px.coerceIn(r, w - r)
            p.style = Paint.Style.FILL; p.shader = null; p.color = Color.WHITE
            c.drawCircle(tcx, cy, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = th.accent
            c.drawCircle(tcx, cy, r, p)
            return
        }
        val cy = height / 2f; val th6 = 3f * d
        if (pend.isNotEmpty()) strip(c, w, 2f * d, 3f * d, pend, 0xFFF59E0B.toInt(), 0x33F59E0B)
        p.style = Paint.Style.FILL
        p.color = 0x2EFFFFFF
        c.drawRoundRect(RectF(0f, cy - th6, w, cy + th6), th6, th6, p)
        p.color = 0xFF3B82F6.toInt()
        val px = w * played.coerceIn(0f, 1f)
        if (px > 0f) c.drawRoundRect(RectF(0f, cy - th6, px, cy + th6), th6, th6, p)
        strip(c, w, cy + th6 + 4f * d, 3f * d, cov, 0xFF22C55E.toInt(), 0x2EFFFFFF)
        val r = (if (dragging) 8f else 6.5f) * d
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        c.drawCircle(px.coerceIn(r, w - r), cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = 0xFF3B82F6.toInt()
        c.drawCircle(px.coerceIn(r, w - r), cy, r, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width <= 0) return true
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); played = f; invalidate(); onDrag?.invoke(true); onScrub?.invoke(f) }
            MotionEvent.ACTION_MOVE -> { played = f; invalidate(); onScrub?.invoke(f) }
            MotionEvent.ACTION_UP -> { dragging = false; played = f; invalidate(); onDrag?.invoke(false); onSeek?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); onDrag?.invoke(false) }
        }
        return true
    }
}

/** شارة النسبة وعدد الجمل أعلى يمين الفيديو (.fs-trans-badge) */
class TransBadge(ctx: Context, th: Theme) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val pct = TextView(ctx).apply { textSize = 13f; setTextColor(th.primary); typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(10f, 0f, 0f, (th.primary and 0x00FFFFFF) or 0x55000000); includeFontPadding = false }
    private val cnt = TextView(ctx).apply { textSize = 10f; setTextColor(0xB3FFFFFF.toInt()); includeFontPadding = false }
    init {
        orientation = VERTICAL; gravity = Gravity.END; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
        background = GradientDrawable().apply { setColor(0xE0141418.toInt()); cornerRadius = 12 * d; setStroke((1 * d).toInt().coerceAtLeast(1), 0x1FFFFFFF) }
        addView(pct); addView(cnt, LayoutParams(-2, -2).apply { topMargin = (2 * d).toInt() })
    }
    fun set(p: String, c: String) { pct.text = p; cnt.text = c }
}

class MaxHeightScroll(ctx: Context, val maxH: Int) : ScrollView(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
    }
}

class Ctl(
    val root: LinearLayout, val prog: DualProgress, val tEl: TextView, val tDur: TextView,
    val prev: TextView, val next: TextView, val play: TextView, val speed: TextView,
    val vol: SeekBar, val cc: TextView, val exp: TextView, val rot: TextView
)

class MemRow(val root: LinearLayout, val ramTv: TextView, val ramBar: ProgressBar, val cacheTv: TextView, val cacheBar: ProgressBar) {
    fun update(ctx: Context) {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory(); val mx = rt.maxMemory()
        val pct = if (mx > 0) (used * 100 / mx).toInt() else 0
        ramTv.text = "🧠 رام: $pct% (${used / 1048576} م.ب)"; ramBar.progress = pct
        val dir = ctx.cacheDir
        Thread {
            var sz = 0L
            try { dir.walkTopDown().forEach { f -> if (f.isFile) sz += f.length() } } catch (_: Exception) {}
            val cap = 512L * 1048576L
            val cp = (sz * 100 / cap).toInt().coerceIn(0, 100)
            root.post { cacheTv.text = "💾 كاش الفيديو: $cp% (${sz / 1048576} م.ب)"; cacheBar.progress = cp }
        }.apply { isDaemon = true }.start()
    }
}

fun Ui.onPrimary(): Int = if (th.isLight) Color.WHITE else Color.BLACK

/** .icon-tool-btn: دايرة 32dp، المفتاح بحدود متقطعة (accent) */
fun Ui.circleBtn(t: String, dashed: Boolean = false, f: () -> Unit): TextView = TextView(ctx).apply {
    text = t; textSize = 14f; gravity = Gravity.CENTER; setTextColor(if (dashed) th.primary else th.muted)
    includeFontPadding = false
    background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(th.card)
        if (dashed) setStroke(dp(1), th.success, dp(3).toFloat(), dp(2).toFloat()) else setStroke(dp(1), th.border)
    }
    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(3), 0, dp(3), 0) }
    setOnClickListener { f() }
}

/** .model-badge */
fun Ui.pillChip(t: String, f: () -> Unit): TextView = TextView(ctx).apply {
    text = t; textSize = 11f; gravity = Gravity.CENTER; setTextColor(th.accent); setSingleLine()
    setPadding(dp(10), dp(4), dp(10), dp(4))
    background = box(th.card, th.border, 20)
    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(3), 0, dp(3), 0) }
    setOnClickListener { f() }
}

private fun Ui.gap25(): Int = (ctx.resources.displayMetrics.density * 2.5f).toInt()

/** .tools-row-btn: أيقونة + عنوان 8sp، radius 8، padding 5/4 */
fun Ui.gridBtn(icon: String, label: String, f: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    setPadding(dp(4), dp(5), dp(4), dp(5))
    background = box(th.card, th.border, 8)
    addView(TextView(ctx).apply { text = icon; textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false })
    addView(text(label, 8f, th.muted, true).apply { gravity = Gravity.CENTER; maxLines = 2; includeFontPadding = false },
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
    val m = gap25()
    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(m, m, m, m) }
    setOnClickListener { f() }
}

/** شبكة أزرار: 6 أعمدة زي .tools-row (الخانات الفاضية بتتملى بفراغ عشان العرض يفضل ثابت) */
fun Ui.grid(items: List<View>, cols: Int = 6): LinearLayout {
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    val m = gap25()
    items.chunked(cols).forEach { rowItems ->
        val row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        rowItems.forEach { row.addView(it) }
        for (i in rowItems.size until cols) row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, 0, m, 0) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
    }
    return col
}

/** .action-fab-translate: كبسولة accent ثابتة أسفل المنتصف (ارتفاع 46، radius 26، نص 12.5 bold) */
fun Ui.bigAction(icon: String, label: String, f: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL
    setPadding(dp(20), 0, dp(20), 0)
    background = GradientDrawable().apply { setColor(th.accent); cornerRadius = dp(26).toFloat() }
    elevation = dp(6).toFloat()
    addView(TextView(ctx).apply { text = icon; textSize = 17f; setTextColor(Color.WHITE); includeFontPadding = false })
    addView(TextView(ctx).apply { text = label; textSize = 12.5f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; includeFontPadding = false },
        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    layoutParams = FrameLayout.LayoutParams(-2, dp(46), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(18) }
    setOnClickListener { f() }
}

/** .main-fab-bubble: فقاعة ☰ ثابتة (يمين، 76dp من تحت) */
fun Ui.mainBubble(f: () -> Unit): TextView = TextView(ctx).apply {
    text = "☰"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = 0.75f
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xEB1E2228.toInt()); setStroke((1.5f * ctx.resources.displayMetrics.density).toInt(), 0x66F5A623) }
    elevation = dp(6).toFloat()
    layoutParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(76); marginEnd = dp(18) }
    setOnClickListener { f() }
}

/** نافذة سفلية (bottom sheet) بنفس كروت الأصل. fixedH = ارتفاع ثابت للقوائم الطويلة */
fun Ui.sheet(act: Activity, title: String, views: List<View>, fixedH: Boolean = false, onClose: () -> Unit = {}): Dialog {
    val d = Dialog(act)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    views.forEach { body.addView(it) }
    val head = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, dp(8)) }
    head.addView(text(title, 17f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(ctx).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { d.dismiss() } })
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(14), dp(12), dp(14), dp(14)); background = box(th.card, th.border, 18)
    }
    root.addView(head, LinearLayout.LayoutParams(-1, -2))
    val maxH = (ctx.resources.displayMetrics.heightPixels * 0.78f).toInt()
    val holder: View
    if (fixedH) {
        holder = body
        root.addView(body, LinearLayout.LayoutParams(-1, maxH))
    } else {
        val sv = MaxHeightScroll(ctx, maxH).apply { addView(body) }
        holder = sv
        root.addView(sv, LinearLayout.LayoutParams(-1, -2))
    }
    d.setContentView(root)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setGravity(Gravity.BOTTOM)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    // الأفقي: النافذة بتتفتح كصفحة كاملة؛ الرأسي: bottom sheet زي الأول (بيتحدد وقت الفتح مش وقت الإنشاء)
    d.setOnShowListener {
        val land = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = d.window
        if (land) {
            root.background = box(th.card, th.card, 0)
            w?.setBackgroundDrawable(ColorDrawable(th.bg))
            w?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (fixedH) holder.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        } else {
            root.background = box(th.card, th.border, 18)
            w?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            if (fixedH) holder.layoutParams = LinearLayout.LayoutParams(-1, (ctx.resources.displayMetrics.heightPixels * 0.78f).toInt())
        }
    }
    d.setOnDismissListener { onClose() }
    return d
}

/** الأفقي: أي نافذة (Dialog) بتتفتح كصفحة كاملة بدل نافذة صغيرة. بتتنده قبل d.show() */
fun Ui.fullPage(d: Dialog) {
    if (ctx.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) return
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(th.bg))
        setGravity(Gravity.FILL)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}

/** زرار القايمة الجانبية (الأفقي): أيقونة كبيرة + عنوان، بيتوزع 3 في الصف عن طريق Ui.grid(items, 3) */
fun Ui.sideBtn(icon: String, label: String, f: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
    setPadding(dp(4), dp(8), dp(4), dp(8))
    background = box(th.surface, th.border, 12)
    addView(TextView(ctx).apply { text = icon; textSize = 24f; gravity = Gravity.CENTER; includeFontPadding = false })
    addView(text(label, 11f, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 2; includeFontPadding = false },
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
    val m = dp(4)
    layoutParams = LinearLayout.LayoutParams(0, dp(84), 1f).apply { setMargins(m, m, m, m) }
    setOnClickListener { f() }
}

/** .fs-subsize-btn: مربع 32dp داكن شفاف بحدود خفيفة (radius 10، 12sp bold) */
fun Ui.fsBtn(t: String, f: (TextView) -> Unit): TextView = TextView(ctx).apply {
    text = t; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setSingleLine()
    typeface = android.graphics.Typeface.DEFAULT_BOLD
    minimumWidth = dp(32); setPadding(dp(9), 0, dp(9), 0)
    background = box(0xE0141418.toInt(), 0x1FFFFFFF, 10)
    layoutParams = LinearLayout.LayoutParams(-2, dp(32)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    setOnClickListener { f(this) }
}

/** .fullscreen-btn: دايرة 34dp داكنة (أسفل يسار الفيديو) */
fun Ui.fsCircle(t: String, f: () -> Unit): TextView = TextView(ctx).apply {
    text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE00F0F12.toInt()); setStroke(dp(1), 0x1FFFFFFF) }
    setOnClickListener { f() }
}

/** شريط التقدم + الوقت + سابق/تالي + الكبسولة (.controls): [تشغيل] [سرعة] [🔊 سلايدر] [CC] [📤] [▯] */
fun Ui.controls(): Ctl {
    val prog = DualProgress(ctx, th).apply { mini = true }
    fun mono(t: String) = text(t, 11f, th.muted).apply { typeface = android.graphics.Typeface.MONOSPACE }
    val tEl = mono("0:00"); val tDur = mono("0:00")
    fun small(t: String) = TextView(ctx).apply { text = t; textSize = 11f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(dp(8), dp(2), dp(8), dp(2)) }
    val prev = small("⏮"); val next = small("⏭")
    val timeRow = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
    timeRow.addView(tEl)
    timeRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    timeRow.addView(prev); timeRow.addView(next)
    timeRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    timeRow.addView(tDur)

    fun ic(t: String) = TextView(ctx).apply {
        text = t; textSize = 16f; setTextColor(th.text); gravity = Gravity.CENTER; includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(38)).apply { setMargins(dp(2), 0, dp(2), 0) }
    }
    val play = TextView(ctx).apply {
        text = "▶"; textSize = 19f; gravity = Gravity.CENTER; setTextColor(onPrimary()); includeFontPadding = false
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.primary, th.accent)).apply { shape = GradientDrawable.OVAL }
        elevation = dp(4).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(dp(2), 0, dp(4), 0) }
    }
    val speed = ic("⏩")
    val vol = SeekBar(ctx).apply {
        max = 100; progress = 100; layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(8), 0, dp(8), 0)
        progressTintList = ColorStateList.valueOf(th.accent); progressBackgroundTintList = ColorStateList.valueOf(th.border)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
    }
    val cc = ic("CC").apply { textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
    val exp = ic("📤"); val rot = ic("▯")
    val pill = LinearLayout(ctx).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(th.card, th.surface)).apply { cornerRadius = dp(40).toFloat(); setStroke(dp(1), th.border) }
        elevation = dp(2).toFloat()
    }
    pill.addView(play); pill.addView(speed); pill.addView(ic("🔊"))
    pill.addView(vol, LinearLayout.LayoutParams(0, -2, 1f))
    pill.addView(cc); pill.addView(exp); pill.addView(rot)

    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(16), 0, dp(16), 0) }
    root.addView(prog, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    root.addView(timeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    root.addView(pill, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    return Ctl(root, prog, tEl, tDur, prev, next, play, speed, vol, cc, exp, rot)
}

/** .mem-monitor: شريط عرضه كامل بخلفية card وحد سفلي؛ رام يمين وكاش شمال */
fun Ui.memRow(): MemRow {
    fun bar() = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; isIndeterminate = false; progress = 0
        progressTintList = ColorStateList.valueOf(th.success); progressBackgroundTintList = ColorStateList.valueOf(th.surface)
    }
    val ramTv = text("🧠 رام: —", 10f, th.muted); val cacheTv = text("💾 كاش الفيديو: —", 10f, th.muted)
    val ramBar = bar(); val cacheBar = bar()
    fun item(tv: TextView, b: ProgressBar) = LinearLayout(ctx).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        addView(tv); addView(b, LinearLayout.LayoutParams(dp(46), dp(5)).apply { marginStart = dp(4) })
    }
    val row = LinearLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)) }
    row.addView(item(ramTv, ramBar), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
    row.addView(item(cacheTv, cacheBar), LinearLayout.LayoutParams(-2, -2))
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.card) }
    root.addView(row, LinearLayout.LayoutParams(-1, -2))
    root.addView(View(ctx).apply { setBackgroundColor(th.border) }, LinearLayout.LayoutParams(-1, 1))
    return MemRow(root, ramTv, ramBar, cacheTv, cacheBar)
}

/** مؤشر رأسي للصوت/السطوع (أيقونة + شريط + نسبة) زي fs-gesture-indicator */
class VertInd(ctx: Context, icon: String) : LinearLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val trackH = (90 * d).toInt()
    private val fill = View(ctx)
    private val lb = TextView(ctx)
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE
        setPadding((10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt())
        background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = 14 * d }
        addView(TextView(ctx).apply { text = icon; textSize = 18f; gravity = Gravity.CENTER })
        val track = android.widget.FrameLayout(ctx).apply { background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = 4 * d } }
        fill.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 4 * d }
        track.addView(fill, android.widget.FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM))
        addView(track, LayoutParams((5 * d).toInt(), trackH).apply { setMargins(0, (6 * d).toInt(), 0, (6 * d).toInt()) })
        lb.textSize = 11f; lb.setTextColor(Color.WHITE); lb.typeface = android.graphics.Typeface.DEFAULT_BOLD; addView(lb)
    }
    fun set(f: Float) {
        val v = f.coerceIn(0f, 1f)
        val lp = fill.layoutParams; lp.height = (trackH * v).toInt(); fill.layoutParams = lp
        lb.text = Math.round(v * 100).toString() + "%"; visibility = View.VISIBLE
    }
}
