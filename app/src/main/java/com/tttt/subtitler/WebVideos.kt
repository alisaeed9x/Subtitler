package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** فيديو اتصاد من الإنترنت: الرابط + هيدرزه + الاسم (اتعرف من لقطة) — id هو نفس videoId بتاع المشغّل عشان التقدم والترجمة يتربطوا بيه */
data class WebVid(
    val id: String, val url: String, val ref: String, val ua: String, val cookie: String,
    val title: String, val named: Boolean, val tries: Int, val ts: Long, val kind: String
)

/** سجل الفيديوهات المصطادة (web_videos.json) + لقطة مصغّرة لكل فيديو (الاسم بيتاخد من صفحة الموقع مش من اللقطة) */
object WebVideos {
    private val lock = Any()
    private const val MAX = 300

    private fun file(ctx: Context) = File(ctx.filesDir, "web_videos.json")
    fun thumbFile(ctx: Context, id: String): File = File(File(ctx.filesDir, "webthumbs"), Store.keyFor(id) + ".jpg")

    private fun parse(s: String): List<WebVid> = try {
        val a = JSONArray(s.ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            WebVid(it.optString("id"), it.optString("url"), it.optString("ref"), it.optString("ua"), it.optString("cookie"),
                it.optString("title"), it.optBoolean("named", false), it.optInt("tries", 0), it.optLong("ts", 0), it.optString("kind"))
        }.filter { it.id.isNotEmpty() && it.url.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    private fun toJson(l: List<WebVid>): String {
        val a = JSONArray()
        for (w in l) a.put(JSONObject().put("id", w.id).put("url", w.url).put("ref", w.ref).put("ua", w.ua).put("cookie", w.cookie)
            .put("title", w.title).put("named", w.named).put("tries", w.tries).put("ts", w.ts).put("kind", w.kind))
        return a.toString()
    }

    fun load(ctx: Context): List<WebVid> = synchronized(lock) {
        parse(try { file(ctx).readText() } catch (_: Exception) { "" }).sortedByDescending { it.ts }
    }

    private fun write(ctx: Context, l: List<WebVid>) { try { file(ctx).writeText(toJson(l.sortedByDescending { it.ts }.take(MAX))) } catch (_: Exception) {} }

    /** تسجيل فيديو اتفتح: لو موجود قبل كده بيحتفظ باسمه ويحدّث الرابط والهيدرز (الكوكيز بتتجدد) */
    fun register(ctx: Context, id: String, url: String, ref: String, ua: String, cookie: String, title0: String, kind: String, named0: Boolean = false): WebVid = synchronized(lock) {
        val l = parse(try { file(ctx).readText() } catch (_: Exception) { "" })
        val old = l.firstOrNull { it.id == id }
        val w = WebVid(id, url, ref, ua, cookie, if (old?.named == true) old.title else title0, old?.named == true || named0, old?.tries ?: 0, System.currentTimeMillis(), kind)
        write(ctx, listOf(w) + l.filter { it.id != id })
        w
    }

    fun update(ctx: Context, id: String, f: (WebVid) -> WebVid) = synchronized(lock) {
        val l = parse(try { file(ctx).readText() } catch (_: Exception) { "" })
        write(ctx, l.map { if (it.id == id) f(it) else it })
    }

    fun find(ctx: Context, id: String): WebVid? = synchronized(lock) { parse(try { file(ctx).readText() } catch (_: Exception) { "" }).firstOrNull { it.id == id } }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        val l = parse(try { file(ctx).readText() } catch (_: Exception) { "" })
        write(ctx, l.filter { it.id != id })
        try { thumbFile(ctx, id).delete() } catch (_: Exception) {}
    }

    /** لقطة مصغّرة (عرض 320) بتتحفظ JPEG وبترجّع البايتات (اللي هتتبعت لـ Gemini) */
    fun saveThumb(ctx: Context, id: String, bmp: Bitmap): ByteArray? = try {
        val w = 480; val h = (bmp.height * (w.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
        val sc = Bitmap.createScaledBitmap(bmp, w, h, true)
        val bo = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 80, bo)
        if (sc !== bmp) sc.recycle()
        val bytes = bo.toByteArray()
        val f = thumbFile(ctx, id); f.parentFile?.mkdirs(); f.writeBytes(bytes)
        bytes
    } catch (_: Throwable) { null }
}
