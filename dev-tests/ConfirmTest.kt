import com.tttt.subtitler.*

var cfails = 0
fun cc(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else "")); if (!ok) cfails++ }

fun mk(s: Double, e: Double, o: String, t: String = "ترجمة $o", sound: Boolean = false) =
    Sub(s, e, o, t, "male", "male", "none", emptyList(), emptyList(), false, false, isSound = sound)

fun main() {
    // سيناريو المستخدم: 15 جملة حقيقية. النسخة أ فيها 13 (ناقص 2 و 9)، النسخة ب فيها 14 (ناقص 5)
    val truth = (0 until 15).map { mk(it * 4.0, it * 4.0 + 3.0, "جملة رقم $it هنا") }
    val a = truth.filter { it.start / 4 != 2.0 && it.start / 4 != 9.0 }
    val b = truth.filter { it.start / 4 != 5.0 }
    cc("حجم أ=13 وب=14", a.size == 13 && b.size == 14)
    val addToA = Confirm.extras(a, b)          // أ اتطبّقت الأول، ب وصلت
    cc("ب بتضيف لأ الجملتين الناقصتين (2 و 9)", addToA.map { it.original }.toSet() == setOf("جملة رقم 2 هنا", "جملة رقم 9 هنا"), addToA.map { it.original }.toString())
    val mergedA = (a + addToA).sortedBy { it.start }
    cc("أ + الجملتين = 15 جملة", mergedA.size == 15, "n=${mergedA.size}")
    val addToB = Confirm.extras(b, a)          // العكس: ب الأول، أ وصلت
    cc("العكس: ب + جملة 5 من أ", addToB.map { it.original } == listOf("جملة رقم 5 هنا"))
    // الاتحاد الكامل (بعد ما النسختين يتقارنوا بالترتيب الاتنين): 15
    val full = (a + Confirm.extras(a, b)).let { x -> x + Confirm.extras(x, b + a) }.sortedBy { it.start }
    cc("الاتحاد = 15 جملة من غير تكرار", full.size == 15 && full.map { it.original }.toSet().size == 15, "n=${full.size}")

    // نفس الجملة بتوقيت منزاح شوية وصياغة مختلفة = مش جديدة
    val x = listOf(mk(10.0, 13.0, "merhaba nasılsın", "أهلا، عامل إيه"))
    val y = listOf(mk(10.4, 13.2, "merhaba nasilsin", "أهلا عامل ايه"))
    cc("توقيت منزاح + إملاء مختلف = نفس الجملة", Confirm.extras(x, y).isEmpty())
    // جملة طويلة في أ بتغطي جملتين قصيرتين في ب = مش جديدة
    val longA = listOf(mk(0.0, 8.0, "كلام طويل جدا فيه كذا جملة"))
    val shortB = listOf(mk(0.5, 3.5, "كلام طويل جدا"), mk(4.0, 7.5, "فيه كذا جملة"))
    cc("جملة طويلة بتغطي قصيرتين = مفيش جديد", Confirm.extras(longA, shortB).isEmpty())
    // جملة في ثغرة (مفيش حاجة في نفس الوقت) = جديدة
    cc("جملة في ثغرة بتتضاف", Confirm.extras(listOf(mk(0.0, 3.0, "أ"), mk(20.0, 23.0, "ج")), listOf(mk(10.0, 12.0, "ب"))).size == 1)
    // وصف صوت مش بيتغطّى بكلام والعكس
    cc("وصف الصوت ماينطمسش بجملة كلام في نفس الوقت", Confirm.extras(listOf(mk(0.0, 3.0, "كلام")), listOf(mk(0.5, 2.5, "(همهمة)", sound = true))).size == 1)
    // جمل جديدة من ب متكررة مع بعض ماتتضافش مرتين
    cc("جملتين متطابقتين في الجديد بتتضاف واحدة", Confirm.extras(emptyList(), listOf(mk(5.0, 8.0, "نفس الجملة"), mk(5.2, 8.1, "نفس الجملة"))).size == 1)
    // تقسيم المفاتيح
    val g = Confirm.split(listOf("KEY_AAAAAAAAAA", "KEY_BBBBBBBBBB", "KEY_CCCCCCCCCC", "KEY_BACKUPXXXX"))!!
    cc("تقسيم 4 مفاتيح 2/2 بالتبادل", g.first == listOf("KEY_AAAAAAAAAA", "KEY_CCCCCCCCCC") && g.second == listOf("KEY_BBBBBBBBBB", "KEY_BACKUPXXXX"))
    cc("مفتاح واحد = مفيش تأكيد", Confirm.split(listOf("KEY_AAAAAAAAAA")) == null)
    cc("3 مفاتيح = 2 + 1", Confirm.split(listOf("KEY_AAAAAAAAAA", "KEY_BBBBBBBBBB", "KEY_CCCCCCCCCC"))!!.let { it.first.size == 2 && it.second.size == 1 })
    println(if (cfails == 0) "\nكل اختبارات التأكيد نجحت" else "\nفشل $cfails")
    System.exit(if (cfails == 0) 0 else 1)
}
