package com.tttt.subtitler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator

/**
 * (v195) الدايرة الزجاجية بتاعة مشغّل الموسيقى (زي التصميم):
 *  • غلاف الأغنية دايري في النص وبيلف ببطء وهي شغّالة
 *  • حلقة زجاجية حواليه، وجواها حلقة رفيعة عليها قوس أزرق = تقدّم الأغنية (اسحبه عشان تقدّم/ترجّع) والوقت في الفتحة اللي تحت
 *  • غلاف الأغنية اللي قبلها واللي بعدها باينين على الجنبين (متغطيين بالزجاج) — اسحب الغلاف يمين/شمال عشان تغيّر الأغنية
 *  • لمسة على الغلاف = تشغيل/إيقاف
 * بنرسم كل حاجة بالكود.
 */
class OrbView(ctx: Context) : View(ctx) {
    // ===== واجهة التحكم =====
    var onToggle: () -> Unit = {}
    /** السحب/الضغط على تالي (+1) أو سابق (-1). بيرجّع true لو الأغنية اتغيّرت فعلًا (عشان الأنيميشن يكمّل) */
    var onStep: (Int) -> Boolean = { false }
    var onSeek: (Float) -> Unit = {}
    /** نص الوقت وهو بيتسحب (بيحسبه الطرف التاني من مدة الأغنية) */
    var seekLabel: (Float) -> String = { "" }

    var accent: Int = 0xFF5B9DFF.toInt()
    var initial: String = "♪"
    var playing = false
        set(v) { if (field != v) { field = v; kick() } }
    var timeText: String = "0:00"
        set(v) { if (field != v) { field = v; if (!dragSeek) invalidate() } }
    private var downR = 0f
    var progress = 0f
        set(v) { if (!dragSeek) { field = v.coerceIn(0f, 1f); invalidate() } }
    private var dragProg = 0f          // (v196) مكان الإصبع على القوس وقت السحب — progress نفسه بيتجاهل التحديث من بره وقت السحب
    private fun shownProg() = if (dragSeek) dragProg else progress

    private var artPrev: Bitmap? = null; private var artCur: Bitmap? = null; private var artNext: Bitmap? = null
    private var pPrev: Bitmap? = null; private var pCur: Bitmap? = null; private var pNext: Bitmap? = null
    private var hasPrev = false; private var hasNext = false
    private var pHasPrev = false; private var pHasNext = false

    /** يحدّث الأغلفة الثلاثة. لو فيه أنيميشن شغّال بيستنى لحد ما يخلص عشان ما يحصلش نطّة */
    fun setArts(prev: Bitmap?, cur: Bitmap?, next: Bitmap?, hasPrevTrack: Boolean, hasNextTrack: Boolean) {
        if (animating || dragPan) { pPrev = prev; pCur = cur; pNext = next; pHasPrev = hasPrevTrack; pHasNext = hasNextTrack; pending = true; return }
        artPrev = prev; artCur = cur; artNext = next; hasPrev = hasPrevTrack; hasNext = hasNextTrack; pending = false; invalidate()
    }
    private var pending = false

    // ===== حالة الأنيميشن =====
    private val d = resources.displayMetrics.density
    private var rot = 0f
    private var s = 0f                 // إزاحة الكاروسيل: -1 = التالي في النص · +1 = السابق في النص
    private var nbAlpha = 1f
    private var animating = false
    private var running = false
    private var last = 0L
    private var anim: ValueAnimator? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mx = Matrix()
    private val arcBox = RectF()
    private val glassClip = Path()
    private val holeClip = Path()
    private val shaders = HashMap<Bitmap, BitmapShader>()
    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            if (playing) rot = (rot + 9f * dt) % 360f
            invalidate()
            if (playing || animating) postOnAnimation(this) else { running = false; last = 0L }
        }
    }
    private fun kick() { if (!running && isAttachedToWindow && isShown) { running = true; last = 0L; postOnAnimation(tick) } else invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (playing) kick() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (playing || animating) kick() } else { removeCallbacks(tick); running = false; last = 0L }
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); running = false; last = 0L; anim?.cancel(); super.onDetachedFromWindow() }

    // ===== الهندسة =====
    private var cx = 0f; private var cy = 0f
    private var ro = 0f      // نص قطر الحلقة الزجاجية
    private var ra = 0f      // الغلاف في النص
    private var rt = 0f      // الحلقة الرفيعة (المسار)
    private var rn = 0f      // الأغلفة اللي على الجنبين
    private var dOut = 0f    // مكان الجنبين بالنسبة للنص
    private var dIn = 0f     // مسافة الكاروسيل جوه فتحة الغلاف
    private var gapHalf = 14f
    private var startAng = 104f
    /** (v198) مركز/نص قطر الحلقة الزجاجية — بيستخدمهم المشغّل عشان ينزّل الدواير من غير ما يصغّرها */
    val ringCy: Float get() = cy
    val ringR: Float get() = ro
    /** لما الكلمات ظاهرة: اللمس بره الحلقة والجنبين يعدّي للكلمات اللي ورا (عشان تتسحب) */
    var passThrough = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cx = w / 2f; cy = h / 2f
        ro = minOf(w * 0.40f, h * 0.49f)
        ra = ro * 0.62f; rt = ro * 0.845f; rn = ro * 0.39f
        dOut = ro * 1.04f; dIn = ra * 2f + 10 * d
        glassClip.reset(); glassClip.addCircle(cx, cy, ro, Path.Direction.CW)
        holeClip.reset(); holeClip.addCircle(cx, cy, ra, Path.Direction.CW)
        computeGap()
    }
    private fun computeGap() {
        if (rt <= 0f) return
        p.reset(); p.textSize = 15f * d; p.typeface = Typeface.DEFAULT
        val w = p.measureText("00:00") / 2f + 10 * d
        gapHalf = Math.toDegrees(Math.asin((w / rt).coerceIn(0.05f, 0.9f).toDouble())).toFloat()
        startAng = 90f + gapHalf
    }
    private fun total() = 360f - 2f * gapHalf

    // ===== الرسم =====
    private fun shaderFor(b: Bitmap): BitmapShader {
        if (shaders.size > 8) shaders.clear()
        return shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }
    private fun drawArt(c: Canvas, b: Bitmap?, x: Float, y: Float, r: Float, rotate: Float, alpha: Int, hasTrack: Boolean) {
        if (!hasTrack && b == null) return
        p.reset(); p.isAntiAlias = true; p.alpha = alpha
        c.save(); c.rotate(rotate, x, y)
        if (b != null && b.width > 0) {
            val sh = shaderFor(b)
            val sc = (r * 2f) / minOf(b.width, b.height)
            val fp = FaceFocus.focus(b)      // (v196) مركز القص = وش المغني (مش نص الصورة)
            mx.reset(); mx.postTranslate(-fp.x, -fp.y); mx.postScale(sc, sc); mx.postTranslate(x, y)
            sh.setLocalMatrix(mx); p.shader = sh
            c.drawCircle(x, y, r, p)
        } else {
            p.shader = LinearGradient(x - r, y - r, x + r, y + r, accent, 0xFF1A1030.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, p)
            p.shader = null; p.color = 0x66FFFFFF; p.alpha = minOf(alpha, 0x66); p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.9f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(initial.take(2), x, y - (p.ascent() + p.descent()) / 2f, p)
        }
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        if (ro <= 0f) return
        val sIn = s * dIn            // إزاحة الغلاف جوه الفتحة (بكسل)
        val sOut = s * dOut

        // 1) الأغلفة اللي على الجنبين (تحت الزجاج)
        val a1 = (255 * 0.92f * nbAlpha).toInt()
        drawArt(c, artPrev, cx - dOut + sOut, cy, rn, 0f, a1, hasPrev)
        drawArt(c, artNext, cx + dOut + sOut, cy, rn, 0f, a1, hasNext)
        // الغلاف الحالي وهو طالع/نازل للجنب (نسخته اللي بره الفتحة)
        if (Math.abs(s) > 0.02f) drawArt(c, artCur, cx + sOut, cy, rn, 0f, 235, true)

        // 2) الحلقة الزجاجية
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(cx, cy, ro, intArrayOf(0xEE050508.toInt(), 0xD40B0B12.toInt(), 0xA0242434.toInt(), 0x66FFFFFF),
            floatArrayOf(0f, 0.6f, 0.9f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, ro, p)
        // لمعتين على الجنبين جوه الزجاج (زي انعكاس الضوء)
        c.save(); c.clipPath(glassClip)
        for (sx in floatArrayOf(-1f, 1f)) {
            p.reset(); p.isAntiAlias = true
            val gx = cx + sx * ro * 0.9f
            p.shader = RadialGradient(gx, cy, ro * 0.34f, intArrayOf(0x55FFFFFF, 0x1AFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(gx, cy, ro * 0.34f, p)
        }
        c.restore()
        // حافة الزجاج
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d
        p.shader = LinearGradient(cx - ro, cy - ro, cx + ro, cy + ro, 0xCCFFFFFF.toInt(), 0x33FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, ro - 0.8f * d, p)
        p.strokeWidth = 7f * d
        p.shader = LinearGradient(cx - ro, cy - ro, cx + ro, cy + ro, 0x30FFFFFF, 0x06FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, ro - 4.5f * d, p)

        // 3) الغلاف في النص (فتحة دايرية بتعدّي منها الأغلفة وهي بتتزحلق)
        c.save()
        c.clipPath(holeClip)
        val rr = rot
        drawArt(c, artPrev, cx - dIn + sIn, cy, ra, rr, 255, hasPrev)
        drawArt(c, artCur, cx + sIn, cy, ra, rr, 255, true)
        drawArt(c, artNext, cx + dIn + sIn, cy, ra, rr, 255, hasNext)
        c.restore()
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = 0x33FFFFFF
        c.drawCircle(cx, cy, ra, p)

        // 4) المسار الرفيع + قوس التقدّم + الوقت
        val box = arcBox; box.set(cx - rt, cy - rt, cx + rt, cy + rt)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * d; p.color = 0x40FFFFFF; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(box, startAng, total(), false, p)
        val sweep = total() * shownProg()
        if (sweep > 0.3f) {
            p.strokeWidth = 8f * d; p.color = (accent and 0x00FFFFFF) or 0x22000000; c.drawArc(box, startAng, sweep, false, p)    // هالة
            p.strokeWidth = 3.2f * d; p.color = accent; c.drawArc(box, startAng, sweep, false, p)
        }
        // مقبض صغير
        val endA = Math.toRadians((startAng + sweep).toDouble())
        val tx = cx + rt * Math.cos(endA).toFloat(); val ty = cy + rt * Math.sin(endA).toFloat()
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL
        p.color = (accent and 0x00FFFFFF) or 0x44000000; c.drawCircle(tx, ty, (if (dragSeek) 12f else 8f) * d, p)
        p.color = Color.WHITE; c.drawCircle(tx, ty, (if (dragSeek) 6f else 4.2f) * d, p)
        // الوقت في الفتحة
        p.reset(); p.isAntiAlias = true; p.color = Color.WHITE; p.textSize = 15f * d; p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        val label = if (dragSeek) seekLabel(dragProg).ifEmpty { timeText } else timeText
        c.drawText(label, cx, cy + rt - (p.ascent() + p.descent()) / 2f, p)
    }

    // ===== اللمس =====
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var vt: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f
    private var dragSeek = false; private var dragPan = false; private var moved = false
    private var lastP = 0f

    private fun angleToProgress(x: Float, y: Float): Float {
        var a = Math.toDegrees(Math.atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        if (a < 0) a += 360f
        var rel = a - startAng; if (rel < 0) rel += 360f
        val t = total()
        var pr = if (rel <= t) rel / t else if (rel - t < gapHalf) 1f else 0f
        if (Math.abs(pr - lastP) > 0.5f) pr = if (lastP > 0.5f) 1f else 0f      // ما نقفزش من آخر القوس لأوله
        return pr.coerceIn(0f, 1f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false; dragSeek = false; dragPan = false
                val r = Math.hypot((e.x - cx).toDouble(), (e.y - cy).toDouble()).toFloat()
                if (passThrough && r > ro * 1.06f && !(Math.abs(e.y - cy) <= rn * 1.1f && Math.abs(e.x - cx) > ro * 0.55f)) return false
                downR = r
                parent?.requestDisallowInterceptTouchEvent(true)
                if (Math.abs(r - rt) <= 30 * d && r <= ro) { dragSeek = true; lastP = progress; dragProg = angleToProgress(e.x, e.y); lastP = dragProg; invalidate() }
                else { dragPan = true; anim?.cancel(); animating = false; vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragSeek) { dragProg = angleToProgress(e.x, e.y); lastP = dragProg; invalidate(); return true }
                if (dragPan) {
                    vt?.addMovement(e)
                    val dx = e.x - downX
                    if (!moved && Math.abs(dx) > slop) moved = true
                    if (moved) { s = (dx / dIn).coerceIn(-1f, 1f); invalidate() }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragSeek) { dragSeek = false; progress = dragProg; onSeek(dragProg); invalidate(); return true }
                if (dragPan) {
                    dragPan = false
                    vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (!moved) { s = 0f; applyPending(); if (downR <= ro) onToggle(); invalidate(); return true }
                    val dir = if (s < -0.28f || vx < -900f) 1 else if (s > 0.28f || vx > 900f) -1 else 0
                    if (dir == 0) settle(false, 0) else commit(dir)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragSeek) { dragSeek = false; invalidate() }
                if (dragPan) { dragPan = false; vt?.recycle(); vt = null; settle(false, 0) }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** تغيير الأغنية بالأنيميشن (من السحب أو من زراري التالي/السابق). dir=+1 تالي · -1 سابق */
    fun commit(dir: Int) {
        val ok = onStep(dir)
        if (ok) settle(true, dir) else settle(false, 0)
    }
    private fun applyPending() {
        if (pending) { artPrev = pPrev; artCur = pCur; artNext = pNext; hasPrev = pHasPrev; hasNext = pHasNext; pending = false }
    }
    /** changed=true: كمّل الكاروسيل لحد الغلاف الجديد في النص وبعدين صفّر · false: ارجع مكانك */
    private fun settle(changed: Boolean, dir: Int) {
        anim?.cancel()
        val from = s; val to = if (changed) (if (dir > 0) -1f else 1f) else 0f
        if (from == to) { s = 0f; animating = false; applyPending(); nbAlpha = 1f; invalidate(); return }
        animating = true; kick()
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = 260L; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { s = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    animating = false; s = 0f
                    val had = pending
                    applyPending()
                    if (changed && had) { nbAlpha = 0f; fadeNeighbors() }      // الجار الجديد يظهر بنعومة
                    invalidate()
                }
            })
            start()
        }
    }
    private fun fadeNeighbors() {
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 280L; addUpdateListener { nbAlpha = it.animatedValue as Float; invalidate() }; start() }
    }
    /** لو الأغنية اتغيّرت من بره (مثلًا خلصت لوحدها) والأنيميشن مش شغّال */
    fun flush() { if (!animating && !dragPan) applyPending().also { invalidate() } }
}

/**
 * أيقونة مرسومة بالكود (24×24): shuffle · prev · play · pause · next · repeat · repeat1 · chev · dots
 */
class GlyphBtn(ctx: Context, var glyph: String, private val iconDp: Int) : View(ctx) {
    var tint: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(g: String, color: Int = tint) { if (glyph == g && tint == color) return; glyph = g; tint = color; invalidate() }

    private fun line(c: Canvas, vararg pts: Float) {
        val path = Path(); path.moveTo(pts[0], pts[1]); var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        c.drawPath(path, p)
    }

    override fun onDraw(c: Canvas) {
        val sz = iconDp * d
        c.save()
        c.translate((width - sz) / 2f, (height - sz) / 2f); c.scale(sz / 24f, sz / 24f)
        p.reset(); p.isAntiAlias = true; p.color = tint
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        fun fill() { p.style = Paint.Style.FILL }
        fun stroke(w: Float = 1.9f) { p.style = Paint.Style.STROKE; p.strokeWidth = w }
        when (glyph) {
            "pause" -> { fill(); c.drawRoundRect(5.5f, 4f, 10.3f, 20f, 1.6f, 1.6f, p); c.drawRoundRect(13.7f, 4f, 18.5f, 20f, 1.6f, 1.6f, p) }
            "play" -> { p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 2f; val t = Path(); t.moveTo(7.5f, 4.8f); t.lineTo(19.5f, 12f); t.lineTo(7.5f, 19.2f); t.close(); c.drawPath(t, p) }
            "prev" -> { p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.6f
                c.drawRoundRect(4.6f, 5f, 6.8f, 19f, 1f, 1f, p)
                val t = Path(); t.moveTo(19.4f, 5.4f); t.lineTo(9.2f, 12f); t.lineTo(19.4f, 18.6f); t.close(); c.drawPath(t, p) }
            "next" -> { p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.6f
                c.drawRoundRect(17.2f, 5f, 19.4f, 19f, 1f, 1f, p)
                val t = Path(); t.moveTo(4.6f, 5.4f); t.lineTo(14.8f, 12f); t.lineTo(4.6f, 18.6f); t.close(); c.drawPath(t, p) }
            "shuffle" -> { stroke()
                val a = Path(); a.moveTo(3f, 7f); a.lineTo(6.5f, 7f); a.cubicTo(11f, 7f, 10.6f, 17f, 15.5f, 17f); a.lineTo(20f, 17f); c.drawPath(a, p)
                val b = Path(); b.moveTo(3f, 17f); b.lineTo(6.5f, 17f); b.cubicTo(8f, 17f, 8.9f, 16.2f, 9.6f, 15.1f); c.drawPath(b, p)
                val e = Path(); e.moveTo(14.2f, 8.6f); e.cubicTo(14.8f, 7.6f, 15.8f, 7f, 17f, 7f); e.lineTo(20f, 7f); c.drawPath(e, p)
                line(c, 17.6f, 4.4f, 20.2f, 7f, 17.6f, 9.6f); line(c, 17.6f, 14.4f, 20.2f, 17f, 17.6f, 19.6f) }
            "repeat", "repeat1" -> { stroke()
                val a = Path(); a.moveTo(4f, 11f); a.lineTo(4f, 10f); a.cubicTo(4f, 7.8f, 5.8f, 6f, 8f, 6f); a.lineTo(19.5f, 6f); c.drawPath(a, p)
                line(c, 16.8f, 3.2f, 19.8f, 6f, 16.8f, 8.8f)
                val b = Path(); b.moveTo(20f, 13f); b.lineTo(20f, 14f); b.cubicTo(20f, 16.2f, 18.2f, 18f, 16f, 18f); b.lineTo(4.5f, 18f); c.drawPath(b, p)
                line(c, 7.2f, 15.2f, 4.2f, 18f, 7.2f, 20.8f)
                if (glyph == "repeat1") { p.style = Paint.Style.FILL; p.textSize = 8.5f; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
                    c.drawText("1", 12f, 14.6f, p) } }
            "chev" -> { stroke(2.1f); line(c, 5f, 9f, 12f, 16f, 19f, 9f) }
            "dots" -> { fill(); c.drawCircle(12f, 5f, 1.9f, p); c.drawCircle(12f, 12f, 1.9f, p); c.drawCircle(12f, 19f, 1.9f, p) }
        }
        c.restore()
    }
}

/** استخراج لون مميّز من الغلاف لخلفية المشغّل (متوسط الألوان الحيّة، مع تجاهل الأسود/الأبيض الباهت) */
object ArtColor {
    fun dominant(b: Bitmap?): Int? {
        if (b == null || b.width <= 0) return null
        return try {
            val s = Bitmap.createScaledBitmap(b, 24, 24, true)
            var r = 0.0; var g = 0.0; var bl = 0.0; var wsum = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until 24) for (x in 0 until 24) {
                val px = s.getPixel(x, y); Color.colorToHSV(px, hsv)
                val w = (hsv[1] * (0.35f + hsv[2])).toDouble() + 0.02
                r += Color.red(px) * w; g += Color.green(px) * w; bl += Color.blue(px) * w; wsum += w
            }
            if (s !== b) s.recycle()
            if (wsum <= 0.0) return null
            val col = Color.rgb((r / wsum).toInt(), (g / wsum).toInt(), (bl / wsum).toInt())
            Color.colorToHSV(col, hsv)
            hsv[1] = (hsv[1] * 1.15f).coerceIn(0.25f, 0.85f); hsv[2] = hsv[2].coerceIn(0.55f, 0.85f)
            Color.HSVToColor(hsv)
        } catch (_: Throwable) { null }
    }
}

/**
 * (v196) مركز القص للغلاف الدايري: لو فيه وش (android.media.FaceDetector) نقص حواليه، وإلا للصور الطويلة (بورتريه) نميل للجزء الفوقاني
 * (غالبًا الوش هناك) بدل نص الصورة اللي بيطلّع الجسم من غير وش. بيتحسب مرة لكل صورة (شغّله من خيط خلفية بـ compute).
 */
object FaceFocus {
    private val cache = java.util.WeakHashMap<Bitmap, PointF>()
    /** المركز (بكسلات الصورة الأصلية) متظبّط عشان مربع القص يفضل جوه الصورة */
    fun focus(b: Bitmap): PointF {
        val raw = synchronized(cache) { cache[b] }
        val w = b.width.toFloat(); val h = b.height.toFloat(); val half = minOf(w, h) / 2f
        val fx = (raw?.x ?: w / 2f).coerceIn(half, w - half)
        val fy = (raw?.y ?: h / 2f).coerceIn(half, h - half)
        return PointF(fx, fy)
    }
    fun compute(b: Bitmap) {
        if (b.width <= 0 || b.height <= 0) return
        synchronized(cache) { if (cache.containsKey(b)) return }
        var pt: PointF? = null
        try {
            val sc = 320f / maxOf(b.width, b.height).toFloat()
            var sw = maxOf(64, (b.width * sc).toInt()); val sh = maxOf(64, (b.height * sc).toInt())
            if (sw % 2 != 0) sw++                                    // FaceDetector بيطلب عرض زوجي
            val small = Bitmap.createScaledBitmap(b, sw, sh, true).copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<android.media.FaceDetector.Face>(4)
            val n = android.media.FaceDetector(small.width, small.height, 4).findFaces(small, faces)
            var best = 0f; val mid = PointF()
            for (i in 0 until n) {
                val f = faces[i] ?: continue
                if (f.confidence() < 0.4f) continue
                val sz = f.eyesDistance()
                if (sz > best) { best = sz; f.getMidPoint(mid) }
            }
            if (best > 0f) pt = PointF(mid.x / small.width * b.width, mid.y / small.height * b.height)
            if (small !== b) small.recycle()
        } catch (_: Throwable) {}
        // مفيش وش اتلقى: الصور الطويلة الوش عادةً في تلثها الفوقاني
        if (pt == null && b.height > b.width * 1.15f) pt = PointF(b.width / 2f, b.height * 0.30f)
        synchronized(cache) { cache[b] = pt ?: PointF(b.width / 2f, b.height / 2f) }
    }
}
