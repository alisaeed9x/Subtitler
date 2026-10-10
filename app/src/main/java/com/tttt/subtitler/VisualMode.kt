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

/**
 * الوضع البصري: بياخد فريم كل ثانيتين قدّام مكان التشغيل، يبعته لـ Gemini، ويعرض النصوص المترجمة فوق الفيديو في مكانها.
 * (v181) ML Kit اتشال بالكامل: الاعتماد الأساسي على Gemini (مفتاح الوضع البصري فقط).
 */
class VisualMode(
    private val conf: Conf,
    private val position: () -> Double,
    private val retriever: () -> MediaMetadataRetriever?,
    private val say: (String) -> Unit,
    private val changed: () -> Unit
) {
    companion object {
        private val active = java.util.concurrent.atomic.AtomicInteger(0)
        /** (v154) true لو الوضع البصري شغّال دلوقتي — التنقيح الجزئي مابيستعملش مفاتيح الصور وقتها */
        fun anyActive(): Boolean = active.get() > 0
        const val STEP = 2.0            // ثانية بين كل فريم والتاني
        const val AHEAD = 30.0          // أقصى مسافة قدّام مكان التشغيل
        const val WIN_SEC = 10.0        // (v181) طول نافذة الفريمات المبعوتة لجيميناي
        const val OVERLAP = 2.0         // (v181) التداخل بين نافذة والتانية
        /** (v181) كثافة الفريمات: 1 أو 2 أو 3 في الثانية (جيميناي بيحلل الفيديو بفريم تقريبًا في الثانية فأكتر من 3 مالوش لازمة) */
        /** (v181) تشغيل تلقائي مع فتح أي فيديو (لو فيه مفتاح للوضع البصري) — الافتراضي شغّال */
        fun auto(): Boolean = try { Cfg.bool("vis_auto", true) } catch (_: Throwable) { true }
        fun fps(): Int = try { Cfg.int("vis_fps", 1).coerceIn(1, 3) } catch (_: Throwable) { 1 }
        const val WAIT_429 = 30_000L
        const val REST_MIN = 4_000L     // (v181) أقل راحة بين نافذة والتانية
        const val REST_MAX = 20_000L
        const val SNAP_DUR = 6.0        // ثواني عرض نتيجة اللقطة

        const val PROMPT_WINDOW = "أنت نظام OCR وترجمة بصري متخصص للفيديو. أمامك مجموعة فريمات متتالية من فيديو، وقبل كل فريم وقته بالثواني على خط زمن الفيديو.\n" +
            "المطلوب: كل نص غير عربي ظاهر على الشاشة (لافتات، محلات، أسماء شوارع، نصوص شاشات، كتابات على ملابس/أغراض، عناوين وكروت نصية) ترجمه ورجّع له مكانه ووقت ظهوره ووقت اختفائه.\n" +
            "🚫 تجاهل تمامًا: أي ترجمة عربية جاهزة/محروقة أسفل أو أعلى الفيديو، شعار القناة/الواترمارك الثابت، واجهة المشغّل، وأي نص أقل من حرفين.\n" +
            "⏱ قواعد التوقيت (إلزامية):\n" +
            "- appear = وقت أول فريم بيبقى فيه النص كامل وواضح ومستقر في مكانه. disappear = وقت أول فريم النص مش موجود فيه (لو لسه ظاهر في آخر فريم اكتب وقت آخر فريم + الفرق بين فريمين، وكمان continues=true).\n" +
            "- 🔴 لو النص لسه بيتحرك (بيتزحلق، بيكبر، بيتلاشى، بيدخل من حافة الشاشة): استنى لحد ما يثبت في مكانه، وخلّي appear عند لحظة ما يثبت، وx/y/w/h هما مكانه بعد الثبات مش وهو بيتحرك. لو ما ثبتش خالص في الفريمات دي (لسه بيتحرك في آخرها) ماترجّعوش، هيتشاف في النافذة اللي بعدها.\n" +
            "- لو النص بيتحرك باستمرار (كريدت بيطلع لفوق): خد الفريم اللي بيبان فيه كامل ومقروء، وحط مكانه فيه.\n" +
            "- نفس النص الظاهر في أكتر من فريم = عنصر واحد بس. نفس العبارة لو ظهرت تاني بعد ما اختفت = عنصر جديد.\n" +
            "- الأوقات بالثواني بنفس خط الزمن المكتوب قبل الفريمات (أرقام عشرية).\n" +
            "═ قواعد الموضع (نسبة من 0 إلى 1) ═\n" +
            "x=مركز النص أفقياً (0=يسار→1=يمين) | y=مركز النص رأسياً (0=أعلى→1=أسفل) | w=عرض النص÷عرض الصورة | h=ارتفاع النص÷ارتفاع الصورة | angle=زاوية الميل (موجب=عكس عقارب الساعة)\n" +
            "═ قواعد الستايل ═\n" +
            "bg_hex: لون خلفية النص (لو لافتة/بوكس اكتب لونها، لو نص مباشر على المشهد اكتب \"#000000\" مع opacity_pct=0) | text_hex: لون النص | opacity_pct: شفافية الخلفية 0-100 | has_box: true لو جوه إطار/بوكس\n" +
            "═ الترجمة ═\nعربية فصحى مبسطة وقصيرة (بتتعرض فوق النص في مكانه). العلامات التجارية: كما هي. الأرقام والتواريخ كما هي. لو النص رموز أو مش مفهوم ماترجّعوش.\n" +
            "═ الإخراج JSON فقط ═\n" +
            "{\"texts\":[{\"appear\":12.5,\"disappear\":17.0,\"continues\":false,\"x\":0.75,\"y\":0.35,\"w\":0.22,\"h\":0.06,\"angle\":0,\"original\":\"OPEN 24H\",\"translated\":\"مفتوح ٢٤ ساعة\",\"lang\":\"en\",\"bg_hex\":\"#CC0000\",\"text_hex\":\"#FFFFFF\",\"opacity_pct\":90,\"has_box\":true}]}\n" +
            "لو مفيش نصوص: {\"texts\":[]}. لا شرح ولا مقدمة — JSON فقط."

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

    /** (v181) نص ظاهر بوقت ظهور واختفاء (من نوافذ الفريمات) */
    private class TItem(val box: VisBox, var start: Double, var end: Double)
    private val items = java.util.concurrent.CopyOnWriteArrayList<TItem>()
    private var cacheKey = ""
    private var cacheList: List<VisBox> = emptyList()

    fun clear() {
        frames.clear(); items.clear(); sent = 0; cacheKey = ""; cacheList = emptyList()
    }

    /** لقطة واحدة: بتتبعت لـ Gemini والنصوص المترجمة بتتعرض على الفيديو في مكانها */
    fun snap(bmp: Bitmap, nowSec: () -> Double, onDone: () -> Unit = {}) {
        frames.removeIf { it.dur == SNAP_DUR }   // (v104) لقطة جديدة = نتيجة اللقطة القديمة تتشال فورًا
        Thread {
            try { snapWork(bmp, nowSec) } catch (e: Exception) { say("⚠ " + (e.message ?: "").take(80)) }
            finally { try { onDone() } catch (_: Exception) {} }
        }.also { it.isDaemon = true; it.start() }
    }
    private fun snapWork(bmp: Bitmap, nowSec: () -> Double) {
        val keys = keyList()
        if (keys.isEmpty()) { say("ضيف مفتاح للوضع البصري (الإعدادات ← مفتاح الوضع البصري فقط)"); return }
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
                frames.removeIf { Math.abs(it.t - t) < 0.5 || it.dur == SNAP_DUR }
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
        th = Thread { active.incrementAndGet(); try { loop() } catch (e: Exception) { status = "⚠ " + (e.message ?: "").take(80); say(status) } finally { running = false; active.decrementAndGet() } }.also { it.isDaemon = true; it.start() }
    }
    fun stop() { running = false; th?.interrupt(); th = null }

    /** (v141) الوضع البصري بياخد من «مفتاح الوضع البصري فقط» وبس — مفيش رجوع للاحتياطي ولا الأساسي أبدًا، عشان ما يستهلكش كوتة الترجمة */
    private fun keyList(): List<String> = conf.visKeys.filter { it.length > 10 }.distinct()

    private fun loop() {
        val r = retriever() ?: run { status = "المصدر ده مش مدعوم للوضع البصري (m3u8/ملف غير قابل للقراءة)"; say(status); return }
        val keys = keyList()
        if (keys.isEmpty()) { say("ضيف مفتاح للوضع البصري (الإعدادات ← مفتاح الوضع البصري فقط)"); try { r.release() } catch (_: Exception) {}; return }
        val durSec = try { (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0 } catch (_: Exception) { 0.0 }
        val fps = fps(); val step = 1.0 / fps
        var w0 = Math.floor(position())
        var ki = 0; var fails = 0
        // (v181) راحة بين الطلبات عشان خطأ 429: فاصل أدنى بعد كل نافذة، وبيزيد لو جيميناي رفض وبيرجع يقل، وكل مفتاح اتوقف عنده بياخد راحة لوحده
        var restMs = REST_MIN
        val cool = HashMap<String, Long>()
        try {
            while (running) {
                val pos = position()
                // اليوزر قفز (قدّام أو ورا) أو إحنا متأخرين عن التشغيل: نبدأ من مكان التشغيل
                if (pos > w0 + WIN_SEC - 1.0 || pos < w0 - AHEAD - 1.0) w0 = Math.floor(pos)
                if (durSec > 0 && w0 >= durSec - 0.5) { status = "👁 النوافذ خلصت لحد آخر الفيديو"; Thread.sleep(1000); continue }
                if (w0 > pos + AHEAD) { Thread.sleep(500); continue }
                val wEnd = if (durSec > 0) minOf(w0 + WIN_SEC, durSec) else w0 + WIN_SEC
                val fr = ArrayList<Pair<Double, ByteArray>>()
                var t = w0
                while (t < wEnd - 0.01 && running) {
                    val bmp = try { r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                    if (bmp != null) { fr.add(Pair(t, toJpeg(bmp, 640, 68))); bmp.recycle() }
                    t += step
                }
                if (fr.size < 2) { w0 += WIN_SEC - OVERLAP; Thread.sleep(200); continue }
                status = "👁 يحلل ${"%.0f".format(w0)}–${"%.0f".format(wEnd)}ث (${fr.size} فريم)…"; say(status)
                // نختار مفتاح مش في راحة؛ لو كلهم في راحة نستنى أقربهم
                var key = ""
                while (running) {
                    val now = System.currentTimeMillis()
                    val free = keys.indices.map { keys[(ki + it) % keys.size] }.firstOrNull { (cool[it] ?: 0L) <= now }
                    if (free != null) { key = free; break }
                    val wait = (keys.minOf { cool[it] ?: 0L } - now).coerceIn(500L, 65_000L)
                    status = "😴 راحة ${wait / 1000}ث عشان الكوتة (429)"; say(status)
                    Thread.sleep(minOf(wait, 5_000L))
                }
                if (key.isEmpty()) break
                try {
                    val res = Api.generateFrames(conf.model, key, PROMPT_WINDOW, fr)
                    val parsed = parseTimed(res.text, w0, wEnd, step)
                    if (parsed == null) {
                        fails++; say("👁 الرد مش واضح — بحاول تاني")
                        if (fails >= 3) { fails = 0; w0 += WIN_SEC - OVERLAP }
                        Thread.sleep(500); continue
                    }
                    fails = 0
                    addTimed(parsed)
                    say("👁 نافذة ${"%.0f".format(w0)}–${"%.0f".format(wEnd)}ث: ${parsed.size} نص")
                    sent++
                    status = "👁 $sent نافذة | ${items.size} نص"
                    changed()
                    w0 += WIN_SEC - OVERLAP
                    Thread.sleep(restMs); restMs = maxOf(REST_MIN, restMs * 8 / 10)
                } catch (e: ApiErr) {
                    if (e.code == 429) {
                        val sec = Regex("(\\d+(?:\\.\\d+)?)\\s*s").find(e.message ?: "")?.groupValues?.get(1)?.toDoubleOrNull()
                        val ms = ((sec ?: 60.0) * 1000).toLong().coerceIn(15_000L, 120_000L)
                        cool[key] = System.currentTimeMillis() + ms
                        restMs = minOf(REST_MAX, restMs * 2)
                        status = "⏸ 429 على …${key.takeLast(4)} — راحة ${ms / 1000}ث"; say(status); ki++
                    }
                    else if (e.code == 403) { ki++; if (ki > keys.size * 2) { say("المفاتيح كلها مرفوضة (${e.message?.take(80)})"); return } }
                    else { say("👁 خطأ ${e.code}: " + (e.message ?: "").take(110)); ki++; fails++; if (fails >= 3) { fails = 0; w0 += WIN_SEC - OVERLAP }; Thread.sleep(1000) }
                } catch (e: java.io.IOException) {
                    say("👁 مشكلة اتصال — بحاول تاني"); Thread.sleep(1500)
                }
            }
        } catch (_: InterruptedException) {
        } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** (v181) بيضيف نتايج نافذة: نفس النص (أو فيه الآخر) والفترتين متلامستين = نفس الظهور فنمدّد المدة بدل ما نكرره */
    private fun addTimed(list: List<TItem>) {
        for (n in list) {
            val nk = TextTracker.norm(n.box.original)
            if (nk.isEmpty()) continue
            var hit: TItem? = null
            for (e in items) {
                val ek = TextTracker.norm(e.box.original)
                val same = ek == nk || (minOf(ek.length, nk.length) >= 4 && (ek.contains(nk) || nk.contains(ek))) || TextTracker.sim(ek, nk) >= 0.85
                if (same && n.start <= e.end + 1.0 && n.end >= e.start - 1.0) { hit = e; break }
            }
            if (hit == null) items.add(n) else { hit.start = minOf(hit.start, n.start); hit.end = maxOf(hit.end, n.end) }
        }
    }

    /** null = الرد مش JSON مفهوم (يتعاد) */
    private fun parseTimed(txt: String, w0: Double, wEnd: Double, step: Double): List<TItem>? {
        val j = Parse.json(txt) ?: return null
        val a = j.optJSONArray("texts") ?: return if (j.has("texts")) emptyList() else null
        val out = ArrayList<TItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val b = boxOf(o) ?: continue
            var ap = o.optDouble("appear", Double.NaN); var dis = o.optDouble("disappear", Double.NaN)
            if (ap.isNaN()) ap = w0
            ap = ap.coerceIn(w0, wEnd)
            if (dis.isNaN() || dis <= ap) dis = ap + 1.5
            dis = dis.coerceAtMost(wEnd + step * 2)
            if (dis <= ap) dis = ap + step
            out.add(TItem(b, ap, dis))
        }
        return out
    }

    private fun toJpeg(b: Bitmap, maxSide: Int = 1024, q: Int = 82): ByteArray {
        val m = maxOf(b.width, b.height); val sc = if (m > maxSide) maxSide.toFloat() / m else 1f
        val s = if (sc < 1f) Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true) else b
        val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.JPEG, q, o)
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

    private fun boxOf(o: JSONObject): VisBox? {
        val tr = o.optString("translated").trim(); val og = o.optString("original").trim()
        if (tr.isEmpty() || og.isEmpty()) return null
        return VisBox(frac(o.optDouble("x", 0.5), 0.5), frac(o.optDouble("y", 0.5), 0.5),
            frac(o.optDouble("w", 0.15), 0.15).coerceIn(0.03f, 1f), frac(o.optDouble("h", 0.04), 0.04).coerceIn(0.02f, 0.5f),
            o.optDouble("angle", 0.0).toFloat().coerceIn(-45f, 45f), og, tr, col(o.optString("bg_hex"), Color.BLACK), col(o.optString("text_hex"), Color.WHITE), o.optInt("opacity_pct", 80).coerceIn(0, 100), o.optBoolean("has_box"))
    }

    /** null = الرد مش JSON مفهوم (يتعاد)، قائمة فاضية = JSON سليم من غير نصوص */
    private fun parseOrNull(txt: String): List<VisBox>? {
        val j = Parse.json(txt) ?: return null
        val out = ArrayList<VisBox>()
        j.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) { a.optJSONObject(i)?.let { o -> boxOf(o)?.let { out.add(it) } } } }
        j.optJSONArray("lines")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val tr = o.optString("translated").trim(); val og = o.optString("original").trim()
            if (tr.isEmpty() || og.isEmpty()) continue
            val y = frac(o.optDouble("y", 0.88), 0.88).coerceIn(0.05f, 0.97f); val h = frac(o.optDouble("h", 0.09), 0.09).coerceIn(0.05f, 0.3f)
            out.add(VisBox(0.5f, y, 0.9f, h, 0f, og, tr, Color.BLACK, Color.WHITE, 90, true))
        } }
        return out
    }

    /** نتايج اللقطات اليدوية / وضع Gemini الكامل (فريم + مدة) */
    private fun frameBoxes(sec: Double): List<VisBox> {
        var f: VisFrame? = null
        for (x in frames) if (x.t <= sec + 0.3 && (f == null || x.t > f.t)) f = x
        return if (f != null && sec <= f.t + f.dur) f.boxes else emptyList()
    }

    /**
     * اللي يتعرض عند الثانية دي. sec = وقت اللقطات اليدوية (بعد تزامن الترجمة)، media = وقت الفيديو الفعلي:
     * نصوص النوافذ بتظهر من وقت ظهورها لوقت اختفائها بالظبط (مش مدة ثابتة).
     */
    fun boxesAt(sec: Double, media: Double = sec): List<VisBox> {
        val base = frameBoxes(sec)
        if (items.isEmpty()) return base
        val act = items.filter { media >= it.start - 0.05 && media <= it.end }
        if (act.isEmpty()) return base
        if (base.isNotEmpty()) return base + act.map { it.box }
        val key = act.joinToString(",") { System.identityHashCode(it.box).toString() }
        if (key != cacheKey) { cacheKey = key; cacheList = act.map { it.box } }
        return cacheList   // نفس القايمة طول ما مفيش تغيير عشان الطبقة ماتعيدش الرسم كل 100ms
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
