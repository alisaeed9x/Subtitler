package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.view.View

/**
 * (v196) المؤثر الموسيقي: أعمدة بتطلع وتنزل مع الصوت (FFT حقيقي من Visualizer على جلسة الصوت بتاعة المشغّل).
 * Visualizer محتاج إذن RECORD_AUDIO (إذن أندرويد للتحليل بس، مفيش تسجيل). لو الإذن مش موجود أو فشل: حركة هادية بدل ما الأعمدة تفضل واقفة.
 */
class EqView(ctx: Context) : View(ctx) {
    var accent: Int = 0xFF5B9DFF.toInt()
        set(v) { field = v; shader = null; invalidate() }
    var playing = false
        set(v) { if (field != v) { field = v; kick() } }

    private val N = 36
    private val level = FloatArray(N)       // المعروض (بعد التنعيم)
    private val target = FloatArray(N)      // من آخر FFT
    private val peak = FloatArray(N)
    private var viz: Visualizer? = null
    private var sessionId = 0
    @Volatile private var live = false      // وصلنا بيانات حقيقية
    private var lastData = 0L
    private var running = false
    private var last = 0L
    private var shader: LinearGradient? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val d = resources.displayMetrics.density

    /** بيتنده كل شوية من الشاشة: يربط بجلسة الصوت الحالية (ويعيد الربط لو الأغنية اتغيّرت) */
    fun bind(session: Int, canRecord: Boolean) {
        if (!canRecord || session <= 0) { release(); return }
        if (viz != null && sessionId == session) return
        if (System.currentTimeMillis() - failAt < 3000L) return
        release()
        try {
            val v = Visualizer(session)
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = range[1].coerceAtMost(1024).coerceAtLeast(range[0])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, rate: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) feed(fft) }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            v.enabled = true
            viz = v; sessionId = session
        } catch (_: Throwable) { release(); failAt = System.currentTimeMillis() }
    }
    private var failAt = 0L
    fun release() {
        try { viz?.enabled = false } catch (_: Throwable) {}
        try { viz?.release() } catch (_: Throwable) {}
        viz = null; sessionId = 0; live = false
    }

    /** FFT → N عمود (مقياس لوغاريتمي من الباص للأعلى) */
    private fun feed(fft: ByteArray) {
        val bins = fft.size / 2 - 1
        if (bins < 8) return
        val hi = (bins * 0.55f).toInt().coerceAtLeast(N)     // فوق كده ما فيهوش طاقة تفيد في الأغاني
        for (i in 0 until N) {
            val a = Math.pow(hi.toDouble(), i.toDouble() / N).toInt().coerceIn(1, hi)
            val b = Math.pow(hi.toDouble(), (i + 1).toDouble() / N).toInt().coerceIn(a + 1, hi + 1)
            var m = 0f
            for (k in a until b) {
                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                m = maxOf(m, Math.hypot(re.toDouble(), im.toDouble()).toFloat())
            }
            val v = (Math.log10(1.0 + m) / Math.log10(182.0)).toFloat().coerceIn(0f, 1f)
            target[i] = v * v * 0.35f + v * 0.65f
        }
        live = true; lastData = System.nanoTime()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val t = now / 1e9
            val realFresh = live && now - lastData < 400_000_000L
            var moving = false
            for (i in 0 until N) {
                val tg = when {
                    !playing -> 0f
                    realFresh -> target[i]
                    else -> (0.16f + 0.14f * Math.sin(t * 2.1 + i * 0.55) + 0.08f * Math.sin(t * 3.7 + i * 1.3)).toFloat().coerceIn(0.03f, 0.4f)
                }
                val cur = level[i]
                level[i] = if (tg > cur) cur + (tg - cur) * minOf(1f, dt * 28f) else cur + (tg - cur) * minOf(1f, dt * 7f)
                if (level[i] > peak[i]) peak[i] = level[i] else peak[i] = maxOf(level[i], peak[i] - dt * 0.55f)
                if (level[i] > 0.004f || peak[i] > 0.01f) moving = true
            }
            invalidate()
            if (playing || moving) postOnAnimation(this) else { running = false; last = 0L }
        }
    }
    private fun kick() { if (!running && isAttachedToWindow && isShown) { running = true; last = 0L; postOnAnimation(tick) } else invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (playing) kick() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) { if (playing) kick() } else { removeCallbacks(tick); running = false; last = 0L; release() }
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); running = false; last = 0L; release(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); shader = null }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        var sh = shader
        if (sh == null) {
            sh = LinearGradient(0f, h, 0f, 0f, (accent and 0x00FFFFFF) or 0xE0000000.toInt(), 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
            shader = sh
        }
        val gap = 3f * d
        val bw = (w - gap * (N - 1)) / N
        val minH = 3f * d
        p.reset(); p.isAntiAlias = true; p.shader = sh
        for (i in 0 until N) {
            val x = i * (bw + gap)
            val bh = minH + (h - minH - 4f * d) * level[i]
            rf.set(x, h - bh, x + bw, h)
            c.drawRoundRect(rf, bw / 2f, bw / 2f, p)
        }
        // نقطة القمة (بتنزل ببطء)
        p.shader = null; p.color = 0xB3FFFFFF.toInt()
        for (i in 0 until N) {
            val pk = peak[i]
            if (pk - level[i] < 0.02f) continue
            val x = i * (bw + gap)
            val y = h - (minH + (h - minH - 4f * d) * pk) - 3f * d
            rf.set(x, y - 2f * d, x + bw, y)
            c.drawRoundRect(rf, bw / 2f, bw / 2f, p)
        }
    }
}
