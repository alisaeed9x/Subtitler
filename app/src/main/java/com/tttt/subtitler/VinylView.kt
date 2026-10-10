package com.tttt.subtitler

import android.content.Context
import android.graphics.*
import android.view.View

/**
 * (v193) مشغّل أسطوانات قديم: اسطوانة سودا كبيرة بتلف + إبرة (ذراع) بتنزل على الاسطوانة لما الأغنية تشتغل
 * وبتطلع لما توقف، وبتتحرك لجوه شوية شوية مع تقدّم الأغنية. اللمس على الاسطوانة = تشغيل/إيقاف.
 * بنرسم كل حاجة بالكود (من غير صور).
 */
class VinylView(ctx: Context, private val accent: Int) : View(ctx) {
    var art: Bitmap? = null
        set(v) { field = v; shader = null; invalidate() }
    var initial: String = "♪"
        set(v) { field = v; invalidate() }
    var playing = false
        set(v) { if (field != v) { field = v; kick() } }
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); if (!running) invalidate() }

    private var rot = 0f          // زاوية دوران الاسطوانة
    private var spin = 0f         // 0..1 سرعة اللف (بتتسارع وتتباطأ زي الحقيقي)
    private var arm = 0f          // 0 = الإبرة مرفوعة بره · 1 = نازلة على الاسطوانة
    private var last = 0L
    private var running = false
    private var shader: BitmapShader? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val tgt = if (playing) 1f else 0f
            spin += (tgt - spin) * minOf(1f, dt * 1.8f)
            arm += (tgt - arm) * minOf(1f, dt * 3.2f)
            rot = (rot + spin * 150f * dt) % 360f
            invalidate()
            val settled = !playing && spin < 0.01f && Math.abs(arm) < 0.005f
            if (settled) { spin = 0f; arm = 0f; running = false; last = 0L; invalidate() }
            else postOnAnimation(this)
        }
    }
    private fun kick() { if (!running && isAttachedToWindow && isShown) { running = true; last = 0L; postOnAnimation(tick) } else if (!isAttachedToWindow) invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (playing || spin > 0f || arm > 0f) kick() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (playing || spin > 0f || arm > 0f) kick() } else { removeCallbacks(tick); running = false; last = 0L }
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); running = false; last = 0L; super.onDetachedFromWindow() }

    // هندسة الرسم (بتتحسب مرة عند تغيير المقاس)
    private var cx = 0f; private var cy = 0f; private var R = 0f
    private var px = 0f; private var py = 0f; private var armLen = 0f
    private var aOut = 20f; private var aIn = 36f
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        R = minOf(w * 0.40f, h * 0.40f)
        cx = w * 0.46f; cy = h * 0.56f
        px = minOf(cx + R * 0.98f, w - R * 0.16f); py = cy - R * 1.02f
        armLen = R * 1.36f
        aOut = angleFor(R * 0.95f); aIn = angleFor(R * 0.44f)
        shader = null
    }
    /** الزاوية (بالدرجات من الوضع الرأسي) اللي بتخلّي طرف الإبرة على بُعد r من مركز الاسطوانة */
    private fun angleFor(r: Float): Float {
        var best = 20f; var bd = Float.MAX_VALUE
        var a = 0f
        while (a <= 80f) {
            val rad = Math.toRadians(a.toDouble())
            val tx = px - armLen * Math.sin(rad).toFloat(); val ty = py + armLen * Math.cos(rad).toFloat()
            val d = Math.abs(Math.hypot((tx - cx).toDouble(), (ty - cy).toDouble()).toFloat() - r)
            if (d < bd) { bd = d; best = a }
            a += 0.1f
        }
        return best
    }

    override fun onDraw(c: Canvas) {
        if (R <= 0f) return
        // ظل تحت الاسطوانة
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(cx, cy + R * 0.06f, R * 1.16f, intArrayOf(0x99000000.toInt(), 0x00000000), floatArrayOf(0.82f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + R * 0.06f, R * 1.16f, p)
        // الطبق المعدني
        p.reset(); p.isAntiAlias = true
        p.shader = LinearGradient(cx - R, cy - R, cx + R, cy + R, 0xFF3A3A42.toInt(), 0xFF121216.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, R * 1.045f, p)

        // الاسطوانة اللي بتلف
        c.save(); c.rotate(rot, cx, cy)
        p.reset(); p.isAntiAlias = true; p.color = 0xFF0C0C0E.toInt()
        c.drawCircle(cx, cy, R, p)
        groove.strokeWidth = maxOf(1f, R * 0.004f)
        var r = R * 0.44f; var i = 0
        while (r < R * 0.985f) {
            groove.color = if (i % 5 == 0) 0x26FFFFFF else 0x12FFFFFF
            c.drawCircle(cx, cy, r, groove)
            r += R * 0.022f; i++
        }
        // فاصل بين الأغاني (حلقة أوضح)
        groove.color = 0x40000000
        groove.strokeWidth = R * 0.012f; c.drawCircle(cx, cy, R * 0.70f, groove)
        // الليبل (الغلاف أو لون الثيم)
        val lr = R * 0.36f
        p.reset(); p.isAntiAlias = true
        val a = art
        if (a != null && a.width > 0) {
            var sh = shader
            if (sh == null) {
                sh = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val m = Matrix(); val sc = (lr * 2f) / minOf(a.width, a.height)
                m.postTranslate(-a.width / 2f, -a.height / 2f); m.postScale(sc, sc); m.postTranslate(cx, cy)
                sh.setLocalMatrix(m); shader = sh
            }
            p.shader = sh; c.drawCircle(cx, cy, lr, p)
        } else {
            p.shader = LinearGradient(cx - lr, cy - lr, cx + lr, cy + lr, accent, darker(accent), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, lr, p)
            p.reset(); p.isAntiAlias = true; p.color = 0x55FFFFFF; p.textAlign = Paint.Align.CENTER
            p.textSize = lr * 0.9f; p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(initial.take(2), cx, cy - lr * 0.34f - (p.ascent() + p.descent()) / 2f, p)
        }
        // علامة صغيرة عشان اللف يبان
        p.reset(); p.isAntiAlias = true; p.color = 0xCCFFFFFF.toInt()
        c.drawCircle(cx, cy - lr * 0.82f, lr * 0.06f, p)
        // حلقة الليبل + الثقب
        groove.color = 0x66000000; groove.strokeWidth = R * 0.01f; c.drawCircle(cx, cy, lr, groove)
        p.reset(); p.isAntiAlias = true; p.color = 0xFF0C0C0E.toInt(); c.drawCircle(cx, cy, R * 0.045f, p)
        p.color = 0xFFC9CCD2.toInt(); c.drawCircle(cx, cy, R * 0.022f, p)
        c.restore()

        // لمعة ثابتة (مش بتلف) فوق الاسطوانة
        p.reset(); p.isAntiAlias = true
        p.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.08f, 0.17f, 0.5f, 0.58f, 0.67f, 1f))
        c.drawCircle(cx, cy, R, p)

        drawArm(c)
    }

    private fun drawArm(c: Canvas) {
        val ang = aIn.let { aOut + (it - aOut) * progress } * arm      // 0 = بره الاسطوانة
        val a = if (arm <= 0.001f) 0f else ang.coerceAtLeast(0f)
        // قاعدة الذراع
        p.reset(); p.isAntiAlias = true
        p.shader = RadialGradient(px, py, R * 0.15f, 0xFFD9DCE2.toInt(), 0xFF4A4C54.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, R * 0.15f, p)
        c.save(); c.translate(px, py); c.rotate(a)     // rotate موجب = مع عقارب الساعة → الطرف يتحرك ناحية الشمال
        val sw = R * 0.035f
        // ثقل الموازنة
        p.reset(); p.isAntiAlias = true; p.color = 0xFF2B2C31.toInt()
        c.drawRoundRect(-R * 0.075f, -R * 0.33f, R * 0.075f, -R * 0.08f, R * 0.03f, R * 0.03f, p)
        // الذراع
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = sw * 1.5f; p.color = 0xFF55575F.toInt()
        c.drawLine(0f, -R * 0.1f, 0f, armLen, p)
        p.strokeWidth = sw; p.color = 0xFFD5D8DE.toInt()
        c.drawLine(0f, -R * 0.1f, 0f, armLen, p)
        // رأس الإبرة
        p.reset(); p.isAntiAlias = true; p.color = 0xFF1B1C20.toInt()
        c.save(); c.rotate(-14f, 0f, armLen)
        c.drawRoundRect(-R * 0.055f, armLen - R * 0.14f, R * 0.055f, armLen + R * 0.03f, R * 0.02f, R * 0.02f, p)
        p.color = accent; c.drawRect(-R * 0.055f, armLen - R * 0.14f, R * 0.055f, armLen - R * 0.115f, p)
        c.restore()
        c.restore()
        // محور
        p.reset(); p.isAntiAlias = true; p.color = 0xFF16171B.toInt(); c.drawCircle(px, py, R * 0.05f, p)
    }

    private fun darker(col: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(col, hsv); hsv[2] *= 0.45f; return Color.HSVToColor(hsv)
    }
}
