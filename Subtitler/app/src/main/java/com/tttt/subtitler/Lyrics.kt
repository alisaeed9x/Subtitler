package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** سطر كلام متزامن: من start لـ end بالثواني */
class LyricLine(val start: Double, val end: Double, val text: String)

/** نتيجة كلمات أغنية. complete=false لو لسه ناقصة (ok = أرقام المقاطع اللي اتكتبت) وهتتكمّل من اللي فاضل. note = ملاحظة تتعرض فوق الكلمات (مثلًا «التوقيت تقريبي») */
class LyricsResult(
    val title: String, val artist: String, val lines: List<LyricLine>,
    val complete: Boolean = true, val done: Int = 0, val total: Int = 0,
    val ok: Set<Int> = emptySet(), val note: String = "", val raw: List<String> = emptyList()
)

/**
 * (v194) الكلمات بوضعين:
 *  • M_FULL  📝 كلمات كاملة: جيميناي بيجيب كلمات الأغنية كاملة بالاسم (مراحل: طلب JSON ← طلب نص عادي لو الرد اتقطع) — التوقيت تقريبي.
 *  • M_AUDIO 🎧 تفريغ من الصوت: جيميناي بيسمع الأغنية على مقاطع 60ث (زي ترجمة الفيديو) — بالتوازي على كذا مفتاح، وبتتحفظ بعد كل مقطع
 *            فلو حصل فشل في النص بتتكمّل من اللي فاضل بس.
 * كل فشل بيرجّع سببه الحقيقي (كود جيميناي/قراءة الصوت…) بدل رسالة عامة.
 */
object LyricsEngine {
    const val M_AUDIO = "audio"; const val M_FULL = "full"
    private const val CHUNK = 60.0
    // (v202) الكلمات من جيميناي بس (التفريغ من الصوت اتشال) — والتوقيت بيتحسب محليًا من لحظات الغناء الفعلية في الصوت (Karaoke.kt)
    fun mode(): String = M_FULL
    fun setMode(@Suppress("UNUSED_PARAMETER") m: String) {}

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun key(t: Track, m: String) = "t${t.id}_${t.durMs}" + (if (m == M_FULL) "_full" else "") + ".json"
    private val running = ConcurrentHashMap<String, Boolean>()
    @Volatile var version = 0; private set      // بيزيد مع كل حفظ — الشاشة بتقارنه عشان تعرف تحدّث

    fun isRunning(t: Track, m: String) = running.containsKey(key(t, m))
    fun anyRunning() = running.isNotEmpty()

    fun cached(ctx: Context, t: Track, m: String): LyricsResult? = try {
        val o = JSONObject(File(dir(ctx), key(t, m)).readText())
        val r = parse(o)
        // (v198) نسخ «كلمات كاملة» القديمة (توقيت موزّع بالتساوي) بتتعامل كناقصة عشان تتظبط على الصوت
        if (m == M_FULL && o.optInt("v", 0) < 3 && r.complete) LyricsResult(r.title, r.artist, r.lines, false, 0, 0, emptySet(), r.note, r.raw.ifEmpty { r.lines.map { it.text } }) else r
    } catch (_: Throwable) { null }
    fun clear(ctx: Context, t: Track, m: String) { try { File(dir(ctx), key(t, m)).delete() } catch (_: Throwable) {}; version++ }
    fun clearAll(ctx: Context, t: Track) { clear(ctx, t, M_AUDIO); clear(ctx, t, M_FULL) }

    private fun parse(o: JSONObject): LyricsResult {
        val a = o.optJSONArray("lines") ?: JSONArray()
        val l = ArrayList<LyricLine>()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); l.add(LyricLine(x.getDouble("s"), x.getDouble("e"), x.getString("t"))) }
        val ok = HashSet<Int>(); val oa = o.optJSONArray("ok"); if (oa != null) for (i in 0 until oa.length()) ok.add(oa.getInt(i))
        val ra = o.optJSONArray("raw"); val raw = ArrayList<String>(); if (ra != null) for (i in 0 until ra.length()) { val x = ra.optString(i).trim(); if (x.isNotEmpty()) raw.add(x) }
        return LyricsResult(o.optString("title"), o.optString("artist"), l, o.optBoolean("complete", true), o.optInt("done", 0), o.optInt("total", 0), ok, o.optString("note"), raw)
    }
    private fun save(ctx: Context, t: Track, m: String, r: LyricsResult) {
        val a = JSONArray(); for (x in r.lines) a.put(JSONObject().put("s", x.start).put("e", x.end).put("t", x.text))
        val oa = JSONArray(); for (i in r.ok.sorted()) oa.put(i)
        val ra = JSONArray(); for (x in r.raw) ra.put(x)
        try {
            File(dir(ctx), key(t, m)).writeText(JSONObject().put("title", r.title).put("artist", r.artist).put("lines", a)
                .put("complete", r.complete).put("done", r.done).put("total", r.total).put("ok", oa).put("note", r.note).put("raw", ra).put("v", 3).toString())
        } catch (_: Throwable) {}
        version++
    }
    /** ترتيب + شيل التكرار/التداخل عند حدود المقاطع */
    private fun tidy(src: List<LyricLine>): ArrayList<LyricLine> {
        val out = src.sortedBy { it.start }
        val clean = ArrayList<LyricLine>()
        for (l in out) {
            val p = clean.lastOrNull()
            if (p != null && l.start < p.end) {
                if (l.text == p.text) continue
                clean[clean.size - 1] = LyricLine(p.start, minOf(p.end, l.start), p.text)
            }
            clean.add(l)
        }
        return clean
    }
    private fun shortErr(e: Throwable): String {
        if (e is ApiErr) {
            val why = when (e.code) { 429 -> "المفتاح وصل لحد الطلبات (429)"; 401, 403 -> "المفتاح مرفوض (${e.code})"; 400 -> "جيميناي رفض الطلب (400)"; 503, 500 -> "سيرفر جيميناي مشغول (${e.code})"; else -> "رد جيميناي ${e.code}" }
            return why + " — " + e.raw.replace('\n', ' ').take(70)
        }
        return (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(110)
    }
    private fun jsonOf(txt: String): JSONObject = JSONObject(txt.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())

    private fun audioPrompt(first: Boolean, hint: Pair<String, String>?, retry: Boolean) = buildString {
        append("You are given a short audio clip from a song. Transcribe ONLY the words that are actually sung or spoken in this clip, exactly as you hear them, in the original language (do NOT translate, do NOT correct, do NOT complete or add lines from memory, skip instrumental parts). ")
        append("Split into natural lines (one sung phrase per line) and give each line its start and end time in seconds from the start of THIS clip. ")
        append("If there are no clearly audible words, return an empty list. ")
        if (hint != null && hint.first.isNotBlank()) {
            append("The song is believed to be \"${hint.first}\"" + (if (hint.second.isNotBlank()) " by ${hint.second}" else "") + ". ")
            append("If you know its official lyrics, use them ONLY to fix mis-heard words and spelling of lines that are actually sung in this clip (timings must still come from the audio; never add lines that are not in the clip). ")
        }
        if (retry) append("A previous transcription of this song was reported as INACCURATE — listen again very carefully and be precise. ")
        if (first && (hint == null || hint.first.isBlank())) append("Also, only if you genuinely recognise the song, give its title and artist; otherwise leave them empty strings. ")
        append("Return JSON only: {\"title\":\"\",\"artist\":\"\",\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}")
    }

    /** (v198) مواءمة الكلمات الرسمية مع الصوت: جيميناي بيسمع المقطع ويقول أنهي سطور (من القايمة) اتغنّت فيه ومتى بالظبط */
    private fun alignPrompt(raw: List<String>, hint: Pair<String, String>?) = buildString {
        append("You are given a short audio clip (about one minute) from a song")
        if (hint != null && hint.first.isNotBlank()) append(" (\"${hint.first}\"" + (if (hint.second.isNotBlank()) " by ${hint.second}" else "") + ")")
        append(" and the song's official lyrics as numbered lines. Listen to the clip and return ONLY the lines that are actually sung in THIS clip, in the order they are sung, ")
        append("each with its start and end time in seconds measured from the start of THIS clip. Timings must come from what you hear, not from the position of the line in the list. ")
        append("Copy the text of each line EXACTLY as written in the list (do not change, translate or merge lines). If the same line is sung more than once (a chorus), return it once per time it is sung. ")
        append("Do not return lines that are not sung in this clip. If you hear no words from the list, return an empty list. ")
        append("Return JSON only: {\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}\n\nOFFICIAL LYRICS:\n")
        raw.forEachIndexed { i, x -> append(i + 1).append(". ").append(x).append('\n') }
    }
    /** توقيت تقريبي (موزّع بالتساوي) — بنستخدمه بس لو المواءمة مع الصوت فشلت */
    private fun estimate(t: Track, lines: List<String>): List<LyricLine> {
        val tot = (t.durMs / 1000.0).coerceAtLeast(30.0)
        val step = (tot * 0.85) / lines.size.coerceAtLeast(1); val off = tot * 0.07
        return lines.mapIndexed { i, x -> LyricLine(off + i * step, off + (i + 1) * step, x) }
    }

    /**
     * بيشتغل على خيط خلفية. onStage = نص الحالة الحالية (مرحلة/تقدم) · onDone(نتيجة أو null، رسالة).
     * لو فيه نسخة ناقصة من وضع الصوت بتكمّل من اللي فاضل (إلا لو fresh).
     */
    fun start(ctx: Context, t: Track, mode: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val m = M_FULL
        val k = key(t, m)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتكتب دلوقتي"); return }
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش مفتاح Gemini — ضيفه من الإعدادات"); return@Thread }
                val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                if (m == M_FULL) runFull(app, t, keys, model, hint, fresh, onStage, onDone) else runAudio(app, t, keys, model, hint, retry, fresh, onStage, onDone)
            } catch (e: Throwable) { LogStore.err("lyrics", e); onDone(null, "فشل: " + shortErr(e)) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    // ===================== 🎧 تفريغ من الصوت (متوازي + بيتحفظ بعد كل مقطع) =====================
    private fun runAudio(app: Context, t: Track, keys: List<String>, model: String, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit,
                         mode: String = M_AUDIO, raw: List<String>? = null) {
        onStage("🔌 بجهّز الملف الصوتي…")
        val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
        val total0 = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
        val total = Math.ceil(total0 / CHUNK).toInt().coerceAtLeast(1)
        val prev = if (fresh) null else cached(app, t, mode)?.takeIf { !it.complete && it.total == total }
        val okSet = HashSet<Int>()
        if (prev != null) { if (prev.ok.isNotEmpty()) okSet.addAll(prev.ok) else okSet.addAll(0 until prev.done) }
        val out = ArrayList<LyricLine>()
        if (prev != null) out.addAll(prev.lines)
        var title = prev?.title ?: ""; var artist = prev?.artist ?: ""
        if (raw != null && prev == null) save(app, t, mode, LyricsResult(hint?.first ?: t.title, hint?.second ?: t.artist, emptyList(), false, 0, total, emptySet(), "", raw))
        val pending = ConcurrentLinkedQueue<Int>((0 until total).filter { it !in okSet })
        val errs = ConcurrentLinkedQueue<String>()
        val lock = Any(); val srcLock = Any()
        val nThreads = keys.size.coerceIn(1, 3).coerceAtMost(pending.size.coerceAtLeast(1))
        onStage(if (okSet.isNotEmpty()) "🎧 بكمّل من مقطع ${okSet.size + 1} من $total…" else if (raw != null) "⏱ بظبط توقيت الكلمات على الصوت… ($total مقطع، $nThreads مفتاح بالتوازي)" else "🎧 بسمع الأغنية… ($total مقطع، $nThreads مفتاح بالتوازي)")
        val workers = (0 until nThreads).map { wi ->
            Thread {
                while (true) {
                    val c = pending.poll() ?: break
                    val a = c * CHUNK; val b = minOf(a + CHUNK, total0.coerceAtLeast(a + 1))
                    val w = try { synchronized(srcLock) { src.wav(a, b) } } catch (e: Throwable) { errs.add("قراءة الصوت: " + shortErr(e)); null }
                    if (w == null) { if (errs.isEmpty()) errs.add("ماقدرتش أقرا المقطع ${c + 1} من الملف"); continue }
                    if (w.silent) { synchronized(lock) { okSet.add(c); save(app, t, mode, LyricsResult(title, artist, tidy(out), false, okSet.size, total, HashSet(okSet), "", raw ?: emptyList())) }; continue }
                    var got: JSONObject? = null
                    for (kk in 0 until keys.size.coerceAtMost(4)) {
                        val key = keys[(c + wi + kk) % keys.size]
                        try {
                            val r = Api.generate(model, key, if (raw != null) alignPrompt(raw, hint) else audioPrompt(c == 0, hint, retry), w.bytes, 8192, if (retry) 0.3 else 0.0, true)
                            got = jsonOf(r.text); break
                        } catch (e: Throwable) { errs.add(shortErr(e)) }
                    }
                    val g = got ?: continue
                    synchronized(lock) {
                        if (raw == null && c == 0 && (hint == null || hint.first.isBlank())) { title = g.optString("title").trim(); artist = g.optString("artist").trim() }
                        val arr = g.optJSONArray("lines")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val x = arr.optJSONObject(i) ?: continue
                            val txt = x.optString("t").trim(); if (txt.isEmpty()) continue
                            var s0 = x.optDouble("s", 0.0); var e0 = x.optDouble("e", s0 + 3.0)
                            s0 = s0.coerceIn(0.0, CHUNK); e0 = e0.coerceIn(s0 + 0.5, CHUNK + 1.0)
                            out.add(LyricLine(a + s0, a + e0, txt))
                        }
                        okSet.add(c)
                        if (hint != null && hint.first.isNotBlank()) { title = hint.first; artist = hint.second }
                        save(app, t, mode, LyricsResult(title, artist, tidy(out), false, okSet.size, total, HashSet(okSet), "", raw ?: emptyList()))
                    }
                    onStage((if (raw != null) "⏱ اتظبط " else "🎧 اتكتب ") + "${synchronized(lock) { okSet.size }} من $total مقطع (بتتحفظ لوحدها)")
                }
            }.apply { isDaemon = true }
        }
        workers.forEach { it.start() }; workers.forEach { it.join() }
        try { src.close() } catch (_: Throwable) {}
        if (hint != null && hint.first.isNotBlank()) { title = hint.first; artist = hint.second }
        val complete = okSet.size >= total
        val clean = tidy(out)
        val why = errs.firstOrNull()
        // (v198) مواءمة الكلمات الرسمية: لو الصوت ماطابقش تقريبًا ولا سطر، نرجع للتوقيت التقريبي بدل ما نسيب الشاشة فاضية
        if (raw != null && complete && clean.size < (raw.size * 0.35).toInt().coerceAtLeast(3)) {
            val est = LyricsResult(title, artist, estimate(t, raw), true, total, total, HashSet(okSet), "التوقيت تقريبي", raw)
            save(app, t, mode, est); onDone(est, "ماقدرتش أظبط التوقيت على الصوت — التوقيت تقريبي (جرّب وضع 🎧 تفريغ من الصوت للدقة)"); return
        }
        val res = LyricsResult(title, artist, clean, complete, okSet.size, total, HashSet(okSet), "", raw ?: emptyList())
        if (clean.isEmpty()) {
            save(app, t, mode, res)
            if (complete) onDone(res, "ما سمعتش كلام واضح في الأغنية دي — جرّب وضع 📝 كلمات كاملة")
            else onDone(res, "فشل تفريغ ${total - okSet.size} من $total مقطع" + (if (why != null) " — السبب: $why" else ""))
        } else {
            save(app, t, mode, res)
            onDone(res, if (!complete) "اتكتب الكلام بس ${total - okSet.size} مقطع فشل" + (if (why != null) " ($why)" else "") + " — دوس 🔁 يكمّل" else "")
        }
    }

    // ===================== 📝 كلمات كاملة من جيميناي (بالاسم) =====================
    private fun runFull(app: Context, t: Track, keys: List<String>, model: String, hint: Pair<String, String>?, fresh: Boolean, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val name = if (hint != null && hint.first.isNotBlank()) hint.first else t.title
        val by = if (hint != null && hint.second.isNotBlank()) hint.second else t.artist
        val label = "\"$name\"" + (if (by.isNotBlank()) " by $by" else "")
        var lines: List<String> = emptyList(); var notFound = false
        val errs = ArrayList<String>()
        // لو الكلمات الرسمية اتجابت قبل كده (ومحفوظة) بنكمّل مواءمة الصوت من غير ما نطلبها تاني
        val saved = if (fresh) null else cached(app, t, M_FULL)?.raw?.takeIf { it.size >= 4 }
        if (saved != null) { finishLocal(app, t, name, by, saved, onStage, onDone); return }
        // المرحلة 1: JSON (كلمات + found)
        onStage("📝 مرحلة 1 من 3: بطلب كلمات $name من جيميناي…")
        val pr1 = "Give the complete lyrics of the song $label in its original language (Arabic songs must be written in Arabic script — never transliterated, never translated to English): every sung line in order, no section labels, no translation, nothing invented. " +
            "If you do not reliably know the full lyrics of this exact song, return found=false and an empty list. Return JSON only: {\"found\":true,\"lines\":[\"\"]}"
        for (kk in 0 until keys.size.coerceAtMost(3)) {
            try {
                val r = Api.generate(model, keys[kk % keys.size], pr1, null, 8192, 0.0, true)
                val o = jsonOf(r.text)
                if (!o.optBoolean("found", true)) { notFound = true; break }
                val arr = o.optJSONArray("lines")
                lines = (0 until (arr?.length() ?: 0)).map { arr!!.optString(it).trim() }.filter { it.isNotEmpty() }
                if (lines.size >= 4) break
            } catch (e: Throwable) { errs.add(shortErr(e)) }
        }
        // المرحلة 2: لو الرد اتقطع أو مش JSON → نص عادي سطر سطر
        if (lines.size < 4 && !notFound) {
            onStage("📝 مرحلة 2 من 3: بطلبها كنص عادي…")
            val pr2 = "Write the complete lyrics of the song $label in its original language (Arabic songs must be written in Arabic script — never transliterated, never translated to English), one sung line per row, no section labels, no translation, no commentary. " +
                "If you do not reliably know this exact song's lyrics, reply with exactly: NOT_FOUND"
            for (kk in 0 until keys.size.coerceAtMost(3)) {
                try {
                    val r = Api.generate(model, keys[(kk + 1) % keys.size], pr2, null, 8192, 0.0, false)
                    val txt = r.text.trim()
                    if (txt.contains("NOT_FOUND")) { notFound = true; break }
                    val l2 = txt.lines().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
                    if (l2.size > lines.size) lines = l2
                    if (lines.size >= 4) break
                } catch (e: Throwable) { errs.add(shortErr(e)) }
            }
        }
        if (lines.size < 4) {
            val msg = if (notFound) "جيميناي مش متأكد من كلمات «$name» — جرّب 🔎 تعرّف على الأغنية أو وضع 🎧 تفريغ من الصوت"
                      else "ماجاتش كلمات" + (if (errs.isNotEmpty()) " — السبب: " + errs.first() else "") + " — دوس 🔁 تاني"
            onDone(null, msg); return
        }
        // المرحلة 3: توقيت الكلمات على لحظات الغناء الفعلية في الصوت (محليًا، من غير جيميناي)
        finishLocal(app, t, name, by, lines, onStage, onDone)
    }

    /** (v202) بيوزّع السطور على الأجزاء اللي فيها غنا فعلًا في الصوت (بيتخطى المقدمة والفواصل) — فالكلام مايبدأش وهي لسه في أول الموسيقى */
    private fun finishLocal(app: Context, t: Track, name: String, by: String, lines: List<String>, onStage: (String) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        onStage("⏱ مرحلة 3 من 3: بحدّد امتى المغني بيبدأ يغني…")
        val segs = try { Karaoke.segments(app, t) } catch (e: Throwable) { LogStore.err("lyrics-vad", e); emptyList() }
        val placed = if (segs.isNotEmpty()) Karaoke.place(lines, segs) else emptyList()
        val ok = placed.isNotEmpty()
        val res = LyricsResult(name, by, if (ok) placed else estimate(t, lines), true, 1, 1, emptySet(), if (ok) "" else "التوقيت تقريبي", lines)
        save(app, t, M_FULL, res)
        onDone(res, if (ok) "" else "ماقدرتش أحدد لحظات الغنا في الصوت — التوقيت تقريبي")
    }

    /** السطر الشغّال دلوقتي عند الوقت ده (آخر سطر بدأ)، أو -1 قبل أول سطر */
    fun indexAt(lines: List<LyricLine>, sec: Double): Int {
        var lo = 0; var hi = lines.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].start <= sec) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }
}
