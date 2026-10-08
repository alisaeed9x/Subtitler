package com.tttt.subtitler

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Sub(
    val start: Double, val end: Double, val original: String, val translated: String,
    val gender: String, val addressee: String, val topicGender: String,
    val people: List<String>, val places: List<String>, val isSong: Boolean, val lowConf: Boolean,
    val chunk: Int = -1,
    val emotion: String = "", val overlap: Boolean = false, val speakerTag: String = "",
    val isContinuation: Boolean = false, val pivot: String = "",
    /** صوت خافت/همس/خلفية: بيتعرض فوق الكلام العادي */
    val faint: Boolean = false,
    /** اتحوّلت للهجة المختارة (التحويل التلقائي بعد الترجمة الحرفية) */
    val conv: Boolean = false,
    /** صوت غير كلامي (همهمة، موسيقى بدون كلمات، ضحك، ضوضاء…): بيتعرض كوصف فوق الفيديو منفصل عن الحوار */
    val isSound: Boolean = false
)

data class Chr(val name: String, val gender: String, val role: String)
data class Gloss(val term: String, val note: String)

/** نسخة ثابتة من الإعدادات (عشان المحرك ما يقراش SharedPreferences من خيوط تانية). */
class Conf(
    val keys: List<String>, val backup: List<String>, val model: String,
    val lang: String, val style: String,
    val chunkSec: Int, val ahead: Int, val audioTrack: Int,
    val manualChars: List<Chr>, val manualGloss: String,
    val vad: Boolean, val crossReview: Boolean, val autoChars: Boolean,
    val autoPronouns: Boolean, val autoTemplate: Boolean,
    /** طلبات متوازية لكل مفتاح (1–4) */
    val parallelPerKey: Int = 1,
    /** دقة توقيت أعلى: فك الصوت من قبل نقطة البداية بـ 3 ثواني */
    val hiTiming: Boolean = false,
    /** تعديل حدود المقطع لأقرب لحظة صمت */
    val silenceTrim: Boolean = true,
    /** سدّ الفجوات تلقائيًا أثناء المشاهدة */
    val gapFill: Boolean = false,
    /** التقاط الأصوات غير الكلامية والخلفية (همهمة/موسيقى/ضحك…) كسطر وصف فوق الفيديو */
    val soundTags: Boolean = true,
    /** مفاتيح خاصة بالوضع البصري بس (لو موجودة الوضع البصري مايستخدمش غيرها) */
    val visKeys: List<String> = emptyList()
) {
    /** نسخة من الإعدادات بلهجة تانية (الباتشات الجديدة بتتبعت باللهجة المختارة مباشرة بدل فصحى ثم تحويل) */
    fun withLang(l: String): Conf = Conf(keys, backup, model, l, style, chunkSec, ahead, audioTrack, manualChars, manualGloss, vad, crossReview, autoChars,
        autoPronouns, autoTemplate, parallelPerKey, hiTiming, silenceTrim, gapFill, soundTags, visKeys)
}

// ===== ترميز الإعدادات (نقي — متختبر) =====
object CfgCodec {
    /** مفاتيح بتتخزن Boolean / Int فعليًا بعد الهجرة */
    val BOOLS = setOf("vad", "cross", "autochars", "autopron", "autotpl", "hitiming", "strim", "gapfill", "soundtags",
        "sub_nobg", "sub_plain", "sub_uni_on", "sub_split_on", "sub_punct")
    val INTS = setOf("chunk", "ahead", "hls_ahead", "atrack", "parallel", "sub_scale", "sub_bgopa", "sub_blur", "sub_aspeed", "sub_dual", "sub_split")
    const val VERSION = 2
    fun bool(v: Any?, d: Boolean): Boolean = when (v) {
        null -> d
        is Boolean -> v
        is String -> v == "1" || v.equals("true", true)
        is Number -> v.toInt() != 0
        else -> d
    }
    fun int(v: Any?, d: Int): Int = when (v) {
        null -> d
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: d
        else -> d
    }
    /** القراءة النصية القديمة لسه شغالة: Boolean بيرجع "1"/"0" */
    fun str(v: Any?, d: String): String = when (v) {
        null -> d
        is String -> v
        is Boolean -> if (v) "1" else "0"
        else -> v.toString()
    }
    /** قيمة نصية جاية من الواجهة → النوع الصح حسب المفتاح: بيرجع Boolean أو Int أو String */
    fun typed(k: String, v: String): Any = when {
        k in BOOLS -> v == "1" || v.equals("true", true)
        k in INTS -> v.trim().toIntOrNull() ?: v
        else -> v
    }
}

/** أوضاع المفاتيح: both = شغّال في الحوض الأساسي، backup = احتياطي. (باقي أوضاع الأصل خاصة بميزات اتشالت) */
object KeyModes {
    fun parse(s: String): List<String> = try {
        val a = JSONArray(s.ifBlank { "[]" }); (0 until a.length()).map { if (a.optString(it) == "backup") "backup" else "both" }
    } catch (_: Exception) { emptyList() }
    fun toJson(l: List<String>): String { val a = JSONArray(); l.forEach { a.put(it) }; return a.toString() }
    fun modeOf(modes: List<String>, i: Int) = modes.getOrNull(i) ?: "both"
    fun next(m: String) = if (m == "backup") "both" else "backup"
    /** (أساسي، احتياطي من الأوضاع) */
    fun split(all: List<String>, modes: List<String>): Pair<List<String>, List<String>> {
        val main = ArrayList<String>(); val bk = ArrayList<String>()
        all.forEachIndexed { i, k -> if (modeOf(modes, i) == "backup") bk.add(k) else main.add(k) }
        return main to bk
    }
}

// ===== الإعدادات =====
object Cfg {
    lateinit var p: SharedPreferences
    fun init(c: Context) {
        p = c.getSharedPreferences("p", 0)
        Quota.load = { p.getString("quota", "") ?: "" }
        Quota.save = { p.edit().putString("quota", it).apply() }
        Stats.load = { p.getString("stats", "") ?: "" }
        Stats.save = { p.edit().putString("stats", it).apply() }
        migrate()
        try { KeyVault.attach(c.applicationContext) } catch (_: Exception) {}
        // (v141) مرة واحدة: الاسم المتحرك gemini-flash-lite-latest بقى موديل ثابت (3.1)
        if (!p.getBoolean("model_pin_v141", false)) {
            val e = p.edit().putBoolean("model_pin_v141", true)
            if ((p.getString("model", "") ?: "").trim() == Models.OLD_ALIAS) e.putString("model", Models.DEFAULT)
            e.apply()
        }
        // مرة واحدة: الثيم الأساسي بقى «MX أبيض وأزرق» (القديم لسه موجود في الإعدادات ← المظهر)
        if (!p.getBoolean("theme_mx_v1", false)) p.edit().putString("theme", "mx").putBoolean("theme_mx_v1", true).apply()
    }
    /** هجرة لمرة واحدة: القيم النصية القديمة ("1" / "60") بتتحوّل لـ Boolean / Int. أسماء المفاتيح ما اتغيرتش. */
    private fun migrate() {
        if (CfgCodec.int(p.all["cfgv"], 0) >= CfgCodec.VERSION) return
        val e = p.edit()
        for (k in CfgCodec.BOOLS) { val v = p.all[k]; if (v is String) e.putBoolean(k, CfgCodec.bool(v, false)) }
        for (k in CfgCodec.INTS) { val v = p.all[k]; if (v is String) v.trim().toIntOrNull()?.let { e.putInt(k, it) } }
        e.putInt("cfgv", CfgCodec.VERSION).apply()
    }
    fun str(k: String, d: String = "") = CfgCodec.str(p.all[k], d)
    fun bool(k: String, d: Boolean) = CfgCodec.bool(p.all[k], d)
    fun keys(k: String) = str(k).lines().map { it.trim() }.filter { it.length > 10 }
    fun int(k: String, d: Int) = CfgCodec.int(p.all[k], d)
    /** كتابة بالنوع الصح حسب المفتاح */
    fun put(k: String, v: String) {
        val t = CfgCodec.typed(k, v); val e = p.edit()
        when (t) { is Boolean -> e.putBoolean(k, t); is Int -> e.putInt(k, t); else -> e.putString(k, v) }
        e.apply()
    }

    fun parseRoster(text: String): List<Chr> = text.lines().mapNotNull {
        val a = it.split(":")
        if (a.size >= 2 && a[0].isNotBlank())
            Chr(a[0].trim(), if (a[1].trim().lowercase().startsWith("f") || a[1].contains("أنث")) "female" else "male", a.drop(2).joinToString(":").trim())
        else null
    }

    /** كل مفاتيح الأساسي + الإضافية بترتيبها (أوضاع المفاتيح بتقابل الترتيب ده) */
    fun allMainKeys() = (keys("keys") + keys("extra")).distinct()

    fun snapshot(): Conf {
        val (main, bk) = KeyModes.split(allMainKeys(), KeyModes.parse(str("keymodes")))
        return Conf(
            main, (keys("backup") + bk).distinct(), str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT },
            str("lang", "فصحى"), str("style", "حرفي"),
            int("chunk", 60).coerceIn(10, 600), int("ahead", 3).coerceIn(0, 50), int("atrack", 1).coerceAtLeast(1),
            parseRoster(str("roster")), str("gloss"),
            bool("vad", false), bool("cross", true), bool("autochars", true), bool("autopron", true), bool("autotpl", true),
            1 /* (v141) طلب واحد لكل مفتاح */, bool("hitiming", false), bool("strim", true), bool("gapfill", true),
            bool("soundtags", true), keys("viskeys")
        )
    }
}

// ===== بناء الـ prompt: النصوص مستخرجة بالحرف من كود البرنامج الأصلي (assets/prompts) =====
class PromptBuilder(private val readAsset: (String) -> String) {
    private val cache = HashMap<String, String>()
    fun read(p: String): String = synchronized(cache) { cache.getOrPut(p) { readAsset(p) } }
    private val index by lazy { JSONObject(read("prompts/index.json")) }

    companion object {
        const val TAIL_MARK = "\n\n\n⏱ مدة هذا المقطع الصوتي"
        /** تعليمات إضافية بتتحط على كل القوالب: التقسيم عند الوقفات الفعلية + المتحدثين المتداخلين (جيميناي هو اللي بيقسّم، مش التطبيق) */
        /** الأصوات غير الكلامية والخلفية: بتتسجل كعناصر مستقلة (is_sound) وبتتعرض فوق الفيديو */
        const val SOUND_BLOCK = "\n═══ العناصر اللي بتظهر فوق الفيديو (is_sound=true) ═══\n" +
            "- 🔴🔴 ممنوع تسجّل أي صوت غير كلامي نهائيًا، لا كعنصر ولا كوصف بين أقواس: موسيقى، صراخ، ضحك، بكاء، تنهيدة، سعال، تصفيق، همهمة، أنين، طرق، باب، تليفون بيرن، مطر، سيارات، طلقات، ضوضاء. الأصوات دي تتجاهل تمامًا ومتتكتبش في أي مكان.\n" +
            "- 🔴 اللي يتسجّل فوق الفيديو هو الكلام المسموع في الخلفية بس (مش حوار الشخصيات الأساسية): مذيع في راديو/تلفزيون/تليفون/مكبّر صوت، أو ناس في سوق/شارع/مظاهرة/تجمع بيتكلموا أو بيهتفوا أو بيعترضوا. كل واحد = عنصر مستقل بتوقيته الفعلي و \"is_sound\": true.\n" +
            "- 🔴 اكتب في translated الكلام اللي اتقال فعلًا مترجم (مش وصف إنهم بيتكلموا)، وابدأ بمين بيتكلم باختصار ثم نقطتين: «المذيع: ...» أو «ناس في السوق: ...» أو «المتظاهرين: ...». لو الكلام فيه أكتر من جملة، خلّيهم كلهم في نفس العنصر واحط بين كل جملة والتانية فاصلة (،) أو نقطة (.) — ده استثناء من قاعدة التقسيم. اكتب original بنفس الشكل بلغة الأصل.\n" +
            "- الحد الأقصى سطرين قصار. لو الكلام الخلفي مش مفهوم خالص ماتسجّلوش، وماتخترعش كلام مسمعتهوش، وماتكررش نفس العنصر لو الكلام مستمر.\n" +
            "- الأغاني بكلماتها تفضل is_song زي ما هي؛ الموسيقى من غير كلام تتجاهل.\n"
        /** شرح المصطلحات الغريبة: عنصر منفصل فوق الفيديو بنفس شكل أسطر الخلفية */
        const val TERM_BLOCK = "\n═══ شرح المصطلحات الغريبة (بتظهر فوق الفيديو) ═══\n" +
            "- 🔴 لما يتقال في الكلام حاجة أغلب المشاهدين العرب مش هيفهموها: اسم دوا أو علاج، اسم مرض أو حالة طبية، مثل أو تعبير اصطلاحي (إنجليزي أو ياباني أو أي لغة)، إشارة لمسلسل أو فيلم أو شخصية أو حدث أو أغنية أو مشهور — أضف عنصر مستقل بنفس توقيت الجملة و \"is_sound\": true.\n" +
            "- original = المصطلح زي ما اتقال. translated = «المصطلح»: شرح بسيط بالمصري. الدوا: بيتاخد لإيه. المرض: هو إيه باختصار. المثل أو التعبير: المثل المصري اللي يقابله أو معناه. الإشارة: هي إيه والمقصود بيها في الجزء ده. بحد أقصى سطرين قصار.\n" +
            "- الترجمة الأساسية للجملة تفضل عادية زي ما هي، والشرح عنصر إضافي بس.\n" +
            "- اشرح الحاجات الغريبة فعلًا بس، مش المعروفة. لو مش متأكد من المعنى ماتشرحوش ماتخمّنش. ماتزودش عن مصطلح كل نص دقيقة تقريبًا.\n"
        const val SPLIT_BLOCK = "\n═══ تقسيم الجمل عند الوقفات (إلزامي) ═══\n" +
            "- 🔴 كل subtitle = جزء كلام متصل بين وقفتين فعليتين في صوت المتحدث (نَفَس، سكتة قصيرة، تغيير في النبرة، أو نهاية فكرة). لو المتحدث بيتكلم كلام طويل وبيهدى شوية بين الأجزاء، افصل كل جزء في subtitle لوحده.\n" +
            "- 🔴 start = اللحظة الفعلية اللي المتحدث بيبدأ فيها الجزء ده، وend = اللحظة الفعلية اللي بيسكت فيها. الجزء اللي بعده start بتاعه عند بداية كلامه هو، وده بيخلّي الجزء اللي قبله يختفي والجديد يظهر في وقته بالظبط. ممنوع توزيع الوقت بالتساوي أو بعدد الكلمات.\n" +
            "- 🔴🔴 علامات الترقيم هي أماكن القطع: ممنوع يبقى جوه حقل translated الواحد أكتر من جملة مفصولة بنقطة (.) أو ؟ أو ! أو …، وممنوع جزئين مفصولين بفاصلة (،) كل واحد ليه توقيت كلام مختلف. كل جملة بتنتهي بنقطة/؟/! = subtitle مستقل بتوقيته الفعلي (حتى لو المتحدث التاني هو اللي كمّلها بعد الأول مباشرة). وكل جزء بين فاصلتين = subtitle مستقل بتوقيت start/end الحقيقي بتاعه، بشرط يبقى كلمتين فأكتر (الكلمة الواحدة زي \"أيوه،\" بتتلزق في اللي بعدها). مثال غلط: {\"start\":3.0,\"end\":8.0,\"translated\":\"يعني حبر فقعات. آلة الطباعة دي أكتر حاجة بتطبعها هي العلامة المميزة.\"}. الصح: subtitle أول {\"start\":3.0,\"end\":4.6,\"translated\":\"يعني حبر فقعات.\"} وsubtitle تاني {\"start\":5.1,\"end\":8.0,\"translated\":\"آلة الطباعة دي أكتر حاجة بتطبعها هي العلامة المميزة.\"}. كل subtitle يختفي لما صوت صاحبه يخلص ويظهر اللي بعده لما صوت صاحبه يبدأ.\n" +
            "- 🔴 لو الكلام متصل من غير وقفة خالص، قسّم عند أقرب نهاية فكرة أو فاصلة. كل subtitle لازم يكون جملة أو عبارة مفهومة ومكتملة المعنى (ماتقطعش في نص عبارة ولا تسيب جملة ناقصة ولا تحذف أي كلمة من الكلام المسموع).\n" +
            "- 🔴 لو اتنين (أو أكتر) بيتكلموا في نفس الوقت: لكل متحدث subtitle منفصل بتوقيته الفعلي، overlap=true، وspeaker_tag رقم مختلف لكل واحد (1 للأوضح/الأعلى). ممنوع دمج كلامهم في subtitle واحد. التطبيق هيعرضهم كل واحد في سطر تحت التاني بلون مختلف.\n"

        /** بلوكات إضافية على كل القوالب: تغطية كاملة، صوت خافت، فصل متحدثين، جودة ترجمة */
        val EXTRA_BLOCK = "\n" +
            "═══ التغطية الكاملة (إلزامي — ممنوع تفويت أي كلام) ═══\n" +
            "- 🔴🔴 اسمع المقطع كله من أوله لآخره وسجّل كل كلمة منطوقة مهما كانت: كلام عالي أو واطي، همس، كلام بعيد أو في الخلفية، صوت من تليفزيون/راديو/تليفون/مكبّر صوت، مقاطعات قصيرة (\"أه\"، \"إيه؟\"، \"لا\")، تعليقات جانبية، وكلام بيتقال فوق موسيقى أو ضوضاء.\n" +
            "- 🔴 ممنوع تتجاهل أي جزء لأنه خافت أو متداخل أو قصير أو لأن فيه موسيقى. لو سمعت كلام (حتى لو مش متأكد منه) اكتبه واحط low_confidence=true — ده أحسن بكتير من إنك تسيبه.\n" +
            "- 🔴 قبل ما ترد: اعمل مراجعة أخيرة على المقطع من الأول للآخر وتأكد إن مفيش أي فجوة فيها كلام مسموع من غير subtitle (خصوصًا أول ثانيتين وآخر ثانيتين من المقطع وبعد كل وقفة طويلة). لو لقيت فجوة فيها صوت بشري ارجع اسمعها تاني وسجّلها.\n" +
            "- ممنوع تلخّص أو تختصر أو تدمج جمل عشان توفّر. كل جملة منطوقة ليها subtitle خاص بيها وترجمتها كاملة.\n" +
            "- 🔴 كلام جاي من راديو/تلفزيون/تليفون/إذاعة داخلية/مكبّر صوت (مذيع، نشرة، إعلان) أو ناس في الخلفية (سوق، مظاهرة، تجمع): مش جزء من حوار الشخصيات. سجّله كعنصر منفصل بتوقيته و is_sound=true، و translated = «المذيع: الكلام اللي قاله مترجم» (أو «ناس في السوق: ...»)، والجمل مفصولة بفاصلة أو نقطة. مفيش وصف صوت بين أقواس.\n" +
            "\n" +
            "═══ الصوت الخافت / الهمس / كلام الخلفية ═══\n" +
            "- أضف لكل subtitle حقل \"faint\" (true أو false).\n" +
            "- faint=true لأي كلام صوته ضعيف بوضوح مقارنة بالكلام الأساسي في المشهد: همس، همهمة مفهومة، صوت بعيد في الخلفية، حد بيتكلم من غرفة تانية، تعليق جانبي بصوت واطي، صوت جهاز بعيد.\n" +
            "- faint=false للكلام الأساسي الواضح العادي.\n" +
            "- 🔴 الكلام الخافت لازم يتسجّل بنفس الدقة في original وtranslated (ماتسيبوش أبدًا). التطبيق هيعرض الخافت فوق والعادي تحته.\n" +
            "- لو الصوت الخافت مفهوم جزئيًا اكتب اللي فهمته وحط low_confidence=true.\n" +
            "- لو الكلام الخافت بيتقال في نفس وقت كلام أساسي: subtitle منفصل للخافت (faint=true وoverlap=true) وsubtitle منفصل للأساسي.\n" +
            "\n" +
            "═══ فصل المتحدثين ═══\n" +
            "- 🔴 ممنوع subtitle واحد فيه كلام شخصين مختلفين، حتى لو بالتتابع وبدون وقفة. تغيّر المتحدث = subtitle جديد بتوقيته الفعلي. اعتمد على اختلاف الصوت (الطبقة، النبرة، الجنس، العمر) مش على علامات الترقيم بس.\n" +
            "- أي سؤال وجواب (واحد بيسأل والتاني بيرد) لازم يتفصلوا في subtitle لكل واحد.\n" +
            "- لو مش متأكد إن المتحدث اتغيّر: لو في أي اختلاف في الصوت افصل.\n" +
            "- حقل gender في كل subtitle لازم يعبّر عن صاحب الجملة دي بالذات.\n" +
            "\n" +
            "═══ جودة الترجمة (مترجم محترف) ═══\n" +
            "- 🔴 الدقة أولًا: انقل المعنى كاملًا وبدقة. ماتضيفش ولا تحذف ولا تخمّن معلومة مش موجودة في الكلام. كل رقم واسم وتاريخ ووحدة قياس تتنقل صح.\n" +
            "- افهم المقصود قبل الترجمة: العبارات الاصطلاحية والأمثال والسخرية والمجاز ماتتترجمش كلمة بكلمة. ترجم المعنى بما يقابله طبيعيًا في اللغة الهدف مع الحفاظ على نبرة المتحدث (رسمي/ساخر/غاضب/حنون).\n" +
            "- اختار المعنى الصح للكلمة متعددة المعاني حسب السياق والمشهد، مش أول معنى بيخطر على بالك.\n" +
            "- صياغة عربية سليمة وطبيعية: جملة مفهومة تتقري بسهولة، من غير تركيب أجنبي ولا ترجمة آلية ولا كلمات ناقصة.\n" +
            "- ثبّت ترجمة نفس الاسم/المصطلح في كل المقطع وبنفس الكتابة العربية.\n" +
            "- استخدم السياق السابق وجدول الشخصيات في تحديد الضمائر (هو/هي/هم) والمخاطَب.\n" +
            "- ماتكرّرش الجملة ولا تنسخ الأصل. راجع كل subtitle قبل الرد: هل المعنى مطابق للمسموع؟ هل العربية سليمة؟ هل المتحدث صح؟\n"
    }

    /** قواعد اللهجة/الفصحى: بتتحط في آخر الـ prompt (أقوى مكان) */
    fun dialectBlock(lang: String): String = when (lang) {
        "فصحى" -> "\n═══ أسلوب الفصحى ═══\n- ترجمة حرفية أمينة بالعربية الفصحى المعاصرة السليمة نحويًا، قريبة من نص الكلام الأصلي ومعناه، بدون أي لفظ عامي أو لهجة، وبدون إعادة صياغة حرة.\n"
        "مصري" -> "\n═══ قواعد اللهجة المصرية (إلزامي) ═══\n" +
            "- 🔴🔴 كل translated لازم يبقى بالمصري الصرف زي ما مصري حقيقي بيتكلم في الشارع — مش فصحى مبسطة ومش فصحى فيها كلمتين عامية.\n" +
            "- ممنوع الألفاظ الفصحى اللي المصريين مابيقولوهاش: لقد، سوف، لماذا، ماذا، هذا/هذه/هؤلاء، هناك، الآن، أريد، كيف، أين، متى، الذي/التي، لكن، سوى، حيث، يجب أن، أستطيع.\n" +
            "- استعمل بدالها: ده/دي/دول، إيه، ليه، إزاي، فين، إمتى، مين، اللي، بس، عايز/عايزة، مش، مفيش، لسه، دلوقتي، كده، عشان/علشان، أوي، خالص، برضه، لازم، أقدر.\n" +
            "- الأفعال بالتصريف المصري: المضارع بـ\"ب\" (بيقول، بتعمل، بنروح)، المستقبل بـ\"ه\" (هعمل، هنروح)، النفي بـ\"ما…ش\" (ماعرفش، مشفتهوش)، والأمر بالمصري (قول، تعالى، خليك).\n" +
            "- حافظ على الأسلوب المحدد (حرفي/شعبي/جرئ/+18) بس بالمصري، وماتغيّرش المعنى.\n"
        else -> "\n═══ قواعد اللهجة ($lang) (إلزامي) ═══\n- 🔴 كل translated لازم يبقى بلهجة $lang الحقيقية زي ما أهلها بيتكلموا فعلًا: مفردات وتعبيرات وتصريفات اللهجة، مش فصحى ولا مصري. ممنوع الفصحى الرسمية إلا لو الكلام الأصلي رسمي فعلًا.\n- حافظ على الأسلوب المحدد وماتغيّرش المعنى.\n"
    }

    /** قفل لغة الإخراج: بيتحط آخر الـ prompt دايمًا (بالعربي) مهما كانت لغة القالب أو لغة الصوت */
    fun langLock(c: Conf, strict: Boolean): String {
        val target = if (c.lang == "فصحى") "اللغة العربية الفصحى" else "اللهجة ${c.lang}"
        var t = "\n\n═══ لغة الإخراج (إلزامي — أهم قاعدة في الرد) ═══\n" +
            "- 🔴🔴 حقل translated في كل subtitle لازم يتكتب بـ$target وبالحروف العربية فقط. ممنوع تسيب جملة بلغتها الأصلية، وممنوع الإنجليزي، وممنوع تنسخ النص الأصلي في translated — حتى لو الجملة قصيرة أو غناء أو اسم أو كلمة واحدة (الأسماء تتكتب بحروف عربية).\n" +
            "- حقل original بس هو اللي بيتكتب بلغة الصوت الأصلية (وtranslated_en_pivot للإنجليزي). translated ما بيبقاش أبدًا بلغة الصوت الأصلية ولا بالإنجليزي.\n" +
            "- لغة الترجمة النهائية ثابتة ($target) مهما كانت لغة التعليمات اللي فوق أو لغة الصوت أو لغة المقاطع اللي قبل كده.\n"
        t += dialectBlock(c.lang)
        if (strict) t += "- ⚠⚠ الرد اللي فات فيه جمل في translated مش بالعربي. راجع كل subtitle قبل ما ترد وتأكد إن كل translated عربي بـ$target.\n"
        return t
    }

    fun rosterText(chars: List<Chr>, gloss: List<Gloss>): String {
        var out = read("prompts/roster_base.txt")
        if (chars.isNotEmpty()) {
            val lines = chars.joinToString("\n") { "- ${it.name}: الجنس الحقيقي = ${if (it.gender == "female") "أنثى" else "ذكر"}${if (it.role.isNotEmpty()) " — " + it.role else ""}" }
            out += read("prompts/roster_chars.txt").replace("{{LINES}}", lines)
        }
        if (gloss.isNotEmpty()) {
            val lines = gloss.joinToString("\n") { "- ${it.term}${if (it.note.isNotEmpty()) ": " + it.note else ""}" }
            out += read("prompts/roster_gloss.txt").replace("- §T§: §NOTE§", lines)
        }
        return out
    }

    fun caseOf(srcLang: String, detectDone: Boolean) = if (!detectDone) "first" else when {
        srcLang.contains("ياباني") -> "ja"
        srcLang.contains("إنجليز") || srcLang.contains("انجليز") || srcLang.contains("انكليز") -> "en"
        else -> "other"
    }

    fun templateId(c: Conf, case: String): String {
        var id = index.optString("${c.lang}|${c.style}|$case")
        if (id.isEmpty()) id = index.optString("${c.lang}|حرفي|$case")
        if (id.isEmpty()) id = index.optString("مصري|حرفي|$case")
        return id
    }

    /** الجزء الثابت من القالب (قبل سطر المدة). فيه علامة §ROSTER§ مكان جدول الشخصيات. */
    fun fixedPart(id: String): String {
        val raw = read("prompts/$id.txt")
        val cut = raw.indexOf(TAIL_MARK)
        return if (cut < 0) raw else raw.substring(0, cut)
    }

    private fun customBlock(text: String): String =
        if (text.isBlank()) "" else "\n═══ مسرد ثابت إلزامي (من المستخدم) ═══\nالأسماء/المصطلحات دي لازم تتترجم بالظبط بالشكل المحدد جنبها في كل مرة تظهر، بدون أي اجتهاد أو تغيير:\n" + text.trim()

    /**
     * @param translatedFixed نسخة مترجمة من الجزء الثابت (للغات اللي ملهاش قالب جاهز) أو null
     */
    fun build(c: Conf, srcLang: String, detectDone: Boolean, durSec: Double, prev: String,
              chars: List<Chr>, gloss: List<Gloss>, translatedFixed: String? = null, strict: Boolean = false): String {
        val case = caseOf(srcLang, detectDone)
        val id = templateId(c, case)
        val raw = read("prompts/$id.txt")
        val cut = raw.indexOf(TAIL_MARK)
        val fixed0 = if (cut < 0) raw else raw.substring(0, cut)
        val tail = if (cut < 0) "" else raw.substring(cut)
        val fixed = (if (case == "other" && !translatedFixed.isNullOrEmpty()) translatedFixed else fixed0)
            .replace("§ROSTER§", rosterText(chars, gloss))
        val ctxBlock = if (prev.isNotBlank()) read("prompts/ctx.txt").replace("§PREV§", prev) else ""
        val glossBlock = customBlock(c.manualGloss)
        val tailFinal = if (tail.isEmpty()) "" else tail.substring(1)
        return (fixed + "\n" + SPLIT_BLOCK + EXTRA_BLOCK + TERM_BLOCK + (if (c.soundTags) SOUND_BLOCK else "") + glossBlock + tailFinal + langLock(c, strict))
            .replace("\u0001", ctxBlock)
            .replace("{{DUR}}", String.format(java.util.Locale.US, "%.1f", durSec))
    }
}

// ===== مفاتيح Gemini: أساسية + احتياطية، وتعطيل مؤقت عند 429/403 =====
class Pool(private val c: Conf) {
    private val until = HashMap<String, Long>()
    var streak = 0
    /** 25% من المفاتيح الأساسية (لو 3 أو أكتر) "مراقبين": لسدّ الفجوات وكاحتياط كوتة (زي الأصل) */
    private val watchN = 0   // كل المفاتيح أساسية: كل مفتاح بيترجم باتش في نفس الوقت
    val mains: List<String> = c.keys.dropLast(watchN)
    val watchers: List<String> = c.keys.takeLast(watchN)
    /** أقصى عدد طلبات ترجمة متوازية */
    fun capacity(perKey: Int): Int = maxOf(1, (if (mains.isNotEmpty()) mains.size else c.backup.size) * perKey.coerceIn(1, 4))
    @Synchronized fun ok(k: String) = (until[k] ?: 0L) < System.currentTimeMillis()
    @Synchronized fun block(k: String, ms: Long) { until[k] = System.currentTimeMillis() + ms }
    @Synchronized fun good(k: String) { until.remove(k); streak = 0 }
    @Synchronized fun clear() { until.clear() }
    fun count() = all().size
    fun all(): List<String> = (c.keys + c.backup).distinct()
    @Synchronized fun pick(avoid: String?, rr: Int): String? {
        val act = mains.filter { it != avoid && ok(it) }
        if (act.isNotEmpty()) return act[Math.floorMod(rr, act.size)]
        watchers.firstOrNull { it != avoid && ok(it) }?.let { return it }
        return c.backup.firstOrNull { it != avoid && ok(it) }
    }
    /** طلبات شغالة دلوقتي على كل مفتاح + متوسط زمن الرد (ms): بنوزّع الباتشات على المفتاح الفاضي/الأسرع بدل التبادل الأعمى (التبادل كان بيحبس الباتشات الزوجية على المفتاح البطيء) */
    private val load = HashMap<String, Int>()
    private val lat = HashMap<String, Double>()
    @Synchronized fun begin(k: String) { load[k] = (load[k] ?: 0) + 1 }
    @Synchronized fun end(k: String, ms: Long, okReply: Boolean) {
        load[k] = maxOf(0, (load[k] ?: 1) - 1)
        val v = if (okReply) ms.toDouble() else 45_000.0   // الفشل/الانتهاء بتايم أوت بيأخّر المفتاح في الترتيب
        lat[k] = lat[k]?.let { it * 0.6 + v * 0.4 } ?: v
    }
    /** الأساسي الأقل شغلًا، ولو متساويين الأسرع؛ وبعدين الاحتياطي (نفس ترتيب pick) */
    @Synchronized fun pickFree(avoid: String?, rr: Int): String? {
        val act = mains.filter { it != avoid && ok(it) }
        if (act.isNotEmpty()) {
            val minLoad = act.minOf { load[it] ?: 0 }
            val free = act.filter { (load[it] ?: 0) == minLoad }
            return free.sortedWith(compareBy<String>({ lat[it] ?: 0.0 }, { Math.floorMod(act.indexOf(it) - rr, act.size) })).first()
        }
        watchers.firstOrNull { it != avoid && ok(it) }?.let { return it }
        return c.backup.firstOrNull { it != avoid && ok(it) }
    }
    /** كام مفتاح صالح دلوقتي (عشان نعرف نبعت نسخة تانية من باتش اتأخر على مفتاح مختلف) */
    @Synchronized fun usableCount(): Int = all().count { ok(it) }
    @Synchronized fun pickBackup(avoid: String?): String? = c.backup.firstOrNull { it != avoid && ok(it) }
    /** مفتاح لسدّ فجوة: المراقبين، بعدين الاحتياطي، بعدين الأساسي — من غير المفاتيح المشغولة */
    @Synchronized fun gapKey(busy: Set<String>, rr: Int): String? {
        val pref = (watchers + c.backup + mains).distinct().filter { it !in busy && ok(it) }
        return if (pref.isEmpty()) null else pref[Math.floorMod(rr, minOf(pref.size, maxOf(1, watchers.size + c.backup.size)))]
    }
    /** مفتاح للمهام الجانبية (تحليل/مراجعة): الاحتياطي الأول، وإلا أول أساسي. */
    @Synchronized fun helper(): String? =
        c.backup.firstOrNull { ok(it) } ?: c.keys.firstOrNull { ok(it) } ?: all().firstOrNull()
    @Synchronized fun streakUp(): Int { streak++; return streak }
    fun tail(k: String) = "…" + k.takeLast(4)
}

/** عدّاد كوتة يومي محلي لكل موديل، بيتصفّر 00:00 بتوقيت المحيط الهادي PT (زي الأصل) */
object Quota {
    @Volatile var load: (() -> String)? = null
    @Volatile var save: ((String) -> Unit)? = null
    fun dayKey(ms: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US); f.timeZone = java.util.TimeZone.getTimeZone("America/Los_Angeles"); return f.format(java.util.Date(ms))
    }
    private fun read(now: Long): JSONObject {
        val day = dayKey(now)
        val j = try { JSONObject(load?.invoke().orEmpty().ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        return if (j.optString("d") == day) j else JSONObject().put("d", day).put("m", JSONObject())
    }
    @Synchronized fun hit(model: String, now: Long = System.currentTimeMillis()) {
        if (save == null) return
        val j = read(now); val m = j.getJSONObject("m"); m.put(model, m.optInt(model, 0) + 1); save?.invoke(j.toString())
    }
    @Synchronized fun used(model: String, now: Long = System.currentTimeMillis()): Int = read(now).getJSONObject("m").optInt(model, 0)
}

class ApiErr(val code: Int, val raw: String) : Exception("[$code] $raw")
class Unsupported(msg: String) : Exception(msg)

object Api {
    var base = "https://generativelanguage.googleapis.com/v1beta"
    class Result(val text: String, val finish: String)

    private val SAFETY = listOf("SEXUALLY_EXPLICIT", "HARASSMENT", "HATE_SPEECH", "DANGEROUS_CONTENT").joinToString(",") {
        "{\"category\":\"HARM_CATEGORY_$it\",\"threshold\":\"BLOCK_NONE\"}"
    }

    /**
     * طلب generateContent. الصوت (wav) بيتبعت stream على دفعات (من غير ما نبني نص base64 كبير في الرام).
     */
    /** طلب generateContent بصورة JPEG + نص (للوضع البصري) */
    fun generateImage(model: String, key: String, prompt: String, jpeg: ByteArray, maxTokens: Int = 6000, temp: Double = 0.0): Result {
        Quota.hit(model); Stats.req(model, key)
        val b64 = java.util.Base64.getEncoder().encodeToString(jpeg)
        val body = "{\"contents\":[{\"parts\":[{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"" + b64 + "\"}},{\"text\":" + JSONObject.quote(prompt) + "}]}]," +
            "\"generationConfig\":{\"maxOutputTokens\":$maxTokens,\"temperature\":$temp,\"responseMimeType\":\"application/json\"},\"safetySettings\":[$SAFETY]}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val c = URL("$base/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key); c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val txt = (if (code < 300) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 300) throw ApiErr(code, try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { "HTTP $code" })
            val cand = JSONObject(txt).optJSONArray("candidates")?.optJSONObject(0) ?: return Result("", "")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            return Result(sb.toString(), cand.optString("finishReason", ""))
        } finally { c.disconnect() }
    }

    class ModelRow(val id: String, val display: String)

    /** كل الموديلات اللي المفتاح ده يقدر يستخدمها في generateContent (بيعدّي على كل الصفحات) */
    fun listModels(key: String): List<ModelRow> {
        val out = ArrayList<ModelRow>()
        var token = ""
        var pages = 0
        while (pages++ < 10) {
            val u = "$base/models?pageSize=200" + (if (token.isNotEmpty()) "&pageToken=" + java.net.URLEncoder.encode(token, "UTF-8") else "")
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "GET"; c.connectTimeout = 20000; c.readTimeout = 30000
                c.setRequestProperty("x-goog-api-key", key)
                val code = c.responseCode
                val txt = (if (code < 300) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code >= 300) throw ApiErr(code, try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { "HTTP $code" })
                val j = JSONObject(txt)
                val arr = j.optJSONArray("models")
                if (arr != null) for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val id = m.optString("name").removePrefix("models/")
                    if (id.isEmpty()) continue
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    var gen = false
                    if (methods != null) for (k in 0 until methods.length()) if (methods.optString(k) == "generateContent") gen = true
                    if (gen) out.add(ModelRow(id, m.optString("displayName")))
                }
                token = j.optString("nextPageToken", "")
            } finally { c.disconnect() }
            if (token.isEmpty()) break
        }
        return out.distinctBy { it.id }.sortedBy { it.id }
    }

    fun generate(model: String, key: String, prompt: String, wav: ByteArray? = null,
                 maxTokens: Int = 8192, temp: Double = 0.1, json: Boolean = true, search: Boolean = false): Result {
        Quota.hit(model); Stats.req(model, key)
        val enc = java.util.Base64.getEncoder()
        val head = "{\"contents\":[{\"parts\":[" + (if (wav != null) "{\"inline_data\":{\"mime_type\":\"audio/wav\",\"data\":\"" else "")
        val mid = if (wav != null) "\"}}," else ""
        val textPart = "{\"text\":" + JSONObject.quote(prompt) + "}"
        val tail = "]}],\"generationConfig\":{\"maxOutputTokens\":$maxTokens,\"temperature\":$temp" +
            (if (json && !search) ",\"responseMimeType\":\"application/json\"" else "") + "},\"safetySettings\":[$SAFETY]" + (if (search) ",\"tools\":[{\"google_search\":{}}]" else "") + "}"
        val hb = head.toByteArray(Charsets.UTF_8)
        val mb = (mid + textPart + tail).toByteArray(Charsets.UTF_8)
        val b64Len = if (wav != null) ((wav.size + 2) / 3).toLong() * 4 else 0L
        val total = hb.size + b64Len + mb.size

        val c = URL("$base/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 20000; c.readTimeout = 90000
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("x-goog-api-key", key)
            c.setFixedLengthStreamingMode(total)
            c.outputStream.use { os ->
                os.write(hb)
                if (wav != null) {
                    val step = 3 * 16384
                    var i = 0
                    while (i < wav.size) {
                        val e = minOf(wav.size, i + step)
                        os.write(enc.encode(wav.copyOfRange(i, e)))
                        i = e
                    }
                }
                os.write(mb)
            }
            val code = c.responseCode
            val stream = if (code < 300) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 300) {
                val m = try { JSONObject(txt).getJSONObject("error").getString("message") } catch (_: Exception) { "HTTP $code" }
                throw ApiErr(code, m)
            }
            val root = JSONObject(txt)
            val cand = root.optJSONArray("candidates")?.optJSONObject(0)
            if (cand == null) {
                val br = root.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
                return Result("", if (br.isNotEmpty()) "PROMPT_$br" else "")
            }
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text", "") ?: "")
            return Result(sb.toString(), cand.optString("finishReason", ""))
        } finally { c.disconnect() }
    }
}

// ===== حارس اللغة: كشف جمل translated اللي مش بالعربي (لغة الأصل / إنجليزي) =====
object LangGuard {
    private fun letters(t: String) = t.count { Character.isLetter(it) }
    private fun arabic(t: String) = t.count { Character.isLetter(it) && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }
    /** true لو الترجمة المفروض عربي بس أغلب حروفها مش عربي */
    private val MIXED = Regex("[\\u0600-\\u06FF][A-Za-z]|[A-Za-z][\\u0600-\\u06FF]")
    /** حروف من كتابة غريبة (تايلاندي/صيني/سيريلي…) أو كلمة فيها عربي ولاتيني مدموجين = هلوسة من الموديل */
    private fun stray(t: String): Boolean {
        if (MIXED.containsMatchIn(t)) return true
        return t.any { c ->
            Character.isLetter(c) && Character.UnicodeScript.of(c.code).let {
                it != Character.UnicodeScript.ARABIC && it != Character.UnicodeScript.LATIN && it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED
            }
        }
    }
    fun foreign(s: Sub): Boolean {
        if (s.isSound) return false
        if (stray(s.translated)) return true
        val l = letters(s.translated)
        if (l < 3) return false
        return arabic(s.translated) * 100 < l * 50
    }
    fun foreignOf(subs: List<Sub>): List<Sub> = subs.filter { foreign(it) }
}

// ===== تحليل الرد (نفس منطق الإنقاذ الجزئي في الأصل) =====
object Parse {
    fun json(text: String): JSONObject? {
        val clean = text.replace(Regex("```json\\n?"), "").replace(Regex("```\\n?"), "").trim()
        try { return JSONObject(clean) } catch (_: Exception) {}
        Regex("\\{[\\s\\S]*\\}").find(text)?.let { try { return JSONObject(it.value) } catch (_: Exception) {} }
        if (clean.contains("\"subtitles\"")) {
            val i = clean.lastIndexOf("},")
            if (i != -1) try { return JSONObject(clean.substring(0, i + 1) + "]}") } catch (_: Exception) {}
        }
        return null
    }
    private fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { (a!!.opt(it) as? String)?.trim() }.filter { it.isNotEmpty() }
    /** ثواني من رقم أو نص: "12.5" / "01:23.5" / "1:02:03" (القديم كان بيقرا "01:23" كصفر فالجملة تظهر في أول المقطع) */
    fun sec(s: JSONObject, key: String, def: Double): Double {
        if (!s.has(key)) return def
        val v = s.opt(key)
        if (v is Number) return v.toDouble()
        val t = v?.toString()?.trim()?.replace(',', '.') ?: return def
        t.toDoubleOrNull()?.let { return it }
        val m = Regex("^(?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?)$").find(t) ?: return def
        return (m.groupValues[1].toIntOrNull() ?: 0) * 3600.0 + m.groupValues[2].toInt() * 60.0 + m.groupValues[3].toDouble()
    }
    fun sub(s: JSONObject, off: Double): Sub {
        val orig = (s.optString("original").ifEmpty { s.optString("text") }).trim()
        val tr = (s.optString("translated").ifEmpty { s.optString("translation") }.ifEmpty { orig }).trim()
        val ad = s.optString("addressee").lowercase().let { if (it in listOf("male", "female", "plural")) it else "unknown" }
        val tg = s.optString("topic_gender").lowercase().let { if (it in listOf("male", "female", "plural")) it else "none" }
        return Subs.collapseSelfRepeat(Sub(
            off + sec(s, "start", 0.0), off + sec(s, "end", 1.0),
            orig, tr, if (s.optString("gender") == "female") "female" else "male", ad, tg,
            strs(s.optJSONArray("people")), strs(s.optJSONArray("places")), s.optBoolean("is_song", false), s.optBoolean("low_confidence", false), -1,
            s.optString("emotion").trim().lowercase(), s.optBoolean("overlap", false), s.optString("speaker_tag").trim(),
            s.optBoolean("is_continuation", false), s.optString("translated_en_pivot").trim(),
            s.optBoolean("faint", false), false,
            s.optBoolean("is_sound", false) || (tr.length >= 3 && tr.startsWith("[") && tr.endsWith("]") && !tr.contains(" - "))
        ))
    }
    fun subs(j: JSONObject, off: Double, maxEnd: Double): List<Sub> {
        val arr = j.optJSONArray("subtitles") ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { sub(it, off) }
            .filter { it.end > it.start && it.original.isNotEmpty() && !(Subs.isMusicLabel(it) && !it.isSound) }
            .filter { !(it.isSound && it.translated.trim().let { t -> t.startsWith("[") && t.endsWith("]") }) }   // (v105) أوصاف الأصوات [موسيقى] [ضحك] مبقتش بتظهر
        val ok = all.filter { it.start < off + maxEnd + 1.0 }.map { if (it.end > off + maxEnd + 0.3) it.copy(end = off + maxEnd) else it }.filter { it.end > it.start }
        if (ok.isEmpty() && all.isNotEmpty() && off > 1.0) {
            // الموديل ساعات بيرجّع أوقات مطلقة (من أول الفيديو) بدل نسبية لبداية المقطع → الجمل كانت بتتشال كلها والباتش يطلع ✅ فاضي
            val abs = all.map { it.copy(start = it.start - off, end = it.end - off) }
                .filter { it.start >= off - 2.0 && it.start < off + maxEnd + 1.0 }
            if (abs.isNotEmpty()) return abs
        }
        return ok
    }
}

// ===== مجالات زمنية (للمقاطع المترجمة) =====
class Ranges {
    private val r = ArrayList<DoubleArray>()
    @Synchronized fun add(s: Double, e: Double) {
        if (e <= s) return
        r.add(doubleArrayOf(s, e)); r.sortBy { it[0] }
        val m = ArrayList<DoubleArray>()
        for (x in r) {
            if (m.isNotEmpty() && x[0] <= m.last()[1] + 0.05) { if (x[1] > m.last()[1]) m.last()[1] = x[1] } else m.add(doubleArrayOf(x[0], x[1]))
        }
        r.clear(); r.addAll(m)
    }
    @Synchronized fun covers(s: Double, e: Double) = r.any { it[0] <= s + 0.05 && it[1] >= e - 0.05 }
    @Synchronized fun list(): List<DoubleArray> = r.map { it.copyOf() }
    @Synchronized fun total(): Double = r.sumOf { it[1] - it[0] }
    @Synchronized fun clear() = r.clear()
    /** يشيل مجال زمني من المجالات المترجمة (لإعادة الترجمة) */
    @Synchronized fun remove(s: Double, e: Double) {
        val m = ArrayList<DoubleArray>()
        for (x in r) {
            if (x[1] <= s || x[0] >= e) { m.add(x); continue }
            if (x[0] < s) m.add(doubleArrayOf(x[0], s))
            if (x[1] > e) m.add(doubleArrayOf(e, x[1]))
        }
        r.clear(); r.addAll(m)
    }
}

// ===== سياق الحوار السابق + إزالة التكرار + الدمج + تقسيم الجمل الطويلة (منقولة من الأصل) =====
object Subs {
    fun prevContext(all: List<Sub>, before: Double): String {
        val last = all.filter { it.start < before }.takeLast(20)
        // (v140) المقاطع بتتبعت بالتوازي: المقطع اللي قبل الحالي ممكن يكون لسه بيتترجم، فالسياق بيبقى من بعيد — لازم الموديل يعرف ده بدل ما يفترض إنه متصل
        val gapSec = if (last.isEmpty()) 0.0 else before - last.maxOf { it.end }
        val note = if (gapSec > 12.0) "⚠ السياق ده قديم: بينه وبين بداية المقطع الحالي حوالي ${Math.round(gapSec)} ثانية لسه ماترجمتش — ماتفترضش إن أول جملة في المقطع استكمال مباشر ليه إلا لو الكلام نفسه بيدل على كده.\n" else ""
        return note + last.joinToString("\n") { s ->
            val g = if (s.gender == "female") "أنثى" else "ذكر"
            val a = if (s.addressee != "unknown") s.addressee else "-"
            val tg = if (s.topicGender != "none") s.topicGender else "-"
            val names = if (s.people.isNotEmpty()) " | أسماء مذكورة: " + s.people.joinToString("، ") else ""
            val orig = if (s.original.isNotEmpty()) "\n  [أصلي] ${s.original}" else ""
            "- [متكلم:$g | مخاطَب:$a | غايب مذكور:$tg$names] ${s.translated.ifEmpty { s.original }}$orig"
        }
    }
    private fun norm(t: String) = t.replace(Regex("[\\s.,!?؟،«»\"']"), "").trim()
    fun dedup(subs: List<Sub>): List<Sub> {
        if (subs.size < 2) return subs
        val sorted = subs.sortedWith(compareBy({ it.start }, { it.end }))
        val res = ArrayList<Sub>()
        for (cur in sorted) {
            var merged = false
            var back = 1
            while (back <= 6 && res.size - back >= 0) {
                val prev = res[res.size - back]
                val overlaps = cur.start < prev.end - 0.15 && cur.end > prev.start - 0.15
                val similar = norm(cur.original) == norm(prev.original) ||
                    (cur.translated.isNotEmpty() && prev.translated.isNotEmpty() && norm(cur.translated) == norm(prev.translated))
                val sameSpot = cur.chunk != prev.chunk || Math.abs(cur.start - prev.start) < 1.5
                if (overlaps && similar && sameSpot && cur.faint == prev.faint) { res[res.size - back] = cur; merged = true; break }
                back++
            }
            if (!merged) res.add(cur)
        }
        return dropLoops(dropEchoes(dropContained(res)))
    }

    // ===== (v111) تكرار الجملة =====
    private val SENT_SPLIT = Regex("(?<=[.!?؟。！？…])\\s*")
    private fun collapseText(t: String): String {
        val x = t.trim()
        if (x.length < 14) return t
        // 1) نفس النص مكتوب مرتين ورا بعض من غير ترقيم: «X X»
        val w = x.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (w.size >= 4 && w.size % 2 == 0) {
            val h = w.size / 2
            if (norm(w.subList(0, h).joinToString(" ")) == norm(w.subList(h, w.size).joinToString(" ")) && norm(w.subList(0, h).joinToString(" ")).length >= 6)
                return w.subList(0, h).joinToString(" ")
        }
        // 2) جملة طويلة اتكررت جوه نفس النص (الموديل بيكرر آخر جملة قالها)
        val parts = x.split(SENT_SPLIT).filter { it.isNotBlank() }
        if (parts.size < 2) return t
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (p in parts) {
            val n = norm(p)
            if (n.length >= 6 && !seen.add(n)) continue
            out.add(p.trim())
        }
        return if (out.size == parts.size) t else out.joinToString(" ")
    }
    /** الجملة الواحدة اللي الأصل/الترجمة بتاعها فيه نفس الكلام مرتين (الموديل كرر نفسه) — بتتشال النسخة الزيادة */
    fun collapseSelfRepeat(s: Sub): Sub {
        val o = collapseText(s.original); val t = collapseText(s.translated)
        return if (o == s.original && t == s.translated) s else s.copy(original = o, translated = t)
    }

    const val ECHO_WIN = 40.0
    private fun echoMatch(a: Sub, b: Sub): Boolean {
        if (a.isSound || b.isSound || a.isSong || b.isSong || a.faint != b.faint) return false
        val dt = b.start - a.start
        if (dt < 0 || dt > ECHO_WIN) return false
        val ta = norm(a.translated); val tb = norm(b.translated)
        val oa = norm(a.original); val ob = norm(b.original)
        if (a.chunk >= 0 && a.chunk == b.chunk) return dt <= 8.0 && ta.length >= 10 && ta == tb   // نفس الرد كرر السطر
        val origSim = oa.length >= 4 && ob.length >= 4 && dice(oa, ob) >= 0.8
        val trSim = ta.length >= 6 && tb.length >= 6 && dice(ta, tb) >= 0.85
        return origSim || trSim
    }
    private fun echoStrong(a: Sub, b: Sub): Boolean =
        maxOf(norm(a.translated).length, norm(b.translated).length) >= 14 || minOf(norm(a.original).length, norm(b.original).length) >= 10 ||
            // نسخة سدّ الفجوة («…») قريبة (<= 10ث) من نفس الجملة = صدى مؤكد حتى لو قصيرة
            ((a.chunk == -2 || b.chunk == -2) && b.start - a.start <= 10.0 && minOf(norm(a.translated).length, norm(b.translated).length) >= 6)

    /** نفس الجملة ظهرت مرتين بتوقيتين مختلفين (بتحصل لما الموديل يقدّم جملة عن مكانها الحقيقي فيتسد مكانها الصح بعدين):
     *  النسخة المتأخرة هي اللي في مكانها الصح فبنشيل المبكرة. الجمل القصيرة ما بتتشالش إلا لو جارتها اتكررت معاها (تتابع). الأغاني مستثناة (الكورَس بيتكرر عادي). */
    fun dropEchoes(l: List<Sub>): List<Sub> {
        if (l.size < 2) return l
        val sorted = l.sortedWith(compareBy({ it.start }, { it.end }))
        val pairs = HashMap<Int, ArrayList<Int>>()
        for (i in sorted.indices) {
            var j = i + 1
            while (j < sorted.size && sorted[j].start - sorted[i].start <= ECHO_WIN) {
                if (echoMatch(sorted[i], sorted[j])) pairs.getOrPut(i) { ArrayList() }.add(j)
                j++
            }
        }
        if (pairs.isEmpty()) return l
        val drop = BooleanArray(sorted.size)
        for ((i, js) in pairs) {
            for (j in js) {
                val strong = echoStrong(sorted[i], sorted[j])
                val run = (pairs[i - 1]?.contains(j - 1) == true) || (pairs[i + 1]?.contains(j + 1) == true)
                if (strong || run) { drop[i] = true; break }
            }
        }
        return sorted.filterIndexed { idx, _ -> !drop[idx] }
    }

    /** جملة نصها جزء من جملة تانية فوقها في نفس الوقت (نفس السطر اتكرر بتوقيت مختلف) — الأقصر بتتشال */
    private fun dropContained(l: List<Sub>): List<Sub> {
        if (l.size < 2) return l
        val drop = BooleanArray(l.size)
        for (a in l.indices) {
            val s = l[a]; if (s.isSound) continue
            val ns = norm(s.translated); if (ns.length < 6) continue
            val dur = maxOf(0.1, s.end - s.start)
            for (b in maxOf(0, a - 6)..minOf(l.size - 1, a + 6)) {
                if (b == a || drop[b]) continue
                val o = l[b]
                if (o.isSound || o.isSong != s.isSong || o.faint != s.faint) continue
                val no = norm(o.translated)
                if (no.length < ns.length + 4 || !no.contains(ns)) continue
                val ov = minOf(s.end, o.end) - maxOf(s.start, o.start)
                if (ov >= 0.6 * dur) { drop[a] = true; break }
            }
        }
        return l.filterIndexed { i, _ -> !drop[i] }
    }

    /** حلقة هلوسة: نفس الجملة (10 حروف+) 4 مرات ورا بعض ملزوقين = الموديل علق على مقطع موسيقى/ضوضاء — بيفضل الأول بس */
    const val LOOP_MIN_RUN = 4
    private fun dropLoops(l: List<Sub>): List<Sub> {
        if (l.size < LOOP_MIN_RUN) return l
        val drop = BooleanArray(l.size)
        var i = 0
        while (i < l.size) {
            val n0 = norm(l[i].translated)
            if (l[i].isSound || n0.length < 10) { i++; continue }
            var j = i
            while (j + 1 < l.size && !l[j + 1].isSound && norm(l[j + 1].translated) == n0 && l[j + 1].start - l[j].end <= 0.3) j++
            if (j - i + 1 >= LOOP_MIN_RUN) for (k in i + 1..j) drop[k] = true
            i = j + 1
        }
        return l.filterIndexed { idx, _ -> !drop[idx] }
    }
    private fun words(t: String) = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    fun merge(subs: List<Sub>): List<Sub> {
        if (subs.size < 2) return subs
        val sorted = subs.sortedBy { it.start }
        val res = arrayListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val cur = sorted[i]; val prev = res.last()
            val gap = cur.start - prev.end
            val pw = words(prev.original); val cw = words(cur.original)
            val same = prev.gender == cur.gender && prev.addressee == cur.addressee && prev.topicGender == cur.topicGender
            if (gap >= 0 && gap < 0.5 && same && prev.isSong == cur.isSong && pw <= 6 && cw <= 6 && pw + cw <= 10 &&
                !prev.lowConf && !cur.lowConf && !Regex("[.!؟?]\\s*$").containsMatchIn(prev.original.trim()) && !prev.translated.startsWith("«") && !cur.translated.startsWith("«") && !prev.isSound && !cur.isSound) {
                res[res.size - 1] = prev.copy(end = cur.end, original = "${prev.original} ${cur.original}".trim(), translated = "${prev.translated} ${cur.translated}".trim())
            } else res.add(cur)
        }
        // جملة قصيرة جدًا (< 0.6ث) ملزوقة في اللي قبلها ونفس المتحدث: بتتدمج بدل ما تظهر وتختفي في لحظة
        val out = ArrayList<Sub>()
        for (s in res) {
            val p = out.lastOrNull()
            if (p != null && s.end - s.start < 0.6 && s.start - p.end >= -0.05 && s.start - p.end <= 0.35 && p.gender == s.gender && p.addressee == s.addressee &&
                p.isSong == s.isSong && !p.isSound && !s.isSound && !p.translated.startsWith("«") && !s.translated.startsWith("«") && words(p.translated) + words(s.translated) <= 20) {
                out[out.size - 1] = p.copy(end = s.end, original = "${p.original} ${s.original}".trim(), translated = "${p.translated} ${s.translated}".trim())
            } else out.add(s)
        }
        return unifyOverlaps(out)
    }

    // ===== متحدثين في نفس الوقت =====
    const val UNIFY_MAX_SEC = 9.0
    private fun otherSpeaker(a: Sub, b: Sub): Boolean {
        val ta = a.speakerTag.trim(); val tb = b.speakerTag.trim()
        if (ta.isNotEmpty() && tb.isNotEmpty() && ta != tb) return true
        return a.overlap || b.overlap || a.gender != b.gender
    }
    private fun ovLen(a: Sub, b: Sub) = minOf(a.end, b.end) - maxOf(a.start, b.start)
    /** جملتين لشخصين مختلفين بيتكلموا فوق بعض: الاتنين ياخدوا نفس التوقيت (من بداية أول واحد لنهاية آخر واحد)،
     *  فيظهروا مع بعض طول المدة دي بدل ما الأول يظهر لوحده وبعدين التاني يدخل وبعدين الأول يختفي.
     *  بيغيّر التوقيت بس — العدد والترتيب زي ما هم. */
    fun unifyOverlaps(l: List<Sub>): List<Sub> {
        if (l.size < 2) return l
        val res = l.toMutableList()
        val idx = l.indices.sortedBy { l[it].start }
        val used = BooleanArray(l.size)
        for (i in idx.indices) {
            val f = idx[i]
            if (used[f] || l[f].isSound || l[f].faint) continue
            val mem = arrayListOf(f); var lo = l[f].start; var hi = l[f].end
            var j = i + 1
            while (j < idx.size && mem.size < 3) {
                val k = idx[j]; val b = l[k]
                if (b.start >= hi - 0.05) break
                if (!used[k] && !b.isSound && !b.faint) {
                    val ok = mem.any { m ->
                        val a = l[m]
                        val shorter = minOf(a.end - a.start, b.end - b.start)
                        val flagged = a.overlap || b.overlap
                        val need = if (flagged) minOf(0.3, 0.5 * shorter) else maxOf(0.6, 0.5 * shorter)
                        otherSpeaker(a, b) && ovLen(a, b) >= need
                    }
                    if (ok && maxOf(hi, b.end) - minOf(lo, b.start) <= UNIFY_MAX_SEC) { mem.add(k); lo = minOf(lo, b.start); hi = maxOf(hi, b.end) }
                }
                j++
            }
            if (mem.size > 1) for (m in mem) { used[m] = true; res[m] = l[m].copy(start = lo, end = hi) }
        }
        return res
    }

    // ===== منطقة التداخل بين المقاطع (الـ OVERLAP) =====
    private fun coversMost(n: Sub, o: Sub): Boolean = ovLen(n, o) >= 0.4 * maxOf(0.1, minOf(n.end - n.start, o.end - o.start))
    /** جملة من المقطع الجديد وقعت قبل بدايته الحقيقية (في ذيل المقطع اللي قبله) وفوق جملة اتترجمت قبل كده = بتتشال.
     *  المنطقة دي ملك المقطع السابق؛ الجديد كان بيطلّع نفس الكلام بصياغة/توقيت مختلف فيظهر مكرر نص ثانية. */
    fun dropOverlapZone(fresh: List<Sub>, existing: List<Sub>, rawStart: Double): List<Sub> {
        if (existing.isEmpty()) return fresh
        return fresh.filter { n ->
            if (n.isSound || (n.start + n.end) / 2 >= rawStart) true
            else existing.none { o -> !o.isSound && coversMost(n, o) }
        }
    }
    /** عكس اللي فوق: مقطع لاحق خلّص قبل السابق، فجمله اللي في ذيل المقطع الحالي (قبل rawEnd) وفوق جمل جديدة بتتشال */
    fun inMyZone(o: Sub, mine: List<Sub>, i: Int, rawEnd: Double): Boolean =
        o.chunk > i && !o.isSound && (o.start + o.end) / 2 < rawEnd && mine.any { n -> !n.isSound && coversMost(n, o) }

    private val PUNCT_END = Regex("[،,.؟?!:;؛…][\"'»)\\]]*$")
    /** يقسّم النص لـ n جزء: القطع بيفضّل علامة ترقيم قريبة (±3 كلمات) من النقطة المثالية بدل القطع الأعمى بعدد الكلمات */
    private fun splitN(text: String, n: Int): List<String> {
        val w = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (n <= 1 || w.size <= 1) return listOf(text.trim())
        val total = w.size
        val cuts = arrayListOf(0)
        for (k in 1 until n) {
            val prev = cuts.last()
            val ideal = Math.round(total.toDouble() * k / n).toInt()
            var best = ideal; var bd = Int.MAX_VALUE
            for (c in maxOf(prev + 1, ideal - 3)..minOf(total - 1, ideal + 3)) {
                if (PUNCT_END.containsMatchIn(w[c - 1])) { val d = Math.abs(c - ideal); if (d < bd) { best = c; bd = d } }
            }
            best = minOf(maxOf(best, prev + 1), total - (n - k))
            if (best <= prev) break
            cuts.add(best)
        }
        cuts.add(total)
        val parts = ArrayList<String>()
        for (q in 0 until cuts.size - 1) parts.add(w.subList(cuts[q], cuts[q + 1]).joinToString(" "))
        while (parts.size < n) parts.add("")
        return parts
    }

    // ----- سطور "موسيقى" اللي النموذج بيكتبها رغم المنع: (تشغيل الموسيقى) [Music] ♪ -----
    private val MUSIC_RE = Regex("^[\\s(\\[{«\"'♪♫🎵🎶*–—-]*(?:(?:تشغيل|يتم تشغيل|صوت|تعزف|عزف)\\s+)?(?:الموسيقى|موسيقى|موسيقي|music|musik|müzik)?[\\s♪♫🎵🎶]*[\\s)\\]}»\"'♪♫🎵🎶.*–—-]*$", RegexOption.IGNORE_CASE)
    private val MUSIC_WORD = Regex("(موسيق|music|musik|müzik|♪|♫|🎵|🎶)", RegexOption.IGNORE_CASE)
    private fun musicish(t0: String): Boolean { val t = t0.trim(); return t.isNotEmpty() && t.length <= 30 && MUSIC_RE.matches(t) && MUSIC_WORD.containsMatchIn(t) }
    private fun bracketed(t: String) = t.any { it in "([{«♪♫*" } || t.contains("🎵") || t.contains("🎶")
    /** سطر تسمية موسيقى بس (مش كلام): بين أقواس/♪، أو الأصل والترجمة الاتنين كلمة موسيقى */
    fun isMusicLabel(s: Sub): Boolean {
        val o = s.original; val t = s.translated
        return (musicish(o) && bracketed(o)) || (musicish(t) && bracketed(t)) || (musicish(o) && musicish(t))
    }

    // ----- توحيد ترجمة السطور المتكررة في الأغاني (الكورَس): كل مقطع كان بيترجم نفس السطر بصيغة مختلفة -----
    private fun normH(t: String) = t.lowercase().replace(Regex("[\\s.,!?؟،«»\"'()\\-–—…]"), "")
    fun dice(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val m = HashMap<String, Int>()
        for (i in 0 until a.length - 1) m.merge(a.substring(i, i + 2), 1, Int::plus)
        var hit = 0
        for (i in 0 until b.length - 1) { val g = b.substring(i, i + 2); val c = m[g] ?: 0; if (c > 0) { hit++; m[g] = c - 1 } }
        return 2.0 * hit / (a.length + b.length - 2)
    }
    fun harmonize(fresh: List<Sub>, existing: List<Sub>): List<Sub> {
        val pool = existing.filter { it.isSong && it.original.isNotBlank() && it.translated.isNotBlank() && normH(it.original).length >= 12 }
            .map { it to normH(it.original) }
        if (pool.isEmpty()) return fresh
        return fresh.map { n ->
            if (!n.isSong || n.translated.isBlank() || n.translated.contains('\n')) return@map n
            val no = normH(n.original); if (no.length < 12) return@map n
            val votes = HashMap<String, IntArray>()   // الترجمة -> [عدد، أبكر بداية*1000]
            for ((p, po) in pool) {
                if (p.start < n.end && p.end > n.start) continue
                if (dice(no, po) < 0.85) continue
                val base = p.translated.removePrefix("«").removeSuffix("»").trim()
                val v = votes.getOrPut(base) { intArrayOf(0, Int.MAX_VALUE) }
                v[0]++; v[1] = minOf(v[1], (p.start * 1000).toInt())
            }
            if (votes.isEmpty()) return@map n
            val win = votes.entries.sortedWith(compareBy({ -it.value[0] }, { it.value[1] })).first().key
            n.copy(translated = if (n.translated.startsWith("«")) "«$win»" else win)
        }
    }
    const val MIN_PART_SEC = 1.0
    /** تقسيم الجملة الطويلة لأجزاء: عدد الأجزاء مايزيدش عن اللي وقت الجملة يسمح بيه (كل جزء ≥ 1ث)، والجزء مابيبقاش فاضي أبدًا
     *  (القديم كان بيحط الجملة كلها في الجزء الفاضي، فتظهر لحظة وتختفي) */
    fun splitLong(sub: Sub, maxWords: Int = 14): List<Sub> {
        val ow = words(sub.original); val tw = words(sub.translated)
        val basis = if (sub.translated.isBlank()) ow else tw
        if (basis <= maxWords) return listOf(sub)
        val dur = maxOf(0.01, sub.end - sub.start)
        var n = maxOf(2, Math.ceil(basis.toDouble() / maxWords).toInt())
        n = minOf(n, maxOf(1, Math.floor(dur / MIN_PART_SEC).toInt()), basis)
        if (n <= 1) return listOf(sub)
        val op = splitN(sub.original, n); val tp = splitN(sub.translated, n)
        val weights = (0 until n).map { maxOf(1, (tp.getOrNull(it)?.takeIf { s -> s.isNotEmpty() } ?: op.getOrNull(it) ?: "").length).toDouble() }
        val tot = weights.sum()
        val sh = weights.map { it / tot * dur }.toMutableList()
        val minS = minOf(MIN_PART_SEC, dur / n)
        val low = sh.indices.filter { sh[it] < minS }
        if (low.isNotEmpty()) {
            val rest = sh.indices.filter { it !in low }
            if (rest.isNotEmpty()) {
                val restTot = rest.sumOf { sh[it] }; val avail = dur - low.size * minS
                for (k in rest) sh[k] = if (restTot > 0) sh[k] / restTot * avail else avail / rest.size
            }
            for (k in low) sh[k] = minS
        }
        val res = ArrayList<Sub>()
        var cursor = sub.start
        for (i in 0 until n) {
            val o = op.getOrNull(i) ?: ""; val t = tp.getOrNull(i) ?: ""
            if (o.isEmpty() && t.isEmpty()) continue
            val e0 = if (i == n - 1) sub.end else minOf(sub.end, cursor + sh[i])
            res.add(sub.copy(start = cursor, end = e0, original = o.ifEmpty { sub.original }, translated = t))
            cursor = e0
        }
        return if (res.isEmpty()) listOf(sub) else res
    }
    /** إعداد واحد للتقسيم (الإعدادات ← العرض ← تقسيم الجمل): مطفي = الجملة تظهر كاملة من أول الكلام لآخره */
    @Volatile var splitEnabled = false
    fun splitAll(l: List<Sub>): List<Sub> = if (splitEnabled) l.flatMap { splitLong(it) } else l

    /** الموديل ساعات بيدّي جملة قصيرة مدة طويلة جدًا (18ث لسطر غنائي) فتفضل ظاهرة والمتكلم سكت — بنقصّرها على قد كلامها */
    fun maxDurFor(s: Sub): Double {
        val w = maxOf(words(s.translated), words(s.original))
        return maxOf(3.0, w * 0.7 + 1.5)
    }
    fun capPace(s: Sub): Sub {
        val m = maxDurFor(s)
        return if (s.end - s.start > m + 0.3) s.copy(end = s.start + m) else s
    }
}
