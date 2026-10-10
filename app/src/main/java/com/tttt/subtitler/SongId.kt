package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** نتيجة التعرف على أغنية */
class SongMatch(val title: String, val artist: String, val album: String)

/**
 * (v192) التعرف على أغنية مجهولة بالبصمة عن طريق خدمة AudD (api.audd.io): بياخد مقطع ~12ث من الأغنية، يبعته للخدمة، ويرجع الاسم والفنان.
 * محتاج توكن من audd.io (بتحطه مرة واحدة). المقطع بيتبعت لسيرفراتهم؛ والخدمة ليها حد مجاني وبعده بتتحاسب.
 */
object SongId {
    private const val ENDPOINT = "https://api.audd.io/"
    private const val CLIP_SEC = 12.0

    fun token(): String = Cfg.str("audd_token", "").trim()
    fun saveToken(t: String) = Cfg.put("audd_token", t.trim())

    /** الاسم المحفوظ يدويًا لأغنية (من نتيجة التعرف) — "العنوان\u0001الفنان" */
    fun override(id: Long): Pair<String, String>? {
        val v = Cfg.str("mname_$id", "")
        if (v.isBlank()) return null
        val p = v.split('\u0001'); return p[0] to p.getOrElse(1) { "" }
    }
    fun saveOverride(id: Long, m: SongMatch) = Cfg.put("mname_$id", m.title + "\u0001" + m.artist)
    fun clearOverride(id: Long) = Cfg.put("mname_$id", "")

    /**
     * (v193) التعرف التلقائي: لو فيه توكن AudD بنجرّبه الأول؛ ولو مفيش توكن (أو ما اتعرفتش، أو اليوزر قال إن الاسم غلط)
     * بنبعت المقطع لجيميناي يقول اسم الأغنية والفنان. [avoid] = أسماء اتقال إنها غلط ("العنوان — الفنان").
     */
    fun auto(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        if (token().isEmpty() || avoid.isNotEmpty()) { gemini(ctx, t, startSec, avoid, onDone); return }
        recognize(ctx, t, startSec) { m, msg -> if (m != null) onDone(m, msg) else gemini(ctx, t, startSec, avoid) { m2, msg2 -> onDone(m2, if (m2 == null) msg2.ifBlank { msg } else "") } }
    }

    private const val GEM_CLIP = 25.0
    fun gemini(ctx: Context, t: Track, startSec: Double, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش توكن AudD ولا مفتاح Gemini — ضيف واحد منهم"); return@Thread }
                val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
                val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
                val a = startSec.coerceIn(0.0, (total - GEM_CLIP).coerceAtLeast(0.0))
                val w = try { src.wav(a, minOf(a + GEM_CLIP, total.coerceAtLeast(a + 1))) } catch (_: Throwable) { null }
                try { src.close() } catch (_: Throwable) {}
                if (w == null || w.silent) { onDone(null, "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }
                val pr = buildString {
                    append("Identify the song in this audio clip. Use what you hear (voice, melody, sung words, language) and your knowledge. ")
                    append("The file is named \"${t.title}\" / artist tag \"${t.artist}\" (may be missing or wrong — treat as a weak hint only). ")
                    if (avoid.isNotEmpty()) append("These answers were already reported WRONG, do not repeat them: " + avoid.joinToString("; ") + ". ")
                    append("If you are not reasonably sure, return empty strings — never invent a song. ")
                    append("Return JSON only: {\"title\":\"\",\"artist\":\"\",\"album\":\"\"}")
                }
                var res: JSONObject? = null; var err = ""
                for (k in 0 until keys.size.coerceAtMost(4)) {
                    try {
                        val r = Api.generate(model, keys[k % keys.size], pr, w.bytes, 1024, 0.2, true)
                        res = JSONObject(r.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()); break
                    } catch (e: Throwable) { err = e.message ?: "" }
                }
                if (res == null) { onDone(null, "جيميناي ما ردّش: $err"); return@Thread }
                val title = res.optString("title").trim(); val artist = res.optString("artist").trim()
                if (title.isEmpty()) onDone(null, "جيميناي مش متأكد من الأغنية دي — جرّب مقطع تاني من وسط الأغنية")
                else onDone(SongMatch(title, artist, res.optString("album").trim()), "")
            } catch (e: Throwable) { LogStore.err("songid-gemini", e); onDone(null, "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    /** على خيط خلفية. startSec = من أنهي ثانية ناخد المقطع. onDone(نتيجة أو null، رسالة خطأ/عدم تطابق) */
    fun recognize(ctx: Context, t: Track, startSec: Double, onDone: (SongMatch?, String) -> Unit) {
        val app = ctx.applicationContext
        val tk = token()
        if (tk.isEmpty()) { onDone(null, "محتاج توكن AudD"); return }
        Thread {
            try {
                val src = AudioSources.make(app, Uri.parse(t.uri), null, emptyMap(), 1) { }
                val total = src.durationSec().takeIf { it > 0 } ?: (t.durMs / 1000.0)
                val a = startSec.coerceIn(0.0, (total - CLIP_SEC).coerceAtLeast(0.0))
                val w = try { src.wav(a, minOf(a + CLIP_SEC, total.coerceAtLeast(a + 1))) } catch (_: Throwable) { null }
                try { src.close() } catch (_: Throwable) {}
                if (w == null || w.silent) { onDone(null, "المقطع ده صامت — شغّل الأغنية لحد جزء فيه صوت وجرّب تاني"); return@Thread }
                val pk = AudioEnc.pack(w.bytes)
                val ext = if (pk.mime.contains("wav")) "wav" else "m4a"
                val body = multipart(tk, pk.bytes, "clip.$ext", pk.mime)
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 45000
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$B")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    parse(txt, code, onDone)
                } finally { c.disconnect() }
            } catch (e: Throwable) { LogStore.err("songid", e); onDone(null, "فشل الاتصال: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    private const val B = "----SubtitlerBoundary7MA4YWxk"
    private fun multipart(token: String, data: ByteArray, name: String, mime: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun s(x: String) = o.write(x.toByteArray(Charsets.UTF_8))
        s("--$B\r\nContent-Disposition: form-data; name=\"api_token\"\r\n\r\n$token\r\n")
        s("--$B\r\nContent-Disposition: form-data; name=\"return\"\r\n\r\napple_music,spotify\r\n")
        s("--$B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n")
        o.write(data); s("\r\n--$B--\r\n")
        return o.toByteArray()
    }

    private fun parse(txt: String, code: Int, onDone: (SongMatch?, String) -> Unit) {
        val j = try { JSONObject(txt) } catch (_: Throwable) { null }
        if (j == null) { onDone(null, "رد غير مفهوم من الخدمة (كود $code)"); return }
        if (j.optString("status") != "success") {
            val er = j.optJSONObject("error")
            val m = er?.optString("error_message").orEmpty()
            onDone(null, if (m.isNotBlank()) "الخدمة رفضت الطلب: $m" else "الخدمة رفضت الطلب (كود $code)"); return
        }
        val r = j.optJSONObject("result")
        if (r == null) { onDone(null, "ما اتعرفتش — جرّب مقطع تاني من وسط الأغنية"); return }
        onDone(SongMatch(r.optString("title"), r.optString("artist"), r.optString("album")), "")
    }

    // ===== (v197) التعرف من مقطع WAV جاهز (صوت الجهاز الملتقط من تطبيق تاني) =====
    fun autoClip(wav: ByteArray, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        if (token().isEmpty() || avoid.isNotEmpty()) { geminiClip(wav, avoid, onDone); return }
        recognizeClip(wav) { m, msg -> if (m != null) onDone(m, "") else geminiClip(wav, avoid) { m2, msg2 -> onDone(m2, if (m2 == null) msg2.ifBlank { msg } else "") } }
    }

    fun recognizeClip(wav: ByteArray, onDone: (SongMatch?, String) -> Unit) {
        val tk = token()
        if (tk.isEmpty()) { onDone(null, "محتاج توكن AudD"); return }
        Thread {
            try {
                val pk = AudioEnc.pack(wav)
                val ext = if (pk.mime.contains("wav")) "wav" else "m4a"
                val body = multipart(tk, pk.bytes, "clip.$ext", pk.mime)
                val c = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 45000
                    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$B")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    parse(txt, code, onDone)
                } finally { c.disconnect() }
            } catch (e: Throwable) { LogStore.err("songid-clip", e); onDone(null, "فشل الاتصال: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }

    fun geminiClip(wav: ByteArray, avoid: List<String>, onDone: (SongMatch?, String) -> Unit) {
        Thread {
            try {
                val keys = Cfg.allMainKeys()
                if (keys.isEmpty()) { onDone(null, "مفيش توكن AudD ولا مفتاح Gemini"); return@Thread }
                val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
                val pr = buildString {
                    append("This audio was captured from a phone's playback (another app is playing it). Identify the song. Use the voice, melody, sung words, language and your knowledge. ")
                    append("Ignore speech/ads/sound effects that are not part of a song. ")
                    if (avoid.isNotEmpty()) append("These answers were already reported WRONG, do not repeat them: " + avoid.joinToString("; ") + ". ")
                    append("If you are not reasonably sure, return empty strings — never invent a song. ")
                    append("Return JSON only: {\"title\":\"\",\"artist\":\"\",\"album\":\"\"}")
                }
                var res: JSONObject? = null; var err = ""
                for (k in 0 until keys.size.coerceAtMost(3)) {
                    try {
                        val r = Api.generate(model, keys[k % keys.size], pr, wav, 1024, 0.2, true)
                        res = JSONObject(r.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()); break
                    } catch (e: Throwable) { err = e.message ?: "" }
                }
                if (res == null) { onDone(null, "جيميناي ما ردّش: $err"); return@Thread }
                val title = res.optString("title").trim(); val artist = res.optString("artist").trim()
                if (title.isEmpty()) onDone(null, "مش متأكد من الأغنية دي (هجرّب بمقطع تاني)")
                else onDone(SongMatch(title, artist, res.optString("album").trim()), "")
            } catch (e: Throwable) { LogStore.err("songid-geminiclip", e); onDone(null, "فشل: " + (e.message ?: "")) }
        }.apply { isDaemon = true }.start()
    }
}
