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

/** سجل الفيديوهات المصطادة (web_videos.json) + لقطة مصغّرة لكل فيديو + التعرف على الاسم من اللقطة بـ Gemini */
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
    fun register(ctx: Context, id: String, url: String, ref: String, ua: String, cookie: String, title0: String, kind: String): WebVid = synchronized(lock) {
        val l = parse(try { file(ctx).readText() } catch (_: Exception) { "" })
        val old = l.firstOrNull { it.id == id }
        val w = WebVid(id, url, ref, ua, cookie, old?.title?.takeIf { it.isNotBlank() } ?: title0, old?.named ?: false, old?.tries ?: 0, System.currentTimeMillis(), kind)
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

    private const val PROMPT = "أمامك لقطة شاشة واحدة من فيديو. المطلوب: اسم العمل (فيلم / مسلسل / أنمي / برنامج) اللي اللقطة منه.\n" +
        "استخدم الدلائل الظاهرة فقط: عنوان أو لوجو أو واترمارك مكتوب، نص الترجمة أو الكابشن، أسلوب الرسم (للأنمي)، مشهد مشهور، اسم القناة/الموقع.\n" +
        "🚫 ممنوع تتعرف على أي شخص حقيقي من ملامح وجهه. لو الدليل الوحيد هو وجه شخص، رجّع العنوان فاضي.\n" +
        "لو مش متأكد، رجّع العنوان فاضي — التخمين الغلط أسوأ من ما يتعرفش.\n" +
        "الاسم يتكتب بالإنجليزي الأصلي لو العمل أجنبي (ومعاه الاسم العربي بين قوسين لو معروف)، وبالعربي لو العمل عربي.\n" +
        "═ الإخراج JSON فقط ═\n{\"title\":\"اسم العمل\",\"year\":\"2019\",\"confidence\":0.8}\nلا شرح ولا مقدمة — JSON فقط."

    /** الاسم من لقطة JPEG. المفاتيح الاحتياطية الأول (عشان ما يستهلكش كوتة الترجمة)، وبعدها مفاتيح الوضع البصري، وبعدها الأساسية */
    fun identify(jpeg: ByteArray): String? {
        val conf = Cfg.snapshot()
        val ks = (conf.backup + conf.visKeys + conf.keys).map { it.trim() }.filter { it.length > 10 }.distinct()
        for (k in ks) {
            try {
                val r = Api.generateImage(conf.model, k, PROMPT, jpeg, 300, 0.0)
                val j = Parse.json(r.text) ?: continue
                val t = j.optString("title", "").trim()
                val c = j.optDouble("confidence", 0.0)
                if (t.isEmpty() || t.equals("null", true) || t.equals("unknown", true) || c < 0.45) return null
                val y = j.optString("year", "").trim().takeIf { it.length == 4 && it.all { ch -> ch.isDigit() } }
                return if (y != null && !t.contains(y)) "$t ($y)" else t
            } catch (_: Exception) { /* المفتاح ده فشل (كوتة/شبكة) — جرّب اللي بعده */ }
        }
        return null
    }
}
