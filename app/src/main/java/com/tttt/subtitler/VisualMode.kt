package com.tttt.subtitler

import android.content.Context
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** نص ظاهر على الشاشة (لافتة/عنوان/هاردسب) بموضعه ونسبه (0..1) وستايله */
class VisBox(
    val x: Float, val y: Float, val w: Float, val h: Float, val angle: Float,
    val original: String, val translated: String, val bg: Int, val fg: Int, val opacity: Int, val hasBox: Boolean,
    /** (v185) شفافية الظهور/الاختفاء (fade) — 1 = كامل */
    val alpha: Float = 1f
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
    private val changed: () -> Unit,
    /** (v182) لقط فريم من شاشة المشغّل نفسها (PixelCopy) — للمصادر اللي MediaMetadataRetriever مابيقراهاش (m3u8) */
    private val grab: (() -> Bitmap?)? = null,
    /** (v185) قاريء مقاطع (MediaExtractor) لنفس مصدر الفيديو — لقص مقطع 10 ثواني وإرساله لجيميناي بدل الفريمات */
    private val clipSrc: (() -> MediaExtractor?)? = null,
    private val cacheDir: File? = null
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
        /** (v186) مصدر التحليل: «clip» مقاطع فيديو 10 ثواني (الافتراضي) أو «frames» فريمات (من الإعدادات) */
        fun useClip(): Boolean = try { Cfg.str("vis_src", "clip") != "frames" } catch (_: Throwable) { true }
        fun fps(): Int = try { Cfg.int("vis_fps", 2).coerceIn(1, 3) } catch (_: Throwable) { 2 }
        const val WAIT_429 = 30_000L
        const val REST_MIN = 4_000L     // (v181) أقل راحة بين نافذة والتانية
        const val REST_MAX = 20_000L
        const val SNAP_DUR = 6.0        // ثواني عرض نتيجة اللقطة
        const val FADE = 0.2            // (v185) ثواني الظهور/الاختفاء التدريجي
        const val CLIP_MAX = 15_000_000 // (v185) أقصى حجم مقطع بيتبعت inline (بعد كده بنرجع للفريمات للنافذة دي)

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

        /** (v185) مقطع فيديو 10 ثواني — الأوقات نسبية لبداية المقطع (0 = أول المقطع). فيه مسار حركة للنص المتحرك. */
        const val PROMPT_CLIP = "أنت نظام OCR وترجمة بصري متخصص للفيديو. أمامك مقطع فيديو قصير (حوالي 10 ثواني). كل الأوقات اللي ترجّعها بالثواني من بداية المقطع (0 = أول لحظة في المقطع) بأرقام عشرية.\n" +
            "المطلوب: ارصد كل نص غير عربي ظاهر على الشاشة في أي لحظة من المقطع — بدون استثناء ولا تلخيص: لافتات، محلات، أسماء شوارع/مبانٍ، نصوص شاشات وتلفزيونات وموبايلات، كتابات على ملابس/أغراض/سيارات، عناوين وكروت نصية وكابشن وتايتلات وكريدت — وترجمه ورجّع مكانه ووقت ظهوره ووقت اختفائه وحركته.\n" +
            "⚠ مهم جدًا: المقطع ممكن يكون فيه 5 لـ 10 نصوص أو أكتر (متتالية أو في نفس الوقت). لا تكتفي بأول نص أو اتنين — افحص المقطع كله من أوله لآخره أكتر من مرة وارصد حتى النصوص الصغيرة والسريعة (ثانية أو أقل). كل جملة/لافتة/سطر مستقل = عنصر منفصل في القائمة.\n" +
            "🚫 تجاهل تمامًا: أي ترجمة عربية جاهزة/محروقة (hardsub) أسفل أو أعلى الفيديو، شعار القناة/الواترمارك الثابت، واجهة المشغّل، وأي نص أقل من حرفين.\n" +
            "⏱ التوقيت:\n" +
            "- appear = أول لحظة النص بيبان فيها (حتى لو لسه بيدخل أو بيتحرك). disappear = أول لحظة النص مش موجود فيها. لو كان ظاهر من أول المقطع appear=0؛ لو لسه ظاهر في آخر المقطع disappear = مدة المقطع و continues=true.\n" +
            "- نفس النص الظاهر طول فترة = عنصر واحد بس. نفس العبارة لو ظهرت تاني بعد ما اختفت = عنصر جديد.\n" +
            "🎬 الحركة (الأنيميشن):\n" +
            "- motion: واحدة من static (ثابت) | slide (بيتزحلق/بيدخل من حافة) | scroll (كريدت/شريط بيمشي) | zoom (بيكبر أو بيصغر) | fade (بيظهر/بيختفي تدريجي) | pop (بيطلع فجأة).\n" +
            "- لو motion مش static: رجّع track = قائمة نقاط مفتاحية (من 3 لـ 12 نقطة) كل واحدة {t,x,y,w,h,angle} بتوصف مكان النص وحجمه في اللحظة t على طول فترة ظهوره (نقطة أول ظهور، نقط في النص كل ~0.3-0.5 ثانية لو الحركة مستمرة، ونقطة آخر ظهور). x,y,w,h,angle الرئيسية = أول نقطة في track.\n" +
            "- لو النص ثابت: ماترجّعش track.\n" +
            "═ قواعد الموضع (نسبة من 0 إلى 1) ═\n" +
            "x=مركز النص أفقياً (0=يسار→1=يمين) | y=مركز النص رأسياً (0=أعلى→1=أسفل) | w=عرض النص÷عرض الصورة | h=ارتفاع النص÷ارتفاع الصورة | angle=زاوية الميل (موجب=عكس عقارب الساعة)\n" +
            "═ قواعد الستايل ═\n" +
            "bg_hex: لون خلفية النص (لو لافتة/بوكس اكتب لونها، لو نص مباشر على المشهد اكتب \"#000000\" مع opacity_pct=0) | text_hex: لون النص | opacity_pct: شفافية الخلفية 0-100 | has_box: true لو جوه إطار/بوكس\n" +
            "═ الترجمة ═\nعربية فصحى مبسطة وقصيرة (بتتعرض فوق النص في مكانه). العلامات التجارية: كما هي. الأرقام والتواريخ كما هي. لو النص رموز أو مش مفهوم ماترجّعوش.\n" +
            "═ الإخراج JSON فقط ═\n" +
            "{\"texts\":[{\"appear\":1.2,\"disappear\":4.8,\"continues\":false,\"motion\":\"slide\",\"x\":0.75,\"y\":0.35,\"w\":0.22,\"h\":0.06,\"angle\":0,\"original\":\"OPEN 24H\",\"translated\":\"مفتوح ٢٤ ساعة\",\"lang\":\"en\",\"bg_hex\":\"#CC0000\",\"text_hex\":\"#FFFFFF\",\"opacity_pct\":90,\"has_box\":true,\"track\":[{\"t\":1.2,\"x\":0.95,\"y\":0.35,\"w\":0.22,\"h\":0.06,\"angle\":0},{\"t\":1.7,\"x\":0.75,\"y\":0.35,\"w\":0.22,\"h\":0.06,\"angle\":0}]}]}\n" +
            "لو مفيش نصوص: {\"texts\":[]}. لا شرح ولا مقدمة — JSON فقط."

        val PROMPT_FRAMES: String = PROMPT_CLIP.replace("مقطع فيديو قصير (حوالي 10 ثواني)", "مجموعة فريمات متتالية من فيديو (قبل كل فريم وقته بالثواني من بداية المجموعة)").replace("افحص المقطع كله", "افحص كل الفريمات")

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
    /** (v182) عدد جمل الوضع البصري (نصوص النوافذ + نتايج اللقطات اليدوية) — للعدّاد فوق الفيديو */
    val count: Int get() = items.size + frames.sumOf { it.boxes.size }
    private var grabFails = 0

    /** (v181) نص ظاهر بوقت ظهور واختفاء (من نوافذ الفريمات) */
    private class TItem(val box: VisBox, var start: Double, var end: Double, var track: List<DoubleArray>? = null)
    /** (v185) المدد اللي اتحلّلت فعلًا (a,b) — بتتحفظ مع الفيديو عشان مانعيدش تحليلها لما نرجع للفيديو */
    private val done = ArrayList<DoubleArray>()
    private var file: File? = null
    private var clipBad = false
    private var clipFails = 0
    private var extrasOk = true
    private val items = java.util.concurrent.CopyOnWriteArrayList<TItem>()
    private class Clip(val base: Double, val end: Double, val bytes: ByteArray)
    private var cacheKey = ""
    private var cacheList: List<VisBox> = emptyList()

    /** مسح كل النتائج (في الذاكرة وعلى الديسك) */
    fun clear() {
        frames.clear(); items.clear(); synchronized(done) { done.clear() }; sent = 0; cacheKey = ""; cacheList = emptyList()
        try { file?.delete() } catch (_: Throwable) {}
    }

    /** (v185) مسح من الثانية دي وقدّام بس (إعادة تحليل من مكان التشغيل) — اللي قبلها يفضل زي ما هو */
    fun clearFrom(sec: Double) {
        items.removeAll { it.end >= sec }
        frames.removeIf { it.t >= sec }
        synchronized(done) {
            val it2 = done.iterator()
            while (it2.hasNext()) { val r = it2.next(); if (r[0] >= sec) it2.remove() else if (r[1] > sec) r[1] = sec }
        }
        cacheKey = ""; cacheList = emptyList()
        save()
    }

    /** (v185) ربط الوضع البصري بملف الفيديو: بيحمّل النتايج المحفوظة (لو فيه) وكل نتيجة جديدة بتتحفظ فيه */
    fun bind(f: File) {
        file = f
        try { load() } catch (_: Throwable) {}
    }

    private fun boxJson(b: VisBox): JSONObject = JSONObject().put("x", b.x.toDouble()).put("y", b.y.toDouble()).put("w", b.w.toDouble()).put("h", b.h.toDouble())
        .put("a", b.angle.toDouble()).put("o", b.original).put("t", b.translated).put("bg", b.bg).put("fg", b.fg).put("op", b.opacity).put("hb", b.hasBox)
    private fun boxFrom(o: JSONObject): VisBox = VisBox(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(), o.optDouble("w", 0.15).toFloat(), o.optDouble("h", 0.04).toFloat(),
        o.optDouble("a", 0.0).toFloat(), o.optString("o"), o.optString("t"), o.optInt("bg", Color.BLACK), o.optInt("fg", Color.WHITE), o.optInt("op", 80), o.optBoolean("hb"))

    @Synchronized fun save() {
        val f = file ?: return
        try {
            val root = JSONObject().put("v", 1)
            val da = org.json.JSONArray(); synchronized(done) { for (r in done) da.put(org.json.JSONArray().put(r[0]).put(r[1])) }
            root.put("done", da)
            val ia = org.json.JSONArray()
            for (it in items) {
                val o = JSONObject().put("s", it.start).put("e", it.end).put("b", boxJson(it.box))
                it.track?.let { tr -> val ta = org.json.JSONArray(); for (k in tr) ta.put(org.json.JSONArray().put(k[0]).put(k[1]).put(k[2]).put(k[3]).put(k[4]).put(k[5])); o.put("k", ta) }
                ia.put(o)
            }
            root.put("items", ia)
            val fa = org.json.JSONArray()
            for (fr in frames) { val bs = org.json.JSONArray(); for (b in fr.boxes) bs.put(boxJson(b)); fa.put(JSONObject().put("t", fr.t).put("d", fr.dur).put("b", bs)) }
            root.put("snaps", fa)
            if (items.isEmpty() && frames.isEmpty() && done.isEmpty()) { f.delete(); return }
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp"); tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.writeText(root.toString(), Charsets.UTF_8); tmp.delete() }
        } catch (_: Throwable) {}
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        val root = JSONObject(f.readText(Charsets.UTF_8))
        items.clear(); frames.clear(); synchronized(done) { done.clear() }
        root.optJSONArray("done")?.let { a -> synchronized(done) { for (i in 0 until a.length()) { val r = a.optJSONArray(i) ?: continue; done.add(doubleArrayOf(r.optDouble(0), r.optDouble(1))) } } }
        root.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val b = o.optJSONObject("b") ?: continue
            val tr = o.optJSONArray("k")?.let { ka -> (0 until ka.length()).mapNotNull { j -> ka.optJSONArray(j)?.let { k -> doubleArrayOf(k.optDouble(0), k.optDouble(1), k.optDouble(2), k.optDouble(3), k.optDouble(4), k.optDouble(5)) } } }
            items.add(TItem(boxFrom(b), o.optDouble("s"), o.optDouble("e"), if (tr != null && tr.size >= 2) tr else null))
        } }
        root.optJSONArray("snaps")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val bs = o.optJSONArray("b") ?: continue
            frames.add(VisFrame(o.optDouble("t"), (0 until bs.length()).mapNotNull { j -> bs.optJSONObject(j)?.let { boxFrom(it) } }, o.optDouble("d", SNAP_DUR)))
        } }
    }

    /** ضم مدة جديدة للمدد اللي اتحلّلت (بيدمج المتلامس) */
    private fun markDone(a: Double, b: Double) {
        if (b <= a) return
        synchronized(done) {
            var na = a; var nb = b
            val it2 = done.iterator()
            while (it2.hasNext()) { val r = it2.next(); if (r[1] >= na - 0.05 && r[0] <= nb + 0.05) { na = minOf(na, r[0]); nb = maxOf(nb, r[1]); it2.remove() } }
            done.add(doubleArrayOf(na, nb))
        }
    }
    /** لو w داخل مدة اتحلّلت نقفز لآخرها (من غير ما نعيد تحليلها) */
    private fun skipDone(w: Double): Double {
        var x = w
        synchronized(done) { var moved = true; var n = 0; while (moved && n++ < 50) { moved = false; for (r in done) if (r[0] <= x + 0.05 && r[1] > x + 0.5) { x = r[1]; moved = true } } }
        return x
    }

    /** لقطة واحدة: بتتبعت لـ Gemini والنصوص المترجمة بتتعرض على الفيديو في مكانها */
    fun snap(bmp: Bitmap, nowSec: () -> Double, onDone: () -> Unit = {}) {
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
                frames.removeIf { Math.abs(it.t - t) < 0.5 }   // (v185) اللقطات بتتحفظ مع الفيديو: بنشيل بس اللي في نفس اللحظة
                frames.add(VisFrame(t, boxes, SNAP_DUR)); sent++; save()
                say(if (boxes.isEmpty()) "👁 مفيش نصوص واضحة في اللقطة" else "👁 اتترجم ${boxes.size} نص — اتعرض على الفيديو")
                changed(); return
            } catch (e: ApiErr) { last = e.message ?: ""; say("👁 خطأ ${e.code} على …${key.takeLast(4)}"); if (e.code == 429) Thread.sleep(1500) }
            catch (e: java.io.IOException) { last = e.message ?: "مشكلة اتصال"; say("👁 مشكلة اتصال — بحاول تاني"); Thread.sleep(800) }
        }
        say("👁 فشل: " + last.take(110))
    }

    fun start() {
        stop(); running = true
        val t = Thread {
            active.incrementAndGet()
            try { loop(); if (running) say("👁 الحلقة خلصت") }
            catch (e: Throwable) { status = "⚠ " + e.javaClass.simpleName + ": " + (e.message ?: "").take(80); say(status) }   // (v182) السبب بيتسجّل في اللوج دايمًا
            finally { if (th === Thread.currentThread()) running = false; active.decrementAndGet() }
        }
        t.isDaemon = true; th = t; t.start()
    }
    fun stop() { running = false; th?.interrupt(); th = null; save() }

    /** (v141) الوضع البصري بياخد من «مفتاح الوضع البصري فقط» وبس — مفيش رجوع للاحتياطي ولا الأساسي أبدًا، عشان ما يستهلكش كوتة الترجمة */
    private fun keyList(): List<String> = conf.visKeys.filter { it.length > 10 }.distinct()

    private fun loop() {
        val r = retriever()
        val live = r == null   // (v182) m3u8 / مصدر مش مقروء: بنلقط الفريمات من الشاشة وقت التشغيل
        if (live && grab == null) { status = "المصدر ده مش مدعوم للوضع البصري"; say(status); return }
        val keys = keyList()
        if (keys.isEmpty()) { say("ضيف مفتاح للوضع البصري (الإعدادات ← مفتاح الوضع البصري فقط)"); try { r?.release() } catch (_: Exception) {}; return }
        if (live) say("👁 المصدر m3u8 — بلقط الفريمات من الشاشة وقت التشغيل (النتيجة بتتأخر ~${WIN_SEC.toInt()}ث عن التشغيل)")
        val liveBuf = ArrayList<Pair<Double, ByteArray>>()
        val durSec = if (r == null) 0.0 else try { (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0 } catch (_: Exception) { 0.0 }
        val fps = fps(); val step = 1.0 / fps
        var w0 = Math.floor(position())
        // (v182) تقديم النافذة: في الوضع العادي w0 بيتزحزح، وفي الالتقاط الحي بنشيل الفريمات القديمة ونسيب آخر OVERLAP ثانية
        fun advance() {
            if (!live) { w0 += WIN_SEC - OVERLAP; return }
            val last = liveBuf.lastOrNull()?.first ?: return
            liveBuf.removeAll { it.first < last - OVERLAP }
        }
        var ki = 0; var fails = 0
        // (v181) راحة بين الطلبات عشان خطأ 429: فاصل أدنى بعد كل نافذة، وبيزيد لو جيميناي رفض وبيرجع يقل، وكل مفتاح اتوقف عنده بياخد راحة لوحده
        var restMs = REST_MIN
        val cool = HashMap<String, Long>()
        try {
            while (running) {
                var wEnd: Double
                val fr = ArrayList<Pair<Double, ByteArray>>()
                var clip: Clip? = null
                if (live) {
                    if (!collectLive(step, liveBuf)) break
                    fr.addAll(liveBuf); w0 = fr.first().first; wEnd = fr.last().first + step
                } else {
                    val pos = position()
                    // اليوزر قفز (قدّام أو ورا) أو إحنا متأخرين عن التشغيل: نبدأ من مكان التشغيل
                    if (pos > w0 + WIN_SEC - 1.0 || pos < w0 - AHEAD - 1.0) w0 = Math.floor(pos)
                    w0 = skipDone(w0)   // (v185) اللي اتحلّل قبل كده (محفوظ) مانعيدوش
                    if (durSec > 0 && w0 >= durSec - 0.5) { status = "👁 النوافذ خلصت لحد آخر الفيديو"; Thread.sleep(1000); continue }
                    if (w0 > pos + AHEAD) { Thread.sleep(500); continue }
                    wEnd = if (durSec > 0) minOf(w0 + WIN_SEC, durSec) else w0 + WIN_SEC
                    // (v185) الأول: مقطع فيديو 10 ثواني (جيميناي بيشوف الحركة والتوقيت بنفسه)؛ لو فشل القص بنرجع للفريمات
                    if (clipSrc != null && !clipBad && useClip()) {
                        try { clip = cutClip(w0, wEnd); if (clip != null) clipFails = 0 }
                        catch (e: InterruptedException) { throw e }
                        catch (e: Throwable) {
                            clipFails++; say("👁 قص المقطع فشل: " + e.javaClass.simpleName + " " + (e.message ?: "").take(60))
                            if (clipFails >= 3) { clipBad = true; say("👁 قص المقطع مش شغّال مع المصدر ده — رجعت للفريمات") }
                        }
                    }
                    var t = w0
                    while (clip == null && t < wEnd - 0.01 && running) {
                        val bmp = try { r!!.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                        if (bmp != null) { fr.add(Pair(t, toJpeg(bmp, 960, 78))); bmp.recycle() }
                        t += step
                    }
                }
                if (clip == null && fr.size < 2) { advance(); Thread.sleep(200); continue }
                status = "👁 يحلل ${"%.0f".format(w0)}–${"%.0f".format(wEnd)}ث (" + (if (clip != null) "مقطع ${clip.bytes.size / 1024}KB" else "${fr.size} فريم") + ")…"; say(status)
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
                    val cl = clip
                    // (v186) وضع الفريمات بقى بنفس برومبت المقاطع (مسار حركة + أنيميشن): أوقات الفريمات نسبية لبداية النافذة
                    val rel = if (cl == null) fr.map { Pair(it.first - w0, it.second) } else emptyList()
                    val res = if (cl != null) askClip(key, cl.bytes, fps) else Api.generateFrames(conf.model, key, PROMPT_FRAMES, rel, 16000)
                    val parsed = if (cl != null) parseClip(res.text, cl.base, cl.end, step) else parseClip(res.text, w0, wEnd, step)
                    if (parsed == null) {
                        fails++; say("👁 الرد مش واضح — بحاول تاني")
                        if (fails >= 3) { fails = 0; advance() }
                        Thread.sleep(500); continue
                    }
                    fails = 0
                    addTimed(parsed)
                    markDone(w0, if (durSec > 0 && wEnd >= durSec - 0.5) wEnd else wEnd - OVERLAP)
                    save()
                    say("👁 نافذة ${"%.0f".format(w0)}–${"%.0f".format(wEnd)}ث: ${parsed.size} نص")
                    sent++
                    status = "👁 $sent نافذة | ${items.size} نص"
                    changed()
                    advance()
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
                    else { say("👁 خطأ ${e.code}: " + (e.message ?: "").take(110)); ki++; fails++; if (fails >= 3) { fails = 0; advance() }; Thread.sleep(1000) }
                } catch (e: java.io.IOException) {
                    say("👁 مشكلة اتصال — بحاول تاني"); Thread.sleep(1500)
                } catch (e: InterruptedException) { throw e
                } catch (e: Exception) {
                    // (v182) أي خطأ تاني (JSON/ذاكرة…) مايوقفش الوضع: نسجّله ونكمّل
                    say("👁 خطأ " + e.javaClass.simpleName + ": " + (e.message ?: "").take(80)); fails++; if (fails >= 3) { fails = 0; advance() }; Thread.sleep(1500)
                }
            }
        } catch (_: InterruptedException) {
        } finally { try { r?.release() } catch (_: Exception) {} }
    }

    /** (v182) تجميع فريمات نافذة من الشاشة أثناء التشغيل: فريم كل step ثانية من وقت الفيديو الفعلي. true = النافذة جاهزة، false = وقف/فشل اللقط */
    private fun collectLive(step: Double, buf: ArrayList<Pair<Double, ByteArray>>): Boolean {
        while (running) {
            val pos = position()
            val lastT = buf.lastOrNull()?.first
            // قفز/رجوع: الفريمات القديمة مالهاش لازمة
            if (lastT != null && (pos < lastT - 0.5 || pos > lastT + step * 3 + 1.0)) buf.clear()
            if (buf.size >= 2 && buf.last().first - buf.first().first >= WIN_SEC - step) return true
            val lt = buf.lastOrNull()?.first
            if (lt == null || pos >= lt + step - 0.05) {
                val bmp = try { grab?.invoke() } catch (_: Throwable) { null }
                if (bmp != null) { grabFails = 0; buf.add(Pair(pos, toJpeg(bmp, 960, 78))); bmp.recycle() }
                else if (++grabFails >= 8) { status = "⚠ مش قادر ألقط فريمات من الشاشة (الفيديو محمي أو السطح مش جاهز)"; say(status); return false }
            }
            Thread.sleep(150)
        }
        return false
    }

    /**
     * (v185) قص مقطع [a,b] من الفيديو من غير إعادة ترميز (remux بس) وبدون صوت — بيبدأ من آخر فريم مفتاحي قبل a.
     * بيرجّع null لو مفيش مسار فيديو أو المقطع أكبر من CLIP_MAX (النافذة دي تتبعت فريمات).
     */
    private fun cutClip(a: Double, b: Double): Clip? {
        val src = clipSrc ?: return null
        val dir = cacheDir ?: return null
        val ex = src() ?: return null
        var mux: MediaMuxer? = null
        var started = false
        val tmp = File(dir, "vis_clip_${System.nanoTime()}.mp4")
        try {
            var vt = -1
            for (i in 0 until ex.trackCount) { if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { vt = i; break } }
            if (vt < 0) return null
            ex.selectTrack(vt)
            val fmt = ex.getTrackFormat(vt)
            ex.seekTo((a * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val m = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            try { if (fmt.containsKey("rotation-degrees")) m.setOrientationHint(fmt.getInteger("rotation-degrees")) } catch (_: Exception) {}
            val ti = m.addTrack(fmt)
            m.start(); started = true
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val endUs = (b * 1_000_000).toLong()
            var base0 = -1L; var lastUs = 0L; var n = 0
            while (running) {
                buf.clear()
                val sz = ex.readSampleData(buf, 0)
                if (sz < 0) break
                val ts = ex.sampleTime
                if (ts < 0) break
                if (base0 >= 0 && ts > endUs) break
                if (base0 < 0) base0 = ts
                info.set(0, sz, maxOf(0L, ts - base0), if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(ti, buf, info)
                if (ts > lastUs) lastUs = ts
                n++
                if (!ex.advance()) break
            }
            if (n < 2 || base0 < 0) return null
            m.stop(); started = false
            m.release(); mux = null
            if (tmp.length() > CLIP_MAX) { say("👁 المقطع كبير (${tmp.length() / 1_000_000}MB) — النافذة دي بالفريمات"); return null }
            return Clip(base0 / 1_000_000.0, lastUs / 1_000_000.0 + 1.0 / 24, tmp.readBytes())
        } finally {
            try { if (started) mux?.stop() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
            try { tmp.delete() } catch (_: Throwable) {}
        }
    }

    /** (v185) طلب المقطع لجيميناي: بإعدادات الفريمات/الدقة العالية، ولو الموديل رفضهم (400) بنكمّل من غيرهم */
    private fun askClip(key: String, bytes: ByteArray, fps: Int): Api.Result {
        if (extrasOk) {
            try { return Api.generateClip(conf.model, key, PROMPT_CLIP, bytes, fps, true) }
            catch (e: ApiErr) {
                val m = (e.message ?: "").lowercase()
                if (e.code != 400 || !(m.contains("fps") || m.contains("metadata") || m.contains("resolution") || m.contains("unknown name") || m.contains("invalid json"))) throw e
                extrasOk = false; say("👁 الموديل مادعمش إعدادات الفريمات/الدقة — بكمّل بالافتراضي")
            }
        }
        return Api.generateClip(conf.model, key, PROMPT_CLIP, bytes, fps, false)
    }

    /** (v185) رد المقطع: الأوقات نسبية لبداية المقطع (base = ثانية بداية المقطع الفعلية في الفيديو). null = الرد مش JSON مفهوم */
    private fun parseClip(txt: String, base: Double, end: Double, step: Double): List<TItem>? {
        val j = Parse.json(txt) ?: return null
        val a = j.optJSONArray("texts") ?: return if (j.has("texts")) emptyList() else null
        val dur = maxOf(end - base, step)
        val out = ArrayList<TItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val bx = boxOf(o) ?: continue
            var ap = o.optDouble("appear", Double.NaN); var dis = o.optDouble("disappear", Double.NaN)
            if (ap.isNaN()) ap = 0.0
            ap = ap.coerceIn(0.0, dur)
            if (dis.isNaN() || dis <= ap) dis = ap + 1.5
            if (o.optBoolean("continues")) dis = maxOf(dis, dur)
            dis = dis.coerceAtMost(dur + step * 2)
            if (dis <= ap) dis = ap + step
            var tr: List<DoubleArray>? = null
            val ka = o.optJSONArray("track")
            if (ka != null && ka.length() >= 2) {
                val l = ArrayList<DoubleArray>()
                for (k in 0 until ka.length()) {
                    val q = ka.optJSONObject(k) ?: continue
                    val t = q.optDouble("t", Double.NaN); if (t.isNaN()) continue
                    l.add(doubleArrayOf(base + t.coerceIn(0.0, dur + step), frac(q.optDouble("x", bx.x.toDouble()), bx.x.toDouble()).toDouble(), frac(q.optDouble("y", bx.y.toDouble()), bx.y.toDouble()).toDouble(),
                        frac(q.optDouble("w", bx.w.toDouble()), bx.w.toDouble()).toDouble().coerceIn(0.03, 1.0), frac(q.optDouble("h", bx.h.toDouble()), bx.h.toDouble()).toDouble().coerceIn(0.02, 0.5),
                        q.optDouble("angle", bx.angle.toDouble()).coerceIn(-45.0, 45.0)))
                }
                l.sortBy { it[0] }
                if (l.size >= 2) tr = l
            }
            out.add(TItem(bx, base + ap, base + dis, tr))
        }
        return out
    }

    /** ضم مسارين حركة لنفس النص (من نافذتين متداخلتين) — نقط بنفس الوقت بتتشال */
    private fun mergeTrack(a: List<DoubleArray>?, b: List<DoubleArray>?): List<DoubleArray>? {
        val all = ((a ?: emptyList()) + (b ?: emptyList())).sortedBy { it[0] }
        val out = ArrayList<DoubleArray>()
        for (k in all) if (out.isEmpty() || k[0] - out.last()[0] > 0.05) out.add(k)
        return if (out.size >= 2) out else null
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
            if (hit == null) items.add(n) else { hit.start = minOf(hit.start, n.start); hit.end = maxOf(hit.end, n.end); if (n.track != null || hit.track != null) hit.track = mergeTrack(hit.track, n.track) }
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
        var dyn = false
        for (x in act) if (x.track != null || media - x.start < FADE || x.end - media < FADE) { dyn = true; break }
        if (dyn) {
            // (v185) فيه نص بيتحرك أو بيظهر/بيختفي تدريجي: قايمة جديدة كل مرة (الطبقة بترسم من جديد)
            val out = ArrayList<VisBox>(act.size + base.size)
            out.addAll(base)
            for (x in act) out.add(boxAt(x, media))
            return out
        }
        if (base.isNotEmpty()) return base + act.map { it.box }
        val key = act.joinToString(",") { System.identityHashCode(it.box).toString() }
        if (key != cacheKey) { cacheKey = key; cacheList = act.map { it.box } }
        return cacheList   // نفس القايمة طول ما مفيش تغيير عشان الطبقة ماتعيدش الرسم كل 100ms
    }

    /** مكان وحجم وشفافية النص عند الوقت t: بيمشي على مسار الحركة (لو فيه) وبيظهر/يختفي تدريجي */
    private fun boxAt(it: TItem, t: Double): VisBox {
        val b = it.box
        var x = b.x; var y = b.y; var w = b.w; var h = b.h; var ang = b.angle
        val tr = it.track
        if (tr != null && tr.size >= 2) {
            val f = tr.first(); val l = tr.last()
            if (t <= f[0]) { x = f[1].toFloat(); y = f[2].toFloat(); w = f[3].toFloat(); h = f[4].toFloat(); ang = f[5].toFloat() }
            else if (t >= l[0]) { x = l[1].toFloat(); y = l[2].toFloat(); w = l[3].toFloat(); h = l[4].toFloat(); ang = l[5].toFloat() }
            else {
                var i = 0
                while (i < tr.size - 2 && tr[i + 1][0] <= t) i++
                val p = tr[i]; val q = tr[i + 1]
                val k = ((t - p[0]) / maxOf(q[0] - p[0], 1e-3)).coerceIn(0.0, 1.0)
                fun lerp(a: Double, c: Double) = (a + (c - a) * k).toFloat()
                x = lerp(p[1], q[1]); y = lerp(p[2], q[2]); w = lerp(p[3], q[3]); h = lerp(p[4], q[4]); ang = lerp(p[5], q[5])
            }
        }
        val fin = ((t - it.start) / FADE).coerceIn(0.0, 1.0); val fout = ((it.end - t) / FADE).coerceIn(0.0, 1.0)
        val al = (0.1 + 0.9 * minOf(fin, fout)).toFloat()
        return VisBox(x, y, w, h, ang, b.original, b.translated, b.bg, b.fg, b.opacity, b.hasBox, al)
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
        tp.color = fg; bgP.color = bg; bgP.alpha = (alpha * b.alpha).toInt().coerceIn(0, 255); tp.alpha = (255 * b.alpha).toInt().coerceIn(0, 255)
        // لازم نعيد بناء الـ layout بعد تغيير اللون مش مطلوب (اللون بيتقري وقت draw)
        c.save(); c.translate(cx, cy); c.rotate(-b.angle); c.scale(sc, sc)
        val rect = RectF(-bw / 2 - pad, -boxH / 2 - pad, bw / 2 + pad, boxH / 2 + pad)
        c.drawRoundRect(rect, 6f * d, 6f * d, bgP)
        c.translate(-bw / 2, -lay.height / 2f); lay.draw(c)
        c.restore()
    }
}
