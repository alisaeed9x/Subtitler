package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * (v193) غلاف الأغنية (البوستر) من قواعد بيانات مجانية من غير مفتاح: iTunes Search أولًا، وبعدها Deezer.
 * بيدوّر باسم الأغنية + الفنان، ولازم اسم النتيجة يطابق اسمنا (عشان ما نجيبش غلاف غلط). الغلاف بيتخزن على الجهاز؛
 * ولو ما لقيناش حاجة بنفتكر ده 7 أيام عشان ما نكرّرش الطلب.
 */
object Poster {
    private fun dir(ctx: Context) = File(ctx.filesDir, "posters").apply { mkdirs() }
    private fun key(title: String, artist: String) = Integer.toHexString((clean(title).lowercase() + "|" + artist.trim().lowercase()).hashCode())

    /** تنضيف اسم ملف/عنوان: شيل الأقواس والـ _ والأرقام اللي في الأول */
    fun clean(s: String): String = s.replace('_', ' ')
        .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
        .replace(Regex("^\\s*\\d{1,3}\\s*[-.)]\\s*"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun matches(found: String, want: String): Boolean {
        val a = norm(found); val b = norm(want)
        if (a.length < 2 || b.length < 2) return false
        return a == b || (minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a)))
    }

    fun forget(ctx: Context, title: String, artist: String) {
        val k = key(title, artist)
        try { File(dir(ctx), "$k.jpg").delete(); File(dir(ctx), "$k.none").delete() } catch (_: Throwable) {}
    }

    private fun http(url: String, max: Int = 2_000_000): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 10000; c.setRequestProperty("User-Agent", "Subtitler/1.93")
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }.takeIf { it.size in 1..max }
        } catch (_: Throwable) { null } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** كل الأغلفة المطابقة بالترتيب (iTunes ثم Deezer) */
    private fun findUrls(title: String, artist: String): List<String> {
        val q = (artist + " " + title).trim()
        val out = ArrayList<String>()
        try {
            val txt = http("https://itunes.apple.com/search?media=music&entity=song&limit=15&term=${enc(q)}", 500_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("results") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("trackName"), title)) continue
                if (artist.isNotBlank() && !matches(o.optString("artistName"), artist)) continue
                val u = o.optString("artworkUrl100").replace("100x100bb", "600x600bb")
                if (u.isNotBlank() && u !in out) out.add(u)
            }
        } catch (_: Throwable) {}
        try {
            val txt = http("https://api.deezer.com/search?limit=15&q=${enc(q)}", 500_000)?.toString(Charsets.UTF_8)
            val arr = txt?.let { JSONObject(it).optJSONArray("data") }
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!matches(o.optString("title"), title)) continue
                if (artist.isNotBlank() && !matches(o.optJSONObject("artist")?.optString("name").orEmpty(), artist)) continue
                val u = o.optJSONObject("album")?.optString("cover_big").orEmpty()
                if (u.isNotBlank() && u !in out) out.add(u)
            }
        } catch (_: Throwable) {}
        return out
    }

    private fun skipKey(title: String, artist: String) = "pskip_" + key(title, artist)
    /** «البوستر غلط»: ننسى الغلاف الحالي وننزل للي بعده. بيرجّع false لو مفيش تاني */
    fun reject(ctx: Context, title0: String, artist0: String): Boolean {
        val title = clean(title0); val artist = artist0.trim()
        forget(ctx, title, artist)
        val n = (Cfg.str(skipKey(title, artist), "0").toIntOrNull() ?: 0) + 1
        Cfg.put(skipKey(title, artist), n.toString())
        return true
    }
    fun resetSkip(title0: String, artist0: String) { Cfg.put(skipKey(clean(title0), artist0.trim()), "0") }

    /** على خيط خلفية. بيرجّع الغلاف أو null */
    fun fetchSync(ctx: Context, title0: String, artist0: String): Bitmap? {
        val title = clean(title0); val artist = artist0.trim()
        if (title.length < 2) return null
        val k = key(title, artist); val jpg = File(dir(ctx), "$k.jpg"); val none = File(dir(ctx), "$k.none")
        try {
            if (jpg.exists()) BitmapFactory.decodeFile(jpg.path)?.let { return it }
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < 7L * 86400_000L) return null
            val skip = Cfg.str(skipKey(title, artist), "0").toIntOrNull() ?: 0
            val url = findUrls(title, artist).getOrNull(skip)
            val bytes = url?.let { http(it) }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) { jpg.writeBytes(bytes); return bmp }
            none.writeText("x")
        } catch (e: Throwable) { LogStore.err("poster", e) }
        return null
    }
}
