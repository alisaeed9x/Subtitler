package com.tttt.subtitler

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/** لودينج على طريقة ويندوز 10/11: 5 نقط بتلف في دايرة — بتتسارع وبتتباطأ ورا بعض */
class WinSpinnerDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidateSelf() }
    }
    fun start() { if (!anim.isStarted) anim.start() }
    fun stop() { anim.cancel() }

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val side = minOf(b.width(), b.height()).toFloat()
        val r = side * 0.30f; val dot = side * 0.055f
        for (k in 0 until 5) {
            var x = t - k * 0.075f
            x -= Math.floor(x.toDouble()).toFloat()
            val e = x * x * (3f - 2f * x)                       // بطيء في الأول والآخر، سريع في النص
            val ang = Math.toRadians(e * 360.0 - 90.0)
            paint.alpha = if (x < 0.04f || x > 0.96f) 120 else 255
            c.drawCircle(cx + (r * Math.cos(ang)).toFloat(), cy + (r * Math.sin(ang)).toFloat(), dot, paint)
        }
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
