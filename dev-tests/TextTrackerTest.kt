import com.tttt.subtitler.*

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

fun det(text: String, x: Float = 0.5f, y: Float = 0.3f) = Det(text, "en", x, y, 0.2f, 0.05f)

fun main() {
    // ===== sim =====
    check("sim: نفس النص", TextTracker.sim("OPEN 24H", "open 24h") == 1.0)
    check("sim: غلطة OCR بسيطة", TextTracker.sim("HOTEL PARADISE", "HOTEL PARADlSE") >= 0.55)
    check("sim: نصين مختلفين", TextTracker.sim("HOTEL", "PHARMACY") < 0.55)

    // ===== ظهور من الثانية 5 لـ 8 (لقطة كل 1ث): بيبدأ ≈ 4.5..5 وبينتهي ≈ 8..8.5 =====
    val tr = TextTracker(1.0)
    var prev = Double.NaN
    for (t in 0..14) {
        val d = if (t in 5..8) listOf(det("EXIT")) else emptyList()
        tr.feed(t.toDouble(), d, prev); prev = t.toDouble()
    }
    check("ظهور واحد بس", tr.tracks.size == 1)
    val k = tr.tracks[0]
    check("بداية ≈ 4.5", Math.abs(k.start - 4.5) < 0.01)
    check("نهاية ≈ 8.5", Math.abs(k.end - 8.5) < 0.01)
    check("مش ظاهر قبل 4.4", tr.activeAt(4.4).isEmpty())
    check("ظاهر عند 6", tr.activeAt(6.0).size == 1)
    check("مش ظاهر بعد 8.7 (مش 6 ثواني زيادة)", tr.activeAt(8.7).isEmpty())

    // ===== غلطة OCR في لقطة واحدة ماتقطّعش الظهور =====
    val t2 = TextTracker(1.0); prev = Double.NaN
    for (t in 0..9) {
        val d = if (t in 2..7 && t != 4) listOf(det("SALE", 0.4f, 0.2f)) else emptyList()
        t2.feed(t.toDouble(), d, prev); prev = t.toDouble()
    }
    check("لقطة فايتة = ظهور واحد", t2.tracks.size == 1)
    check("بعد الفايتة لسه ظاهر عند 5", t2.activeAt(5.0).size == 1)

    // ===== لقطتين فايتين = اختفى، ولما يرجع بعدين يبقى ظهور جديد =====
    val t3 = TextTracker(1.0); prev = Double.NaN
    for (t in 0..12) {
        val d = if (t in 1..3 || t in 8..10) listOf(det("MENU")) else emptyList()
        t3.feed(t.toDouble(), d, prev); prev = t.toDouble()
    }
    check("اختفى ورجع = ظهورين", t3.tracks.size == 2)
    check("مش ظاهر في الفجوة (6)", t3.activeAt(6.0).isEmpty())

    // ===== نصين في مكانين مختلفين في نفس الوقت =====
    val t4 = TextTracker(1.0)
    val f = t4.feed(0.0, listOf(det("BANK", 0.2f, 0.2f), det("CAFE", 0.8f, 0.5f)), Double.NaN)
    check("نصين = ظهورين جداد", f.size == 2 && t4.activeAt(0.0).size == 2)
    val f2 = t4.feed(1.0, listOf(det("BANK", 0.2f, 0.2f), det("CAFE", 0.8f, 0.5f)), 0.0)
    check("اللقطة التانية مفيهاش ظهور جديد", f2.isEmpty() && t4.tracks.size == 2)

    // ===== قفزة في الفيديو (تقديم): الظهور المفتوح بيتقفل =====
    val t5 = TextTracker(1.0)
    t5.feed(10.0, listOf(det("GATE")), Double.NaN)
    t5.feed(11.0, listOf(det("GATE")), 10.0)
    t5.feed(60.0, emptyList(), Double.NaN)
    check("بعد القفزة اتقفل عند ≈ 11.5", Math.abs(t5.tracks[0].end - 11.5) < 0.01)
    check("مش ظاهر عند 30", t5.activeAt(30.0).isEmpty())

    // ===== لسه ظاهر (مفتوح): بيفضل ظاهر لحد آخر لقطة + نص ثانية =====
    val t6 = TextTracker(1.0)
    t6.feed(3.0, listOf(det("LIVE")), Double.NaN); t6.feed(4.0, listOf(det("LIVE")), 3.0)
    check("مفتوح: ظاهر عند 4.3", t6.activeAt(4.3).size == 1)
    check("مفتوح: مش ظاهر عند 5", t6.activeAt(5.0).isEmpty())

    println("الاختبارات: نجح $pass / فشل $fail")
    if (fail > 0) System.exit(1)
}
