package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** مستطيل بنسب 0..1 من أبعاد الفيديو */
class FRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun str() = "$l,$t,$r,$b"
    companion object {
        fun parse(s: String): FRect? {
            val a = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
            return if (a.size == 4) FRect(a[0], a[1], a[2], a[3]) else null
        }
    }
}

/** أدوات مشتركة لطلبات الصور (بتستخدم «مفتاح الوضع البصري فقط» زي VisualMode عشان ماتاكلش كوتة الترجمة) */
object FxApi {
    fun frac(v: Double, def: Double): Float {
        if (v.isNaN()) return def.toFloat()
        val n = when { v > 1.5 && v <= 100.0 -> v / 100.0; v > 100.0 -> v / 1000.0; else -> v }
        return n.coerceIn(0.0, 1.0).toFloat()
    }

    fun jpeg(b: Bitmap, maxSide: Int = 1024, q: Int = 80): ByteArray {
        val m = maxOf(b.width, b.height)
        val sc = if (m > maxSide) maxSide.toFloat() / m else 1f
        val s = if (sc < 1f) Bitmap.createScaledBitmap(b, (b.width * sc).toInt().coerceAtLeast(1), (b.height * sc).toInt().coerceAtLeast(1), true) else b
        val o = java.io.ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, q, o)
        if (s !== b) s.recycle()
        return o.toByteArray()
    }

    /** بيجرّب لحد 3 مفاتيح بالترتيب؛ null لو الرد مش JSON مفهوم أو فشل الاتصال */
    fun ask(prompt: String, jpeg: ByteArray, keys: List<String>, startAt: Int): JSONObject? {
        if (keys.isEmpty()) return null
        val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
        for (n in 0 until minOf(keys.size, 3)) {
            val key = keys[(startAt + n) % keys.size]
            try {
                val res = Api.generateImage(model, key, prompt, jpeg, 2000, 0.0)
                val j = Parse.json(res.text)
                if (j != null) return j
            } catch (e: ApiErr) {
                if (e.code == 429) { try { Thread.sleep(1500) } catch (_: InterruptedException) { return null } }
            } catch (e: java.io.IOException) {
                try { Thread.sleep(600) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }
}

// ===================== تغطية الهاردساب القديم =====================

object CoverDetect {
    const val PROMPT = "أمامك فريم واحد من فيديو. المطلوب: هل فيه ترجمة محروقة (hardsub) على الصورة؟ يعني سطر أو سطرين نص ثابت للحوار مكتوب فوق الفيديو (بأي لغة)، غالبًا في الأسفل.\n" +
        "🚫 مش المقصود: لافتات جوه المشهد، شعار القناة، عناوين الحلقة، واجهة المشغّل، كتابة على ملابس أو أغراض.\n" +
        "لو فيه، رجّع المستطيل اللي بيغطي كل سطور الترجمة المحروقة بنسب من 0 إلى 1 من أبعاد الصورة (left, top, right, bottom).\n" +
        "JSON فقط: {\"found\":true,\"left\":0.1,\"top\":0.84,\"right\":0.9,\"bottom\":0.96} أو {\"found\":false}"

    /** بياخد 6 فريمات موزّعة على الفيديو، ولو 2 على الأقل لقوا هاردساب بياخد الوسيط (median) للمستطيل */
    fun detect(r: MediaMetadataRetriever, durSec: Double, keys: List<String>): FRect? {
        val pts = doubleArrayOf(0.12, 0.27, 0.42, 0.57, 0.72, 0.87)
        val hits = ArrayList<FRect>()
        for ((i, p) in pts.withIndex()) {
            val bmp = (try { r.getFrameAtTime((durSec * p * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }) ?: continue
            val jpg = FxApi.jpeg(bmp); bmp.recycle()
            val j = FxApi.ask(PROMPT, jpg, keys, i) ?: continue
            if (!j.optBoolean("found", false)) continue
            val l = FxApi.frac(j.optDouble("left", 0.05), 0.05)
            val t = FxApi.frac(j.optDouble("top", 0.85), 0.85)
            val rr = FxApi.frac(j.optDouble("right", 0.95), 0.95)
            val b = FxApi.frac(j.optDouble("bottom", 0.97), 0.97)
            if (rr - l < 0.2f || b - t < 0.02f || b - t > 0.35f) continue
            hits.add(FRect(l, t, rr, b))
        }
        if (hits.size < 2) return null
        fun med(f: (FRect) -> Float): Float { val s = hits.map(f).sorted(); return s[s.size / 2] }
        return FRect(
            (med { it.l } - 0.012f).coerceAtLeast(0f), (med { it.t } - 0.014f).coerceAtLeast(0f),
            (med { it.r } + 0.012f).coerceAtMost(1f), (med { it.b } + 0.014f).coerceAtMost(1f)
        )
    }
}

/** طبقة بترسم صندوق فوق الهاردساب القديم (تحت ترجمتنا) */
class CoverView(ctx: Context) : View(ctx) {
    var rect: FRect? = null
    var alphaPct = 240
    var area: () -> RectF = { RectF(0f, 0f, width.toFloat(), height.toFloat()) }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(r: FRect?, a: Int) {
        if (rect !== r || alphaPct != a) { rect = r; alphaPct = a; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val r = rect ?: return
        val a = area()
        if (a.width() < 20f || a.height() < 20f) return
        val d = resources.displayMetrics.density
        p.color = Color.BLACK; p.alpha = alphaPct
        c.drawRoundRect(RectF(a.left + r.l * a.width(), a.top + r.t * a.height(), a.left + r.r * a.width(), a.top + r.b * a.height()), 6f * d, 6f * d, p)
    }
}

// ===================== فلتر المشاهد الحساسة =====================

class SceneRange(val s: Double, val e: Double, val type: String) {
    fun label() = when (type) { "violence" -> "عنف"; "intimate" -> "حميمي"; "scary" -> "مخيف"; else -> "حساس" }
}

/**
 * بيمسح الفيديو قدّام مكان التشغيل: كل 12 لقطة (لقطة كل 4 ثواني) في صورة واحدة مرقّمة بتتبعت لجيميناي،
 * واللقطات المعلّمة بتبقى مجالات تخطي. النتيجة بتتحفظ للفيديو. للفيديوهات اللي المصدر بتاعها قابل للقراءة (محلي / رابط مباشر) بس.
 */
class SceneSkip(
    private val retriever: () -> MediaMetadataRetriever?,
    private val position: () -> Double,
    private val duration: () -> Double,
    private val vid: String,
    private val say: (String) -> Unit
) {
    companion object {
        const val STEP = 4.0
        const val TILES = 12
        const val WIN = STEP * TILES
        const val AHEAD = 150.0
    }

    @Volatile private var ranges: List<SceneRange> = emptyList()
    private val done = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val tries = HashMap<Int, Int>()
    @Volatile private var running = false
    private var th: Thread? = null
    private val sig = (if (Extras.sceneViolence) "1" else "0") + (if (Extras.sceneIntimate) "1" else "0") + (if (Extras.sceneScary) "1" else "0")
    private val kR = "fx_scn_" + vid.hashCode() + "_" + sig
    private val kD = "fx_scd_" + vid.hashCode() + "_" + sig

    init { load() }

    private fun load() {
        try {
            val out = ArrayList<SceneRange>()
            for (x in Cfg.str(kR, "").split(";")) {
                val a = x.split(":")
                if (a.size != 3) continue
                val s = a[0].toDoubleOrNull() ?: continue
                val e = a[1].toDoubleOrNull() ?: continue
                out.add(SceneRange(s, e, a[2]))
            }
            ranges = out
            for (x in Cfg.str(kD, "").split(",")) x.trim().toIntOrNull()?.let { done.add(it) }
        } catch (_: Throwable) {}
    }

    private fun save() {
        try {
            Cfg.put(kR, ranges.joinToString(";") { String.format(java.util.Locale.US, "%.1f:%.1f:%s", it.s, it.e, it.type) })
            Cfg.put(kD, done.sorted().joinToString(","))
        } catch (_: Throwable) {}
    }

    fun rangeAt(t: Double): SceneRange? {
        for (r in ranges) if (t >= r.s && t < r.e - 0.25) return r
        return null
    }

    fun start() {
        stop(); running = true
        th = Thread {
            try { loop() }
            catch (_: InterruptedException) {}
            catch (e: Throwable) { say("⏭ فلتر المشاهد وقف: " + (e.message ?: "").take(80)) }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; th?.interrupt(); th = null }

    private fun allowed(ty: String) = when (ty) {
        "violence" -> Extras.sceneViolence
        "intimate" -> Extras.sceneIntimate
        "scary" -> Extras.sceneScary
        else -> false
    }

    private fun prompt(): String {
        val ty = ArrayList<String>()
        if (Extras.sceneViolence) ty.add("violence = عنف جسدي أو دم أو إصابة أو سلاح موجّه أو قتال عنيف")
        if (Extras.sceneIntimate) ty.add("intimate = قبلة أو حميمية أو لمس رومانسي أو مشهد جنسي أو عري أو ملابس فاضحة")
        if (Extras.sceneScary) ty.add("scary = رعب شديد أو وحش مخيف أو جثث")
        return "الصورة دي شبكة 3×4 من 12 لقطة مرقّمة من 1 إلى 12 (من الشمال لليمين ثم لتحت) من فيديو واحد، بين كل لقطة والتانية 4 ثواني.\n" +
            "علّم رقم أي لقطة فيها واحد من الأنواع دي بس:\n- " + ty.joinToString("\n- ") +
            "\nلو مش متأكد ماتعلّمش. لو مفيش لقطات: {\"flags\":[]}\nJSON فقط: {\"flags\":[{\"i\":3,\"type\":\"intimate\"}]}"
    }

    private fun loop() {
        val keys = Cfg.keys("viskeys").distinct()
        if (keys.isEmpty()) { say("ضيف مفتاح للوضع البصري عشان فلتر المشاهد يشتغل"); return }
        val r = retriever() ?: run { say("المصدر ده مش مدعوم لفلتر المشاهد (m3u8 / ملف غير قابل للقراءة)"); return }
        var ki = 0
        try {
            while (running) {
                val dur = duration()
                if (dur <= 0.0) { Thread.sleep(800); continue }
                val pos = position()
                val first = Math.floor(pos / WIN).toInt()
                val last = Math.floor(minOf(pos + AHEAD, dur - 1.0) / WIN).toInt()
                var next = -1
                var k = first
                while (k <= last) {
                    if (!done.contains(k)) { next = k; break }
                    k++
                }
                if (next < 0) { Thread.sleep(1500); continue }
                val ok = scanWindow(r, next, dur, keys, ki++)
                if (ok) { done.add(next); save() }
                else {
                    val n = (tries[next] ?: 0) + 1
                    tries[next] = n
                    if (n >= 3) { done.add(next); save() } else Thread.sleep(4000)
                }
            }
        } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun scanWindow(r: MediaMetadataRetriever, k: Int, dur: Double, keys: List<String>, ki: Int): Boolean {
        val t0 = k * WIN
        val tw = 320; val thh = 180
        val sheet = Bitmap.createBitmap(tw * 3, thh * 4, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.BLACK)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 34f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        var any = 0
        for (i in 0 until TILES) {
            val t = t0 + i * STEP
            if (t >= dur - 0.3) break
            val f = (try { r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }) ?: continue
            val col = i % 3; val row = i / 3
            val dst = RectF(col * tw.toFloat(), row * thh.toFloat(), (col + 1) * tw.toFloat(), (row + 1) * thh.toFloat())
            val sc = minOf(dst.width() / f.width, dst.height() / f.height)
            val w = f.width * sc; val h = f.height * sc
            val fit = RectF(dst.centerX() - w / 2f, dst.centerY() - h / 2f, dst.centerX() + w / 2f, dst.centerY() + h / 2f)
            c.drawBitmap(f, null, fit, null)
            f.recycle()
            c.drawText((i + 1).toString(), dst.left + 8f, dst.top + 36f, num)
            any++
        }
        if (any == 0) { sheet.recycle(); return true }
        val jpg = FxApi.jpeg(sheet, 1100, 78)
        sheet.recycle()
        val j = FxApi.ask(prompt(), jpg, keys, ki) ?: return false
        val fl = j.optJSONArray("flags")
        val fresh = ArrayList<SceneRange>()
        if (fl != null) for (q in 0 until fl.length()) {
            val o = fl.optJSONObject(q) ?: continue
            val i = o.optInt("i", 0)
            if (i < 1 || i > TILES) continue
            val ty = o.optString("type").lowercase().trim()
            if (!allowed(ty)) continue
            val t = t0 + (i - 1) * STEP
            fresh.add(SceneRange(Math.max(0.0, t - 0.8), Math.min(dur, t + STEP), ty))
        }
        if (fresh.isNotEmpty()) ranges = merged(ranges + fresh)
        return true
    }

    private fun merged(l0: List<SceneRange>): List<SceneRange> {
        val l = l0.sortedBy { it.s }
        val out = ArrayList<SceneRange>()
        for (x in l) {
            val last = out.lastOrNull()
            if (last != null && x.s <= last.e + 4.5) out[out.size - 1] = SceneRange(last.s, Math.max(last.e, x.e), last.type)
            else out.add(x)
        }
        return out
    }
}

// ===================== حارس الوشوش (موضع الترجمة الذكي) =====================

/**
 * بياخد لقطة صغيرة كل ~1.1 ثانية ويشغّل كاشف الوشوش المدمج في أندرويد (android.media.FaceDetector — من غير نت ولا API).
 * ملحوظة: الكاشف ده بيلقط الوشوش الحقيقية الأمامية بس، مش وشوش الأنمي/الكرتون.
 */
class FaceGuard(
    private val retriever: () -> MediaMetadataRetriever?,
    private val position: () -> Double,
    private val playing: () -> Boolean
) {
    @Volatile var bottomBusy = false
    @Volatile var topBusy = false
    @Volatile private var running = false
    private var th: Thread? = null

    fun start() {
        stop(); running = true
        th = Thread { try { loop() } catch (_: InterruptedException) {} catch (_: Throwable) {} }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; th?.interrupt(); th = null }

    private fun loop() {
        val r = retriever() ?: return
        try {
            while (running) {
                if (!playing()) { Thread.sleep(500); continue }
                val t = position() + 0.4
                val bmp = try { r.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                if (bmp != null) {
                    val w0 = 320
                    val h0 = Math.max(32, (bmp.height * (w0.toFloat() / bmp.width)).toInt())
                    val small = Bitmap.createScaledBitmap(bmp, w0, h0, true)
                    if (small !== bmp) bmp.recycle()
                    val b565 = small.copy(Bitmap.Config.RGB_565, false)
                    small.recycle()
                    if (b565 != null) {
                        val fd = FaceDetector(b565.width, b565.height, 4)
                        val faces = arrayOfNulls<FaceDetector.Face>(4)
                        val n = fd.findFaces(b565, faces)
                        var bot = false; var top = false
                        for (i in 0 until n) {
                            val f = faces[i] ?: continue
                            if (f.confidence() < 0.4f) continue
                            val p = PointF(); f.midPoint(p)
                            val e = f.eyesDistance()
                            if ((p.y + e * 2.4f) / b565.height > 0.72f) bot = true
                            if ((p.y - e * 1.8f) / b565.height < 0.28f) top = true
                        }
                        b565.recycle()
                        bottomBusy = bot; topBusy = top
                    }
                }
                Thread.sleep(1100)
            }
        } finally { try { r.release() } catch (_: Exception) {} }
    }
}

// ===================== المنسّق: بيربط كل ده بالمشغّل =====================

class Fx(
    private val act: Activity,
    private val box: FrameLayout,
    private val sub: View,
    private val area: () -> RectF,
    private val position: () -> Double,
    private val duration: () -> Double,
    private val playing: () -> Boolean,
    private val retriever: () -> MediaMetadataRetriever?,
    private val seek: (Double) -> Unit,
    private val say: (String) -> Unit,
    private val boxesAt: (Double) -> List<VisBox>
) {
    val cover = CoverView(act)
    private val skipBtn = TextView(act)
    private val ui = Handler(Looper.getMainLooper())
    private var vid = ""
    private var started = false
    private var scene: SceneSkip? = null
    private var sceneSig = ""
    private var face: FaceGuard? = null
    private var coverRect: FRect? = null
    private var coverChecked = false
    private var detecting = false
    private var curSkip: SceneRange? = null
    private var lastSkip = 0L
    private var lastSwitch = 0L
    private var lastEnsure = 0L
    private var wantTop = false
    private var lastDy = Float.NaN

    init {
        cover.area = area
        box.addView(cover, FrameLayout.LayoutParams(-1, -1))
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    /** بيتنادى بعد ما طبقة الإيماءات تتضاف عشان الزرار يستقبل اللمس */
    fun attachButton() {
        skipBtn.text = "⏭ تخطي"
        skipBtn.setTextColor(Color.WHITE)
        skipBtn.textSize = 14f
        skipBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
        skipBtn.visibility = View.GONE
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(0xDD1E2A3A.toInt()); bg.cornerRadius = dp(18).toFloat(); bg.setStroke(dp(1), 0x88FFFFFF.toInt())
        skipBtn.background = bg
        skipBtn.setOnClickListener {
            curSkip?.let { r -> seek(r.e + 0.3) }
            skipBtn.visibility = View.GONE
        }
        box.addView(skipBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(90); rightMargin = dp(16) })
    }

    fun start(videoId: String) {
        stopHelpers()
        vid = videoId; started = true
        coverChecked = false; detecting = false; lastEnsure = 0L; lastDy = Float.NaN
        val c = Cfg.str("fx_hsc_" + vid.hashCode(), "")
        if (c.isNotEmpty()) { coverChecked = true; coverRect = FRect.parse(c) } else coverRect = null
        ensure()
    }

    /** بعد حفظ الإعدادات */
    fun refresh() { stopHelpers(); sceneSig = ""; lastEnsure = 0L; lastDy = Float.NaN; if (started) ensure() }

    fun redetect() {
        Cfg.put("fx_hsc_" + vid.hashCode(), "")
        coverRect = null; coverChecked = false; lastEnsure = 0L
    }

    fun stop() {
        stopHelpers(); started = false
        try { sub.animate().cancel(); sub.translationY = 0f } catch (_: Throwable) {}
    }

    private fun stopHelpers() {
        scene?.stop(); scene = null
        face?.stop(); face = null
    }

    private fun ensure() {
        if (!started) return
        val now = System.currentTimeMillis()
        if (now - lastEnsure < 2000L) return
        lastEnsure = now
        val sig = (if (Extras.sceneViolence) "1" else "0") + (if (Extras.sceneIntimate) "1" else "0") + (if (Extras.sceneScary) "1" else "0")
        if (Extras.sceneMode == 0) { scene?.stop(); scene = null; sceneSig = "" }
        else if (scene == null || sceneSig != sig) {
            scene?.stop()
            scene = SceneSkip(retriever, position, duration, vid, say).also { it.start() }
            sceneSig = sig
        }
        if (!Extras.smartPos) { face?.stop(); face = null }
        else if (face == null) face = FaceGuard(retriever, position, playing).also { it.start() }
        if (Extras.coverMode > 0 && !coverChecked && !detecting && duration() > 20.0) detectCover()
    }

    private fun detectCover() {
        val keys = Cfg.keys("viskeys").distinct()
        if (keys.isEmpty()) { coverChecked = true; say("ضيف مفتاح للوضع البصري عشان كشف الهاردساب يشتغل"); return }
        detecting = true
        val myVid = vid
        Thread {
            var res: FRect? = null
            var okRun = false
            try {
                val r = retriever()
                if (r != null) {
                    try { res = CoverDetect.detect(r, duration(), keys); okRun = true }
                    finally { try { r.release() } catch (_: Exception) {} }
                }
            } catch (_: Throwable) {}
            ui.post {
                detecting = false
                if (myVid == vid) {
                    coverChecked = true
                    if (okRun) {
                        Cfg.put("fx_hsc_" + vid.hashCode(), res?.str() ?: "none")
                        coverRect = res
                        say(if (res != null) "🧽 لقيت ترجمة محروقة وغطّيتها" else "🧽 مفيش ترجمة محروقة ظاهرة")
                    } else say("🧽 مقدرتش أقرا الفيديو لكشف الهاردساب (المصدر مش مدعوم)")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** بيتنادى من حلقة الـ 200ms في المشغّل (على خيط الواجهة) */
    fun tick(posSec: Double) {
        if (!started) return
        ensure()
        val now = System.currentTimeMillis()
        cover.set(if (Extras.coverMode > 0) coverRect else null, if (Extras.coverMode == 1) 240 else 170)
        val mode = Extras.sceneMode
        val r = if (mode > 0) scene?.rangeAt(posSec) else null
        curSkip = r
        if (r != null && mode == 2) {
            if (playing() && now - lastSkip > 1200L) { lastSkip = now; seek(r.e + 0.3); say("⏭ اتخطّى مشهد ${r.label()}") }
            if (skipBtn.visibility != View.GONE) skipBtn.visibility = View.GONE
        } else if (r != null && mode == 1) {
            val t = "⏭ تخطي مشهد ${r.label()}"
            if (skipBtn.text.toString() != t) skipBtn.text = t
            if (skipBtn.visibility != View.VISIBLE) skipBtn.visibility = View.VISIBLE
        } else if (skipBtn.visibility != View.GONE) skipBtn.visibility = View.GONE
        placeSub(posSec, now)
    }

    /** بنحرّك الترجمة بـ translationY بس (من غير ما نلمس الـ layout بتاع المشغّل اللي بيغيّر الهوامش لوحده) */
    private fun placeSub(posSec: Double, now: Long) {
        if (sub.height <= 0 || box.height <= 0) return
        if (Extras.smartPos) {
            val busy = boxesAt(posSec).any { it.y + it.h / 2f > 0.74f } || (face?.let { it.bottomBusy && !it.topBusy } ?: false)
            if (busy != wantTop && now - lastSwitch > 2500L) { wantTop = busy; lastSwitch = now }
        } else wantTop = false
        var dy = 0f
        if (wantTop) dy = -(sub.top - dp(56)).toFloat()
        else {
            val cr = coverRect
            if (Extras.coverMode > 0 && cr != null && cr.t > 0.5f) {
                val a = area()
                val want = a.top + cr.b * a.height()
                dy = (want - sub.bottom - dp(2)).coerceIn(-dp(40).toFloat(), maxOf(0f, (box.height - sub.bottom - dp(2)).toFloat()))
            }
        }
        if (dy != lastDy) { lastDy = dy; sub.animate().translationY(dy).setDuration(160).start() }
    }
}

// ===================== شاشة الإعدادات =====================

object ExtrasUi {
    fun show(act: Activity, fx: Fx?, onSaved: () -> Unit) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)); layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun head(t: String) = TextView(act).apply {
            text = t; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(16), 0, dp(4))
        }
        fun note(t: String) = TextView(act).apply { text = t; textSize = 12f; alpha = 0.7f; setPadding(0, 0, 0, dp(4)) }
        fun radios(opts: List<String>, sel: Int): RadioGroup {
            val g = RadioGroup(act)
            opts.forEachIndexed { i, t -> g.addView(RadioButton(act).apply { text = t; id = 1000 + i; isChecked = i == sel }) }
            return g
        }
        fun idx(g: RadioGroup) = (g.checkedRadioButtonId - 1000).coerceAtLeast(0)

        col.addView(head("🔊 وضع الصم — وصف الأصوات"))
        val cbDeaf = CheckBox(act).apply { text = "اكتب [باب بيتفتح] [طلقة نار] [تليفون بيرن]…"; isChecked = Extras.deaf }
        col.addView(cbDeaf)
        col.addView(note("بيتطلب من جيميناي في المقاطع الجاية (اللي اتترجمت قبل كده ما بتتغيرش). بيظهر فوق الفيديو بخط مائل."))

        col.addView(head("⏭ فلتر المشاهد الحساسة"))
        val rgScene = radios(listOf("مقفول", "زرار «تخطي» يظهر وقت المشهد", "تخطي تلقائي"), Extras.sceneMode)
        col.addView(rgScene)
        val cbV = CheckBox(act).apply { text = "عنف ودم"; isChecked = Extras.sceneViolence }
        val cbI = CheckBox(act).apply { text = "قبلات وحميمية"; isChecked = Extras.sceneIntimate }
        val cbS = CheckBox(act).apply { text = "رعب شديد"; isChecked = Extras.sceneScary }
        col.addView(cbV); col.addView(cbI); col.addView(cbS)
        col.addView(note("بيحلل لقطات قدّام مكان التشغيل (طلب واحد لكل ~48 ثانية) بمفتاح «الوضع البصري فقط». للفيديوهات المحلية والروابط المباشرة بس (مش m3u8). بيشتغل على الصورة، فالصراخ والكلام من غير مشهد مش بيتلقط."))

        col.addView(head("🧽 تغطية الهاردساب القديم"))
        val rgCover = radios(listOf("مقفول", "صندوق غامق", "صندوق شفاف"), Extras.coverMode)
        col.addView(rgCover)
        col.addView(note("بيكشف مكان الترجمة المحروقة مرة واحدة للفيديو (6 لقطات) ويغطيها، وترجمتك بتتحط فوق الصندوق."))
        col.addView(android.widget.Button(act).apply {
            text = "🔄 إعادة كشف الهاردساب للفيديو ده"
            setOnClickListener { fx?.redetect(); android.widget.Toast.makeText(act, "هيعيد الكشف لما تحفظ", android.widget.Toast.LENGTH_SHORT).show() }
        })

        col.addView(head("🧭 موضع الترجمة الذكي"))
        val cbPos = CheckBox(act).apply { text = "انقل الترجمة فوق لو تحت وش أو نص"; isChecked = Extras.smartPos }
        col.addView(cbPos)
        col.addView(note("بيستخدم كاشف الوشوش المدمج في أندرويد (وشوش حقيقية بس، مش أنمي) + نصوص الوضع البصري لو شغّال."))

        col.addView(head("🔴 ترجمة حية فوق أي تطبيق"))
        col.addView(note("بيلقط صوت الجهاز ويعرض ترجمة عايمة. محتاج أندرويد 10+، وإذن العرض فوق التطبيقات، وبعض التطبيقات (نتفليكس وغيره) بتمنع التقاط الصوت."))
        col.addView(android.widget.Button(act).apply {
            text = "▶ شغّل الترجمة الحية"
            setOnClickListener { act.startActivity(Intent(act, LiveRequestActivity::class.java)) }
        })

        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("🎛 ميزات المشاهدة")
            .setView(ScrollView(act).apply { addView(col) })
            .setPositiveButton("حفظ") { _, _ ->
                Cfg.put("fx_deaf", if (cbDeaf.isChecked) "1" else "0")
                Cfg.put("fx_scene", idx(rgScene).toString())
                Cfg.put("fx_s_viol", if (cbV.isChecked) "1" else "0")
                Cfg.put("fx_s_int", if (cbI.isChecked) "1" else "0")
                Cfg.put("fx_s_scary", if (cbS.isChecked) "1" else "0")
                Cfg.put("fx_cover", idx(rgCover).toString())
                Cfg.put("fx_smartpos", if (cbPos.isChecked) "1" else "0")
                Extras.load()
                onSaved()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
