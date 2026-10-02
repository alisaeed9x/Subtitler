import com.tttt.subtitler.*
var fails = 0
fun check(n: String, ok: Boolean, d: String = "") { if (ok) println("PASS $n") else { fails++; println("FAIL $n $d") } }
fun sb(s: Double, e: Double, t: String) = Sub(s, e, "o", t, "male", "unknown", "none", emptyList(), emptyList(), false, false)
fun main() {
    check("سرعات دوّارة", PlayerLogic.nextSpeed(1f) == 1.25f && PlayerLogic.nextSpeed(2f) == 0.5f && PlayerLogic.speedLabel(1.5f) == "1.5x" && PlayerLogic.speedLabel(2f) == "2x")
    check("احتواء 16:9 في 1000x1000", PlayerLogic.fitSize(1000, 1000, 1920, 1080, 0).let { it.first == 1000 && it.second in 562..563 })
    check("ملء بالقص", PlayerLogic.fitSize(1000, 1000, 1920, 1080, 1).let { it.second == 1000 && it.first > 1000 })
    check("تمديد", PlayerLogic.fitSize(800, 400, 1920, 1080, 2) == (800 to 400))
    val st = longArrayOf(0, 2000, 5000); val en = longArrayOf(1500, 4000, 6000)
    check("فهرس الجملة", PlayerLogic.currentIndex(st, en, 1000, 0) == 0 && PlayerLogic.currentIndex(st, en, 1800, 0) == -1 && PlayerLogic.currentIndex(st, en, 5500, 0) == 2)
    check("إزاحة التوقيت", PlayerLogic.currentIndex(st, en, 2500, 1000) == 0 && PlayerLogic.currentIndex(st, en, 2500, -1000) == 1)
    check("قايمة فاضية", PlayerLogic.currentIndex(LongArray(0), LongArray(0), 100, 0) == -1)
    check("clock", PlayerLogic.clock(65000) == "01:05" && PlayerLogic.clock(3725000) == "1:02:05")
    val srt = PlayerLogic.toSrt(listOf(sb(2.0, 3.5, "تاني"), sb(0.5, 1.0, "أول")))
    check("SRT مرتب وصيغته", srt.startsWith("1\n00:00:00,500 --> 00:00:01,000\nأول\n\n2\n00:00:02,000"), srt)
    val back = PlayerLogic.parseSrt(srt)
    check("استيراد SRT يرجّع نفس الجمل", back.size == 2 && back[0].third == "أول" && Math.abs(back[1].first - 2.0) < 0.001 && Math.abs(back[1].second - 3.5) < 0.001)
    check("SRT بإزاحة", PlayerLogic.toSrt(listOf(sb(1.0, 2.0, "x")), 500).contains("00:00:01,500 --> 00:00:02,500"))
    check("نسبة", PlayerLogic.percent(30.0, 120.0) == 25 && PlayerLogic.percent(5.0, 0.0) == 0 && PlayerLogic.percent(500.0, 100.0) == 100)
    // ===== الجمل المتداخلة =====
    check("تداخل: من غير تداخل زي الأول", PlayerLogic.activeIndices(st, en, 1000, 0) == listOf(0) && PlayerLogic.activeIndices(st, en, 1800, 0).isEmpty() && PlayerLogic.activeIndices(st, en, 5500, 0) == listOf(2))
    val s2 = longArrayOf(0, 1000); val e2 = longArrayOf(3000, 2500)
    check("تداخل: متحدثين مع بعض", PlayerLogic.activeIndices(s2, e2, 1500, 0) == listOf(0, 1))
    check("تداخل: بعد ما التانية تخلص", PlayerLogic.activeIndices(s2, e2, 2700, 0) == listOf(0))
    check("تداخل: قبل ما التانية تبدأ", PlayerLogic.activeIndices(s2, e2, 500, 0) == listOf(0))
    check("تداخل: فرق حدود صغير (100ms) مايتحسبش", PlayerLogic.activeIndices(longArrayOf(0, 2900), longArrayOf(3000, 5000), 2950, 0) == listOf(1))
    check("تداخل: أقصى 3 جمل", PlayerLogic.activeIndices(longArrayOf(0, 100, 200, 300), longArrayOf(5000, 5000, 5000, 5000), 1000, 0) == listOf(1, 2, 3))
    check("تداخل: مع إزاحة", PlayerLogic.activeIndices(s2, e2, 2500, 1000) == listOf(0, 1))
    check("تداخل: جملة طويلة بدأت بدري وجملة قصيرة خلصت", PlayerLogic.activeIndices(longArrayOf(0, 1000, 1200), longArrayOf(9000, 1500, 9000), 2000, 0).let { it == listOf(0, 2) })
    check("combine: واحدة ترجع نفسها", sb(1.0, 2.0, "أ").let { PlayerLogic.combine(listOf(it)) === it } && PlayerLogic.combine(emptyList()) == null)
    val cb = PlayerLogic.combine(listOf(sb(0.0, 3.0, "أ"), sb(1.0, 2.5, "ب")))!!
    check("combine: ترقيم عربي وتوقيت", cb.translated == "١) أ\n٢) ب" && cb.start == 0.0 && cb.end == 3.0 && cb.overlap && !cb.isContinuation, cb.translated)
    check("arNum", PlayerLogic.arNum(12) == "١٢" && PlayerLogic.arNum(3) == "٣")
    if (fails > 0) { println("فشل $fails"); System.exit(1) } else println("كل الاختبارات نجحت")
}
