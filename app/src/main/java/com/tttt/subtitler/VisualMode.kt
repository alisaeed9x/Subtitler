package com.tttt.subtitler

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** نص ظاهر على الشاشة (لافتة/عنوان/هاردسب) بموضعه ونسبه (0..1) وستايله */
class VisBox(
    val x: Float, val y: Float, val w: Float, val h: Float, val angle: Float,
    val original: String, val translated: String, val bg: Int, val fg: Int, val opacity: Int, val hasBox: Boolean
)
class VisFrame(val t: Double, val boxes: List<VisBox>, val dur: Double = VisualMode.STEP + 0.6)

/** الوضع البصري: بياخد فريم كل ثانيتين قدّام مكان التشغيل، يبعته لـ Gemini، ويعرض النصوص المترجمة فوق الفيديو في مكانها */
class VisualMode(
    private val conf: Conf,
    private val position: () -> Double,
    private val retriever: () -> MediaMetadataRetriever?,
    private val say: (String) -> Unit,
    private val changed: () -> Unit
) {
    companion object {
        const val STEP = 2.0            // ثانية بين كل فريم والتاني
        const val AHEAD = 20.0          // أقصى مسافة قدّام مكان التشغيل
        const val WAIT_429 = 30_000L
        const val SNAP_DUR = 8.0        // ثواني عرض نتيجة اللقطة

        const val PROMPT_SCENE = "أنت نظام OCR وترجمة بصري متخصص للفيديو. أمامك فريم واحد من فيديو.\n" +
            "🔍 افحص الصورة بأقصى دقة ممكنة — النصوص أحياناً صغيرة ويسهل تفويتها.\n" +
            "🚫🚫 ممنوع منعاً باتاً: أي نص عربي مكتوب كترجمة أسفل أو أعلى الفيديو (الترجمة الجاهزة/الحرقة/hardsub) — تجاهله تمامًا.\n" +
            "✅ ابحث عن أي نص غير عربي أياً كان نوعه ولغته (إنجليزي، ياباني، كوري، صيني، فرنسي...): لافتات، محلات، أسماء شوارع/مبانٍ، نصوص شاشات/تلفزيونات، كتابات على ملابس/أغراض/سيارات، عناوين/كروت نصية/كابشن على الفيديو نفسه.\n" +
            "❌ تجاهل: النص العربي الحرقة، شعار القناة/الواترمارك، واجهة المشغّل.\n" +
            "═ قواعد الموضع (نسبة من 0 إلى 1) ═\n" +
            "x=مركز النص أفقياً (0=يسار→1=يمين) | y=مركز النص رأسياً (0=أعلى→1=أسفل) | w=عرض النص÷عرض الصورة | h=ارتفاع النص÷ارتفاع الصورة | angle=زاوية الميل (موجب=عكس عقارب الساعة)\n" +
            "═ قواعد الستايل ═\n" +
            "bg_hex: لون خلفية النص hex (لو بوكس/لافتة اكتب لونها، لو نص مباشر على المشهد اكتب \"#000000\" مع opacity_pct=0)\n" +
            "text_hex: لون النص hex | opacity_pct: شفافية الخلفية 0-100 | has_box: true لو جوه إطار/بوكس\n" +
            "═ الترجمة ═\nعربية فصحى مبسطة. العلامات التجارية: كما هي + الترجمة بين قوسين. الأرقام والتواريخ كما هي.\n" +
            "═ الإخراج JSON فقط ═\n" +
            "{\"texts\":[{\"x\":0.75,\"y\":0.35,\"w\":0.22,\"h\":0.06,\"angle\":0,\"original\":\"OPEN 24H\",\"translated\":\"مفتوح ٢٤ ساعة\",\"lang\":\"en\",\"bg_hex\":\"#CC0000\",\"text_hex\":\"#FFFFFF\",\"opacity_pct\":90,\"has_box\":true}]}\n" +
            "لو مفيش نصوص: {\"texts\":[]}. لا شرح ولا مقدمة — JSON فقط."

        const val PROMPT_HARDSUB = "أنت نظام OCR متخصص في قراءة الترجمة المحروقة (hardsub) الموجودة بالفعل على فيديو. أمامك فريم واحد.\n" +
            "اقرأ نص الترجمة الهاردسب الظاهر فعليًا (سطر أو سطرين غالبًا أسفل أو أعلى الفيديو) وترجمه: §DIALECT§\n" +
            "🚫 ممنوع قراءة أو ترجمة أي نص تاني غير الهاردسب نفسه (لافتات، شعارات، عناوين، كتابة على الملابس…) إلا لو كان بنفس لغة الهاردسب بالظبط.\n" +
            "🚫 لو مفيش هاردسب ظاهر في الفريم، رجّع قائمة فاضية.\n" +
            "§LANG§\n" +
            "أعطني كمان موضع سطر الهاردسب: y=مركز السطر رأسياً (0=أعلى→1=أسفل)، h=ارتفاع الكتلة÷ارتفاع الصورة.\n" +
            "═ الإخراج JSON فقط ═\n{\"lines\":[{\"original\":\"النص كما ظهر\",\"translated\":\"الترجمة العربية\",\"y\":0.88,\"h\":0.09}]}\nلا شرح ولا مقدمة — JSON فقط."
    }

    @Volatile var running = false
        private set
    @Volatile var mode = "scene"          // scene | hardsub
    @Volatile var hardLang = ""
    @Volatile var status = ""
        private set
    private val frames = java.util.concurrent.CopyOnWriteArrayList<VisFrame>()
    private var th: Thread? = null
    var sent = 0; private set

    fun clear() { frames.clear(); sent = 0 }

    /** لقطة واحدة: بتتبعت لـ Gemini والنصوص المترجمة بتتعرض على الفيديو في مكانها */
    fun snap(bmp: Bitmap, nowSec: () -> Double, onDone: () -> Unit = {}) {
        Thread {
            try { snapWork(bmp, nowSec) } catch (e: Exception) { say("⚠ " + (e.message ?: "").take(80)) }
            finally { try { onDone() } catch (_: Exception) {} }
        }.also { it.isDaemon = true; it.start() }
    }
    private fun snapWork(bmp: Bitmap, nowSec: () -> Double) {
        val keys = keyList()
        if (keys.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        val jpeg = toJpeg(bmp); bmp.recycle()
        val prompt = if (mode == "hardsub") PROMPT_HARDSUB.replace("§DIALECT§", "بلهجة ${conf.lang} وأسلوب ${conf.style}")
            .replace("§LANG§", if (hardLang.isBlank()) "لغة الهاردسب: اكتشفها تلقائيًا من السطر المحروق." else "لغة الهاردسب في هذا الفيديو هي: \"$hardLang\" — اقرأ فقط الأسطر المكتوبة بيها.") else PROMPT_SCENE
        var last = ""
        var attempts = 0; var emptyTries = 0
        loop@ for (round in 0 until 3) for (key in keys) {
            if (attempts++ >= 6) break@loop
            try {
                val res = Api.generateImage(conf.model, key, prompt, jpeg)
                val boxes = parseOrNull(res.text)
                if (boxes == null) { last = "رد Gemini مش مفهوم"; say("👁 الرد مش واضح — بحاول تاني"); Thread.sleep(400); continue }
                // رد فاضي مرة واحدة ممكن يكون عشوائية من الموديل — نعيد مرة قبل ما نقول «مفيش نصوص»
                if (boxes.isEmpty() && emptyTries++ < 1) { say("👁 مفيش نصوص — محاولة تانية للتأكد"); Thread.sleep(300); continue }
                val t = nowSec()   // الفيديو واقف: النتيجة بتتعرض من لحظة اللقطة
                frames.removeIf { Math.abs(it.t - t) < 0.5 }
                frames.add(VisFrame(t, boxes, SNAP_DUR)); sent++
                say(if (boxes.isEmpty()) "👁 مفيش نصوص واضحة في اللقطة" else "👁 اتترجم ${boxes.size} نص — اتعرض على الفيديو")
                changed(); return
            } catch (e: ApiErr) { last = e.message ?: ""; say("👁 خطأ ${e.code} على …${key.takeLast(4)}"); if (e.code == 429) Thread.sleep(1500) }
            catch (e: java.io.IOException) { last = e.message ?: "مشكلة اتصال"; say("👁 مشكلة اتصال — بحاول تاني"); Thread.sleep(800) }
        }
        say("👁 فشل: " + last.take(110))
    }

    fun start() {
        stop(); running = true
        th = Thread { try { loop() } catch (e: Exception) { status = "⚠ " + (e.message ?: "").take(80); say(status) } finally { running = false } }.also { it.isDaemon = true; it.start() }
    }
    fun stop() { running = false; th?.interrupt(); th = null }

    /** الوضع البصري: مفتاحه الخاص أولًا (إعداد «مفتاح الوضع البصري فقط»)، وبعده الاحتياطية، وبعده الأساسية — عشان ما يستهلكش كوتة الترجمة */
    private fun keyList(): List<String> {
        val v = conf.visKeys.filter { it.isNotBlank() }.distinct()
        if (v.isNotEmpty()) return v
        val b = conf.backup.filter { it.isNotBlank() }.distinct()
        return if (b.isNotEmpty()) b else conf.keys.filter { it.isNotBlank() }.distinct()
    }

    private fun loop() {
        val r = retriever() ?: run { status = "المصدر ده مش مدعوم للوضع البصري (m3u8/ملف غير قابل للقراءة)"; say(status); return }
        val keys = keyList(); if (keys.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        var next = Math.floor(position())
        var ki = 0
        try {
            while (running) {
                val pos = position()
                if (next < pos - 1.0) next = Math.floor(pos)
                if (next > pos + AHEAD) { Thread.sleep(500); continue }
                val bmp = try { r.getFrameAtTime((next * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                if (bmp == null) { next += STEP; Thread.sleep(200); continue }
                val jpeg = toJpeg(bmp); bmp.recycle()
                status = "👁 يحلل ${"%.0f".format(next)}ث…"
                try {
                    val key = keys[ki % keys.size]
                    val prompt = if (mode == "hardsub") PROMPT_HARDSUB.replace("§DIALECT§", "بلهجة ${conf.lang} وأسلوب ${conf.style}")
                        .replace("§LANG§", if (hardLang.isBlank()) "لغة الهاردسب: اكتشفها تلقائيًا من السطر المحروق." else "لغة الهاردسب في هذا الفيديو هي: \"$hardLang\" — اقرأ فقط الأسطر المكتوبة بيها.") else PROMPT_SCENE
                    val res = Api.generateImage(conf.model, key, prompt, jpeg)
                    val boxes = parse(res.text)
                    frames.add(VisFrame(next, boxes)); sent++
                    status = "👁 $sent فريم | ${boxes.size} نص"
                    changed()
                    next += STEP
                } catch (e: ApiErr) {
                    if (e.code == 429) { status = "⏸ ${e.message?.take(90)} — انتظار 30ث"; say(status); ki++; Thread.sleep(WAIT_429) }
                    else if (e.code == 403) { ki++; if (ki > keys.size * 2) { say("المفاتيح كلها مرفوضة (${e.message?.take(80)})"); return } }
                    else { say("👁 خطأ: " + (e.message ?: "").take(110)); next += STEP; Thread.sleep(1000) }
                }
            }
        } catch (_: InterruptedException) {
        } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun toJpeg(b: Bitmap): ByteArray {
        val m = maxOf(b.width, b.height); val sc = if (m > 1024) 1024f / m else 1f
        val s = if (sc < 1f) Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true) else b
        val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, 82, o)
        if (s !== b) s.recycle()
        return o.toByteArray()
    }

    private fun col(h: String?, def: Int): Int = try { Color.parseColor((h ?: "").trim()) } catch (_: Exception) { def }

    private fun parse(txt: String): List<VisBox> = parseOrNull(txt) ?: emptyList()

    /** Gemini ساعات بيرجّع الإحداثيات 0-100 أو 0-1000 بدل 0-1 — بنرجّعها لنسبة صحيحة عشان النص مايطلعش بره الفيديو */
    private fun frac(v: Double, def: Double): Float {
        if (v.isNaN()) return def.toFloat()
        val n = when { v > 1.5 && v <= 100.0 -> v / 100.0; v > 100.0 -> v / 1000.0; else -> v }
        return n.coerceIn(0.0, 1.0).toFloat()
    }

    /** null = الرد مش JSON مفهوم (يتعاد)، قائمة فاضية = JSON سليم من غير نصوص */
    private fun parseOrNull(txt: String): List<VisBox>? {
        val j = Parse.json(txt) ?: return null
        val out = ArrayList<VisBox>()
        j.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val tr = o.optString("translated").trim(); val og = o.optString("original").trim()
            if (tr.isEmpty() || og.isEmpty()) continue
            out.add(VisBox(frac(o.optDouble("x", 0.5), 0.5), frac(o.optDouble("y", 0.5), 0.5),
                frac(o.optDouble("w", 0.15), 0.15).coerceIn(0.03f, 1f), frac(o.optDouble("h", 0.04), 0.04).coerceIn(0.02f, 0.5f),
                o.optDouble("angle", 0.0).toFloat().coerceIn(-45f, 45f), og, tr, col(o.optString("bg_hex"), Color.BLACK), col(o.optString("text_hex"), Color.WHITE), o.optInt("opacity_pct", 80).coerceIn(0, 100), o.optBoolean("has_box")))
        } }
        j.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val tr = o.optString("translated").trim(); val og = o.optString("original").trim()
            if (tr.isEmpty() || og.isEmpty()) continue
            val y = frac(o.optDouble("y", 0.88), 0.88).coerceIn(0.05f, 0.97f); val h = frac(o.optDouble("h", 0.09), 0.09).coerceIn(0.05f, 0.3f)
            out.add(VisBox(0.5f, y, 0.9f, h, 0f, og, tr, Color.BLACK, Color.WHITE, 90, true))
        } }
        return out
    }

    /** الفريم الشغّال عند الثانية دي (آخر فريم بدأ قبلها ولسه ما عدّتش مدته) */
    fun boxesAt(sec: Double): List<VisBox> {
        var f: VisFrame? = null
        for (x in frames) if (x.t <= sec + 0.3 && (f == null || x.t > f.t)) f = x
        return if (f != null && sec <= f.t + f.dur) f.boxes else emptyList()
    }
}

/** طبقة رسم فوق الفيديو: بترسم نصوص الوضع البصري في مواضعها (نسب من حدود الفيديو الفعلية) */
class VisualOverlay(ctx: Context) : View(ctx) {
    var boxes: List<VisBox> = emptyList()
    var area: () -> RectF = { RectF(0f, 0f, width.toFloat(), height.toFloat()) }
    private val bgP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    fun showBoxes(b: List<VisBox>) { if (b !== boxes && !(b.isEmpty() && boxes.isEmpty())) { boxes = b; invalidate() } }
    private fun lum(c: Int) = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    override fun onDraw(c: Canvas) {
        if (boxes.isEmpty()) return
        val r = area(); val d = resources.displayMetrics.density
        if (r.width() < 20f || r.height() < 20f) return
        for (b in boxes) drawBox(c, r, d, b)
    }

    /** بيرسم النص جوه حدود الفيديو دايمًا: الخط بيصغر والمربع بيتزحزح لحد ما المستطيل (بعد الميل) يبقى كله جوه الفيديو */
    private fun drawBox(c: Canvas, r: RectF, d: Float, b: VisBox) {
        val rad = Math.toRadians(b.angle.toDouble())
        val ca = Math.abs(Math.cos(rad)).toFloat(); val sa = Math.abs(Math.sin(rad)).toFloat()
        val margin = 4f * d; val pad = 4f * d
        val maxW = r.width() - 2 * margin; val maxH = r.height() - 2 * margin
        var bw = minOf(maxOf(b.w * r.width() * 1.1f, 70f * d), maxW * 0.95f)
        val bhT = maxOf(b.h * r.height(), 14f * d)
        var ts = minOf(bhT * 0.8f, r.height() * 0.12f).coerceAtLeast(9f * d)
        tp.color = b.fg
        var lay: StaticLayout
        var boxH: Float; var bbW: Float; var bbH: Float
        while (true) {
            tp.textSize = ts
            lay = StaticLayout.Builder.obtain(b.translated, 0, b.translated.length, tp, bw.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
            boxH = maxOf(bhT, lay.height.toFloat())
            val rw = bw + 2 * pad; val rh = boxH + 2 * pad
            bbW = rw * ca + rh * sa; bbH = rw * sa + rh * ca
            val fits = bbW <= maxW && bbH <= maxH && lay.height <= bhT * 1.25f
            if (fits || ts <= 9f * d) break
            ts *= 0.9f
        }
        // آخر حارس: لو لسه أكبر من الفيديو بعد أصغر خط، نصغّر الرسمة كلها
        val sc = minOf(1f, maxW / bbW, maxH / bbH)
        val sW = bbW * sc; val sH = bbH * sc
        val cx = (r.left + b.x * r.width()).coerceIn(r.left + margin + sW / 2, r.right - margin - sW / 2)
        val cy = (r.top + b.y * r.height()).coerceIn(r.top + margin + sH / 2, r.bottom - margin - sH / 2)
        // ألوان: خلفية تغطي الأصلي + تباين كافي للنص
        val hasFill = b.opacity > 0
        val bg = if (hasFill) b.bg else Color.BLACK
        val alpha = if (hasFill) maxOf(b.opacity * 255 / 100, 225) else 175
        var fg = b.fg
        if (Math.abs(lum(fg) - lum(bg)) < 0.4f) fg = if (lum(bg) > 0.5f) Color.BLACK else Color.WHITE
        tp.color = fg; bgP.color = bg; bgP.alpha = alpha
        // لازم نعيد بناء الـ layout بعد تغيير اللون مش مطلوب (اللون بيتقري وقت draw)
        c.save(); c.translate(cx, cy); c.rotate(-b.angle); c.scale(sc, sc)
        val rect = RectF(-bw / 2 - pad, -boxH / 2 - pad, bw / 2 + pad, boxH / 2 + pad)
        c.drawRoundRect(rect, 6f * d, 6f * d, bgP)
        c.translate(-bw / 2, -lay.height / 2f); lay.draw(c)
        c.restore()
    }
}
