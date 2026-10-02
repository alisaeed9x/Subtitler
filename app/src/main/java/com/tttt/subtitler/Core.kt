package com.tttt.subtitler

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Sub(
    val start: Double, val end: Double, val original: String, val translated: String,
    val gender: String, val addressee: String, val topicGender: String,
    val people: List<String>, val places: List<String>, val isSong: Boolean, val lowConf: Boolean,
    val chunk: Int = -1,
    val emotion: String = "", val overlap: Boolean = false, val speakerTag: String = "",
    val isContinuation: Boolean = false, val pivot: String = ""
)

data class Chr(val name: String, val gender: String, val role: String)
data class Gloss(val term: String, val note: String)

/** نسخة ثابتة من الإعدادات (عشان المحرك ما يقراش SharedPreferences من خيوط تانية). */
class Conf(
    val keys: List<String>, val backup: List<String>, val model: String,
    val lang: String, val style: String,
    val chunkSec: Int, val ahead: Int, val audioTrack: Int,
    val manualChars: List<Chr>, val manualGloss: String,
    val vad: Boolean, val crossReview: Boolean, val autoChars: Boolean,
    val autoPronouns: Boolean, val autoTemplate: Boolean,
    /** طلبات متوازية لكل مفتاح (1–4) */
    val parallelPerKey: Int = 1,
    /** دقة توقيت أعلى: فك الصوت من قبل نقطة البداية بـ 3 ثواني */
    val hiTiming: Boolean = false,
    /** تعديل حدود المقطع لأقرب لحظة صمت */
    val silenceTrim: Boolean = true,
    /** سدّ الفجوات تلقائيًا أثناء المشاهدة */
    val gapFill: Boolean = false
)

// ===== ترميز الإعدادات (نقي — متختبر) =====
object CfgCodec {
    /** مفاتيح بتتخزن Boolean / Int فعليًا بعد الهجرة */
    val BOOLS = setOf("vad", "cross", "autochars", "autopron", "autotpl", "hitiming", "strim", "gapfill",
        "sub_nobg", "sub_plain", "sub_uni_on", "sub_split_on", "sub_punct")
    val INTS = setOf("chunk", "ahead", "atrack", "parallel", "sub_scale", "sub_bgopa", "sub_blur", "sub_aspeed", "sub_dual", "sub_split")
    const val VERSION = 2
    fun bool(v: Any?, d: Boolean): Boolean = when (v) {
        null -> d
        is Boolean -> v
        is String -> v == "1" || v.equals("true", true)
        is Number -> v.toInt() != 0
        else -> d
    }
    fun int(v: Any?, d: Int): Int = when (v) {
        null -> d
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: d
        else -> d
    }
    /** القراءة النصية القديمة لسه شغالة: Boolean بيرجع "1"/"0" */
    fun str(v: Any?, d: String): String = when (v) {
        null -> d
        is String -> v
        is Boolean -> if (v) "1" else "0"
        else -> v.toString()
    }
    /** قيمة نصية جاية من الواجهة → النوع الصح حسب المفتاح: بيرجع Boolean أو Int أو String */
    fun typed(k: String, v: String): Any = when {
        k in BOOLS -> v == "1" || v.equals("true", true)
        k in INTS -> v.trim().toIntOrNull() ?: v
        else -> v
    }
}

/** أوضاع المفاتيح: both = شغّال في الحوض الأساسي، backup = احتياطي. (باقي أوضاع الأصل خاصة بميزات اتشالت) */
object KeyModes {
    fun parse(s: String): List<String> = try {
        val a = JSONArray(s.ifBlank { "[]" }); (0 until a.length()).map { if (a.optString(it) == "backup") "backup" else "both" }
    } catch (_: Exception) { emptyList() }
    fun toJson(l: List<String>): String { val a = JSONArray(); l.forEach { a.put(it) }; return a.toString() }
    fun modeOf(modes: List<String>, i: Int) = modes.getOrNull(i) ?: "both"
    fun next(m: String) = if (m == "backup") "both" else "backup"
    /** (أساسي، احتياطي من الأوضاع) */
    fun split(all: List<String>, modes: List<String>): Pair<List<String>, List<String>> {
        val main = ArrayList<String>(); val bk = ArrayList<String>()
        all.forEachIndexed { i, k -> if (modeOf(modes, i) == "backup") bk.add(k) else main.add(k) }
        return main to bk
    }
}

// ===== الإعدادات =====
object Cfg {
    lateinit var p: SharedPreferences
    fun init(c: Context) {
        p = c.getSharedPreferences("p", 0)
        Quota.load = { p.getString("quota", "") ?: "" }
        Quota.save = { p.edit().putString("quota", it).apply() }
        migrate()
    }
    /** هجرة لمرة واحدة: القيم النصية القديمة ("1" / "60") بتتحوّل لـ Boolean / Int. أسماء المفاتيح ما اتغيرتش. */
    private fun migrate() {
        if (CfgCodec.int(p.all["cfgv"], 0) >= CfgCodec.VERSION) return
        val e = p.edit()
        for (k in CfgCodec.BOOLS) { val v = p.all[k]; if (v is String) e.putBoolean(k, CfgCodec.bool(v, false)) }
        for (k in CfgCodec.INTS) { val v = p.all[k]; if (v is String) v.trim().toIntOrNull()?.let { e.putInt(k, it) } }
        e.putInt("cfgv", CfgCodec.VERSION).apply()
    }
    fun str(k: String, d: String = "") = CfgCodec.str(p.all[k], d)
    fun bool(k: String, d: Boolean) = CfgCodec.bool(p.all[k], d)
    fun keys(k: String) = str(k).lines().map { it.trim() }.filter { it.length > 10 }
    fun int(k: String, d: Int) = CfgCodec.int(p.all[k], d)
    /** كتابة بالنوع الصح حسب المفتاح */
    fun put(k: String, v: String) {
        val t = CfgCodec.typed(k, v); val e = p.edit()
        when (t) { is Boolean -> e.putBoolean(k, t); is Int -> e.putInt(k, t); else -> e.putString(k, v) }
        e.apply()
    }

    fun parseRoster(text: String): List<Chr> = text.lines().mapNotNull {
        val a = it.split(":")
        if (a.size >= 2 && a[0].isNotBlank())
            Chr(a[0].trim(), if (a[1].trim().lowercase().startsWith("f") || a[1].contains("أنث")) "female" else "male", a.drop(2).joinToString(":").trim())
        else null
    }

    /** كل مفاتيح الأساسي + الإضافية بترتيبها (أوضاع المفاتيح بتقابل الترتيب ده) */
    fun allMainKeys() = (keys("keys") + keys("extra")).distinct()

    fun snapshot(): Conf {
        val (main, bk) = KeyModes.split(allMainKeys(), KeyModes.parse(str("keymodes")))
        return Conf(
            main, (keys("backup") + bk).distinct(), str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT },
            str("lang", "مصري"), str("style", "حرفي"),
            int("chunk", 60).coerceIn(10, 600), int("ahead", 3).coerceIn(0, 50), int("atrack", 1).coerceAtLeast(1),
            parseRoster(str("roster")), str("gloss"),
            bool("vad", false), bool("cross", true), bool("autochars", true), bool("autopron", true), bool("autotpl", true),
            int("parallel", 2).coerceIn(1, 4), bool("hitiming", false), bool("strim", true), bool("gapfill", true)
        )
    }
}

// ===== بناء الـ prompt: النصوص مستخرجة بالحرف من كود البرنامج الأصلي (assets/prompts) =====
class PromptBuilder(private val readAsset: (String) -> String) {
    private val cache = HashMap<String, String>()
    fun read(p: String): String = synchronized(cache) { cache.getOrPut(p) { readAsset(p) } }
    private val index by lazy { JSONObject(read("prompts/index.json")) }

    companion object {
        const val TAIL_MARK = "\n\n\n⏱ مدة هذا المقطع الصوتي"
        /** تعليمات إضافية بتتحط على كل القوالب: التقسيم عند الوقفات الفعلية + المتحدثين المتداخلين (جيميناي هو اللي بيقسّم، مش التطبيق) */
        const val SPLIT_BLOCK = "\n═══ تقسيم الجمل عند الوقفات (إلزامي) ═══\n" +
            "- 🔴 كل subtitle = جزء كلام متصل بين وقفتين فعليتين في صوت المتحدث (نَفَس، سكتة قصيرة، تغيير في النبرة، أو نهاية فكرة). لو المتحدث بيتكلم كلام طويل وبيهدى شوية بين الأجزاء، افصل كل جزء في subtitle لوحده.\n" +
            "- 🔴 start = اللحظة الفعلية اللي المتحدث بيبدأ فيها الجزء ده، وend = اللحظة الفعلية اللي بيسكت فيها. الجزء اللي بعده start بتاعه عند بداية كلامه هو، وده بيخلّي الجزء اللي قبله يختفي والجديد يظهر في وقته بالظبط. ممنوع توزيع الوقت بالتساوي أو بعدد الكلمات.\n" +
            "- 🔴🔴 علامات الترقيم هي أماكن القطع: ممنوع يبقى جوه حقل translated الواحد أكتر من جملة مفصولة بنقطة (.) أو ؟ أو ! أو …، وممنوع جزئين مفصولين بفاصلة (،) كل واحد ليه توقيت كلام مختلف. كل جملة بتنتهي بنقطة/؟/! = subtitle مستقل بتوقيته الفعلي (حتى لو المتحدث التاني هو اللي كمّلها بعد الأول مباشرة). وكل جزء بين فاصلتين = subtitle مستقل بتوقيت start/end الحقيقي بتاعه، بشرط يبقى كلمتين فأكتر (الكلمة الواحدة زي \"أيوه،\" بتتلزق في اللي بعدها). مثال غلط: {\"start\":3.0,\"end\":8.0,\"translated\":\"يعني حبر فقعات. آلة الطباعة دي أكتر حاجة بتطبعها هي العلامة المميزة.\"}. الصح: subtitle أول {\"start\":3.0,\"end\":4.6,\"translated\":\"يعني حبر فقعات.\"} وsubtitle تاني {\"start\":5.1,\"end\":8.0,\"translated\":\"آلة الطباعة دي أكتر حاجة بتطبعها هي العلامة المميزة.\"}. كل subtitle يختفي لما صوت صاحبه يخلص ويظهر اللي بعده لما صوت صاحبه يبدأ.\n" +
            "- 🔴 لو الكلام متصل من غير وقفة خالص، قسّم عند أقرب نهاية فكرة أو فاصلة بحيث الجزء الواحد ما يزيدش عن 8 كلمات عربي تقريبًا.\n" +
            "- 🔴 لو اتنين (أو أكتر) بيتكلموا في نفس الوقت: لكل متحدث subtitle منفصل بتوقيته الفعلي، overlap=true، وspeaker_tag رقم مختلف لكل واحد (1 للأوضح/الأعلى). ممنوع دمج كلامهم في subtitle واحد. التطبيق هيعرضهم كل واحد في سطر تحت التاني بلون مختلف.\n"
    }

    fun rosterText(chars: List<Chr>, gloss: List<Gloss>): String {
        var out = read("prompts/roster_base.txt")
        if (chars.isNotEmpty()) {
            val lines = chars.joinToString("\n") { "- ${it.name}: الجنس الحقيقي = ${if (it.gender == "female") "أنثى" else "ذكر"}${if (it.role.isNotEmpty()) " — " + it.role else ""}" }
            out += read("prompts/roster_chars.txt").replace("{{LINES}}", lines)
        }
        if (gloss.isNotEmpty()) {
            val lines = gloss.joinToString("\n") { "- ${it.term}${if (it.note.isNotEmpty()) ": " + it.note else ""}" }
            out += read("prompts/roster_gloss.txt").replace("- §T§: §NOTE§", lines)
        }
        return out
    }

    fun caseOf(srcLang: String, detectDone: Boolean) = if (!detectDone) "first" else when {
        srcLang.contains("ياباني") -> "ja"
        srcLang.contains("إنجليز") || srcLang.contains("انجليز") || srcLang.contains("انكليز") -> "en"
        else -> "other"
    }

    fun templateId(c: Conf, case: String): String {
        var id = index.optString("${c.lang}|${c.style}|$case")
        if (id.isEmpty()) id = index.optString("${c.lang}|حرفي|$case")
        if (id.isEmpty()) id = index.optString("مصري|حرفي|$case")
        return id
    }

    /** الجزء الثابت من القالب (قبل سطر المدة). فيه علامة §ROSTER§ مكان جدول الشخصيات. */
    fun fixedPart(id: String): String {
        val raw = read("prompts/$id.txt")
        val cut = raw.indexOf(TAIL_MARK)
        return if (cut < 0) raw else raw.substring(0, cut)
    }

    private fun customBlock(text: String): String =
        if (text.isBlank()) "" else "\n═══ مسرد ثابت إلزامي (من المستخدم) ═══\nالأسماء/المصطلحات دي لازم تتترجم بالظبط بالشكل المحدد جنبها في كل مرة تظهر، بدون أي اجتهاد أو تغيير:\n" + text.trim()

    /**
     * @param translatedFixed نسخة مترجمة من الجزء الثابت (للغات اللي ملهاش قالب جاهز) أو null
     */
    fun build(c: Conf, srcLang: String, detectDone: Boolean, durSec: Double, prev: String,
              chars: List<Chr>, gloss: List<Gloss>, translatedFixed: String? = null): String {
        val case = caseOf(srcLang, detectDone)
        val id = templateId(c, case)
        val raw = read("prompts/$id.txt")
        val cut = raw.indexOf(TAIL_MARK)
        val fixed0 = if (cut < 0) raw else raw.substring(0, cut)
        val tail = if (cut < 0) "" else raw.substring(cut)
        val fixed = (if (case == "other" && !translatedFixed.isNullOrEmpty()) translatedFixed else fixed0)
            .replace("§ROSTER§", rosterText(chars, gloss))
        val ctxBlock = if (prev.isNotBlank()) read("prompts/ctx.txt").replace("§PREV§", prev) else ""
        val glossBlock = customBlock(c.manualGloss)
        val tailFinal = if (tail.isEmpty()) "" else tail.substring(1)
        return (fixed + "\n" + SPLIT_BLOCK + glossBlock + tailFinal)
            .replace("\u0001", ctxBlock)
            .replace("{{DUR}}", String.format(java.util.Locale.US, "%.1f", durSec))
    }
}

// ===== مفاتيح Gemini: أساسية + احتياطية، وتعطيل مؤقت عند 429/403 =====
class Pool(private val c: Conf) {
    private val until = HashMap<String, Long>()
    var streak = 0
    /** 25% من المفاتيح الأساسية (لو 3 أو أكتر) "مراقبين": لسدّ الفجوات وكاحتياط كوتة (زي الأصل) */
    private val watchN = if (c.keys.size >= 3) maxOf(1, Math.round(c.keys.size * 0.25).toInt()) else 0
    val mains: List<String> = c.keys.dropLast(watchN)
    val watchers: List<String> = c.keys.takeLast(watchN)
    /** أقصى عدد طلبات ترجمة متوازية */
    fun capacity(perKey: Int): Int = maxOf(1, (if (mains.isNotEmpty()) mains.size else c.backup.size) * perKey.coerceIn(1, 4))
    @Synchronized fun ok(k: String) = (until[k] ?: 0L) < System.currentTimeMillis()
    @Synchronized fun block(k: String, ms: Long) { until[k] = System.currentTimeMillis() + ms }
    @Synchronized fun good(k: String) { until.remove(k); streak = 0 }
    @Synchronized fun clear() { until.clear() }
    fun count() = all().size
    fun all(): List<String> = (c.keys + c.backup).distinct()
    @Synchronized fun pick(avoid: String?, rr: Int): String? {
        val act = mains.filter { it != avoid && ok(it) }
        if (act.isNotEmpty()) return act[Math.floorMod(rr, act.size)]
        watchers.firstOrNull { it != avoid && ok(it) }?.let { return it }
        return c.backup.firstOrNull { it != avoid && ok(it) }
    }
    /** مفتاح لسدّ فجوة: المراقبين، بعدين الاحتياطي، بعدين الأساسي — من غير المفاتيح المشغولة */
    @Synchronized fun gapKey(busy: Set<String>, rr: Int): String? {
        val pref = (watchers + c.backup + mains).distinct().filter { it !in busy && ok(it) }
        return if (pref.isEmpty()) null else pref[Math.floorMod(rr, minOf(pref.size, maxOf(1, watchers.size + c.backup.size)))]
    }
    /** مفتاح للمهام الجانبية (تحليل/مراجعة): الاحتياطي الأول، وإلا أول أساسي. */
    @Synchronized fun helper(): String? =
        c.backup.firstOrNull { ok(it) } ?: c.keys.firstOrNull { ok(it) } ?: all().firstOrNull()
    @Synchronized fun streakUp(): Int { streak++; return streak }
    fun tail(k: String) = "…" + k.takeLast(4)
}

/** عدّاد كوتة يومي محلي لكل موديل، بيتصفّر 00:00 بتوقيت المحيط الهادي PT (زي الأصل) */
object Quota {
    @Volatile var load: (() -> String)? = null
    @Volatile var save: ((String) -> Unit)? = null
    fun dayKey(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US); f.timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles"); return f.format(java.util.Date(ms))
    }
    private fun read(now: Long): JSONObject {
        val day = dayKey(now)
        val j = try { JSONObject(load?.invoke().orEmpty().ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        return if (j.optString("d") == day) j else JSONObject().put("d", day).put("m", JSONObject())
    }
    @Synchronized fun hit(model: String, now: Long = System.currentTimeMillis()) {
        if (save == null) return
        val j = read(now); val m = j.getJSONObject("m"); m.put(model, m.optInt(model, 0) + 1); save?.invoke(j.toString())
    }
    @Synchronized fun used(model: String, now: Long = System.currentTimeMillis()): Int = read(now).getJSONObject("m").optInt(model, 0)
}

class ApiErr(val code: Int, msg: String) : Exception(msg)
class Unsupported(msg: String) : Exception(msg)

object Api {
    var base = "https://generativelanguage.googleapis.com/v1beta"
    class Result(val text: String, val finish: String)

    private val SAFETY = listOf("SEXUALLY_EXPLICIT", "HARASSMENT", "HATE_SPEECH", "DANGEROUS_CONTENT").joinToString(",") {
        "{\"category\":\"HARM_CATEGORY_$it\",\"threshold\":\"BLOCK_NONE\"}"
    }

    /**
     * طلب generateContent. الصوت (wav) بيتبعت stream على دفعات (من غير ما نبني نص base64 كبير في الرام).
     */
    fun generate(model: String, key: String, prompt: String, wav: ByteArray? = null,
                 maxTokens: Int = 8192, temp: Double = 0.1, json: Boolean = true): Result {
        Quota.hit(model)
        val enc = java.util.Base64.getEncoder()
        val head = "{\"contents\":[{\"parts\":[" + (if (wav != null) "{\"inline_data\":{\"mime_type\":\"audio/wav\",\"data\":\"" else "")
        val mid = if (wav != null) "\"}}," else ""
        val textPart = "{\"text\":" + JSONObject.quote(prompt) + "}"
        val tail = "]}],\"generationConfig\":{\"maxOutputTokens\":$maxTokens,\"temperature\":$temp" +
            (if (json) ",\"responseMimeType\":\"application/json\"" else "") + "},\"safetySettings\":[$SAFETY]}"
        val hb = head.toByteArray(Charsets.UTF_8)
        val mb = (mid + textPart + tail).toByteArray(Charsets.UTF_8)
        val b64Len = if (wav != null) ((wav.size + 2) / 3).toLong() * 4 else 0L
        val total = hb.size + b64Len + mb.size

        val c = URL("$base/models/$model:generateContent?key=$key").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 90000
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(total)
            c.outputStream.use { os ->
                os.write(hb)
                if (wav != null) {
                    val step = 3 * 16384
                    var i = 0
                    while (i < wav.size) {
                        val e = minOf(wav.size, i + step)
                        os.write(enc.encode(wav.copyOfRange(i, e)))
                        i = e
                    }
                }
                os.write(mb)
            }
            val code = c.responseCode
            val stream = if (code < 300) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 300) {
                val m = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { "HTTP $code" }
                throw ApiErr(code, m)
            }
            val root = JSONObject(txt)
            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            if (cand == null) {
                val br = root.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
                return Result("", if (br.isNotEmpty()) "PROMPT_$br" else "")
            }
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            return Result(sb.toString(), cand.optString("finishReason", ""))
        } finally { c.disconnect() }
    }
}

// ===== تحليل الرد (نفس منطق الإنقاذ الجزئي في الأصل) =====
object Parse {
    fun json(text: String): JSONObject? {
        val clean = text.replace(Regex("```json\\n?"), "").replace(Regex("```\\n?"), "").trim()
        try { return JSONObject(clean) } catch (_: Exception) {}
        Regex("\\{[\\s\\S]*\\}").find(text)?.let { try { return JSONObject(it.value) } catch (_: Exception) {} }
        if (clean.contains("\"subtitles\"")) {
            val i = clean.lastIndexOf("},")
            if (i != -1) try { return JSONObject(clean.substring(0, i + 1) + "]}") } catch (_: Exception) {}
        }
        return null
    }
    private fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { (a!!.opt(it) as? String)?.trim() }.filter { it.isNotEmpty() }
    fun sub(s: JSONObject, off: Double): Sub {
        val orig = (s.optString("original").ifEmpty { s.optString("text") }).trim()
        val tr = (s.optString("translated").ifEmpty { s.optString("translation") }.ifEmpty { orig }).trim()
        val ad = s.optString("addressee").lowercase().let { if (it in listOf("male", "female", "plural")) it else "unknown" }
        val tg = s.optString("topic_gender").lowercase().let { if (it in listOf("male", "female", "plural")) it else "none" }
        return Sub(
            off + (if (s.has("start")) s.optDouble("start", 0.0) else 0.0), off + (if (s.has("end")) s.optDouble("end", 1.0) else 1.0),
            orig, tr, if (s.optString("gender") == "female") "female" else "male", ad, tg,
            strs(s.optJSONArray("people")), strs(s.optJSONArray("places")), s.optBoolean("is_song", false), s.optBoolean("low_confidence", false), -1,
            s.optString("emotion").trim().lowercase(), s.optBoolean("overlap", false), s.optString("speaker_tag").trim(),
            s.optBoolean("is_continuation", false), s.optString("translated_en_pivot").trim()
        )
    }
    fun subs(j: JSONObject, off: Double, maxEnd: Double): List<Sub> {
        val arr = j.optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { sub(it, off) }
            .filter { it.end > it.start && it.original.isNotEmpty() && it.start < off + maxEnd + 1.0 }
    }
}

// ===== مجالات زمنية (للمقاطع المترجمة) =====
class Ranges {
    private val r = ArrayList<DoubleArray>()
    @Synchronized fun add(s: Double, e: Double) {
        if (e <= s) return
        r.add(doubleArrayOf(s, e)); r.sortBy { it[0] }
        val m = ArrayList<DoubleArray>()
        for (x in r) {
            if (m.isNotEmpty() && x[0] <= m.last()[1] + 0.05) { if (x[1] > m.last()[1]) m.last()[1] = x[1] } else m.add(doubleArrayOf(x[0], x[1]))
        }
        r.clear(); r.addAll(m)
    }
    @Synchronized fun covers(s: Double, e: Double) = r.any { it[0] <= s + 0.05 && it[1] >= e - 0.05 }
    @Synchronized fun list(): List<DoubleArray> = r.map { it.copyOf() }
    @Synchronized fun total(): Double = r.sumOf { it[1] - it[0] }
    @Synchronized fun clear() = r.clear()
}

// ===== سياق الحوار السابق + إزالة التكرار + الدمج + تقسيم الجمل الطويلة (منقولة من الأصل) =====
object Subs {
    fun prevContext(all: List<Sub>, before: Double): String {
        val last = all.filter { it.start < before }.takeLast(20)
        return last.joinToString("\n") { s ->
            val g = if (s.gender == "female") "أنثى" else "ذكر"
            val a = if (s.addressee != "unknown") s.addressee else "-"
            val tg = if (s.topicGender != "none") s.topicGender else "-"
            val names = if (s.people.isNotEmpty()) " | أسماء مذكورة: " + s.people.joinToString("، ") else ""
            val orig = if (s.original.isNotEmpty()) "\n  [أصلي] ${s.original}" else ""
            "- [متكلم:$g | مخاطَب:$a | غايب مذكور:$tg$names] ${s.translated.ifEmpty { s.original }}$orig"
        }
    }
    private fun norm(t: String) = t.replace(Regex("[\\s.,!?؟،«»\"']"), "").trim()
    fun dedup(subs: List<Sub>): List<Sub> {
        if (subs.size < 2) return subs
        val sorted = subs.sortedWith(compareBy({ it.start }, { it.end }))
        val res = ArrayList<Sub>()
        for (cur in sorted) {
            var merged = false
            var back = 1
            while (back <= 6 && res.size - back >= 0) {
                val prev = res[res.size - back]
                val overlaps = cur.start < prev.end - 0.15 && cur.end > prev.start - 0.15
                val similar = norm(cur.original) == norm(prev.original) ||
                    (cur.translated.isNotEmpty() && prev.translated.isNotEmpty() && norm(cur.translated) == norm(prev.translated))
                if (overlaps && similar) { res[res.size - back] = cur; merged = true; break }
                back++
            }
            if (!merged) res.add(cur)
        }
        return res
    }
    private fun words(t: String) = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    fun merge(subs: List<Sub>): List<Sub> {
        if (subs.size < 2) return subs
        val sorted = subs.sortedBy { it.start }
        val res = arrayListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val cur = sorted[i]; val prev = res.last()
            val gap = cur.start - prev.end
            val pw = words(prev.original); val cw = words(cur.original)
            val same = prev.gender == cur.gender && prev.addressee == cur.addressee && prev.topicGender == cur.topicGender
            if (gap >= 0 && gap < 0.5 && same && prev.isSong == cur.isSong && pw <= 6 && cw <= 6 && pw + cw <= 10 &&
                !prev.lowConf && !cur.lowConf && !Regex("[.!؟?]\\s*$").containsMatchIn(prev.original.trim()) && !prev.translated.startsWith("«") && !cur.translated.startsWith("«")) {
                res[res.size - 1] = prev.copy(end = cur.end, original = "${prev.original} ${cur.original}".trim(), translated = "${prev.translated} ${cur.translated}".trim())
            } else res.add(cur)
        }
        return res
    }

    private fun splitN(text: String, n: Int): List<String> {
        val w = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (n <= 1 || w.size <= 1) return listOf(text.trim())
        val per = Math.ceil(w.size.toDouble() / n).toInt()
        val parts = ArrayList<String>()
        var i = 0
        while (i < w.size) { parts.add(w.subList(i, minOf(w.size, i + per)).joinToString(" ")); i += per }
        while (parts.size < n) parts.add("")
        return parts
    }
    fun splitLong(sub: Sub, maxWords: Int = 14): List<Sub> {
        val ow = words(sub.original); val tw = words(sub.translated)
        val wc = maxOf(ow, tw)
        if (wc <= maxWords) return listOf(sub)
        val n = maxOf(2, Math.ceil(wc.toDouble() / maxWords).toInt())
        val op = splitN(sub.original, n); val tp = splitN(sub.translated, n)
        val weights = (0 until n).map { maxOf(1, (tp.getOrNull(it)?.takeIf { s -> s.isNotEmpty() } ?: op.getOrNull(it) ?: "").length) }
        val tot = weights.sum().toDouble()
        val dur = maxOf(0.01, sub.end - sub.start)
        val res = ArrayList<Sub>()
        var cursor = sub.start
        for (i in 0 until n) {
            val o = op.getOrNull(i) ?: ""; val t = tp.getOrNull(i) ?: ""
            if (o.isEmpty() && t.isEmpty()) continue
            val share = weights[i] / tot * dur
            val s0 = cursor
            val e0 = if (i == n - 1) sub.end else minOf(sub.end, cursor + share)
            cursor = e0
            res.add(sub.copy(start = s0, end = e0, original = o.ifEmpty { sub.original }, translated = t.ifEmpty { sub.translated }))
        }
        return if (res.isEmpty()) listOf(sub) else res
    }
    fun splitAll(l: List<Sub>): List<Sub> = l.flatMap { splitLong(it) }
}
