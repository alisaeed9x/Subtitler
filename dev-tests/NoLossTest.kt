import com.tttt.subtitler.*

// (v177) اختبار «ماتضيعش جمل»: لو أي تعديل مستقبلي رجّع الفقد ده، run_tests.sh بيفشل.
// كل حالة هنا اتسببت في ضياع جمل فعلًا في v176.
var nFails = 0
fun nCheck(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else "")); if (!ok) nFails++ }
fun nsub(s: Double, e: Double, o: String, t: String) = Sub(s, e, o, t, "male", "unknown", "none", emptyList(), emptyList(), false, false)

fun wavOf(segs: List<Triple<Double, Double, Double>>, total: Double, rate: Int = 16000): ByteArray {
    val n = (total * rate).toInt(); val b = ByteArray(44 + n * 2)
    b[0] = 'R'.code.toByte(); b[1] = 'I'.code.toByte(); b[2] = 'F'.code.toByte(); b[3] = 'F'.code.toByte()
    b[24] = (rate and 255).toByte(); b[25] = ((rate shr 8) and 255).toByte()
    val rnd = java.util.Random(7)
    for (i in 0 until n) {
        val t = i.toDouble() / rate; var a = 0.0004 * rnd.nextGaussian()
        for (s in segs) if (t >= s.first && t < s.second) a += s.third * Math.sin(2 * Math.PI * 200 * t) * (0.5 + 0.5 * rnd.nextDouble())
        val v = (a * 32767).toInt().coerceIn(-32768, 32767); b[44 + 2 * i] = (v and 255).toByte(); b[45 + 2 * i] = ((v shr 8) and 255).toByte()
    }
    return b
}

fun main() {
    // 1) كلام واطي اتضخّم (gain 8): لازم الكاشف يلقطه (v176 كان بيطلّع صفر مجالات فكل جمل المقطع كانت بتتشال)
    val g = 8.0
    val quiet = wavOf(listOf(Triple(2.0, 6.0, 0.012 * g), Triple(10.0, 14.0, 0.012 * g)), 20.0)
    val sp = Speech.activeSpans(quiet, g)!!
    nCheck("كلام واطي مضخّم: الكاشف بيلقط المجالين", sp.size == 2, sp.joinToString { "%.1f-%.1f".format(it[0], it[1]) })
    // 2) ضوضاء بس: مفيش مجالات (عشان مانطلبش ثغرات من الهواء)
    nCheck("ضوضاء بس = مفيش كلام", Speech.activeSpans(wavOf(emptyList(), 20.0), 1.0)!!.isEmpty())
    // 3) fit
    val spans = listOf(doubleArrayOf(2.0, 6.0), doubleArrayOf(10.0, 14.0))
    nCheck("جملة 2ث على بعد 1ث من الصوت مابتتشالش (v176 كانت بتتشال)", Speech.fit(nsub(7.0, 9.0, "x", "س"), spans) != null)
    nCheck("جملة فوق الصوت بالظبط بتفضل", Speech.fit(nsub(3.0, 5.0, "x", "س"), spans) != null)
    nCheck("جملة بعيدة جدًا (>3ث) عن أي صوت = تأليف على صمت بتتشال", Speech.fit(nsub(18.5, 19.5, "x", "س"), spans) == null)
    // 4) Parse.subs: end <= start مايضيعش الجملة
    val j = org.json.JSONObject("""{"subtitles":[
        {"start":1.0,"end":1.0,"original":"a b c","translated":"واحد اتنين تلاتة"},
        {"start":3.0,"end":2.0,"original":"d e","translated":"اربعة خمسة"},
        {"start":5.0,"end":7.0,"original":"f","translated":"ستة"}]}""")
    val ps = Parse.subs(j, 100.0, 60.0)
    nCheck("3 جمل في الرد = 3 جمل بعد التحليل (حتى لو end<=start)", ps.size == 3 && ps.all { it.end > it.start }, "n=${ps.size}")
    // 5) حد التوكنز بيكبر مع طول المقطع
    nCheck("حد الرد 8192 للمقطع القصير", Api.outTokens(30.0) == 8192)
    nCheck("حد الرد بيكبر للمقطع الطويل", Api.outTokens(300.0) > 8192 && Api.outTokens(300.0) <= 24576)
    // 6) dedup: سطرين مختلفين قريبين مايتشالوش
    val a = nsub(10.0, 12.0, "I am going home now", "انا رايح البيت دلوقتي").copy(chunk = 0)
    val b = nsub(12.5, 14.0, "Do you want to come with me", "عايز تيجي معايا").copy(chunk = 0)
    nCheck("جملتين مختلفتين الاتنين بيفضلوا", Subs.dedup(listOf(a, b)).size == 2)
    // 7) prompt الثغرة القريبة موجود ومختلف عن العادي
    nCheck("بلوك الثغرة القريبة بيسمح بالكلام الخافت", PromptBuilder.HOLE_NEAR_BLOCK.contains("faint=true") && !PromptBuilder.HOLE_NEAR_BLOCK.contains("ممنوع faint"))
    // 8) (v179) اسم المتكلم
    Extras.spkNames = true
    val jn = org.json.JSONObject("""{"subtitles":[{"start":1.0,"end":3.0,"original":"Hi","translated":"أهلا","speaker_name":"د. هاوس","voice":"V1"},
        {"start":4.0,"end":6.0,"original":"Yo","translated":"مرحبا","speaker_name":"","voice":"V1"},
        {"start":7.0,"end":9.0,"original":"Hey","translated":"هاي","speaker_name":"ستيف: x","voice":"V2"}]}""")
    val pn = Parse.subs(jn, 0.0, 60.0)
    nCheck("اسم المتكلم بيتقرا من الرد", pn[0].speakerName == "د. هاوس" && pn[2].speakerName == "ستيف x", pn.joinToString { it.speakerName })
    nCheck("الجملة بتتعرض باسم المتكلم", pn[0].withSpeaker(pn[0].translated) == "د. هاوس: أهلا")
    nCheck("الاسم الفاضي مابيضيفش حاجة", pn[1].withSpeaker(pn[1].translated) == "مرحبا")
    val filled = Subs.fillSpeakerNames(listOf(pn[0], pn[0].copy(start = 20.0, end = 22.0), pn[1]))
    nCheck("نفس بصمة الصوت بتملا الاسم الفاضي", filled[2].speakerName == "د. هاوس")
    Extras.spkNames = false
    nCheck("الميزة مقفولة = مفيش اسم", pn[0].withSpeaker("أهلا") == "أهلا")
    Extras.spkNames = true
    println(if (nFails == 0) "اختبارات عدم الفقد: كلها نجحت" else "اختبارات عدم الفقد: فشل $nFails")
    if (nFails > 0) System.exit(1)
}
