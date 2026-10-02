import com.tttt.subtitler.*
var fails = 0
fun check(n: String, ok: Boolean, d: String = "") { if (ok) println("PASS $n") else { fails++; println("FAIL $n $d") } }
fun mk(g: String = "male", tr: String = "مرحبا يا أحمد", o: String = "hello", piv: String = "", cont: Boolean = false, ppl: List<String> = emptyList(), pl: List<String> = emptyList()) =
    Sub(0.0, 1.0, o, tr, g, "unknown", "none", ppl, pl, false, false, -1, "", false, "", cont, piv)
fun main() {
    check("كل ثيم فيه ألوان مختلفة وعددهم 11", Themes.all.size == 11 && Themes.all.map { it.id }.toSet().size == 11)
    check("ثيم الأصل الافتراضي primary", Themes.byId("default").primary == 0xFFF5A623.toInt())
    check("ثيم مجهول → الافتراضي", Themes.byId("zzz").id == "default")
    check("فاتح/غامق", Themes.byId("light").isLight && !Themes.byId("amoled").isLight)
    check("parse لون 3 خانات", Theme.parse("#fff") == 0xFFFFFFFF.toInt())
    val d = SubStyle()
    check("افتراضيات الأصل", d.scale == 100 && d.bgOpa == 45 && d.blur == 0 && d.animMs == 250 && d.splitThresh == 8 && d.font == "Cairo")
    val st = SubStyle.load { k, def -> mapOf("sub_scale" to "999", "sub_bgopa" to "-5", "sub_anim" to "bogus", "sub_dual" to "2")[k] ?: def }
    check("load بيقصّ القيم", st.scale == 200 && st.bgOpa == 0 && st.anim == "default" && st.dual == 2)
    check("13 أنيميشن و22 خط", SubStyle.entrances.size == 13 && SubStyle.fonts.size == 22)
    check("لون الذكر", d.copy(plain = false).colorFor(mk("male")) == SubStyle.MALE)
    check("لون الأنثى", d.copy(plain = false).colorFor(mk("female")) == SubStyle.FEMALE)
    check("لون موحّد", d.copy(plain = false, uniOn = true, uniColor = "#FFE600").colorFor(mk("female")) == 0xFFFFE600.toInt())
    check("نص أبيض عادي يغلب", d.copy(plain = true, uniOn = true).colorFor(mk("female")) == SubStyle.WHITE)
    check("سطر ثانوي: الأصلي", d.copy(dual = 1).secondary(mk()) == "hello")
    check("سطر ثانوي: نفس النص → فاضي", d.copy(dual = 1).secondary(mk(tr = "x", o = "x")) == "")
    check("سطر ثانوي: pivot", d.copy(dual = 2).secondary(mk(piv = "hi")) == "hi" && d.secondary(mk(piv = "hi")) == "")
    check("تقسيم 10 كلمات بحد 8 → سطرين متوازنين", SubStyle.splitLong("1 2 3 4 5 6 7 8 9 10", 8) == "1 2 3 4 5\n6 7 8 9 10")
    check("جملة قصيرة ما بتتقسمش", SubStyle.splitLong("a b c", 8) == "a b c")
    check("استكمال ⋯", d.mainText(mk(cont = true)).endsWith(" ⋯"))
    val s = mk(tr = "قابلت أحمد في القاهرة وأحمد ضحك", ppl = listOf("أحمد"), pl = listOf("القاهرة"))
    val hl = d.copy(plain = false).highlights(s.translated, s)
    check("تلوين الأسماء والأماكن", hl.count { !it.third } == 2 && hl.count { it.third } == 1)
    check("fontPx بيحترم الحدود والسكيل", SubStyle.fontPx(1080, 2f, 100) == 36f && SubStyle.fontPx(300, 2f, 200) == 52f)
    check("emotionKind", SubStyle.emotionKind("shouting") == "shout" && SubStyle.emotionKind("Whisper") == "whisper" && SubStyle.emotionKind("neutral") == "")
    check("الافتراضي: نص عادي + تقسيم شغال", d.plain && d.splitOn && SubStyle.load { _, def -> def }.let { it.plain && it.splitOn })
    check("plain: حجم ثابت ومن غير تمييز", d.sizeFor(1) == 1f && d.sizeFor(20) == 1f && d.highlights("أحمد", mk(ppl = listOf("أحمد"))).isEmpty())
    check("غير plain: تكبير تلقائي وألوان جنس شغالة", d.copy(plain = false).sizeFor(1) == 1.18f && d.copy(plain = false).colorFor(mk("female")) == SubStyle.FEMALE)
    check("mainText ما بيقسّمش (التقسيم بالتتابع)", d.mainText(mk(tr = "1 2 3 4 5 6 7 8 9 10")) == "1 2 3 4 5 6 7 8 9 10")
    if (fails > 0) { println("فشل $fails"); System.exit(1) } else println("كل الاختبارات نجحت")
}
