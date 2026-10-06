package com.tttt.subtitler

/**
 * قياس الصوت الحقيقي ومقارنته بالجمل اللي الموديل رجّعها.
 * الموديل بيقدّر التوقيت وبيفوّت أجزاء، فهنا بنراجع عليه من الصوت نفسه:
 *  - ثغرات: كلام/غنا مسموع من غير ولا جملة فوقه
 *  - جملة أطول من اللازم بكتير (بتفضل ظاهرة والمغني سكت)
 *  - جملة فوق صمت تام
 * كله بيشتغل على WAV 16-bit mono، وأي بيانات مش WAV بترجع null/من غير تغيير.
 */
object Speech {
    const val WIN_SEC = 0.25
    const val THRESH = 0.012
    private const val JOIN_SEC = 0.5

    private fun isWav(b: ByteArray) = b.size > 46 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()
    private fun rateOf(b: ByteArray) = (b[24].toInt() and 255) or ((b[25].toInt() and 255) shl 8) or ((b[26].toInt() and 255) shl 16) or ((b[27].toInt() and 255) shl 24)

    /** مجالات الصوت العالي (ثواني نسبية لبداية الـ WAV). null لو مش WAV صالح. */
    fun activeSpans(wav: ByteArray): List<DoubleArray>? {
        try {
            if (!isWav(wav)) return null
            val rate = rateOf(wav); if (rate <= 0) return null
            val n = (wav.size - 44) / 2
            val win = maxOf(1, (WIN_SEC * rate).toInt())
            val out = ArrayList<DoubleArray>()
            var w = 0; var i = 0
            var curS = -1.0; var lastEnd = -1.0
            while (i < n) {
                val e = minOf(n, i + win)
                var sq = 0.0
                for (k in i until e) {
                    val p = 44 + k * 2
                    val v = ((wav[p + 1].toInt() shl 8) or (wav[p].toInt() and 0xFF)).toShort().toInt() / 32768.0
                    sq += v * v
                }
                val rms = Math.sqrt(sq / (e - i))
                val t0 = i.toDouble() / rate; val t1 = e.toDouble() / rate
                if (rms > THRESH) {
                    if (curS < 0) curS = t0 else if (t0 - lastEnd > JOIN_SEC) { out.add(doubleArrayOf(curS, lastEnd)); curS = t0 }
                    lastEnd = t1
                }
                i = e; w++
            }
            if (curS >= 0) out.add(doubleArrayOf(curS, lastEnd))
            return out
        } catch (_: Exception) { return null }
    }

    /** نفس المجالات بس بالثواني المطلقة (من أول الفيديو) */
    fun absolute(spans: List<DoubleArray>, wavStart: Double): List<DoubleArray> = spans.map { doubleArrayOf(it[0] + wavStart, it[1] + wavStart) }

    /** الأجزاء اللي فيها صوت (داخل from..to) ومفيش ولا جملة فوقها، وطولها >= minSec */
    fun holes(spansAbs: List<DoubleArray>, subs: List<Sub>, from: Double, to: Double, minSec: Double): List<DoubleArray> {
        val cov = subs.filter { !it.isSound && it.end > from - 1.0 && it.start < to + 1.0 }.sortedBy { it.start }   // وصف الصوت [موسيقى] مايغطيش كلام تحته
        val out = ArrayList<DoubleArray>()
        for (sp in spansAbs) {
            var cur = maxOf(sp[0], from); val end = minOf(sp[1], to)
            if (end - cur < minSec) continue
            for (s in cov) {
                if (s.end + 0.3 <= cur) continue
                if (s.start - 0.3 >= end) break
                if (s.start - 0.3 - cur >= minSec) out.add(doubleArrayOf(cur, s.start - 0.3))
                if (s.end + 0.3 > cur) cur = s.end + 0.3
            }
            if (end - cur >= minSec) out.add(doubleArrayOf(cur, end))
        }
        return out
    }

    /** يضبط بداية/نهاية الجملة على الصوت الفعلي. null = الجملة فوق صمت تام (تتشال). */
    fun fit(s: Sub, spansAbs: List<DoubleArray>): Sub? {
        val ov = spansAbs.filter { it.size == 2 && it[1] > s.start && it[0] < s.end }
        val dur = s.end - s.start
        if (ov.isEmpty()) return if (dur >= 1.5 && !s.faint) null else s
        var a = s.start; var b = s.end
        val fs = ov.first()[0] - 0.15; val le = ov.last()[1] + 0.35
        if (fs - a >= 1.0) a = fs
        if (b - le >= 1.0) b = le
        return if (b - a >= 0.8) s.copy(start = a, end = b) else s
    }
}
