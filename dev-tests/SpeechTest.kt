import com.tttt.subtitler.*
import java.io.File

var sFails = 0
fun sCheck(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else "")); if (!ok) sFails++ }

fun sub(s: Double, e: Double, o: String, t: String) = Sub(s, e, o, t, "male", "unknown", "none", emptyList(), emptyList(), false, false)

fun main() {
    // 1) سقف السرعة: جملة 10 كلمات متسجلة 18 ثانية (حالة حقيقية من الفيديو الغنائي)
    val long = sub(10.9, 29.3, "a b c d e f g h i j", "انتهى الأمر غدًا مرحلة الميلاد فقدت نفسي أين توازني")
    val c = Subs.capPace(long)
    sCheck("جملة قصيرة بمدة 18ث اتقصّرت", c.end - c.start < 9.0 && c.start == 10.9, "dur=${c.end - c.start}")
    val ok = Subs.capPace(sub(0.0, 3.0, "a b c", "واحد اتنين تلاتة"))
    sCheck("جملة مدتها معقولة مابتتغيرش", ok.end == 3.0)

    // 2) WAV صناعي: صمت 0-2، صوت 2-12، صمت 12-14، صوت 14-20
    val rate = 16000; val n = rate * 20
    val pcm = ByteArray(44 + n * 2)
    pcm[0] = 'R'.code.toByte(); pcm[1] = 'I'.code.toByte(); pcm[2] = 'F'.code.toByte(); pcm[3] = 'F'.code.toByte()
    pcm[24] = (rate and 255).toByte(); pcm[25] = ((rate shr 8) and 255).toByte(); pcm[26] = 0; pcm[27] = 0
    for (i in 0 until n) {
        val t = i.toDouble() / rate
        val on = (t in 2.0..12.0) || (t in 14.0..20.0)
        val v = if (on) (8000 * Math.sin(2 * Math.PI * 220 * t)).toInt() else 0
        pcm[44 + i * 2] = (v and 255).toByte(); pcm[45 + i * 2] = ((v shr 8) and 255).toByte()
    }
    val sp = Speech.activeSpans(pcm)!!
    sCheck("مجالات الصوت = 2", sp.size == 2, sp.joinToString { "%.1f-%.1f".format(it[0], it[1]) })
    val abs = Speech.absolute(sp, 100.0)
    // جملة واحدة بس على أول 4 ثواني من الصوت: باقي الصوت (106..112 و114..120) من غير ترجمة
    val holes = Speech.holes(abs, listOf(sub(102.0, 106.0, "x", "س")), 100.0, 120.0, 5.0)
    sCheck("بيلاقي الثغرات", holes.size == 2 && Math.abs(holes[0][0] - 106.3) < 0.5, holes.joinToString { "%.1f-%.1f".format(it[0], it[1]) })
    sCheck("مفيش ثغرة لو كله مغطى", Speech.holes(abs, listOf(sub(102.0, 112.0, "x", "س"), sub(114.0, 120.0, "x", "س")), 100.0, 120.0, 5.0).isEmpty())

    // 3) fit: جملة فوق صمت تتشال، جملة بتعدّي نهاية الصوت بتتقصّر
    sCheck("جملة فوق صمت تام تتشال", Speech.fit(sub(112.5, 114.0, "x", "س"), abs) == null)
    val f = Speech.fit(sub(104.0, 118.0, "x", "س"), abs)!!
    sCheck("جملة بتعدّي الصوت بتتقصّر", f.end <= 112.5 + 0.01 || f.end == 118.0, "end=${f.end}")
    sCheck("بيانات مش WAV مابتتحللش", Speech.activeSpans("CHUNK:0".toByteArray()) == null)

    // 4) dedup: سطر الكورس المتكرر في نفس المقطع مايتمسحش
    val a = sub(10.0, 14.0, "الحياة تدهشني", "الحياة تدهشني").copy(chunk = 0)
    val b = sub(12.0, 16.0, "الحياة تدهشني", "الحياة تدهشني").copy(chunk = 0)
    sCheck("تكرار حقيقي (بعد 2ث) في نفس المقطع بيفضل", Subs.dedup(listOf(a, b)).size == 2)
    val d1 = sub(10.0, 14.0, "hello there", "اهلا").copy(chunk = 0); val d2 = sub(10.2, 14.1, "hello there", "اهلا").copy(chunk = 1)
    sCheck("نسختين من منطقة التداخل بين مقطعين بيتدمجوا", Subs.dedup(listOf(d1, d2)).size == 1)

    // 5) الملف الحقيقي (لو موجود): الأغنية نفسها
    val real = File("/tmp/a.wav")
    if (real.exists()) {
        val bytes = real.readBytes()
        val sp2 = Speech.activeSpans(bytes)!!
        val absr = Speech.absolute(sp2, 0.0)
        val srt = listOf(sub(29.3, 37.1, "x", "س"), sub(37.1, 47.7, "x", "س"), sub(48.1, 52.7, "x", "س"), sub(52.7, 57.4, "x", "س"), sub(57.7, 62.5, "x", "س"), sub(62.5, 67.2, "x", "س"))
        val hs = Speech.holes(absr, srt, 60.0, 180.0, 5.0)
        sCheck("الأغنية الحقيقية: بيلاقي ثغرة 1:08→3:16 اللي من غير ترجمة", hs.isNotEmpty() && hs.sumOf { it[1] - it[0] } > 90, hs.joinToString { "%.0f-%.0f".format(it[0], it[1]) })
    }
    // 6) جلب الموديلات (صفحتين، فلترة generateContent)
    val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    srv.createContext("/v1beta/models") { ex ->
        val q = ex.requestURI.query ?: ""
        val body = if (q.contains("pageToken")) """{"models":[{"name":"models/gemini-3-flash","displayName":"Gemini 3 Flash","supportedGenerationMethods":["generateContent"]}]}"""
        else """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","supportedGenerationMethods":["generateContent","countTokens"]},{"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"P2"}"""
        val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }
    srv.start()
    Api.base = "http://127.0.0.1:${srv.address.port}/v1beta"
    val ms = Api.listModels("KEY")
    sCheck("جلب الموديلات: صفحتين + تجاهل اللي مش generateContent", ms.map { it.id } == listOf("gemini-2.5-flash", "gemini-3-flash"), ms.joinToString { it.id })
    srv.stop(0)
    println(if (sFails == 0) "كل اختبارات الصوت نجحت" else "فشل $sFails")
}
