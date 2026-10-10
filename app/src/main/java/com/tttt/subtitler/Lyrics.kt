package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** سطر كلام متزامن: من start لـ end بالثواني */
class LyricLine(val start: Double, val end: Double, val text: String)

/** نتيجة تفريغ أغنية: الاسم/الفنان المقترحين (ممكن يبقوا فاضيين) + السطور */
class LyricsResult(val title: String, val artist: String, val lines: List<LyricLine>)

/**
 * (v191) كلمات متزامنة بتتكتب من الصوت نفسه: جيميناي بيسمع الملف وبيفرّغ اللي اتغنّى فعلًا بتوقيت كل سطر (زي ترجمة الفيديو بالظبط).
 * مش بيكتب كلمات من حفظه — لو الصوت مفهوش كلام مفهوم (موسيقى بس / كلام مش واضح) النتيجة بتبقى فاضية أو ناقصة.
 * الاسم المقترح تخمين من جيميناي، مش بصمة صوت.
 */
object LyricsEngine {
    private const val CHUNK = 60.0
    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun key(t: Track) = "t${t.id}_${t.durMs}.json"

    fun cached(ctx: Context, t: Track): LyricsResult? = try { parse(JSONObject(File(dir(ctx), key(t)).readText())) } catch (_: Throwable) { null }
    fun clear(ctx: Context, t: Track) { try { File(dir(ctx), key(t)).delete() } catch (_: Throwable) {} }

    private fun parse(o: JSONObject): LyricsResult {
        val a = o.optJSONArray("lines") ?: JSONArray()
        val l = ArrayList<LyricLine>()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); l.add(LyricLine(x.getDouble("s"), x.getDouble("e"), x.getString("t"))) }
        return LyricsResult(o.optString("title"), o.optString("artist"), l)
    }
    private fun save(ctx: Context, t: Track, r: LyricsResult) {
        val a = JSONArray(); for (x in r.lines) a.put(JSONObject().put("s", x.start).put("e", x.end).put("t", x.text))
        try { File(dir(ctx), key(t)).writeText(JSONObject().put("title", r.title).put("artist", r.artist).put("lines", a).toString()) } catch (_: Throwable) {}
    }

    private fun prompt(first: Boolean) = buildString {
        append("You are given a short audio clip from a song. Transcribe ONLY the words that are actually sung or spoken in this clip, exactly as you hear them, in the original language (do NOT translate, do NOT correct, do NOT complete or add lines from memory, skip instrumental parts). ")
        append("Split into natural lines (one sung phrase per line) and give each line its start and end time in seconds from the start of THIS clip. ")
        append("If there are no clearly audible words, return an empty list. ")
        if (first) append("Also, only if you genuinely recognise the song, give its title and artist; otherwise leave them empty strings. ")
        append("Return JSON only: {\"title\":\"\",\"artist\":\"\",\"lines\":[{\"s\":0.0,\"e\":0.0,\"t\":\"\"}]}")
    }

    /** بيشتغل على خيط الخلفية؛ onProgress(n, total) و onDone(نتيجة أو null + رسالة) على أي خيط */
    fun transcribe(ctx: Context, t: Track, onProgress: (Int, Int) -> Unit, onDone: (LyricsResult?, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش مفتاح Gemini — ضيفه من الإعدادات"); return@Thread }
                val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
                val total0 = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
                val total = Math.ceil(total0 / CHUNK).toInt().coerceAtLeast(1)
                val out = ArrayList<LyricLine>()
                var title = ""; var artist = ""
                var failed = 0
                for (c in 0 until total) {
                    onProgress(c + 1, total)
                    val a = c * CHUNK; val b = minOf(a + CHUNK, total0.coerceAtLeast(a + 1))
                    val w = try { src.wav(a, b) } catch (_: Throwable) { null }
                    if (w == null || w.silent) continue
                    var got: JSONObject? = null
                    for (k in 0 until keys.size.coerceAtMost(4)) {
                        val key = keys[(c + k) % keys.size]
                        try {
                            val r = Api.generate(model, key, prompt(c == 0), w.bytes, 8192, 0.0, true)
                            got = JSONObject(r.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()); break
                        } catch (_: Throwable) { }
                    }
                    if (got == null) { failed++; continue }
                    if (c == 0) { title = got.optString("title").trim(); artist = got.optString("artist").trim() }
                    val arr = got.optJSONArray("lines") ?: continue
                    for (i in 0 until arr.length()) {
                        val x = arr.optJSONObject(i) ?: continue
                        val txt = x.optString("t").trim(); if (txt.isEmpty()) continue
                        var s = x.optDouble("s", 0.0); var e = x.optDouble("e", s + 3.0)
                        s = s.coerceIn(0.0, CHUNK); e = e.coerceIn(s + 0.5, CHUNK + 1.0)
                        out.add(LyricLine(a + s, a + e, txt))
                    }
                }
                try { src.close() } catch (_: Throwable) {}
                out.sortBy { it.start }
                // تنضيف: أسطر بتتداخل من حدود المقاطع
                val clean = ArrayList<LyricLine>()
                for (l in out) {
                    val p = clean.lastOrNull()
                    if (p != null && l.start < p.end) {
                        if (l.text == p.text) continue
                        clean[clean.size - 1] = LyricLine(p.start, minOf(p.end, l.start), p.text)
                    }
                    clean.add(l)
                }
                val res = LyricsResult(title, artist, clean)
                if (clean.isEmpty()) onDone(res, if (failed > 0) "الطلبات فشلت — جرّب تاني بعد شوية" else "ما سمعتش كلام واضح في الأغنية دي")
                else { save(app, t, res); onDone(res, if (failed > 0) "اتكتب الكلام بس $failed مقطع فشل" else "") }
            } catch (e: Throwable) { LogStore.err("lyrics", e); onDone(null, "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    /** السطر الشغّال دلوقتي عند الوقت ده (آخر سطر بدأ)، أو -1 قبل أول سطر */
    fun indexAt(lines: List<LyricLine>, sec: Double): Int {
        var lo = 0; var hi = lines.size - 1; var r = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (lines[m].start <= sec) { r = m; lo = m + 1 } else hi = m - 1 }
        return r
    }
}
