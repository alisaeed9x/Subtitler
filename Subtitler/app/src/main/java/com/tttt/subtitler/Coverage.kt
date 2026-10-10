package com.tttt.subtitler

/**
 * 🛡 فحص التغطية (v55): بعد ما Gemini يرد على مقطع، بنقيس الصوت الفعلي ونشوف فين فيه صوت عالي
 * ومفيش أي جملة بتغطيه (جمل ناقصة / فجوات / رد فاضي)، وبنعيد ترجمة المناطق دي بس بقطع صغيرة.
 * منطق نقي (من غير أندرويد) — متختبر في dev-tests/CoverageTest.kt
 */
object Coverage {
    const val RMS = 0.025            // أقل من كده = مش كلام/غنا
    const val WIN_SEC = 0.5
    const val MIN_RUN_SEC = 8.0      // أقل مدة بدون ترجمة نعتبرها مشكلة
    const val MAX_RANGES = 3         // أقصى مناطق بنعيدها في المقطع الواحد
    const val PIECE_SEC = 30.0       // نقسم المنطقة لقطع مش أطول من كده
    const val MARGIN_SEC = 1.0
    const val BRIDGE_WINS = 3        // سماحية بين النوافذ (1.5 ثانية)
    const val MAX_CALLS = 80         // سقف طلبات الإنقاذ لكل تشغيل (حماية الكوتة)

    class Run(val start: Double, val end: Double)   // بالثواني، نسبة لبداية الـ WAV

    private fun rateOf(w: ByteArray): Int =
        (w[24].toInt() and 255) or ((w[25].toInt() and 255) shl 8) or ((w[26].toInt() and 255) shl 16) or ((w[27].toInt() and 255) shl 24)

    private fun isWav(b: ByteArray) = b.size > 46 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    /** مناطق فيها صوت عالي ومفيش جملة (±MARGIN) بتغطيها، كل واحدة ≥ MIN_RUN_SEC. WAV 16-bit mono. */
    fun findRuns(wav: ByteArray, startSec: Double, subs: List<Sub>): List<Run> {
        if (!isWav(wav)) return emptyList()
        val rate = rateOf(wav); if (rate <= 0) return emptyList()
        val n = (wav.size - 44) / 2
        val win = maxOf(1, (WIN_SEC * rate).toInt())
        val nw = (n + win - 1) / win
        val sorted = subs.sortedBy { it.start }
        val bad = BooleanArray(nw)
        for (w in 0 until nw) {
            val a = w * win; val b = minOf(n, a + win)
            var sq = 0.0
            for (i in a until b) {
                val o = 44 + i * 2
                val v = ((wav[o].toInt() and 255) or (wav[o + 1].toInt() shl 8)).toShort() / 32768.0
                sq += v * v
            }
            if (Math.sqrt(sq / maxOf(1, b - a)) <= RMS) continue
            val mid = startSec + (w + 0.5) * WIN_SEC
            bad[w] = sorted.none { mid >= it.start - MARGIN_SEC && mid <= it.end + MARGIN_SEC }
        }
        val runs = ArrayList<IntArray>()
        var rs = -1; var last = -1
        for (w in 0 until nw) {
            if (bad[w]) { if (rs < 0) rs = w; last = w }
            else if (rs >= 0 && w - last > BRIDGE_WINS) { runs.add(intArrayOf(rs, last + 1)); rs = -1 }
        }
        if (rs >= 0) runs.add(intArrayOf(rs, last + 1))
        val total = n.toDouble() / rate
        return runs.map { Run(maxOf(0.0, it[0] * WIN_SEC - MARGIN_SEC), minOf(total, it[1] * WIN_SEC + MARGIN_SEC)) }
            .filter { it.end - it.start >= MIN_RUN_SEC }
    }

    /** يقسّم منطقة لقطع متساوية تقريبًا مش أطول من PIECE_SEC */
    fun pieces(r: Run): List<Run> {
        val len = r.end - r.start
        val n = maxOf(1, Math.ceil(len / PIECE_SEC).toInt())
        val step = len / n
        return (0 until n).map { Run(r.start + it * step, minOf(r.end, r.start + (it + 1) * step)) }
    }

    /** WAV جديد من [a,b] ثانية (نسبة لبداية الـ WAV الأصلي) */
    fun slice(wav: ByteArray, a: Double, b: Double): ByteArray {
        val rate = rateOf(wav)
        val n = (wav.size - 44) / 2
        val i0 = (Math.floor(a * rate).toInt()).coerceIn(0, n)
        val i1 = (Math.ceil(b * rate).toInt()).coerceIn(i0, n)
        val data = wav.copyOfRange(44 + i0 * 2, 44 + i1 * 2)
        return Wav.header(data.size, rate) + data
    }

    /** يدمج الجمل في مجالات [بداية,نهاية]: أي فجوة ≤ gap ثانية بتتعتبر متصلة (الأخضر بيتقطع بس عند الفجوات الحقيقية) */
    fun spans(subs: List<Sub>, gap: Double): List<DoubleArray> {
        if (subs.isEmpty()) return emptyList()
        val out = ArrayList<DoubleArray>()
        for (s in subs.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && s.start - last[1] <= gap) { if (s.end > last[1]) last[1] = s.end }
            else out.add(doubleArrayOf(s.start, s.end))
        }
        return out
    }

    fun mmss(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(java.util.Locale.US, s / 60, s % 60) }
}
