import com.sun.net.httpserver.HttpServer
import com.tttt.subtitler.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class Call(val type: String, val key: String, val chunkStart: Double, val prompt: String)

val calls = CopyOnWriteArrayList<Call>()
val violations = CopyOnWriteArrayList<String>()
val failuresLeft = AtomicInteger(3)      // مقطع 3 (يبدأ 116ث) يفشل 3 مرات
val first429 = AtomicInteger(1)
@Volatile var failChunkStart = 116.0

fun reply(text: String): String = JSONObject().put("candidates", JSONArray().put(JSONObject()
    .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))).put("finishReason", "STOP"))).toString()

fun startServer(): HttpServer {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { ex ->
        val key = Regex("key=([^&]+)").find(ex.requestURI.query ?: "")?.groupValues?.get(1) ?: ""
        val body = JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        var prompt = ""; var audio = ""
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.has("text")) prompt = p.getString("text")
            if (p.has("inline_data")) audio = String(Base64.getDecoder().decode(p.getJSONObject("inline_data").getString("data")))
        }
        val type = when {
            prompt.contains("محلل قصصي") -> "analysis"
            prompt.contains("مراجع جودة") -> "review"
            prompt.contains("مصحح لغوي") -> "pronoun"
            prompt.contains("فيما يلي مجموعة تعليمات") -> "template"
            else -> "translate"
        }
        val cs = if (audio.startsWith("CHUNK:")) audio.removePrefix("CHUNK:").toDouble() else -1.0
        calls.add(Call(type, key, cs, prompt))
        if (type == "translate") {
            for (bad in listOf("§", "{{", "\u0001")) if (prompt.contains(bad)) violations.add("prompt فيه $bad (chunk $cs)")
        }
        var code = 200
        var out: String
        when (type) {
            "translate" -> {
                if (key.startsWith("KEY_A") && first429.getAndDecrement() > 0) { code = 429; out = "{\"error\":{\"message\":\"quota\"}}" }
                else if (cs == failChunkStart && failuresLeft.getAndDecrement() > 0) { code = 400; out = "{\"error\":{\"message\":\"bad request\"}}" }
                else {
                    val subs = JSONArray()
                    for (k in 0 until 5) subs.put(JSONObject().put("start", k * 10 + 1.0).put("end", k * 10 + 3.0)
                        .put("original", "جملة ${cs.toInt()}-$k").put("translated", "ترجمة ${cs.toInt()}-$k")
                        .put("gender", if (k % 2 == 0) "female" else "male").put("addressee", "male").put("topic_gender", "none")
                        .put("people", JSONArray().put("منى")).put("places", JSONArray()))
                    out = reply(JSONObject().put("detected_source_language", "كورية").put("subtitles", subs).toString())
                }
            }
            "analysis" -> out = reply("{\"characters\":[{\"name\":\"منى\",\"gender\":\"female\",\"role\":\"البطلة\"},{\"name\":\"علي\",\"gender\":\"male\",\"role\":\"البطل\"}],\"glossary\":[{\"term\":\"سينسي\",\"note\":\"معلم\"}]}")
            "pronoun" -> {
                val idx = Regex("\"idx\":(\\d+),\"review\":true").find(prompt)?.groupValues?.get(1)?.toInt() ?: 0
                out = reply("{\"corrections\":[{\"idx\":$idx,\"translated\":\"مصحّح-ضمير\"}]}")
            }
            "review" -> out = reply("{\"corrections\":[{\"idx\":0,\"translated\":\"راجعتها-المراجعة\",\"gender\":\"female\",\"addressee\":\"male\",\"topic_gender\":\"none\"}]}")
            else -> {
                val t = prompt.substringAfter("§TEXT§", prompt)
                out = reply("TRANSLATED_TPL " + "نص مترجم طويل ".repeat(40) + "\n[[ROSTER_BLOCK]]\n")
            }
        }
        val bytes = out.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
    }
    s.start(); return s
}

class FakeSource(val dur: Double) : AudioSource {
    override fun durationSec() = dur
    override fun wav(startSec: Double, endSec: Double) = WavChunk("CHUNK:$startSec".toByteArray(), startSec, endSec - startSec, false)
    override fun close() {}
}

class FakeHost : Host {
    @Volatile var pos = 0.0
    val logs = CopyOnWriteArrayList<String>()
    override fun log(s: String) { logs.add(s); println("   [log] $s") }
    override fun status(s: String) {}
    override fun changed() {}
    override fun position() = pos
    override fun playerDuration() = 0.0
}

var fails = 0
fun check(name: String, ok: Boolean, extra: String = "") { println((if (ok) "PASS " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else "")); if (!ok) fails++ }

fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
    return cond()
}

fun main() {
    val srv = startServer()
    Api.base = "http://127.0.0.1:${srv.address.port}/v1beta"
    val assets = File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets")
    val pb = PromptBuilder { File(assets, it).readText(Charsets.UTF_8) }
    val tmp = File.createTempFile("prog", "").also { it.delete(); it.mkdirs() }
    val store = Store(tmp, Store.keyFor("f:test.mp4:123"))
    fun conf() = Conf(listOf("KEY_A_1234567", "KEY_B_1234567"), listOf("KEY_C_1234567"), "gemini-2.5-flash", "مصري", "حرفي",
        60, 3, 1, emptyList(), "", false, true, true, true, true)

    println("=== مرحلة 1: ترجمة من البداية + 429 + مقطع بيفشل ===")
    val host = FakeHost()
    val e1 = Engine(conf(), { FakeSource(1000.0) }, store, host, pb)
    val t1 = Thread { e1.run() }.apply { isDaemon = true; start() }
    check("المقاطع 0-1 اتترجمت", waitFor(20000) { e1.coveredSec() >= 120 - 1 }, "covered=${e1.coveredSec()}")
    check("تحويل المفتاح عند 429", host.logs.any { it.contains("429") && it.contains("تحويل") })
    check("المقطع الفاشل اتسجل كفجوة وبعدين اتعاد ونجح", waitFor(60000) { host.logs.any { it.contains("اتترجم في إعادة المحاولة") } && e1.coveredSec() >= 180 - 1 }, "covered=${e1.coveredSec()}")
    check("تحليل الشخصيات اشتغل", waitFor(10000) { e1.charactersNow().size == 2 }, "chars=${e1.charactersNow().size}")
    check("ترجمة قالب الـ prompt (لغة كورية) اتعملت", waitFor(10000) { calls.any { it.type == "template" } })
    val n1 = e1.subs.size
    check("عدد الجمل = 15 (3 مقاطع × 5)", n1 == 15, "subs=$n1")
    check("مفيش علامات متروكة في الـ prompts", violations.isEmpty(), violations.joinToString())
    check("مفيش جمل مكررة", e1.subs.map { it.original }.toSet().size == e1.subs.size)
    check("الجمل مرتبة زمنيًا", e1.subs.zipWithNext().all { it.first.start <= it.second.start })
    check("جدول الشخصيات بيدخل الـ prompt بعد التحليل", waitFor(1) { true } && calls.filter { it.type == "translate" }.lastOrNull()?.prompt?.contains("منى") == true)

    println("=== مرحلة 2: نتحرك لدقيقة 200 → ضمائر + مراجعة + قالب مترجم ===")
    host.pos = 200.0
    check("المقاطع 3-5 اتترجمت", waitFor(30000) { e1.coveredSec() >= 360 - 1 }, "covered=${e1.coveredSec()}")
    check("تصحيح الضمائر اشتغل", waitFor(15000) { e1.subs.any { it.translated == "مصحّح-ضمير" } })
    check("المراجعة بين المقاطع اشتغلت", waitFor(20000) { e1.subs.any { it.translated == "راجعتها-المراجعة" } })
    check("القالب المترجم اتستخدم في المقاطع التالية", calls.filter { it.type == "translate" }.any { it.prompt.contains("TRANSLATED_TPL") })
    check("العلامة §ROSTER§ اتبدلت بالجدول في القالب المترجم", calls.filter { it.type == "translate" && it.prompt.contains("TRANSLATED_TPL") }.all { it.prompt.contains("منى") && !it.prompt.contains("§") })
    Thread.sleep(500)
    e1.stop(); t1.join(5000)
    val saved = store.load()
    check("الحفظ اتعمل على الديسك", saved != null && saved.subs.size == e1.subs.size, "saved=${saved?.subs?.size} live=${e1.subs.size}")
    check("الحفظ فيه الشخصيات واللغة والقالب", saved != null && saved.chars.size == 2 && saved.srcLang == "كورية" && saved.tpl.isNotEmpty())
    val doneBefore = e1.coveredSec(); val subsBefore = e1.subs.size

    println("=== مرحلة 3: استكمال بعد قفل التطبيق ===")
    calls.clear()
    val host2 = FakeHost()
    val e2 = Engine(conf(), { FakeSource(1000.0) }, store, host2, pb)
    val pos = e2.load()
    check("استرجاع الجمل", e2.subs.size == subsBefore, "${e2.subs.size} vs $subsBefore")
    check("استرجاع المقاطع المتعملة", Math.abs(e2.coveredSec() - doneBefore) < 0.01, "${e2.coveredSec()} vs $doneBefore")
    host2.pos = 100.0     // جوه المنطقة المترجمة
    val t2 = Thread { e2.run() }.apply { isDaemon = true; start() }
    Thread.sleep(2500)
    check("مفيش طلبات ترجمة تاني للمقاطع المتعملة", calls.none { it.type == "translate" }, "translate calls=${calls.count { it.type == "translate" }}")
    host2.pos = 365.0     // مقطع جديد (6)
    check("بيكمل من المقطع الجديد بس", waitFor(15000) { calls.any { it.type == "translate" && it.chunkStart == 356.0 } }, calls.filter { it.type == "translate" }.map { it.chunkStart }.toString())
    check("المقاطع 6-8 اتضافت من غير تكرار (3 مقاطع قدّام المشاهد)", waitFor(10000) { e2.subs.size == subsBefore + 15 } && e2.subs.map { it.original }.toSet().size == e2.subs.size, "subs=${e2.subs.size}")
    println("=== أدوات يدوية: دمج المكرر + النسخ ===")
    val e3 = Engine(conf(), { FakeSource(1000.0) }, null, FakeHost(), pb)
    val b0 = e2.subs[0]
    e3.importSubs(listOf(b0, b0.copy(start = b0.start + 0.1, end = b0.end + 0.5), b0.copy(start = b0.end + 5, end = b0.end + 7, translated = "جملة تانية خالص مختلفة")))
    check("دمج المكرر بيدمج المتطابقتين المتداخلتين بس", e3.removeDuplicates() == 1 && e3.subs.size == 2, "subs=${e3.subs.size}")
    e3.saveVersion("قبل")
    val keep = e3.subs
    e3.importSubs(keep.map { it.copy(translated = "معدّلة") })
    check("applyVersion بيرجّع نص الترجمة من النسخة", e3.applyVersion(e3.versions.size - 1) == 2 && e3.subs.map { it.translated } == keep.map { it.translated })
    e2.stop(); t2.join(5000)
    srv.stop(0)
    println(if (fails == 0) "\nكل الاختبارات نجحت" else "\nفشل $fails اختبار")
    System.exit(if (fails == 0) 0 else 1)
}
