package com.tttt.subtitler

/** منطق نقي لـ blur الخلفية (backdrop) ورا صندوق الترجمة — بدون Android عشان يتختبر على JVM. */
object Blur {
    /** الصورة بتتصغّر بالمعامل ده قبل الـ blur (أسرع بكتير، والشكل نفسه تقريبًا) */
    const val SCALE = 4

    /** نصف قطر الـ blur بعد التصغير. blurPx = قيمة السلايدر (بنفس وحدة الـ CSS px في الأصل) */
    fun radiusFor(blurPx: Int, density: Float, scale: Int = SCALE): Int {
        if (blurPx <= 0) return 0
        return Math.round(blurPx * density / scale).coerceIn(1, 16)
    }

    /** مقاس الصورة المصغّرة لمنطقة بعرض/ارتفاع بالبكسل */
    fun smallSize(w: Int, h: Int, scale: Int = SCALE): Pair<Int, Int> =
        maxOf(4, w / scale) to maxOf(4, h / scale)

    /** box blur على مصفوفة ARGB (في نفس المصفوفة). الأطراف بتتكرر (clamp) فمفيش غمق عند الحواف. */
    fun box(px: IntArray, w: Int, h: Int, r: Int, passes: Int = 2) {
        if (r < 1 || w < 1 || h < 1 || px.size < w * h) return
        val tmp = IntArray(w * h)
        for (p in 0 until passes) { pass(px, tmp, w, h, r, true); pass(tmp, px, w, h, r, false) }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h          // طول الخط
        val lines = if (horizontal) h else w      // عدد الخطوط
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int { val k = if (i < 0) 0 else if (i > n - 1) n - 1 else i; return if (horizontal) line * w + k else k * w + line }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) { val c = src[at(i)]; a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
            for (x in 0 until n) {
                dst[at(x)] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val add = src[at(x + r + 1)]; val rem = src[at(x - r)]
                a += (add ushr 24) - (rem ushr 24)
                rr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                g += ((add shr 8) and 255) - ((rem shr 8) and 255)
                b += (add and 255) - (rem and 255)
            }
        }
    }
}
