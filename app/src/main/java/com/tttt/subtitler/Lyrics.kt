package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** سطر كلام متزامن: من start لـ end بالثواني */
class LyricLine(val start: Double, val end: Double, val text: String)

/** نتيجة تفريغ أغنية: الاسم/الفنان المقترحين + السطور. complete=false لو لسه ناقصة (done مقطع من total اتكتبوا) وهتتكمّل لما ترجع للأغنية */
class LyricsResult(val title: String, val artist: String, val lines: List<LyricLine>, val complete: Boolean = true, val done: Int = 0, val total: Int = 0)

/**
 * (v191) كلمات متزامنة بتتكتب من الصوت نفسه: جيميناي بيسمع الملف وبيفرّغ اللي اتغنّى فعلًا بتوقيت كل سطر (زي ترجمة الفيديو بالظبط).
 * (v193) الكلمات بتتحفظ بعد كل مقطع — لو خرجت من الأغنية أو التطبيق قفل في النص، بتتكمّل من آخر مقطع لما ترجع.
 */
object LyricsEngine {
    private const val CHUNK = 60.0
    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun key(t: Track) = "t${t.id}_${t.durMs}.json"
    private val running = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile var version = 0; private set      // بيزيد مع كل حفظ — الشاشة بتقارنه عشان تعرف تحدّث

    fun isRunning(t: Track) = running.containsKey(key(t))
    fun anyRunning() = running.isNotEmpty()

    fun cached(ctx: Context, t: Track): LyricsResult? = try { parse(JSONObject(File(dir(ctx), key(t)).readText())) } catch (_: Throwable) { null }
    fun clear(ctx: Context, t: Track) { try { File(dir(ctx), key(t)).delete() } catch (_: Throwable) {}; version++ }

    private fun parse(o: JSONObject): LyricsResult {
        val a = o.optJSONArray("lines") ?: JSONArray()
        val l = ArrayList<LyricLine>()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); l.add(LyricLine(x.getDouble("s"), x.getDouble("e"), x.getString("t"))) }
        return LyricsResult(o.optString("title"), o.optString("artist"), l, o.optBoolean("complete", true), o.optInt("done", 0), o.optInt("total", 0))
    }
    private fun save(ctx: Context, t: Track, r: LyricsResult) {
        val a = JSONArray(); for (x in r.lines) a.put(JSONObject().put("s", x.start).put("e", x.end).put("t", x.text))
        try {
            File(dir(ctx), key(t)).writeText(JSONObject().put("title", r.title).put("artist", r.artist).put("lines", a)
                .put("complete", r.complete).put("done", r.done).put("total", r.total).toString())
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

    private fun prompt(first: Boolean, hint: Pair<String, String>?, retry: Boolean) = buildString {
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

    /** بيشتغل على خيط الخلفية؛ لو فيه نسخة ناقصة محفوظة بيكمّل من بعدها (إلا لو fresh). onDone(نتيجة أو null + رسالة) */
    fun transcribe(ctx: Context, t: Track, hint: Pair<String, String>?, retry: Boolean, fresh: Boolean, onProgress: (Int, Int) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        val k = key(t)
        if (running.putIfAbsent(k, true) != null) { onDone(null, "بتتكتب دلوقتي"); return }
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش مفتاح Gemini — ضيفه من الإعدادات"); return@Thread }
                val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
                val total0 = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
                val total = Math.ceil(total0 / CHUNK).toInt().coerceAtLeast(1)
                // الاستكمال من نسخة ناقصة
                val prev = if (fresh) null else cached(app, t)?.takeIf { !it.complete && it.total == total }
                val startChunk = prev?.done ?: 0
                val out = ArrayList<LyricLine>()
                if (prev != null) out.addAll(prev.lines.filter { it.start < startChunk * CHUNK })
                var title = prev?.title ?: ""; var artist = prev?.artist ?: ""
                var failed = 0; var firstFail = -1
                for (c in startChunk until total) {
                    onProgress(c + 1, total)
                    val a = c * CHUNK; val b = minOf(a + CHUNK, total0.coerceAtLeast(a + 1))
                    val w = try { src.wav(a, b) } catch (_: Throwable) { null }
                    if (w == null || w.silent) { if (firstFail < 0) save(app, t, LyricsResult(title, artist, tidy(out), false, c + 1, total)); continue }
                    var got: JSONObject? = null
                    for (kk in 0 until keys.size.coerceAtMost(4)) {
                        val key = keys[(c + kk) % keys.size]
                        try {
                            val r = Api.generate(model, key, prompt(c == 0, hint, retry), w.bytes, 8192, if (retry) 0.3 else 0.0, true)
                            got = JSONObject(r.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()); break
                        } catch (_: Throwable) { }
                    }
                    if (got == null) { failed++; if (firstFail < 0) firstFail = c; continue }
                    if (c == 0) { title = got.optString("title").trim(); artist = got.optString("artist").trim() }
                    val arr = got.optJSONArray("lines")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val x = arr.optJSONObject(i) ?: continue
                        val txt = x.optString("t").trim(); if (txt.isEmpty()) continue
                        var s0 = x.optDouble("s", 0.0); var e0 = x.optDouble("e", s0 + 3.0)
                        s0 = s0.coerceIn(0.0, CHUNK); e0 = e0.coerceIn(s0 + 0.5, CHUNK + 1.0)
                        out.add(LyricLine(a + s0, a + e0, txt))
                    }
                    // حفظ بعد كل مقطع (done = أول مقطع فشل لو فيه، عشان يتعاد)
                    if (hint != null && hint.first.isNotBlank()) { title = hint.first; artist = hint.second }
                    save(app, t, LyricsResult(title, artist, tidy(out), false, if (firstFail >= 0) firstFail else c + 1, total))
                }
                try { src.close() } catch (_: Throwable) {}
                if (hint != null && hint.first.isNotBlank()) { title = hint.first; artist = hint.second }
                val clean = tidy(out)
                var res = LyricsResult(title, artist, clean, failed == 0, if (failed == 0) total else firstFail, total)
                var note = ""
                // لو التفريغ من الصوت طلع فاضي والاسم معروف: نطلب الكلمات من جيميناي بالاسم (توقيت تقريبي)
                if (clean.isEmpty() && failed == 0 && hint != null && hint.first.isNotBlank()) {
                    val plain = plainLyrics(keys, model, hint, total0)
                    if (plain.isNotEmpty()) { res = LyricsResult(title, artist, plain, true, total, total); note = "الكلمات من جيميناي بالاسم — التوقيت تقريبي" }
                }
                if (res.lines.isEmpty()) {
                    if (failed == 0) save(app, t, res)   // خلصت من غير كلام: نفتكر ده عشان ما نعيدش كل مرة
                    onDone(res, if (failed > 0) "الطلبات فشلت — هتتكمّل لما ترجع للأغنية" else "ما سمعتش كلام واضح في الأغنية دي")
                } else { save(app, t, res); onDone(res, note.ifEmpty { if (failed > 0) "اتكتب الكلام بس $failed مقطع فشل — هيتكمّل لما ترجع للأغنية" else "" }) }
            } catch (e: Throwable) { LogStore.err("lyrics", e); onDone(null, "فشل: " + (e.message ?: "")) }
            finally { running.remove(k) }
        }.apply { isDaemon = true }.start()
    }

    /** كلمات نصية من جيميناي بالاسم (من غير توقيت حقيقي): بنوزّع الأسطر على طول الأغنية بالتساوي */
    private fun plainLyrics(keys: List<String>, model: String, hint: Pair<String, String>, totalSec: Double): List<LyricLine> {
        val pr = "Give the lyrics of the song \"${hint.first}\"" + (if (hint.second.isNotBlank()) " by ${hint.second}" else "") +
            " in their original language, one sung line per array item, without section labels. If you do not reliably know this song's lyrics, return an empty list. " +
            "Return JSON only: {\"lines\":[\"\"]}"
        for (k in 0 until keys.size.coerceAtMost(3)) {
            try {
                val r = Api.generate(model, keys[k], pr, null, 8192, 0.0, true)
                val arr = JSONObject(r.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).optJSONArray("lines") ?: continue
                val txts = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
                if (txts.isEmpty()) return emptyList()
                val step = (totalSec * 0.85) / txts.size; val off = totalSec * 0.07
                return txts.mapIndexed { i, x -> LyricLine(off + i * step, off + (i + 1) * step, x) }
            } catch (_: Throwable) { }
        }
        return emptyList()
    }

    /** السطر الشغّال دلوقتي عند الوقت ده (آخر سطر بدأ)، أو -1 قبل أول سطر */
    fun indexAt(lines: List<LyricLine>, sec: Double): Int {
        var lo = 0; var hi = lines.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].start <= sec) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }
}
