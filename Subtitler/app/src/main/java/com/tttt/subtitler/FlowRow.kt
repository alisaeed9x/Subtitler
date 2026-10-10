package com.tttt.subtitler

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * صف بيلفّ لسطر جديد لما الأزرار تزيد عن العرض (بدل ما تتقص أو تحتاج سحب أفقي).
 * في الأفقي العرض واسع فبيفضل سطر واحد زي ما كان؛ في الرأسي بيتقسّم لعدة سطور وكل الأزرار باينة.
 * بيحترم RTL (البداية من اليمين) والـ margins.
 */
class FlowRow(ctx: Context) : ViewGroup(ctx) {
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    private fun mlp(c: View) = c.layoutParams as MarginLayoutParams

    private fun lines(avail: Int): List<List<View>> {
        val out = ArrayList<List<View>>()
        var cur = ArrayList<View>(); var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = mlp(c)
            val w = c.measuredWidth + lp.leftMargin + lp.rightMargin
            if (cur.isNotEmpty() && x + w > avail) { out.add(cur); cur = ArrayList(); x = 0 }
            cur.add(c); x += w
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun lineH(line: List<View>): Int {
        var h = 0
        for (c in line) { val lp = mlp(c); h = maxOf(h, c.measuredHeight + lp.topMargin + lp.bottomMargin) }
        return h
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val unspecified = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
        val maxW = if (unspecified) Int.MAX_VALUE else MeasureSpec.getSize(widthSpec)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChildWithMargins(c, widthSpec, 0, heightSpec, 0)
        }
        val avail = if (unspecified) Int.MAX_VALUE else maxOf(0, maxW - paddingLeft - paddingRight)
        var widest = 0; var totalH = 0
        for (line in lines(avail)) {
            var w = 0
            for (c in line) { val lp = mlp(c); w += c.measuredWidth + lp.leftMargin + lp.rightMargin }
            widest = maxOf(widest, w); totalH += lineH(line)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthSpec),
            resolveSize(totalH + paddingTop + paddingBottom, heightSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val avail = maxOf(0, width - paddingLeft - paddingRight)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var y = paddingTop
        for (line in lines(avail)) {
            val lh = lineH(line)
            var x = if (rtl) width - paddingRight else paddingLeft
            for (c in line) {
                val lp = mlp(c)
                val cw = c.measuredWidth; val chh = c.measuredHeight
                val top = y + lp.topMargin + (lh - chh - lp.topMargin - lp.bottomMargin) / 2
                if (rtl) { x -= lp.rightMargin; c.layout(x - cw, top, x, top + chh); x -= cw + lp.leftMargin }
                else { x += lp.leftMargin; c.layout(x, top, x + cw, top + chh); x += cw + lp.rightMargin }
            }
            y += lh
        }
    }
}
