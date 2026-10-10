package com.tttt.subtitler

/**
 * (v180) منطق الترجمة الحية بدون Android (عشان يتختبر على JVM).
 * المشاكل اللي بيحلها: (1) مقاطع ثابتة 8ث = تأخير + كلمات بتتقطع في النص، (2) طابور العرض كان بيرمي جمل،
 * (3) الردود المتوازية ممكن توصل بالعكس، (4) الصوت الواطي بيطلع غير دقيق.
 */
object LiveLogic {
    const val MIN_SEC = 2.0       // أقل مدة قبل ما نقطع عند وقفة
    const val MAX_SEC = 6.0       // أقصى مدة للمقطع (بنقطع عند أهدأ نقطة لو وصلنا لها)
    const val PAUSE_SEC = 0.35    // طول الوقفة اللي بنعتبرها نهاية جملة
    const val WIN_SEC = 0.1

    fun winRms(b: ShortArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) { val v = b[i].toDouble(); s += v * v }
        return Math.sqrt(s / (to - from))
    }

    /** مستوى الكلام التقريبي (p90 لنوافذ 100ms) — بيتحسب بس على النوافذ اللي فيها صوت فعلي */
    fun speechLevel(b: ShortArray, n: Int, sr: Int): Double {
        val w = (sr * WIN_SEC).toInt().coerceAtLeast(1)
        val l = ArrayList<Double>()
        var i = 0
        while (i + w <= n) { l.add(winRms(b, i, i + w)); i += w }
        if (l.isEmpty()) return 0.0
        l.sort()
        return l[((l.size - 1) * 0.9).toInt()]
    }

    /** تضخيم الصوت الواطي قبل الإرسال (حد أقصى 6x) عشان الدقة */
    fun gainFor(level: Double): Double = if (level < 40.0) 1.0 else (3500.0 / level).coerceIn(1.0, 6.0)

    /** بيقسّم الصوت الملتقط لمقاطع عند الوقفات. بيتغذى بدفعات صغيرة وبيرجّع المقاطع الجاهزة. */
    class Segmenter(private val sr: Int) {
        private var buf = ShortArray(sr * 8)
        private var n = 0
        private val w = (sr * WIN_SEC).toInt().coerceAtLeast(1)
        class Seg(val pcm: ShortArray, val silent: Boolean)

        fun feed(src: ShortArray, cnt: Int): List<Seg> {
            val out = ArrayList<Seg>()
            var off = 0
            while (off < cnt) {
                val take = minOf(cnt - off, buf.size - n)
                System.arraycopy(src, off, buf, n, take); n += take; off += take
                drain(out)
                if (take == 0) { n = 0 }   // أمان: ماينفعش نعلق
            }
            return out
        }

        private fun drain(out: MutableList<Seg>) {
            val minN = (MIN_SEC * sr).toInt(); val maxN = (MAX_SEC * sr).toInt(); val pauseN = (PAUSE_SEC * sr).toInt()
            while (true) {
                if (n < w * 3) return
                val peak = peakWin(n)
                // صمت طويل لسه ماحصلش كلام: ماننتظرش، بنرمي القديم ونحتفظ بآخر 0.3ث بس (عشان ماتتأخرش بداية الكلام)
                if (peak < SILENT_FLOOR) {
                    if (n >= sr) { val keep = (0.3 * sr).toInt(); System.arraycopy(buf, n - keep, buf, 0, keep); n = keep }
                    return
                }
                if (n >= minN) {
                    val thr = maxOf(SILENT_FLOOR, 0.18 * peak)
                    if (winRmsTail(pauseN) < thr) { out.add(cut(n)); continue }
                }
                if (n >= maxN) { out.add(cut(quietCut(n))); continue }
                return
            }
        }
        private fun winRmsTail(len: Int) = winRms(buf, maxOf(0, n - len), n)
        private fun peakWin(upTo: Int): Double {
            var m = 0.0; var i = 0
            while (i + w <= upTo) { val r = winRms(buf, i, i + w); if (r > m) m = r; i += w }
            return m
        }
        /** أهدأ نافذة في آخر 1.5ث (من غير أول ثانية) — نقطة القطع لو وصلنا للحد الأقصى */
        private fun quietCut(upTo: Int): Int {
            val lo = maxOf(sr, upTo - (1.5 * sr).toInt()); var best = upTo; var bestR = Double.MAX_VALUE
            var i = lo
            while (i + w <= upTo) { val r = winRms(buf, i, i + w); if (r < bestR) { bestR = r; best = i + w / 2 }; i += w / 2 }
            return best
        }
        private fun cut(at: Int): Seg {
            val pcm = buf.copyOf(at)
            val silent = peakWin(at) < SILENT_FLOOR
            val rest = n - at
            if (rest > 0) System.arraycopy(buf, at, buf, 0, rest)
            n = rest
            return Seg(pcm, silent)
        }
    }
    const val SILENT_FLOOR = 60.0

    /** بيرتّب الردود المتوازية: الرد رقم k مايتعرضش قبل k-1، ولو واحد اتأخر أكتر من maxWaitMs بنعدّيه */
    class Order(private val maxWaitMs: Long = 12_000L) {
        private val ready = java.util.TreeMap<Int, List<String>>()
        private var next = 0
        private var headSince = 0L
        @Synchronized fun submit(seq: Int, lines: List<String>, now: Long): List<String> {
            if (seq < next) return emptyList()      // اتعدّى قبل كده (متأخر)
            ready[seq] = lines
            return release(now)
        }
        /** مقطع اتسقط/فشل/صامت: بنكمّل من غيره */
        @Synchronized fun skip(seq: Int, now: Long): List<String> = submit(seq, emptyList(), now)
        /** تحقق دوري: لو الرأس اتأخر بنعدّيه */
        @Synchronized fun tick(now: Long): List<String> = release(now)
        private fun release(now: Long): List<String> {
            val out = ArrayList<String>()
            while (true) {
                val v = ready.remove(next)
                if (v != null) { out.addAll(v); next++; headSince = 0L; continue }
                if (ready.isEmpty()) break
                if (headSince == 0L) { headSince = now; break }
                if (now - headSince >= maxWaitMs) { next++; headSince = 0L; continue }
                break
            }
            return out
        }
    }

    /** مدة عرض الجملة حسب عدد كلماتها، وبتتقصّر لو في طابور مستني (بدل ما نرمي جمل) */
    fun dwellMs(text: String, backlog: Int): Long {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        var s = (words * 0.33 + 0.9).coerceIn(1.5, 5.0)
        if (backlog >= 2) s *= 0.65
        if (backlog >= 4) s *= 0.7
        return (s.coerceAtLeast(0.9) * 1000).toLong()
    }

    /** طابور العرض: مابيرميش جمل — لو اتراكم بيدمج الجديد في آخر واحدة مستنية */
    class Pending(private val mergeAt: Int = 3, private val maxChars: Int = 110) {
        private val q = java.util.ArrayDeque<String>()
        @Synchronized fun add(t: String) {
            val x = t.trim(); if (x.isEmpty()) return
            if (q.size >= mergeAt) {
                val last = q.pollLast()
                val m = last + " " + x
                if (m.length <= maxChars) q.addLast(m) else { q.addLast(last); q.addLast(x) }
            } else q.addLast(x)
            while (q.size > 8) q.pollFirst()
        }
        @Synchronized fun poll(): String? = q.pollFirst()
        @Synchronized fun size() = q.size
        @Synchronized fun clear() = q.clear()
    }
}
