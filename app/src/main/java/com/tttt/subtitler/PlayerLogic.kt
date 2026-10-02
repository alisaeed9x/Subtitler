package com.tttt.subtitler

/** منطق نقي للمشغّل (متختبر في PlayerLogicTest) */
object PlayerLogic {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    val fitNames = listOf("احتواء", "ملء (قص)", "تمديد")

    fun nextSpeed(cur: Float): Float { val i = speeds.indexOfFirst { Math.abs(it - cur) < 0.01f }; return speeds[(i + 1) % speeds.size] }
    fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "x"

    /** مقاس الفيديو جوه الحاوية: 0 = احتواء، 1 = ملء بالقص، 2 = تمديد */
    fun fitSize(boxW: Int, boxH: Int, vw: Int, vh: Int, mode: Int): Pair<Int, Int> {
        if (vw <= 0 || vh <= 0 || boxW <= 0 || boxH <= 0) return boxW to boxH
        if (mode == 2) return boxW to boxH
        val sc = if (mode == 1) maxOf(boxW.toFloat() / vw, boxH.toFloat() / vh) else minOf(boxW.toFloat() / vw, boxH.toFloat() / vh)
        return Math.round(vw * sc) to Math.round(vh * sc)
    }

    /** فهرس الجملة الحالية في قايمة مرتبة بالبداية (-1 لو مفيش). offsetMs بيزحزح توقيت الترجمة */
    fun currentIndex(starts: LongArray, ends: LongArray, posMs: Long, offsetMs: Long): Int {
        val t = posMs - offsetMs
        var lo = 0; var hi = starts.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (starts[m] <= t) { r = m; lo = m + 1 } else hi = m - 1 }
        return if (r >= 0 && t <= ends[r]) r else -1
    }

    /** كل الجمل الشغالة دلوقتي (بترتيب البداية). الجملة الإضافية لازم تتداخل مع الأساسية minOverlapMs على الأقل
     *  عشان فروق الحدود الصغيرة بين جملتين ورا بعض مايبانش كأنها كلام متداخل. */
    fun activeIndices(starts: LongArray, ends: LongArray, posMs: Long, offsetMs: Long, minOverlapMs: Long = 400L, maxN: Int = 3): List<Int> {
        val t = posMs - offsetMs
        var lo = 0; var hi = starts.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (starts[m] <= t) { r = m; lo = m + 1 } else hi = m - 1 }
        if (r < 0) return emptyList()
        val cand = ArrayList<Int>()
        for (j in r downTo maxOf(0, r - 8)) if (ends[j] >= t) cand.add(j)
        if (cand.isEmpty()) return emptyList()
        val p = cand[0]   // أحدث جملة بدأت وماخلصتش
        val keep = ArrayList<Int>(); keep.add(p)
        for (j in cand.drop(1)) if (minOf(ends[j], ends[p]) - maxOf(starts[j], starts[p]) >= minOverlapMs) keep.add(j)
        return keep.sorted().takeLast(maxN)
    }

    private val AR_DIGITS = "٠١٢٣٤٥٦٧٨٩"
    fun arNum(n: Int) = n.toString().map { AR_DIGITS[it - '0'] }.joinToString("")

    /** جملة واحدة للعرض: لو أكتر من متحدث بيتكلموا مع بعض بتتجمّع مرقّمة (١) ... ٢) ... */
    fun combine(subs: List<Sub>): Sub? {
        if (subs.isEmpty()) return null
        if (subs.size == 1) return subs[0]
        fun line(i: Int, t: String) = arNum(i + 1) + ") " + t
        val tr = subs.mapIndexed { i, x -> line(i, x.translated.ifBlank { x.original }) }.joinToString("\n")
        val og = subs.mapIndexed { i, x -> line(i, x.original) }.joinToString("\n")
        val pv = if (subs.all { it.pivot.isNotBlank() }) subs.mapIndexed { i, x -> line(i, x.pivot) }.joinToString("\n") else ""
        return subs[0].copy(start = subs.minOf { it.start }, end = subs.maxOf { it.end }, original = og, translated = tr,
            people = subs.flatMap { it.people }.distinct(), places = subs.flatMap { it.places }.distinct(),
            isContinuation = false, overlap = true, pivot = pv)
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
    }
    fun srtTime(ms: Long) = String.format("%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    fun toSrt(subs: List<Sub>, offsetMs: Long = 0): String {
        val sb = StringBuilder(); var n = 1
        for (q in subs.sortedBy { it.start }) {
            val a = maxOf(0L, (q.start * 1000).toLong() + offsetMs); val b = maxOf(a, (q.end * 1000).toLong() + offsetMs)
            sb.append("${n++}\n${srtTime(a)} --> ${srtTime(b)}\n${q.translated.ifEmpty { q.original }}\n\n")
        }
        return sb.toString()
    }
    /** نسبة التغطية % */
    fun percent(coveredSec: Double, durSec: Double) = if (durSec <= 0) 0 else (coveredSec / durSec * 100).toInt().coerceIn(0, 100)

    /** استيراد SRT بسيط: بيرجّع (start,end,text) بالثواني */
    fun parseSrt(text: String): List<Triple<Double, Double, String>> {
        val rx = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)")
        fun sec(h: String, m: String, s: String, ms: String) = h.toInt() * 3600.0 + m.toInt() * 60 + s.toInt() + ms.padEnd(3, '0').take(3).toInt() / 1000.0
        val out = ArrayList<Triple<Double, Double, String>>()
        for (blk in text.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = blk.trim().lines(); val i = lines.indexOfFirst { rx.containsMatchIn(it) }
            if (i < 0) continue
            val g = rx.find(lines[i])!!.groupValues
            val body = lines.drop(i + 1).joinToString("\n").trim()
            if (body.isNotEmpty()) out.add(Triple(sec(g[1], g[2], g[3], g[4]), sec(g[5], g[6], g[7], g[8]), body))
        }
        return out
    }
}
