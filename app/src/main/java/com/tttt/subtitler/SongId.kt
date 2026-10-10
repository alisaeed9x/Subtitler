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
}
