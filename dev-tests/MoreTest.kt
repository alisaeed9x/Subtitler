import com.tttt.subtitler.*
var fails = 0
fun check(n: String, ok: Boolean, d: String = "") { if (ok) println("PASS $n") else { fails++; println("FAIL $n $d") } }
fun main() {
    // Quota
    var store = ""
    Quota.load = { store }; Quota.save = { store = it }
    val t0 = 1_790_000_000_000L
    Quota.hit("m1", t0); Quota.hit("m1", t0); Quota.hit("m2", t0)
    check("كوتة: عدّ لكل موديل", Quota.used("m1", t0) == 2 && Quota.used("m2", t0) == 1 && Quota.used("zz", t0) == 0)
    check("كوتة: بتتصفّر في اليوم الجاي (PT)", Quota.used("m1", t0 + 26 * 3600_000L) == 0)
    check("كوتة: مفتاح اليوم بتوقيت PT", Quota.dayKey(1_790_000_000_000L).matches(Regex("\\d{4}-\\d\\d-\\d\\d")) && Quota.dayKey(1_767_225_600_000L) == "2025-12-31")
    Quota.hit("m1", t0 + 26 * 3600_000L)
    check("كوتة: يوم جديد يبدأ من 1", Quota.used("m1", t0 + 26 * 3600_000L) == 1)
    check("حدود الكوتة", Models.quotaOf("gemini-2.5-pro") == 50 && Models.quotaOf("custom") == 500)
    // Recents
    val r1 = Recent("a", "فيلم 1", "", "content://x", 10.0, 100.0, 5, 40.0, 1)
    val r2 = Recent("b", "فيلم 2", "http://v", "", 0.0, 0.0, 0, 0.0, 2)
    var l = Recents.upsert(Recents.upsert(emptyList(), r1), r2)
    check("recents: الأحدث فوق", l.map { it.id } == listOf("b", "a"))
    l = Recents.upsert(l, Recent("a", "فيلم 1", "", "content://x", 50.0, 100.0, 9, 80.0, 3))
    check("recents: تحديث نفس الفيديو من غير تكرار", l.size == 2 && l[0].id == "a" && l[0].subs == 9 && l[0].percent == 80)
    check("recents: حد أقصى", (1..30).fold(emptyList<Recent>()) { acc, i -> Recents.upsert(acc, Recent("i$i", "", "", "", 0.0, 0.0, 0, 0.0, 0)) }.size == 15)
    val back = Recents.parse(Recents.toJson(l))
    check("recents: JSON round-trip", back.size == 2 && back[0].title == "فيلم 1" && back[1].url == "http://v")
    check("recents: JSON بايظ → فاضي", Recents.parse("{{{").isEmpty())
    check("عنوان من الملف/الرابط", Recents.titleOf("f:movie.mp4:123") == "movie.mp4" && Recents.titleOf("u:https://h.com/a/b.m3u8?x=1") == "b.m3u8")
    // أنماط الخط
    val s = SubStyle()
    check("وزن الخط: الأصلي = وزن الخط", s.weight() == 800 && s.copy(font = "Mada").weight() == 900)
    check("وزن الخط: عريض/رشيق", s.copy(fontStyle = "bold").weight() == 800 && s.copy(fontStyle = "slim").weight() == 500)
    check("عامي = تشانجا", s.copy(fontStyle = "casual").effectiveFont().id == "Changa")
    check("كل الـ 22 خط ليها ملفات", SubStyle.fonts.all { it.file != null })
    check("load لنمط الخط", SubStyle.load { k, d -> if (k == "sub_fontstyle") "slim" else d }.fontStyle == "slim" && SubStyle.load { k, d -> if (k == "sub_fontstyle") "zz" else d }.fontStyle == "orig")
    if (fails > 0) { println("فشل $fails"); System.exit(1) } else println("كل الاختبارات نجحت")
}
