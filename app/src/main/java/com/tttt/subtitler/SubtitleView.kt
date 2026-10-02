package com.tttt.subtitler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.text.*
import android.text.style.*
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.animation.OvershootInterpolator

/** عرض الترجمة: StaticLayout (تشكيل عربي وBidi صح) + صندوق خلفية + 13 أنيميشن دخول + تأثيرات انفعال */
class SubtitleView(ctx: Context) : View(ctx) {
    var style = SubStyle()
        set(v) { field = v; relayout(); invalidate(); startBackdrop() }
    /** سطح الفيديو: لو اتحدد، الخلفية بتبقى blur حقيقي للفيديو اللي وراها (PixelCopy). من غيره بنرجع للحواف الناعمة. */
    var backdrop: SurfaceView? = null
        set(v) { field = v; startBackdrop() }
    private var sub: Sub? = null
    private var main: StaticLayout? = null
    private var sec: StaticLayout? = null
    private var boxW = 0; private var boxH = 0
    private var progress = 1f
    private var emoT = 0f
    private val d = ctx.resources.displayMetrics.density
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tp2 = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply { addUpdateListener { progress = it.animatedValue as Float; invalidate() } }
    private val emoAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { emoT = it.animatedValue as Float; if (emoKind() != "") invalidate() }
    }
    private fun emoKind() = if (style.plain) "" else (sub?.let { SubStyle.emotionKind(it.emotion) } ?: "")
    /** متحدثين في نفس الوقت: كل سطر بلونه (ذكر/أنثى/موحّد) */
    private var lines: List<Sub> = emptyList()
    // ===== blur حقيقي للي ورا الصندوق =====
    private val bdHandler = Handler(Looper.getMainLooper())
    private val bdPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bdPath = Path()
    private var bd: Bitmap? = null          // الصورة المغبّشة (مصغّرة)
    private var bdRect = RectF()            // مكانها في إحداثيات الـ View
    private var grabBmp: Bitmap? = null     // نسخة 1:1 من الفيديو تحت الصندوق
    private var bdBusy = false
    private val bdTick = object : Runnable {
        override fun run() { grabBackdrop(); if (wantBackdrop()) bdHandler.postDelayed(this, 120) }
    }
    private fun wantBackdrop() = backdrop != null && sub != null && visibility == VISIBLE && isAttachedToWindow &&
        !style.noBg && style.bgOpa > 0 && style.blur > 0
    private fun startBackdrop() {
        bdHandler.removeCallbacks(bdTick)
        if (wantBackdrop()) bdHandler.post(bdTick) else if (bd != null) { bd = null; invalidate() }
    }
    private fun grabBackdrop() {
        val sv = backdrop ?: return
        if (bdBusy || boxW <= 0 || boxH <= 0 || sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) return
        val vl = IntArray(2); val sl = IntArray(2)
        getLocationOnScreen(vl); sv.getLocationOnScreen(sl)
        // مكان الصندوق على الشاشة ثم نسبةً لسطح الفيديو
        val x0 = vl[0] + (width / 2f - boxW / 2f) - sl[0]; val y0 = vl[1] + 2 * d + ex() - sl[1]
        val cx0 = Math.max(0, Math.floor(x0.toDouble()).toInt()); val cy0 = Math.max(0, Math.floor(y0.toDouble()).toInt())
        val cx1 = Math.min(sv.width, Math.ceil((x0 + boxW).toDouble()).toInt()); val cy1 = Math.min(sv.height, Math.ceil((y0 + boxH).toDouble()).toInt())
        val bw = cx1 - cx0; val bh = cy1 - cy0
        if (bw < 8 || bh < 8) { if (bd != null) { bd = null; invalidate() }; return }
        val gb = grabBmp?.takeIf { it.width == bw && it.height == bh } ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { grabBmp = it }
        val rl = (sl[0] + cx0 - vl[0]).toFloat(); val rt = (sl[1] + cy0 - vl[1]).toFloat()
        val rect = RectF(rl, rt, rl + bw, rt + bh)
        bdBusy = true
        try {
            PixelCopy.request(sv, Rect(cx0, cy0, cx1, cy1), gb, { res -> onCopied(res, gb, rect) }, bdHandler)
        } catch (e: Exception) { bdBusy = false; if (bd != null) { bd = null; invalidate() } }
    }
    private fun onCopied(res: Int, gb: Bitmap, rect: RectF) {
        bdBusy = false
        if (res == PixelCopy.SUCCESS) { if (wantBackdrop()) processBackdrop(gb, rect) }
        else if (bd != null) { bd = null; invalidate() }
    }
    private fun processBackdrop(gb: Bitmap, rect: RectF) {
        try {
            val (sw, sh) = Blur.smallSize(gb.width, gb.height)
            val small = Bitmap.createScaledBitmap(gb, sw, sh, true)
            val px = IntArray(sw * sh); small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== gb) small.recycle()
            Blur.box(px, sw, sh, Blur.radiusFor(style.blur, d))
            val out = bd?.takeIf { it.width == sw && it.height == sh && it.isMutable } ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, sw, 0, 0, sw, sh)
            bd = out; bdRect = rect; invalidate()
        } catch (e: Exception) { bd = null }
    }

    private val PAD_H = 12 * d; private val PAD_T = 5 * d; private val PAD_B = 7 * d

    fun show(s: Sub?, group: List<Sub> = emptyList()) {
        val same = s === sub || (s != null && sub != null && s.start == sub!!.start && s.translated == sub!!.translated)
        if (same) return
        sub = s; lines = if (group.size > 1) group else emptyList()
        if (s == null) { main = null; sec = null; animator.cancel(); emoAnim.cancel(); visibility = INVISIBLE; startBackdrop(); return }
        visibility = VISIBLE; relayout(); startBackdrop()
        animator.cancel(); animator.duration = style.animMs.toLong()
        animator.interpolator = if (style.anim == "bounce" || style.anim == "drop") OvershootInterpolator(2f) else android.view.animation.DecelerateInterpolator()
        if (style.anim == "default") progress = 1f else animator.start()
        if (emoKind() != "") emoAnim.start() else emoAnim.cancel()
    }

    private val tfCache = HashMap<String, Typeface>()
    private fun typefaceFor(): Typeface {
        val f = style.effectiveFont()
        f.file?.let { fn ->
            tfCache[fn]?.let { return it }
            try { return Typeface.createFromAsset(context.assets, "fonts/$fn").also { tfCache[fn] = it } } catch (_: Exception) {}
        }
        return Typeface.create(if (f.serif) "serif" else "sans-serif", Typeface.BOLD)
    }
    /** حافة الخلفية الناعمة (بديل الـ backdrop blur اللي مش ممكن فوق SurfaceView) */
    private fun ex(): Float = if (style.noBg || style.bgOpa == 0) 0f else style.blur * 0.6f * d

    private fun build(t: CharSequence, p: TextPaint, w: Int) = StaticLayout.Builder.obtain(t, 0, t.length, p, w)
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(android.text.TextDirectionHeuristics.RTL)
        .setLineSpacing(0f, 1.45f).setIncludePad(false).build()

    private fun relayout() {
        val s = sub ?: return
        val w = measuredWidth.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val avail = (w * 0.95f - 2 * PAD_H).toInt().coerceAtLeast(50)
        val multi = lines.size > 1
        val ranges = ArrayList<IntRange>()
        val txt0 = if (multi) {
            val sb = StringBuilder()
            lines.forEachIndexed { i, x ->
                if (i > 0) sb.append('\n')
                val st0 = sb.length
                sb.append(PlayerLogic.arNum(i + 1)).append(") ").append(x.translated.ifBlank { x.original })
                ranges.add(st0 until sb.length)
            }
            sb.toString()
        } else style.mainText(s)
        val nWords = txt0.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val px = SubStyle.fontPx(w, d, style.scale) * style.sizeFor(nWords)
        val ss = style; val col = ss.colorFor(s)
        tp.apply { textSize = px; typeface = typefaceFor(); fontVariationSettings = "'wght' ${ss.weight()}"; color = col; letterSpacing = 0.02f; setShadowLayer(6f, 0f, 1f, 0xAA000000.toInt()) }
        tp2.apply { textSize = (px * 0.75f).coerceAtLeast(11 * d); typeface = Typeface.create("sans-serif", Typeface.NORMAL); color = 0xBBFFFFFF.toInt(); setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt()) }
        val txt = txt0
        val sp = SpannableString(txt)
        if (!style.plain) {
            var i = 0
            for (m in Regex("\\S+").findAll(txt)) {
                if (SubStyle.isEmphasis(m.value)) {
                    sp.setSpan(ForegroundColorSpan(SubStyle.EMPH), m.range.first, m.range.last + 1, 0)
                    sp.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, 0)
                    sp.setSpan(RelativeSizeSpan(1.08f), m.range.first, m.range.last + 1, 0)
                }
                i++
            }
        }
        if (multi) ranges.forEachIndexed { i, r -> if (!r.isEmpty()) sp.setSpan(ForegroundColorSpan(ss.colorFor(lines[i])), r.first, r.last + 1, 0) }
        for ((a, b, place) in style.highlights(txt, s)) {
            if (place) sp.setSpan(ForegroundColorSpan(SubStyle.PLACE), a, b, 0)
            else { sp.setSpan(ForegroundColorSpan(SubStyle.WHITE), a, b, 0); sp.setSpan(StyleSpan(Typeface.BOLD), a, b, 0) }
        }
        main = build(sp, tp, avail)
        val s2 = style.secondary(s)
        sec = if (s2.isBlank()) null else build(s2, tp2, avail)
        val inner = maxOf(maxLine(main), maxLine(sec))
        boxW = (inner + 2 * PAD_H).toInt()
        boxH = ((main?.height ?: 0) + (sec?.let { it.height + 6 * d } ?: 0f) + PAD_T + PAD_B).toInt()
        if (measuredHeight != totalH()) requestLayout()
    }
    private fun maxLine(l: StaticLayout?): Float { var m = 0f; if (l != null) for (i in 0 until l.lineCount) m = maxOf(m, l.getLineWidth(i)); return m }

    private fun totalH() = boxH + (4 * d + 2 * ex()).toInt()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        if (w != measuredWidth) { setMeasuredDimension(w, 0); relayout() }
        setMeasuredDimension(w, totalH())
    }

    override fun onDraw(c: Canvas) {
        val m = main ?: return
        val s = sub ?: return
        val cx = width / 2f; val left = cx - boxW / 2f; val top = 2 * d + ex()
        val p = progress; val an = style.anim
        c.save()
        var alpha = 1f
        val pvx = cx; val pvy = top + boxH / 2f
        when (an) {
            "fade" -> alpha = p
            "slideup" -> { c.translate(0f, (1 - p) * 24 * d); alpha = p }
            "slideside" -> { c.translate((1 - p) * 48 * d, 0f); alpha = p }
            "zoom" -> { val k = 0.6f + 0.4f * p; c.scale(k, k, pvx, pvy); alpha = p }
            "blur" -> { val k = 1.12f - 0.12f * p; c.scale(k, k, pvx, pvy); alpha = p * p }
            "bounce" -> { val k = 0.5f + 0.5f * p; c.scale(k, k, pvx, pvy); alpha = minOf(1f, p * 3) }
            "flip" -> { c.scale(1f, maxOf(0.02f, p), pvx, pvy); alpha = p }
            "rotate" -> { c.rotate((1 - p) * -14f, pvx, pvy); alpha = p }
            "drop" -> { c.translate(0f, -(1 - p) * 36 * d); alpha = minOf(1f, p * 3) }
            "glow" -> tp.setShadowLayer(6f + 14f * (1f - p) + 4f * Math.sin(emoT * 6.28).toFloat().coerceAtLeast(0f), 0f, 0f, 0xFFFFFFFF.toInt())
        }
        when (emoKind()) {
            "shout" -> { val sh = Math.sin(emoT * 6.28 * 3).toFloat(); c.translate(sh * 1.5f * d, 0f); val k = 1.02f + 0.01f * sh; c.scale(k, k, pvx, pvy) }
            "surprise" -> if (p < 1f || emoT < 0.5f) { val k = 1f + 0.08f * (1 - emoT).coerceIn(0f, 1f); c.scale(k, k, pvx, pvy) }
            "cry" -> { c.translate(0f, Math.sin(emoT * 6.28).toFloat() * 1.5f * d); alpha *= 0.85f + 0.15f * (0.5f + 0.5f * Math.sin(emoT * 6.28).toFloat()) }
            "whisper" -> { c.scale(0.94f, 0.94f, pvx, pvy); alpha *= 0.8f }
        }
        val a255 = (alpha * 255).toInt()
        if (!style.noBg && style.bgOpa > 0) {
            val real = bd != null && style.blur > 0
            if (real) {
                c.save(); bdPath.reset()
                bdPath.addRoundRect(left, top, left + boxW, top + boxH, 8 * d, 8 * d, Path.Direction.CW)
                c.clipPath(bdPath); bdPaint.alpha = a255
                bd?.let { c.drawBitmap(it, null, bdRect, bdPaint) }
                c.restore()
            }
            bgP.color = Color.argb((style.bgOpa / 100f * 255 * alpha).toInt(), 0, 0, 0)
            bgP.maskFilter = if (!real && style.blur > 0) BlurMaskFilter(maxOf(1f, style.blur * 0.6f * d), BlurMaskFilter.Blur.NORMAL) else null
            c.drawRoundRect(left, top, left + boxW, top + boxH, 8 * d, 8 * d, bgP)
        }
        var shown: Layout = m
        if (an == "typewriter" || an == "letters") shown = revealed(m, p, an == "letters") ?: m
        c.saveLayerAlpha(left, top, left + boxW, top + boxH, a255)
        c.translate(cx - shown.width / 2f, top + PAD_T)
        shown.draw(c)
        sec?.let { c.translate(0f, shown.height + 6 * d); it.draw(c) }
        c.restore()
        c.restore()
        tp.setShadowLayer(6f, 0f, 1f, 0xAA000000.toInt())
    }

    /** آلة كاتبة / كلمات متتابعة: نفس التخطيط لكن الجزء الغير ظاهر شفاف (المقاسات ثابتة فمفيش اهتزاز) */
    private var revCache: Pair<Int, StaticLayout>? = null
    private fun revealed(m: StaticLayout, p: Float, words: Boolean): Layout? {
        val text = m.text
        var n = (text.length * p).toInt()
        if (words) { val idx = text.indexOf(' ', n); n = if (idx < 0 || p >= 1f) text.length else idx }
        if (n >= text.length) return m
        val sp = SpannableString(text); sp.setSpan(ForegroundColorSpan(Color.TRANSPARENT), n, text.length, 0)
        return build(sp, tp, m.width)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); startBackdrop() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel(); emoAnim.cancel(); bdHandler.removeCallbacks(bdTick); bd = null }
}
