import com.tttt.subtitler.*

var failsT = 0
fun checkT(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else "")); if (!ok) failsT++ }

fun mk(st: Double, en: Double, o: String, t: String, sound: Boolean = false) = Sub(st, en, o, t, "male", "unknown", "none", emptyList(), emptyList(), false, false, isSound = sound)

fun main() {
    // 1) النسخة الأولى 14 جملة والتانية 12 جملة كلها مكررة → 14
    val a = (0 until 14).map { mk(it * 4.0, it * 4.0 + 3, "line number $it here", "جملة رقم $it هنا") }
    val b = a.take(12).map { it.copy(translated = it.translated + " ") }
    val r1 = Subs.twinMerge(a, b)
    checkT("14 + 12 مكررة = 14", r1.size == 14, "got ${r1.size}")

    // 2) التانية فيها جمل أصلية مش في الأولى → اتحاد
    val c = listOf(mk(100.0, 103.0, "completely different words", "كلام مختلف تمامًا"), mk(110.0, 113.0, "another extra one", "جملة زيادة"))
    val r2 = Subs.twinMerge(a, a.take(10) + c)
    checkT("14 + 2 جمل جديدة = 16", r2.size == 16, "got ${r2.size}")
    checkT("الناتج مرتب بالوقت", r2.zipWithNext().all { (x, y) -> x.start <= y.start })

    // 3) نفس الخانة الزمنية بصياغة مختلفة → جملة واحدة (مفيش سطرين فوق بعض)
    val r3 = Subs.twinMerge(listOf(mk(5.0, 8.0, "hello there", "أهلا")), listOf(mk(5.1, 8.0, "hello there my friend", "أهلا يا صاحبي")))
    checkT("نفس الخانة = جملة واحدة", r3.size == 1, "got ${r3.size}")
    checkT("بياخد الأكمل نصًا", r3[0].original == "hello there my friend")

    // 4) وصف صوتي وكلام في نفس الوقت = الاتنين
    val r4 = Subs.twinMerge(listOf(mk(5.0, 8.0, "hello", "أهلا")), listOf(mk(5.0, 8.0, "[music]", "[موسيقى]", true)))
    checkT("صوت + كلام فوق بعض = الاتنين", r4.size == 2)

    // 5) جملتين بعيد عن بعض بنفس النص (كورَس متكرر) مايتدمجوش
    val r5 = Subs.twinMerge(listOf(mk(5.0, 8.0, "la la la", "لا لا لا")), listOf(mk(40.0, 43.0, "la la la", "لا لا لا")))
    checkT("نفس النص بعيد زمنيًا = اتنين", r5.size == 2)

    // 6) فاضي
    checkT("تانية فاضية = الأولى", Subs.twinMerge(a, emptyList()).size == 14)
    checkT("أولى فاضية = التانية", Subs.twinMerge(emptyList(), b).size == 12)
    println(if (failsT == 0) "الاختبارات: كلها نجحت" else "الاختبارات: فشل $failsT")
}
