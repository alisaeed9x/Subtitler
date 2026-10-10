import com.tttt.subtitler.*
import org.json.JSONObject

var pass = 0; var fail = 0
fun check(name: String, ok: Boolean) { if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name") } }

fun sub(a: Double, b: Double, o: String = "x y z", t: String = "س ص ع", song: Boolean = false) =
    Sub(a, b, o, t, "male", "unknown", "none", emptyList(), emptyList(), song, false)

/** WAV 16k mono: صوت عالي (sine) إلا الفترات الصامتة المحددة */
fun synth(totalSec: Int, silent: List<IntRange>): ByteArray {
    val rate = 16000; val n = totalSec * rate
    val pcm = ByteArray(n * 2)
    for (i in 0 until n) {
        val sec = i / rate
        val v = if (silent.any { sec in it }) 0.0 else 0.3 * Math.sin(2 * Math.PI * 440 * i / rate)
        val s = (v * 32767).toInt()
        pcm[i * 2] = (s and 255).toByte(); pcm[i * 2 + 1] = ((s shr 8) and 255).toByte()
    }
    return Wav.header(pcm.size, rate) + pcm
}

fun main() {
    // ===== Coverage.findRuns: مقطع 60ث يبدأ عند 100ث، صمت 25..34، جمل بتغطي 100..125 بس =====
    val wav = synth(60, listOf(25..34))
    val runs = Coverage.findRuns(wav, 100.0, listOf(sub(100.0, 125.0)))
    check("findRuns: لقى منطقة واحدة", runs.size == 1)
    check("findRuns: بتبدأ بعد الصمت (≈34)", runs.isNotEmpty() && runs[0].start in 33.0..36.5)
    check("findRuns: بتخلص عند نهاية المقطع (60)", runs.isNotEmpty() && runs[0].end > 59.0)
    check("findRuns: مفيش مناطق لو الجمل بتغطي كل حاجة", Coverage.findRuns(wav, 100.0, listOf(sub(99.0, 162.0))).isEmpty())
    check("findRuns: فجوة قصيرة (<8ث) مش بتتعد", Coverage.findRuns(synth(60, emptyList()), 0.0, listOf(sub(0.0, 25.0), sub(31.0, 60.0))).isEmpty())
    check("findRuns: صمت كامل = مفيش مناطق", Coverage.findRuns(synth(30, listOf(0..29)), 0.0, emptyList()).isEmpty())
    check("findRuns: رد فاضي + صوت عالي = المقطع كله", Coverage.findRuns(synth(40, emptyList()), 0.0, emptyList()).let { it.size == 1 && it[0].end - it[0].start > 38 })

    // ===== pieces / slice =====
    val ps = Coverage.pieces(Coverage.Run(0.0, 50.0))
    check("pieces: 50ث = قطعتين ≤30", ps.size == 2 && ps.all { it.end - it.start <= 30.0 + 1e-9 })
    check("pieces: بتغطي المنطقة كلها من غير فجوات", Math.abs(ps[0].end - ps[1].start) < 1e-9 && ps.last().end == 50.0)
    val sl = Coverage.slice(wav, 10.0, 20.0)
    check("slice: حجم WAV = 10ث", sl.size == 44 + 10 * 16000 * 2)
    check("slice: هيدر RIFF سليم", String(sl, 0, 4) == "RIFF" && String(sl, 8, 4) == "WAVE")

    // ===== Parse.sec: توقيتات نصية =====
    val j = JSONObject("""{"a":"01:23.5","b":12.5,"c":"1:02:03","d":"7","e":"abc"}""")
    check("sec: mm:ss.x", Math.abs(Parse.sec(j, "a", 0.0) - 83.5) < 1e-9)
    check("sec: رقم", Parse.sec(j, "b", 0.0) == 12.5)
    check("sec: hh:mm:ss", Parse.sec(j, "c", 0.0) == 3723.0)
    check("sec: نص رقمي", Parse.sec(j, "d", 0.0) == 7.0)
    check("sec: نص مش مفهوم = الافتراضي", Parse.sec(j, "e", 9.0) == 9.0)
    val rawJ = JSONObject("""{"subtitles":[{"start":"00:05","end":"00:09","original":"hello","translated":"أهلا"}]}""")
    val ps2 = Parse.subs(rawJ, 100.0, 60.0)
    check("Parse.subs: start نصي اتقرا صح", ps2.size == 1 && ps2[0].start == 105.0 && ps2[0].end == 109.0)

    // ===== سطور الموسيقى =====
    fun lab(o: String, t: String = o) = Subs.isMusicLabel(sub(0.0, 1.0, o, t))
    check("music: «(تشغيل الموسيقى)»", lab("(Music playing)", "«(تشغيل الموسيقى)»"))
    check("music: (موسيقى)", lab("(موسيقى)"))
    check("music: [Music]", lab("[Music]", "[موسيقى]"))
    check("music: ♪", lab("♪", "♪"))
    check("music: كلمة موسيقى بدون أقواس مع أصل مختلف = كلام", !lab("müzik güzel", "موسيقى"))
    check("music: جملة عادية فيها موسيقى", !lab("I love music", "أحب الموسيقى كثيرًا"))
    check("music: جملة عادية", !lab("Hayat şaşırtır", "الحياة تدهشني"))
    check("Parse.subs: بتشيل سطر الموسيقى", Parse.subs(JSONObject("""{"subtitles":[{"start":0,"end":5,"original":"(music)","translated":"(تشغيل الموسيقى)"},{"start":5,"end":9,"original":"hi","translated":"أهلا"}]}"""), 0.0, 60.0).size == 1)

    // ===== التقسيم عند علامات الترقيم =====
    val long = sub(0.0, 9.0, "o ".repeat(20).trim(), "الحياة تدهشني دائماً، بينما أنا انتهيت بالفعل من حل الأمر، حين لم يبق لي أي أمل نهائياً، وأحياناً تشرق الشمس")
    val sp = Subs.splitLong(long)
    check("split: جزئين", sp.size == 2)
    check("split: الجزء الأول بينتهي بفاصلة", sp.isNotEmpty() && sp[0].translated.trimEnd().endsWith("،"))
    check("split: مفيش كلمة ضايعة", sp.joinToString(" ") { it.translated }.split(" ").size == long.translated.split(" ").size)
    check("split: الأزمنة متصلة", sp.size == 2 && sp[0].start == 0.0 && sp[1].end == 9.0 && Math.abs(sp[0].end - sp[1].start) < 1e-9)

    // ===== توحيد الكورَس =====
    val ex = listOf(sub(10.0, 13.0, "Hayat şaşırtır hep ben bittim derken", "الحياة تدهشني دائماً بينما أقول إنني انتهيت", true),
        sub(20.0, 23.0, "Hayat şaşırtır hep ben bittim derken", "الحياة تدهشني دائماً بينما أقول إنني انتهيت", true),
        sub(30.0, 33.0, "Hayat şaşırtır hep ben bittim derken.", "شيء مختلف", true))
    val nw = listOf(sub(50.0, 53.0, "Hayat şaşırtır, hep ben bittim derken", "«ترجمة مختلفة تمامًا»", true),
        sub(60.0, 62.0, "Hayat şaşırtır hep ben bittim derken", "x", false),
        sub(70.0, 72.0, "Bambaşka bir cümle burada", "y", true))
    val h = Subs.harmonize(nw, ex)
    check("harmonize: الأغلبية بتكسب + «» محفوظة", h[0].translated == "«الحياة تدهشني دائماً بينما أقول إنني انتهيت»")
    check("harmonize: مش أغنية = مابيتغيرش", h[1].translated == "x")
    check("harmonize: سطر مختلف = مابيتغيرش", h[2].translated == "y")


    // ===== أشرطة التقدم: مجالات التغطية الفعلية + الجمل المستنية =====
    val segs = Coverage.spans(listOf(sub(0.0, 5.0), sub(5.5, 9.0), sub(20.0, 25.0), sub(26.5, 30.0), sub(40.0, 41.0)), 2.0)
    check("spans: 3 مجالات بفجوات حقيقية", segs.size == 3 && segs[0][1] == 9.0 && segs[1][0] == 20.0 && segs[1][1] == 30.0 && segs[2][0] == 40.0)
    check("spans: فاضية", Coverage.spans(emptyList(), 2.0).isEmpty())
    check("spans: غير مرتبة", Coverage.spans(listOf(sub(20.0, 22.0), sub(0.0, 3.0)), 2.0).size == 2)
    Api.base = "http://127.0.0.1:1/v1beta"
    val conf = Conf(listOf("KEY_A_1234567"), emptyList(), "gemini-2.5-flash", "مصري", "حرفي", 60, 3, 1, emptyList(), "", true, true, true)
    val host = object : Host {
        override fun log(s: String) {}
        override fun status(s: String) {}
        override fun changed() {}
        override fun position() = 0.0
        override fun playerDuration() = 0.0
    }
    val eng = Engine(conf, { throw Exception("no source") }, null, host, PromptBuilder { "" })
    eng.importSubs(listOf(sub(0.0, 3.0, "a1 a1", "x"), sub(4.0, 7.0, "b2 b2", "y"), sub(60.0, 63.0, "c3 c3", "z")))
    check("pending: من غير تغيير = فاضي", eng.pendingCount() == 0 && eng.pendingSegs().isEmpty())
    check("coverageSegs: جملتين قريبين + بعيدة = مجالين", eng.coverageSegs().size == 2)
    eng.setConvDialect("خليجي", true)
    check("pending: بعد اختيار لهجة كل الجمل مستنية", eng.pendingCount() == 3)
    check("pending: مجالين (جملتين قريبين + واحدة بعيدة)", eng.pendingSegs().size == 2)
    eng.setConvDialect("فصحى", false)
    check("pending: بيختفي لما اللهجة ترجع فصحى", eng.pendingCount() == 0 && eng.pendingSegs().isEmpty())
    eng.stop()

    println("الاختبارات: نجح $pass، فشل $fail")
    if (fail > 0) System.exit(1)
}
