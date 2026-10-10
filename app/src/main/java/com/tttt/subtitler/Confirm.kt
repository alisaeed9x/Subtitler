package com.tttt.subtitler

/**
 * (v176) نظام التأكيد: كل باتش بيتبعت لمفتاحين من مجموعتين مختلفتين في نفس اللحظة.
 * أول رد بيتطبّق على طول، ولما التاني يرجع بنقارنه بالموجود: الجمل اللي مالهاش مقابل (في الزمن) بتتضاف، والباقي بيتتجاهل.
 * منطق نقي (من غير أندرويد) عشان يتختبر في dev-tests.
 */
object Confirm {
    /** مجموعتين بالتبادل: الفردي/الزوجي في ترتيب المفاتيح. null = مفاتيح مش كفاية (أقل من 2) */
    fun split(keys: List<String>): Pair<List<String>, List<String>>? {
        val ks = keys.filter { it.length > 10 }.distinct()
        if (ks.size < 2) return null
        return ks.filterIndexed { i, _ -> i % 2 == 0 } to ks.filterIndexed { i, _ -> i % 2 == 1 }
    }

    private fun ov(a: Sub, b: Sub) = minOf(a.end, b.end) - maxOf(a.start, b.start)
    private fun nrm(t: String) = t.lowercase().replace(Regex("[\\s.,!?؟،«»\"'()\\-–—…]"), "")

    /** تشابه نصّي: dice أو احتواء الأقصر في الأطول */
    fun textSim(a: String, b: String): Double {
        val x = nrm(a); val y = nrm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val sh = if (x.length <= y.length) x else y
        val lg = if (x.length <= y.length) y else x
        if (sh.length >= 4 && lg.contains(sh)) return 1.0
        return Subs.dice(x, y)
    }

    /** الجملة e (من الرد التاني) متغطّية بجملة موجودة s؟ زمن متداخل كفاية، أو تداخل أقل مع تشابه نص */
    fun covered(e: Sub, s: Sub): Boolean {
        if (e.isSound != s.isSound) return false
        val o = ov(e, s)
        if (o <= 0.0) return false
        val sh = maxOf(0.2, minOf(e.end - e.start, s.end - s.start))
        val frac = o / sh
        if (frac >= 0.6) return true
        return frac >= 0.25 && (textSim(e.original, s.original) >= 0.5 || textSim(e.translated, s.translated) >= 0.5)
    }

    /** الجمل الجديدة: اللي في cand ومالهاش مقابل في existing (وبعضها مع بعض مش بتتكرر) */
    fun extras(existing: List<Sub>, cand: List<Sub>): List<Sub> {
        val out = ArrayList<Sub>()
        for (e in cand.sortedBy { it.start }) {
            if (existing.any { covered(e, it) }) continue
            if (out.any { covered(e, it) }) continue
            out.add(e)
        }
        return out
    }
}
