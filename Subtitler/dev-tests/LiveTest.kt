import com.tttt.subtitler.*
var lf = 0
fun lc(n: String, ok: Boolean, x: String = "") { println((if (ok) "PASS " else "FAIL ") + n + (if (x.isNotEmpty()) "  [$x]" else "")); if (!ok) lf++ }
fun tone(sec: Double, amp: Double, sr: Int): ShortArray = ShortArray((sec * sr).toInt()) { (amp * Math.sin(2 * Math.PI * 220 * it / sr)).toInt().toShort() }
fun cat(vararg a: ShortArray): ShortArray { val o = ShortArray(a.sumOf { it.size }); var p = 0; for (x in a) { System.arraycopy(x, 0, o, p, x.size); p += x.size }; return o }
fun main() {
    val sr = 44100
    // كلام 3ث + وقفة 0.6ث + كلام 2.5ث + وقفة
    val sp1 = tone(3.0, 6000.0, sr); val gap = ShortArray((0.6 * sr).toInt()); val sp2 = tone(2.5, 6000.0, sr)
    val s = LiveLogic.Segmenter(sr)
    val all = cat(sp1, gap, sp2, gap, gap)
    val segs = ArrayList<LiveLogic.Segmenter.Seg>()
    var i = 0; while (i < all.size) { val n = minOf(2048, all.size - i); segs.addAll(s.feed(all.copyOfRange(i, i + n), n)); i += n }
    lc("بيقطع عند الوقفة: مقطعين", segs.size == 2, "n=${segs.size} " + segs.joinToString { "%.1fs".format(it.pcm.size.toDouble() / sr) })
    lc("المقطع الأول ≈ الكلام الأول + بداية الوقفة (مش 8ث)", segs.isNotEmpty() && segs[0].pcm.size.toDouble() / sr in 3.0..4.2)
    // كلام متواصل 15ث من غير وقفة: لازم يتقطع كل ≤6ث
    val s2 = LiveLogic.Segmenter(sr); val long = tone(15.0, 6000.0, sr); val segs2 = ArrayList<LiveLogic.Segmenter.Seg>()
    i = 0; while (i < long.size) { val n = minOf(2048, long.size - i); segs2.addAll(s2.feed(long.copyOfRange(i, i + n), n)); i += n }
    lc("كلام متواصل 15ث: مفيش مقطع أطول من 6ث", segs2.size >= 2 && segs2.all { it.pcm.size.toDouble() / sr <= 6.01 }, segs2.joinToString { "%.1f".format(it.pcm.size.toDouble() / sr) })
    // صمت 20ث: مفيش مقطع
    val s3 = LiveLogic.Segmenter(sr); val segs3 = ArrayList<LiveLogic.Segmenter.Seg>(); val z = ShortArray(sr * 20)
    i = 0; while (i < z.size) { val n = minOf(2048, z.size - i); segs3.addAll(s3.feed(z.copyOfRange(i, i + n), n)); i += n }
    lc("صمت 20ث مابيطلّعش مقاطع", segs3.none { !it.silent })
    // الصمت الطويل ماينفعش يأخّر أول كلام: بعد صمت 10ث الكلام يتقطع بعد ~2-3ث مش 8
    val s4 = LiveLogic.Segmenter(sr); val segs4 = ArrayList<LiveLogic.Segmenter.Seg>(); val mix = cat(z.copyOf(sr * 10), tone(2.2, 6000.0, sr), ShortArray(sr))
    i = 0; while (i < mix.size) { val n = minOf(2048, mix.size - i); segs4.addAll(s4.feed(mix.copyOfRange(i, i + n), n)); i += n }
    lc("بعد صمت طويل أول كلام بيتبعت بسرعة وسليم", segs4.count { !it.silent } == 1 && segs4.first { !it.silent }.pcm.size.toDouble() / sr < 3.5)
    // ترتيب
    val o = LiveLogic.Order(12000)
    var r = o.submit(1, listOf("ب"), 1000); lc("رد 1 قبل 0 مايتعرضش", r.isEmpty())
    r = o.submit(0, listOf("أ"), 1100); lc("بعد وصول 0 الاتنين بالترتيب", r == listOf("أ", "ب"), r.toString())
    val o2 = LiveLogic.Order(12000); o2.submit(1, listOf("ب"), 1000)
    lc("مقطع معلّق أكتر من 12ث بيتعدّى", o2.tick(14000).isEmpty().not() || o2.tick(14100) == listOf("ب"))
    // عرض
    val pq = LiveLogic.Pending(); for (t in listOf("واحد","اتنين","تلاتة","اربعة","خمسة")) pq.add(t)
    lc("الطابور بيدمج بدل ما يرمي (كل الكلمات موجودة)", generateSequence { pq.poll() }.joinToString(" ").let { listOf("واحد","اتنين","تلاتة","اربعة","خمسة").all { w -> it.contains(w) } })
    lc("مدة العرض بتقصر مع الطابور", LiveLogic.dwellMs("كلمة كلمة كلمة كلمة كلمة كلمة", 4) < LiveLogic.dwellMs("كلمة كلمة كلمة كلمة كلمة كلمة", 0))
    lc("تضخيم الصوت الواطي", LiveLogic.gainFor(300.0) == 6.0 && LiveLogic.gainFor(3500.0) == 1.0 && LiveLogic.gainFor(10.0) == 1.0)
    println(if (lf == 0) "اختبارات الترجمة الحية: كلها نجحت" else "اختبارات الترجمة الحية: فشل $lf")
}
