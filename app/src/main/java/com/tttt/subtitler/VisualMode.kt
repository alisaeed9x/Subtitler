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
 * (v175) وضع «على الجهاز»: ML Kit بيراقب الفيديو طول الوقت (فريم كل ثانية قدّام مكان التشغيل) وبيلقط أي نص ظاهر
 * ما عدا الجزء السفلي (هارد ساب)، وبيبعت النص لجيميناي (مفتاح الوضع البصري) يترجمه، والترجمة بتتعرض في مكان النص
 * من وقت ظهوره لحد وقت اختفائه (TextTracker) — مش مدة ثابتة.
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
        const val AHEAD = 20.0          // أقصى مسافة قدّام مكان التشغيل
        const val WAIT_429 = 30_000L
        const val SNAP_DUR = 6.0        // ثواني عرض نتيجة اللقطة
        const val WATCH_STEP = 1.0      // (v175) ثانية بين كل لقطتين في وضع المراقبة على الجهاز

        const val PROMPT_TEXTS = "أنت مترجم نصوص ظاهرة على شاشة فيديو (لافتات، عناوين، كتابات على ملابس وأغراض، نصوص شاشات).\n" +
            "النصوص دي اتقرت من الفيديو بقارئ آلي (OCR) فممكن فيها غلطات قراءة بسيطة — صحّحها بالسياق.\n" +
            "ترجم كل نص للعربية الفصحى المبسطة وبإيجاز (الترجمة بتتعرض فوق الفيديو في نفس مكان النص، فخليها قصيرة وواضحة).\n" +
            "العلامات التجارية وأسماء الأعلام: ارجعها كما هي. الأرقام والتواريخ كما هي. لو النص رموز أو مش مفهوم ارجع \"\".\n" +
            "═ الإخراج JSON فقط ═\n{\"t\":[{\"id\":0,\"tr\":\"الترجمة\"}]}\nلا شرح ولا مقدمة — JSON فقط.\n" +
            "═ النصوص ═\n"

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

    // (v175) وضع المراقبة على الجهاز
    private val tracker = TextTracker(WATCH_STEP)
    private val scanned = java.util.Collections.synchronizedSet(HashSet<Long>())      // اللقطات اللي اتفحصت (رقم الثانية)
    private val tcache = java.util.concurrent.ConcurrentHashMap<String, String>()     // نص مطبّع ← ترجمة ("" = مفيش ترجمة صالحة)
    private val tq = java.util.concurrent.LinkedBlockingQueue<Pair<String, String>>() // (لغة، نص) مستني ترجمة
    private val queued = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val keyRR = java.util.concurrent.atomic.AtomicInteger(0)
    private var tth: Thread? = null
    private var liveKey = ""
    private var liveList: List<VisBox> = emptyList()

    fun clear() {
        frames.clear(); sent = 0
        tracker.clear(); scanned.clear(); tcache.clear(); tq.clear(); queued.clear(); liveKey = ""; liveList = emptyList()
    }

    /** لقطة واحدة: بتتبعت لـ Gemini والنصوص المترجمة بتتعرض على الفيديو في مكانها */
    fun snap(bmp: Bitmap, nowSec: () -> Double, onDone: () -> Unit = {}) {
        frames.removeIf { it.dur == SNAP_DUR }   // (v104) لقطة جديدة = نتيجة اللقطة القديمة تتشال فورًا
        Thread {
            try { snapWork(bmp, nowSec) } catch (e: Exception) { say("⚠ " + (e.message ?: "").take(80)) }
            finally { try { onDone() } catch (_: Exception) {} }
        }.also { it.isDaemon = true; it.start() }
    }
    /** (v174) من غير مفاتيح: ML Kit على الجهاز — لو الإعداد «على الجهاز» مفعّل أو مفيش مفتاح للوضع البصري */
    private fun offline() = OfflineVis.enabled() || keyList().isEmpty()
    private fun snapWork(bmp: Bitmap, nowSec: () -> Double) {
        if (offline()) {
            val hard = mode == "hardsub"
            val dets = try { OfflineVis.scan(bmp, hard) } finally { bmp.recycle() }
            resolve(dets.map { Pair(it.lang, it.text) })
            val boxes = dets.mapNotNull { d -> usable(d.text)?.let { OfflineVis.box(d, it, hard) } }
            val t = nowSec()
            frames.removeIf { Math.abs(it.t - t) < 0.5 || it.dur == SNAP_DUR }
            frames.add(VisFrame(t, boxes, SNAP_DUR)); sent++
            say(if (boxes.isEmpty()) "👁 مفيش نصوص واضحة في اللقطة" else "👁 اتترجم ${boxes.size} نص — اتعرض على الفيديو")
            changed(); return
        }
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
    fun stop() { running = false; th?.interrupt(); th = null; tth?.interrupt(); tth = null }

    /** (v141) الوضع البصري بياخد من «مفتاح الوضع البصري فقط» وبس — مفيش رجوع للاحتياطي ولا الأساسي أبدًا، عشان ما يستهلكش كوتة الترجمة */
    private fun keyList(): List<String> = conf.visKeys.filter { it.length > 10 }.distinct()

    private fun loop() {
        val r = retriever() ?: run { status = "المصدر ده مش مدعوم للوضع البصري (m3u8/ملف غير قابل للقراءة)"; say(status); return }
        val keys = keyList(); val off = offline()
        if (keys.isEmpty() && !off) { say("ضيف مفتاح للوضع البصري (الإعدادات ← مفتاح الوضع البصري فقط)"); return }
        if (off) {
            try { watchLoop(r) } catch (_: InterruptedException) {} finally { try { r.release() } catch (_: Exception) {}; tth?.interrupt() }
            return
        }
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

    /** (v175) هل النص ده ليه ترجمة صالحة للعرض؟ (مش فاضية ومش نفس الأصل) */
    private fun usable(text: String): String? {
        val tr = tcache[TextTracker.norm(text)] ?: return null
        if (tr.isBlank() || TextTracker.norm(tr) == TextTracker.norm(text)) return null
        return tr
    }

    /** نتايج اللقطات اليدوية / وضع Gemini الكامل (فريم + مدة ثابتة) */
    private fun frameBoxes(sec: Double): List<VisBox> {
        var f: VisFrame? = null
        for (x in frames) if (x.t <= sec + 0.3 && (f == null || x.t > f.t)) f = x
        return if (f != null && sec <= f.t + f.dur) f.boxes else emptyList()
    }

    /**
     * اللي يتعرض عند الثانية دي. sec = وقت اللقطات اليدوية (بعد تزامن الترجمة)، media = وقت الفيديو الفعلي
     * (للنصوص اللي المراقبة لقطتها: بتظهر من أول ما النص يظهر وتختفي لما يختفي).
     */
    fun boxesAt(sec: Double, media: Double = sec): List<VisBox> {
        val base = frameBoxes(sec)
        if (tracker.tracks.isEmpty()) return base
        val live = ArrayList<VisBox>(); val sb = StringBuilder()
        for (k in tracker.activeAt(media)) {
            val tr = usable(k.text) ?: continue
            sb.append(k.id).append(':').append((k.x * 1000).toInt()).append(',').append((k.y * 1000).toInt()).append(':').append(tr.length).append(';')
            live.add(VisBox(k.x, k.y, k.w, k.h, 0f, k.text, tr, Color.BLACK, Color.WHITE, 85, true))
        }
        if (live.isEmpty()) return base
        if (base.isNotEmpty()) return base + live
        val key = sb.toString()
        if (key != liveKey) { liveKey = key; liveList = live }
        return liveList   // نفس القايمة طول ما مفيش تغيير عشان الطبقة ماتعيدش الرسم كل 100ms
    }

    // ===== (v175) المراقبة المستمرة: ML Kit يلقط ← جيميناي يترجم ← عرض من الظهور للاختفاء =====

    private fun want(lang: String, text: String) {
        val k = TextTracker.norm(text)
        if (k.isEmpty() || tcache.containsKey(k) || !queued.add(k)) return
        tq.offer(Pair(lang, text))
    }

    private fun watchLoop(r: MediaMetadataRetriever) {
        tth = Thread { try { transLoop() } catch (_: InterruptedException) {} }.also { it.isDaemon = true; it.start() }
        var prevSlot = Long.MIN_VALUE
        while (running) {
            val pos = position()
            var s = Math.floor(pos / WATCH_STEP).toLong()
            val endS = Math.floor((pos + AHEAD) / WATCH_STEP).toLong()
            while (s <= endS && scanned.contains(s)) s++
            if (s > endS) { Thread.sleep(400); continue }          // وصلنا لآخر مسافة قدّام التشغيل
            val t = s * WATCH_STEP
            val bmp = try { r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
            scanned.add(s)
            if (bmp == null) { prevSlot = Long.MIN_VALUE; Thread.sleep(150); continue }
            status = "👁 يراقب ${"%.0f".format(t)}ث…"
            val dets = try { OfflineVis.scan(bmp, mode == "hardsub") }
                catch (e: InterruptedException) { throw e }
                catch (e: Exception) { say("👁 خطأ: " + (e.message ?: "").take(110)); null }
                finally { bmp.recycle() }
            if (dets == null) { prevSlot = Long.MIN_VALUE; Thread.sleep(1500); continue }
            val prevT = if (prevSlot == s - 1) (s - 1) * WATCH_STEP else Double.NaN
            val fresh = tracker.feed(t, dets, prevT)
            for (k in fresh) { k.tries = 1; want(k.lang, k.text) }
            // نص ظاهر من غير ترجمة (الطلب فشل قبل كده) — نعيد الطلب لحد 3 مرات
            for (k in tracker.tracks) if (k.end.isNaN() && k.tries in 1..2 && !tcache.containsKey(TextTracker.norm(k.text)) && !queued.contains(TextTracker.norm(k.text))) { k.tries++; want(k.lang, k.text) }
            prevSlot = s; sent++
            status = "👁 يراقب | $sent لقطة | ${tracker.tracks.size} نص"
            changed()
        }
    }

    /** خيط الترجمة: بيجمّع النصوص الجديدة (لحد 8) في طلب واحد لجيميناي */
    private fun transLoop() {
        while (running) {
            val first = tq.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            val batch = ArrayList<Pair<String, String>>(); batch.add(first)
            tq.drainTo(batch, 7)
            try { resolve(batch) } finally { for (b in batch) queued.remove(TextTracker.norm(b.second)) }
            changed()
        }
    }

    /** بيملا tcache لقايمة (لغة، نص): جيميناي الأول (مفتاح الوضع البصري)، وللي مارجعش ترجمته ← ML Kit على الجهاز */
    private fun resolve(items: List<Pair<String, String>>) {
        val todo = items.filter { !tcache.containsKey(TextTracker.norm(it.second)) }.distinctBy { TextTracker.norm(it.second) }
        if (todo.isEmpty()) return
        val got = geminiTexts(todo.map { it.second })
        for ((i, p) in todo.withIndex()) {
            val k = TextTracker.norm(p.second)
            val g = got?.getOrNull(i)
            if (g != null && g.isNotBlank()) { tcache[k] = g; continue }
            val ml = try { OfflineVis.mlTranslate(p.first, p.second, say) }
                catch (e: InterruptedException) { throw e }
                catch (e: Exception) { say("👁 فشلت الترجمة: " + (e.message ?: "").take(90)); null }
            if (ml != null) tcache[k] = ml      // لو فشلت الاتنين ما بنكتبش حاجة فالمحاولة تتعاد
        }
    }

    /** طلب واحد لجيميناي بنصوص الدفعة؛ بيرجّع الترجمات بنفس الترتيب أو null لو فشل (أو مفيش مفتاح) */
    private fun geminiTexts(texts: List<String>): List<String>? {
        val keys = keyList(); if (keys.isEmpty()) return null
        val arr = org.json.JSONArray()
        for ((i, t) in texts.withIndex()) arr.put(JSONObject().put("id", i).put("text", t))
        val prompt = PROMPT_TEXTS + arr.toString()
        var attempts = 0
        while (!Thread.currentThread().isInterrupted && attempts < keys.size * 2 + 1) {
            attempts++
            val key = keys[Math.floorMod(keyRR.getAndIncrement(), keys.size)]
            try {
                val res = Api.generate(conf.model, key, prompt, null, 2048, 0.1, true)
                val a = Parse.json(res.text)?.optJSONArray("t")
                if (a == null) { say("👁 رد جيميناي مش واضح — بحاول تاني"); continue }
                val out = arrayOfNulls<String>(texts.size)
                for (j in 0 until a.length()) {
                    val o = a.optJSONObject(j) ?: continue
                    val id = o.optInt("id", -1)
                    if (id >= 0 && id < out.size) out[id] = o.optString("tr").trim()
                }
                return out.map { it ?: "" }
            } catch (e: ApiErr) {
                say("👁 خطأ ${e.code} على …${key.takeLast(4)}")
                if (e.code == 429 || e.code >= 500) Thread.sleep(700)
            } catch (e: java.io.IOException) { Thread.sleep(500) }
        }
        return null
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
