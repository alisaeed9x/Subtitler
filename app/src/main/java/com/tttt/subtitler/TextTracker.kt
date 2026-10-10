package com.tttt.subtitler

/**
 * (v175) بيحوّل قراءات الـ OCR فريم بفريم لـ «ظهور» ليه وقت بداية ونهاية:
 * النص بيتعرض من أول ما يظهر لحد ما يختفي — مش مدة ثابتة.
 * منطق صرف (من غير Android) عشان يتختبر على الـ JVM.
 */
class Det(val text: String, val lang: String, val x: Float, val y: Float, val w: Float, val h: Float)

class Track(val id: Int, val text: String, val lang: String, val start: Double) {
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var w = 0f
    @Volatile var h = 0f
    @Volatile var last = start        // آخر لقطة اتشاف فيها
    @Volatile var end = Double.NaN    // NaN = لسه ظاهر
    @Volatile var miss = 0            // عدد اللقطات المتتالية اللي مااتشافش فيها
    @Volatile var tries = 0           // محاولات طلب الترجمة
}

class TextTracker(private val step: Double) {
    val tracks = java.util.concurrent.CopyOnWriteArrayList<Track>()
    private var seq = 0

    companion object {
        fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

        /** تشابه نصين (0..1) بمقارنة أزواج الحروف — بيسامح في غلطة أو حرفين من الـ OCR */
        fun sim(a: String, b: String): Double {
            val x = norm(a); val y = norm(b)
            if (x == y) return 1.0
            if (x.length < 2 || y.length < 2) return 0.0
            if (x.contains(y) || y.contains(x)) return 0.8
            val bx = HashMap<String, Int>()
            for (i in 0 until x.length - 1) { val g = x.substring(i, i + 2); bx[g] = (bx[g] ?: 0) + 1 }
            var inter = 0
            for (i in 0 until y.length - 1) {
                val g = y.substring(i, i + 2); val c = bx[g] ?: 0
                if (c > 0) { inter++; bx[g] = c - 1 }
            }
            return 2.0 * inter / ((x.length - 1) + (y.length - 1))
        }
    }

    fun clear() { tracks.clear(); seq = 0 }

    /**
     * فريم واحد عند الثانية t. prevT = وقت اللقطة اللي قبلها لو متتالية، وإلا NaN (قفزة/تقديم/رجوع).
     * بيرجّع الظهورات الجديدة عشان تتبعت للترجمة.
     * البداية = نص المسافة بين آخر لقطة ماكانش فيها واللقطة اللي ظهر فيها (خطأ أقصاه step/2).
     */
    fun feed(t: Double, dets: List<Det>, prevT: Double): List<Track> {
        val half = step / 2.0
        if (prevT.isNaN()) for (k in tracks) if (k.end.isNaN()) k.end = k.last + half
        val used = HashSet<Int>()
        val fresh = ArrayList<Track>()
        for (d in dets) {
            var best: Track? = null; var bs = 0.0
            for (k in tracks) {
                if (!k.end.isNaN() || k.id in used) continue
                if (Math.abs(k.x - d.x) > (k.w + d.w) / 2f + 0.05f) continue
                if (Math.abs(k.y - d.y) > maxOf(k.h, d.h) / 2f + 0.04f) continue
                val s = sim(k.text, d.text)
                if (s >= 0.55 && s > bs) { best = k; bs = s }
            }
            if (best != null) {
                used.add(best.id); best.last = t; best.miss = 0
                best.x = d.x; best.y = d.y; best.w = d.w; best.h = d.h
            } else {
                val k = Track(++seq, d.text, d.lang, if (prevT.isNaN()) t else (prevT + t) / 2.0)
                k.x = d.x; k.y = d.y; k.w = d.w; k.h = d.h; k.last = t
                tracks.add(k); used.add(k.id); fresh.add(k)
            }
        }
        // لقطة واحدة فاتت من غير ما يتشاف = ممكن غلطة OCR (نسيبه مفتوح)؛ لقطتين = اختفى
        for (k in tracks) if (k.end.isNaN() && k.id !in used) { k.miss++; if (k.miss >= 2) k.end = k.last + half }
        return fresh
    }

    /** الظهورات اللي شغّالة عند الثانية دي */
    fun activeAt(sec: Double): List<Track> {
        val half = step / 2.0
        val out = ArrayList<Track>()
        for (k in tracks) {
            val e = if (k.end.isNaN()) k.last + half else k.end
            if (sec >= k.start - 0.05 && sec <= e + 0.05) out.add(k)
        }
        return out
    }
}
