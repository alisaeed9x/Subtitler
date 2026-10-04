import com.sun.net.httpserver.HttpServer
import com.tttt.subtitler.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

var fails2 = 0
fun check2(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else "")); if (!ok) fails2++ }
fun waitFor2(ms: Long, c: () -> Boolean): Boolean { val e = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < e) { if (c()) return true; Thread.sleep(50) }; return c() }

/** WAV 16k mono: نغمة، مع صمت بين sStart و sEnd */
fun makeWav(sec: Double, silentFrom: Double = -1.0, silentTo: Double = -1.0): ByteArray {
    val rate = 16000; val n = (sec * rate).toInt()
    val pcm = ByteArray(n * 2)
    for (i in 0 until n) {
        val t = i.toDouble() / rate
        val v = if (t in silentFrom..silentTo) 0.0 else Math.sin(2 * Math.PI * 300 * t) * 0.4
        val s = (v * 32767).toInt()
        pcm[i * 2] = (s and 255).toByte(); pcm[i * 2 + 1] = ((s shr 8) and 255).toByte()
    }
    return Wav.header(pcm.size, rate) + pcm
}

class Req(val key: String, val startTag: Double)
val reqs = CopyOnWriteArrayList<Req>()
val live = AtomicInteger(0); val maxLive = AtomicInteger(0)

fun reply2(text: String) = JSONObject().put("candidates", JSONArray().put(JSONObject()
    .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))).put("finishReason", "STOP"))).toString()

/** سيرفر وهمي: بيرجّع جمل في أول 6 ثواني بس من كل مقطع (فيه فجوة طويلة بعدها) وبيتأخر delayMs */
fun server(delayMs: Long): HttpServer {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.executor = java.util.concurrent.Executors.newFixedThreadPool(16)
    s.createContext("/") { ex ->
        val key = ex.requestHeaders.getFirst("x-goog-api-key") ?: ""
        val body = JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
        val prompt = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.has("text") }?.getString("text") ?: "" }
        val isTr = !(prompt.contains("محلل قصصي") || prompt.contains("مراجع جودة") || prompt.contains("مصحح لغوي") || prompt.contains("فيما يلي مجموعة تعليمات"))
        val c = live.incrementAndGet(); maxLive.updateAndGet { maxOf(it, c) }
        try { if (isTr) Thread.sleep(delayMs) } finally { live.decrementAndGet() }
        val out = if (isTr) {
            val subs = JSONArray()
            for (k in 0 until 2) subs.put(JSONObject().put("start", 1.0 + k * 2).put("end", 2.5 + k * 2).put("original", "o-${System.nanoTime()}-$k").put("translated", "t-${System.nanoTime()}-$k")
                .put("gender", "male").put("addressee", "male").put("topic_gender", "none").put("people", JSONArray()).put("places", JSONArray()))
            reply2(JSONObject().put("detected_source_language", "كورية").put("subtitles", subs).toString())
        } else reply2("{\"characters\":[],\"glossary\":[],\"corrections\":[]}")
        val bytes = out.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
    }
    s.start(); return s
}

class Src(val dur: Double, val wavFor: (Double, Double) -> ByteArray) : AudioSource {
    val asked = CopyOnWriteArrayList<DoubleArray>()
    @Volatile var pre = 0.0
    override fun durationSec() = dur
    override fun wav(startSec: Double, endSec: Double): WavChunk? { asked.add(doubleArrayOf(startSec, endSec)); return WavChunk(wavFor(startSec, endSec), startSec, endSec - startSec, false) }
    override fun close() {}
    override fun setPreRoll(sec: Double) { pre = sec }
}
class H : Host { @Volatile var pos = 0.0; val logs = CopyOnWriteArrayList<String>()
    override fun log(s: String) { logs.add(s) }; override fun status(s: String) {}; override fun changed() {}
    override fun position() = pos; override fun playerDuration() = 0.0 }

fun main() {
    val srv = server(400)
    Api.base = "http://127.0.0.1:${srv.address.port}/v1beta"
    val assets = File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets")
    val pb = PromptBuilder { File(assets, it).readText(Charsets.UTF_8) }
    fun conf(par: Int, keys: List<String> = listOf("KEY_A_1234567", "KEY_B_1234567"), strim: Boolean = true, gap: Boolean = false, hi: Boolean = false, ahead: Int = 6) =
        Conf(keys, emptyList(), "gemini-2.5-flash", "مصري", "حرفي", 60, ahead, 1, emptyList(), "", false, false, false, false, false, par, hi, strim, gap)

    println("=== أوضاع المفاتيح / الإعدادات / الأدوات النقية ===")
    val all = listOf("k1aaaaaaaaaa", "k2aaaaaaaaaa", "k3aaaaaaaaaa")
    val (m, b) = KeyModes.split(all, listOf("both", "backup", "both"))
    check2("أوضاع المفاتيح: الاحتياطي بيتفصل", m == listOf(all[0], all[2]) && b == listOf(all[1]))
    check2("أوضاع المفاتيح: الافتراضي both", KeyModes.split(all, emptyList()).first == all)
    check2("أوضاع المفاتيح: JSON round-trip", KeyModes.parse(KeyModes.toJson(listOf("both", "backup"))) == listOf("both", "backup"))
    check2("أوضاع المفاتيح: JSON بايظ → فاضي", KeyModes.parse("{{") .isEmpty())
    check2("أوضاع المفاتيح: دورة both→backup→both", KeyModes.next("both") == "backup" && KeyModes.next("backup") == "both")
    check2("CfgCodec: نص/Boolean/Int", CfgCodec.bool("1", false) && CfgCodec.bool(true, false) && !CfgCodec.bool("0", true) && CfgCodec.int("60", 1) == 60 && CfgCodec.int(7, 1) == 7 && CfgCodec.int("x", 9) == 9)
    check2("CfgCodec: القراءة النصية لـ Boolean", CfgCodec.str(true, "") == "1" && CfgCodec.str(false, "") == "0" && CfgCodec.str(5, "") == "5")
    check2("CfgCodec: typed حسب المفتاح", CfgCodec.typed("vad", "1") == true && CfgCodec.typed("chunk", "120") == 120 && CfgCodec.typed("lang", "مصري") == "مصري")
    check2("كلمة تأكيد: ! / لاتيني كبير / حرف مكرر", SubStyle.isEmphasis("لأ!") && SubStyle.isEmphasis("NO") && SubStyle.isEmphasis("لأأأ") && !SubStyle.isEmphasis("عادي") && !SubStyle.isEmphasis("OK1"))
    check2("حجم تلقائي حسب عدد الكلمات", SubStyle.sizeMult(1) == 1.18f && SubStyle.sizeMult(4) == 1.08f && SubStyle.sizeMult(7) == 1f && SubStyle.sizeMult(10) == 0.9f && SubStyle.sizeMult(20) == 0.82f)
    check2("الخطوط: كل الـ 22 ليهم ملفات", SubStyle.fonts.size == 22 && SubStyle.fonts.all { it.file != null })
    val fdir = File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets", "fonts")
    check2("ملفات الخطوط موجودة وبصيغة TTF", SubStyle.fonts.all { f -> val x = File(fdir, f.file!!); x.exists() && x.length() > 20000 && x.inputStream().use { i -> val h = ByteArray(4); i.read(h); (h[0].toInt() == 0 && h[1].toInt() == 1) || String(h) == "true" } })
    val pool3 = Pool(conf(1, listOf("A_aaaaaaaaaaa", "B_aaaaaaaaaaa", "C_aaaaaaaaaaa", "D_aaaaaaaaaaa")))
    check2("المراقبين = 25% من 4 مفاتيح", pool3.watchers == listOf("D_aaaaaaaaaaa") && pool3.mains.size == 3)
    check2("الاستيعاب = مفاتيح × توازي", pool3.capacity(2) == 6 && Pool(conf(1)).capacity(3) == 6)
    check2("pick بيتجنب المراقب لو فيه أساسي", (0 until 10).all { pool3.pick(null, it) != "D_aaaaaaaaaaa" })
    check2("gapKey بيفضّل المراقب", pool3.gapKey(emptySet(), 0) == "D_aaaaaaaaaaa")

    println("=== كشف الصمت وتقصير المقطع ===")
    val w1 = makeWav(60.0, 53.0, 54.0)
    val cut = Silence.findCut(w1)
    check2("findCut بيلاقي الصمت (53–54)", cut != null && cut in 53.0..54.1, "cut=$cut")
    check2("findCut: كلام متصل → null", Silence.findCut(makeWav(60.0)) == null)
    check2("findCut: مقطع قصير → null", Silence.findCut(makeWav(1.5, 0.5, 1.0)) == null)
    check2("findCut: مش WAV → null", Silence.findCut("CHUNK:1.0".toByteArray()) == null)
    val tr = Silence.truncate(w1, 10.0)
    check2("truncate: الطول والهيدر صح", tr.size == 44 + 10 * 16000 * 2 && ((tr[40].toInt() and 255) or ((tr[41].toInt() and 255) shl 8) or ((tr[42].toInt() and 255) shl 16)) == 10 * 16000 * 2)

    println("=== كشف الفجوات ===")
    fun sb(s: Double, e: Double) = Sub(s, e, "a", "b", "male", "unknown", "none", emptyList(), emptyList(), false, false)
    val g = Gaps.find(listOf(sb(1.0, 3.0), sb(40.0, 42.0)), listOf(doubleArrayOf(0.0, 100.0)))
    check2("فجوات: بين الجمل وبعد آخر جملة", g.size == 2 && g[0][0] == 3.0 && g[0][1] == 40.0 && g[1][0] == 42.0 && g[1][1] == 100.0, g.joinToString { "[${it[0]},${it[1]}]" })
    check2("فجوات: أقصر من 12ث متتعدّش", Gaps.find(listOf(sb(0.0, 5.0), sb(10.0, 20.0)), listOf(doubleArrayOf(0.0, 25.0))).isEmpty())
    check2("فجوات: برّه المناطق المترجمة متتعدّش", Gaps.find(emptyList(), emptyList()).isEmpty())
    check2("تقسيم الفجوة لأجزاء", Gaps.parts(0.0, 150.0, 60.0).size == 3 && Gaps.parts(0.0, 150.0, 60.0).last()[1] == 150.0)

    println("=== مرحلة E: parallel ===")
    var src = Src(600.0) { _, _ -> "CHUNK".toByteArray() }
    var h = H()
    var e = Engine(conf(2), { src }, null, h, pb)
    var t = Thread { e.run() }.apply { isDaemon = true; start() }
    val t0 = System.currentTimeMillis()
    check2("المقاطع الأولى اتترجمت", waitFor2(30000) { e.coveredSec() >= 360 - 1 }, "covered=${e.coveredSec()}")
    val el = System.currentTimeMillis() - t0
    check2("طلبات متوازية فعلًا (أكتر من واحد في نفس الوقت)", maxLive.get() >= 2, "max=${maxLive.get()}")
    check2("التوازي أسرع من التسلسل (6 مقاطع × 400ms)", el < 2400 + 2500, "ms=$el")
    check2("مفيش جمل مكررة والترتيب سليم", e.subs.map { it.original }.toSet().size == e.subs.size && e.subs.zipWithNext().all { it.first.start <= it.second.start })
    e.stop(); t.join(5000)

    println("=== مرحلة E: تقصير المقطع لأقرب صمت ===")
    reqs.clear()
    src = Src(600.0) { a, b -> if (a == 0.0) makeWav(b - a, 53.0, 54.0) else makeWav(b - a) }
    h = H()
    e = Engine(conf(1, listOf("KEY_A_1234567"), strim = true, ahead = 2), { src }, null, h, pb)
    t = Thread { e.run() }.apply { isDaemon = true; start() }
    check2("المقطع الأول اتقصّر", waitFor2(15000) { h.logs.any { it.contains("اتقصّر") } }, h.logs.take(5).joinToString(" | "))
    check2("المقطع التاني بيبدأ من نقطة القطع - 4ث (مش من 56)", waitFor2(15000) { src.asked.size >= 2 } && src.asked[1][0] in 48.0..51.0, src.asked.joinToString { "[${it[0]},${it[1]}]" })
    e.stop(); t.join(5000)

    println("=== مرحلة E: strim مطفي ===")
    src = Src(600.0) { a, b -> makeWav(b - a, 53.0, 54.0) }
    h = H()
    e = Engine(conf(1, listOf("KEY_A_1234567"), strim = false, ahead = 2), { src }, null, h, pb)
    t = Thread { e.run() }.apply { isDaemon = true; start() }
    check2("من غير تقصير: المقطع التاني من 56", waitFor2(15000) { src.asked.size >= 2 } && Math.abs(src.asked[1][0] - 56.0) < 0.01 && h.logs.none { it.contains("اتقصّر") }, src.asked.joinToString { "[${it[0]},${it[1]}]" })
    e.stop(); t.join(5000)

    println("=== مرحلة E: دقة التوقيت العالية ===")
    src = Src(600.0) { _, _ -> "CHUNK".toByteArray() }
    h = H(); e = Engine(conf(1, listOf("KEY_A_1234567"), hi = true, ahead = 1), { src }, null, h, pb)
    t = Thread { e.run() }.apply { isDaemon = true; start() }
    check2("hiTiming بيظبط pre-roll = 3ث على المصدر", waitFor2(10000) { src.asked.isNotEmpty() } && src.pre == 3.0)
    e.stop(); t.join(5000)
    src = Src(600.0) { _, _ -> "CHUNK".toByteArray() }
    h = H(); e = Engine(conf(1, listOf("KEY_A_1234567"), hi = false, ahead = 1), { src }, null, h, pb)
    t = Thread { e.run() }.apply { isDaemon = true; start() }
    check2("من غير hiTiming: pre-roll = 0", waitFor2(10000) { src.asked.isNotEmpty() } && src.pre == 0.0)
    e.stop(); t.join(5000)

    println("=== مرحلة E: سدّ الفجوات التلقائي ===")
    src = Src(600.0) { _, _ -> "CHUNK".toByteArray() }
    h = H()
    e = Engine(conf(1, listOf("KEY_A_1234567", "KEY_B_1234567"), gap = true, ahead = 1), { src }, null, h, pb)
    h.pos = 20.0
    t = Thread { e.run() }.apply { isDaemon = true; start() }
    check2("الفجوة اتسدّت وجملها متعلّمة بـ «»", waitFor2(20000) { e.subs.any { it.translated.startsWith("«") } }, h.logs.takeLast(6).joinToString(" | "))
    Thread.sleep(2500)
    val fillLogs = h.logs.count { it.contains("سدّ فجوة تلقائي") }
    check2("نفس الفجوة ما بتتكررش للأبد", fillLogs in 1..3, "fills=$fillLogs")
    e.stop(); t.join(5000)
    h = H(); e = Engine(conf(1, listOf("KEY_A_1234567", "KEY_B_1234567"), gap = false, ahead = 1), { Src(600.0) { _, _ -> "CHUNK".toByteArray() } }, null, h, pb)
    h.pos = 20.0
    t = Thread { e.run() }.apply { isDaemon = true; start() }
    Thread.sleep(4500)
    check2("gapFill مطفي → مفيش سدّ", e.subs.none { it.translated.startsWith("«") } && h.logs.none { it.contains("سدّ فجوة") })
    e.stop(); t.join(5000)

    println("=== حفظ حدود المقاطع ===")
    val tmp = File.createTempFile("prog2", "").also { it.delete(); it.mkdirs() }
    val st = Store(tmp, "x")
    st.save(Saved(60, "كورية", true, emptyList(), listOf(doubleArrayOf(0.0, 50.0)), emptyList(), emptyList(), emptyList(), emptyMap(), 5.0, 0, mapOf(1 to 54.5)))
    check2("bounds بتتحفظ وتتسترجع", st.load()?.bounds == mapOf(1 to 54.5))
    st.save(Saved(60, "", false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, 0))
    check2("ملف من غير bounds (قديم) بيتقرا", st.load()?.bounds?.isEmpty() == true)

    println("=== حفظ الفجوات المحاولة ===")
    st.save(Saved(60, "", false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, 0, emptyMap(), listOf(doubleArrayOf(100.0, 130.0))))
    val gl = st.load()?.gapTried
    check2("gapTried بتتحفظ وتتسترجع", gl != null && gl.size == 1 && gl[0][0] == 100.0 && gl[0][1] == 130.0)
    st.save(Saved(60, "", false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, 0))
    check2("ملف من غير gapTried (قديم) بيتقرا", st.load()?.gapTried?.isEmpty() == true)

    srv.stop(0)
    println(if (fails2 == 0) "\nكل الاختبارات نجحت" else "\nفشل $fails2 اختبار")
    System.exit(if (fails2 == 0) 0 else 1)
}
