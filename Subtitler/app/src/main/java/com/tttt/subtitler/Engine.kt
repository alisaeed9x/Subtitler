package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface Host {
    fun log(s: String)
    fun status(s: String)
    /** القائمة اتغيرت (الواجهة تحدّث الترجمة المعروضة) */
    fun changed()
    /** مكان التشغيل الحالي بالثواني */
    fun position(): Double
    /** مدة الفيديو من المشغّل (0 لو لسه مش معروفة) */
    fun playerDuration(): Double
    /** إشعار صغير للمستخدم (فشل/نجاح باتش) — الافتراضي مفيش */
    fun notice(s: String) {}
}

private class BadReply(msg: String) : Exception(msg)
/** (v151) مفتاح الباتش فشل ومفيش مفتاح فاضي دلوقتي: الباتش بيرجع للطابور ويستنى أقرب مفتاح يخلص باتشه (مش بيتحط فوق باتش شغال) */
private class RehomeEx(val avoid: String?) : Exception("rehome")

class Engine(
    @Volatile private var conf: Conf,
    private val openSource: () -> AudioSource,
    private val store: Store?,
    private val host: Host,
    private val pb: PromptBuilder
) {
    companion object {
        const val OVERLAP = 4.0          // ثواني تداخل بين المقاطع (زي الأصل)
        const val MAX_TRIES = 25
        const val MAX_FAILS = 3          // جولات فشل قبل ما المقطع يتحط كـ "فجوة"
        const val GAP_PASSES = 3         // كام مرة نعيد المحاولة في الفجوات تلقائيًا
        const val CROSS_MIN_DUR = 90.0   // (v140) كانت 600: الفيديوهات القصيرة (3 دقايق = 4 مقاطع) ماكانتش بتتراجع خالص
        const val PRIOR_CAP = 400        // أقصى عدد جمل قديمة نبعتها للمراجعة
        const val CHAR_MIN_LINES = 12
        const val PRON_MIN_LINES = 20
        const val HOLE_MIN = 1.6         // أقل ثغرة صوتية من غير ترجمة نعيد طلبها (ثواني)
        const val HOLE_MAX = 10          // أقصى عدد ثغرات بنسدّها لكل مقطع
    }

    private val lock = Any()
    /** المشغّل بيحمّل الترجمة المحفوظة في الخلفية: لحد ما يخلص ممنوع أي حفظ (عشان مانكتبش حالة فاضية فوق الترجمة المحفوظة) */
    @Volatile var persistBlocked = false
    /** (v139) بعد stop() الإنجن بيتقفل نهائيًا: أي رد متأخر من Gemini (طلب كان لسه في الجو) ما يقدرش يغيّر الترجمة ولا يتحفظ */
    @Volatile private var isClosed = false
    @Volatile var subs: List<Sub> = emptyList()
        private set(v) { if (!isClosed) field = v }
    private val chars = ArrayList<Chr>()
    private val gloss = ArrayList<Gloss>()
    private val tplCache = HashMap<String, String>()
    private val tplBusy = HashSet<String>()
    private val tplFails = HashMap<String, Int>()
    @Volatile private var srcLang = ""
    @Volatile private var detDone = false
    private val done = Ranges()
    private val failed = ConcurrentHashMap<Int, Int>()
    private val gapPass = ConcurrentHashMap<Int, Int>()
    @Volatile private var lastGapTry = 0L
    private val pool = Pool(conf)
    @Volatile private var running = true
    /** وقفة: المحرك مايبعتش مقاطع جديدة (مستني اختيار المستخدم: كمّل ولا ترجم من جديد) */
    @Volatile var paused = false
    /** إيقاف مؤقت بإيد المستخدم (زرار ⏸ في المشغّل): مفيش مقاطع جديدة بتتبعت لحد ما يتلغي — اللي شغال بيخلص عادي */
    @Volatile var userPaused = false
    /** لما الباتشات حوالين مكان التشغيل تخلص: كمّل ترجمة باقي الفيديو (قدّام ثم من الأول) بدل ما تقف */
    @Volatile var keepGoing = true
    /** ترجمة في الخلفية من غير مشغّل: بيمشي من أول مقطع ناقص وبيخلص لوحده */
    @Volatile var headless = false
    /** ترجمة باتشات محددة بس (0-based) — بعد ما تخلص المحرك بيستنى ومابيكمّلش لقدّام */
    @Volatile var onlyChunks: IntRange? = null
    /** لو اتحدد: المحرك يبدأ من المقطع ده بدل مكان التشغيل لحد ما المشاهدة توصله */
    @Volatile private var forcedCursor = -1
    @Volatile private var forcedSticky = false   // (v158) إعادة من باتش سابق: المؤشر يفضل عليه حتى لو مكان التشغيل بعده
    @Volatile private var fullRefineStarted = false
    /** (v160) مجالات الجمل اللي بتتنقّح دلوقتي (خط أزرق في شريط التقدم) */
    @Volatile var refiningSegs: List<DoubleArray> = emptyList()
    @Volatile private var fullRefineDone = false
    @Volatile private var finished = false
    @Volatile private var gapScanClean = false
    @Volatile var stoppedFlag = false
        private set
    var onFinished: ((Boolean) -> Unit)? = null
    @Volatile var fatal: String? = null
        private set
    @Volatile private var source: AudioSource? = null
    private val rr = AtomicInteger(0)
    private val bounds = ConcurrentHashMap<Int, Double>()      // بداية المقطع بعد تعديلها لأقرب صمت
    private val prepared = ConcurrentHashMap.newKeySet<Int>()
    private val trimLock = Any()
    private val inflight = ConcurrentHashMap.newKeySet<Int>()
    // ===== توزيع الباتشات على المفاتيح + نسخة احتياطية للباتش المتأخر (كل باتش يتحسب مرة واحدة بس) =====
    private val startedAt = ConcurrentHashMap<Int, Long>()          // وقت بداية الباتش الشغال
    private val preps = ConcurrentHashMap<Int, Prep>()              // صوت الباتش الشغال (عشان النسخة الاحتياطية ماتفكوش تاني)
    private val keyOf = ConcurrentHashMap<Int, String>()            // المفتاح اللي الباتش شغال عليه دلوقتي
    private val hedged = ConcurrentHashMap.newKeySet<Int>()         // باتشات اتبعتلها نسخة احتياطية
    private val claimed = ConcurrentHashMap.newKeySet<Int>()        // باتشات نتيجتها اتطبّقت (أول نسخة تخلص تكسب)
    /** (v151) باتشات فاشلة مستنية أقرب مفتاح فاضي: avoid = المفتاح اللي فشلت عليه، prep = الصوت المفكوك (من غير فك تاني) */
    private class Rehome(val avoid: String?, val at: Long, val prep: Prep?)
    private val rehomeQ = ConcurrentHashMap<Int, Rehome>()
    private val perKey get() = conf.parallelPerKey.coerceIn(1, 4)
    private val hedgeBusy = AtomicInteger(0)
    private val recentMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()   // أزمنة آخر باتشات خلصت
    // ===== (v146) مفتاح/موديل/زمن رد كل باتش + اختبار سرعة الموديلات =====
    class KeyRun(val keyNo: Int, val model: String, val ms: Long)
    private val runs = ConcurrentHashMap<Int, KeyRun>()                // آخر تنفيذ ناجح لكل باتش (مفتاح · موديل · زمن)
    /** الموديل اللي كسب اختبار السرعة: بيتستخدم بدل موديل الإعدادات لحد ما تغيّر الموديل يدوي */
    @Volatile private var modelOv: String? = null
    private val speedDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private val speedLines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun curModel(): String = modelOv ?: conf.model
    private fun keyNo(k: String): Int = pool.all().indexOf(k) + 1
    private fun shortModel(m: String) = m.removePrefix("gemini-")
    private class RaceWin(val key: String, val model: String, val res: Api.Result, val ms: Long)
    private fun fmtSec(ms: Long) = String.format(java.util.Locale.US, "%.1f", ms / 1000.0) + "ث"

    /** (v149) مرشحين الاختبار: الموديل اللي إنت مختاره + flash-lite-latest بس */
    private fun raceModels(): List<String> = listOf(conf.model, "gemini-flash-lite-latest").distinct()

    /**
     * أول باتش: بيتبعت على كل مفتاح صالح في نفس اللحظة، كل مفتاح بموديل مختلف. أول رد سليم هو اللي بيتطبّق،
     * وموديله بيتثبّت لكل الباتشات الجاية. باقي النتايج بتتسجّل في اللوج بأزمنتها. null = مفيش فايز (الإرسال العادي يكمّل).
     */
    private fun speedRace(i: Int, w: WavChunk): RaceWin? {
        val keys = pool.all().filter { pool.ok(it) }
        if (keys.size < 2) return null
        try {
            val cm = Cfg.p.getString("speed_model", "") ?: ""
            if (cm.isNotEmpty() && (Cfg.p.getString("speed_from", "") ?: "") == conf.model && System.currentTimeMillis() - Cfg.p.getLong("speed_at", 0L) < 3L * 3600_000L) {
                modelOv = cm; host.log("🏁 الموديل الأسرع (من اختبار قريب): ${shortModel(cm)}"); speedLines.add("🏁 الموديل المثبّت: ${shortModel(cm)} (من اختبار قريب)"); return null
            }
        } catch (_: Throwable) {}
        try { if (System.currentTimeMillis() - Cfg.p.getLong("speed_fail_at", 0L) < 10 * 60_000L) return null } catch (_: Throwable) {}   // (v150) فشل قريب: ماتعيدش الاختبار كل مرة
        val cands = raceModels()
        val n = keys.size   // (v149) كل المفاتيح الصالحة بتشارك، والموديلين بيتوزعوا عليها
        if (n < 2) return null
        val prompt = buildPrompt(w.durSec, w.startSec, false, i)
        host.log("🏎 اختبار سرعة: باتش ${i + 1} على $n مفاتيح بالموديلات: ${cands.joinToString(" و ") { shortModel(it) }}…")
        val winner = java.util.concurrent.atomic.AtomicReference<RaceWin?>(null)
        val mainWin = java.util.concurrent.atomic.AtomicReference<RaceWin?>(null)
        val mainLatch = java.util.concurrent.CountDownLatch(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        val left = java.util.concurrent.atomic.AtomicInteger(n)
        for (x in 0 until n) {
            val key = keys[x]; val model = cands[x % cands.size]
            Thread {
                val t0 = System.currentTimeMillis()
                var line: String
                try {
                    pool.begin(key)
                    val r = try { Api.generate(model, key, prompt, w.bytes).also { pool.end(key, System.currentTimeMillis() - t0, true) } } catch (e: Throwable) { pool.end(key, 0, false); throw e }
                    val ms = System.currentTimeMillis() - t0
                    val j = if (r.text.isBlank()) null else Parse.json(r.text)
                    val cnt = if (j == null) 0 else Parse.subs(j, w.startSec, w.durSec).size
                    val valid = (r.finish.isEmpty() || r.finish == "STOP") && (cnt > 0 || (r.text.isBlank() && w.silent))
                    line = "🔑${keyNo(key)} · ${shortModel(model)}: ${fmtSec(ms)} · $cnt جملة" + if (valid) "" else " ⚠ رد مش سليم"
                    if (valid && model == conf.model && mainWin.compareAndSet(null, RaceWin(key, model, r, ms))) mainLatch.countDown()
                    if (valid && winner.compareAndSet(null, RaceWin(key, model, r, ms))) { line += " 🏆"; latch.countDown() }
                } catch (e: Throwable) {
                    line = "🔑${keyNo(key)} · ${shortModel(model)}: ❌ " + (e.message ?: e.toString()).take(70)
                }
                host.log("🏎 $line"); speedLines.add(line)
                if (left.decrementAndGet() == 0) { latch.countDown(); mainLatch.countDown() }
            }.apply { isDaemon = true }.start()
        }
        try { latch.await(150, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { return null }
        var win: RaceWin? = winner.get()
        // (v150) flash-lite أضعف: لو الموديل اللي اخترته رد سليم في ≤6ث بعد الأسرع يفضل هو
        if (win != null && win!!.model != conf.model && cands.contains(conf.model)) {
            try { mainLatch.await(6, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
            val mw = mainWin.get(); if (mw != null) win = mw
        }
        if (win == null) {
            try { Cfg.p.edit().putLong("speed_fail_at", System.currentTimeMillis()).apply() } catch (_: Throwable) {}
            host.log("🏎 اختبار السرعة: مفيش رد سليم من أي مفتاح — كمّلت بالموديل العادي"); return null
        }
        val win0 = win!!
        modelOv = win0.model
        try { Cfg.p.edit().putString("speed_model", win0.model).putString("speed_from", conf.model).putLong("speed_at", System.currentTimeMillis()).apply() } catch (_: Throwable) {}
        host.log("🏁 الموديل الأسرع: ${shortModel(win0.model)} (${fmtSec(win0.ms)} على مفتاح ${keyNo(win0.key)}) — كل المفاتيح اتحوّلت له")
        host.notice("🏁 الموديل الأسرع: ${shortModel(win0.model)} — كل المفاتيح اتحوّلت له")
        return win0
    }

    @Volatile private var maxApplied = -1                           // أعلى باتش اتطبّق (لو في باتش قبله لسه شغال يبقى متأخر)
    private val hedgeEx = Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
    /** (v176) وضع التأكيد: الطلب التاني لنفس الباتش على مفتاح تاني */
    private val twinEx = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    @Volatile private var anyApplied = false
    private val gapTried = Ranges()      // بتتحفظ بين الجلسات
    private val gapSession = Ranges()    // لجلسة التشغيل دي بس (للحد الأقصى)
    private val gapBusy = AtomicInteger(0)
    private val gapKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastGapScan = 0L
    private val gapEx = Executors.newFixedThreadPool(Gaps.MAX_PARALLEL) { r -> Thread(r).also { it.isDaemon = true } }
    private var exec: java.util.concurrent.ExecutorService? = null
    private var autoCharsAttempts = 0
    /** (v107) اسم الفيلم/المسلسل من اسم الملف + الفولدر — بنبحث بيه عن الشخصيات مرة واحدة */
    @Volatile var titleHint: String = ""
    @Volatile private var lookupState = 0   // 0 لسه · 1 شغال · 2 خلص
    /** (v170) بنك بصمات الأصوات: بيفضل طول الفيديو وبيتحفظ مع التقدم، وبيتحوّل لنص في prompt كل مقطع */
    private val voices = VoiceBank()
    /** تقسيم آلي مبدئي لكل مقطع قبل الإرسال (المفتاح = بداية المقطع بالسنتي ثانية) */
    private val voiceHints = ConcurrentHashMap<Long, String>()
    /** (v170) العمل اتعرف عليه (من الاسم أو من الحوار) وشخصياته اتضافت */
    @Volatile private var castFound = false
    private var identTried = false
    private var chunksDone = 0; private var lastCharChunk = 0
    private var charsTrySize = 0
    private var charsBusy = false
    private var pronUpTo = 0
    private var pronBusy = false
    private val reviews = AtomicInteger(0)
    /** لهجة التحويل التلقائي: الترجمة بتتعمل بالفصحى الحرفية وكل ~3 باتشات بتتحوّل للهجة دي. "" = من غير تحويل */
    @Volatile var convDialect: String = ""
    /** أسلوب الترجمة الحي (حرفي/شعبي/جرئ/+18) وشدته (خفيفة/متوسطة/شديدة): بيتحط في أول برومبت كل باتش جديد وكل تحويل لهجة */
    @Volatile var toneStyle: String = ""
    @Volatile var toneStrength: String = "متوسطة"
    fun toneBlock(): String {
        val st = toneStyle
        if (st.isBlank() || st == "حرفي") return ""
        val what = when (st) {
            "شعبي" -> "كلام الشارع الشعبي والتعبيرات اليومية بدل الرسمي والكتابي"
            "جرئ" -> "أجرأ وأكتر حرية في الألفاظ والتعبيرات من غير تلطيف زيادة"
            "+18" -> "صريح بلا تلطيف: الألفاظ الخارجة والإيحاءات تتترجم بصراحة تطابق النص الأصلي بالظبط، من غير حذف ولا تخفيف"
            else -> return ""
        }
        val deg = when (toneStrength) { "خفيفة" -> "خفيفة: لمسة بسيطة بس في الجمل اللي تستاهل"; "شديدة" -> "شديدة: في كل جملة تقريبًا وبأقصى درجة"; else -> "متوسطة: في معظم الجمل بدرجة معتدلة" }
        return "\n═══ أسلوب الترجمة (إلزامي) ═══\n- الأسلوب: $st — $what.\n- الشدة: $deg.\n- الأسلوب ده بيتطبّق على كل translated مع الحفاظ الكامل على المعنى وجنس المتكلم والمخاطَب.\n\n"
    }
    private val convBusy = AtomicBoolean(false)
    @Volatile private var lastConvFail = 0L
    @Volatile private var convFailN = 0
    /** (v150) جمل conv=true لسه شكلها فصحى: بتتعاد مرة واحدة بس (عشان ما نلفّش في حلقة) */
    private val msaTried = ConcurrentHashMap.newKeySet<String>()
    private fun needsConv(s: Sub) = !s.conv || (Msa.fusha(s) && pk(s) !in msaTried)
    /** باتشات رجعت ناقصة (رد اتقطع / من غير جمل رغم وجود صوت) */
    private val incomplete = ConcurrentHashMap.newKeySet<Int>()
    /** باتشات المستخدم طلب إعادتها بالإيد: نتخطّى فلتر الصمت (VAD) عليها عشان ماتتعلّمش «خلصت» من غير ما تتبعت */
    private val forceVad = ConcurrentHashMap.newKeySet<Int>()
    private val bg = Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
    private val ch get() = conf.chunkSec.toDouble()
    /** عدد المقاطع قدّام = الأكبر بين الإعداد وعدد الطلبات المتوازية، عشان كل المفاتيح تشتغل مع بعض */
    private val window get() = maxOf(conf.ahead.coerceAtLeast(1), pool.capacity(conf.parallelPerKey))

    // ===== واجهة للـ UI =====
    fun failedCount() = failed.count { it.value >= MAX_FAILS }
    /** حالة الباتشات حوالين مكان التشغيل للوج العايم: سطر لكل باتش (✅ خلص · ⏳ بيترجم · ❌ فشل · ▫ مستني) */
    fun batchLines(): String = batchRows().joinToString("\n") { it.first }
    /** (v146) سطر لكل باتش + هل هو الأسرع في دفعته (بيتلوّن أخضر). الدفعة = الباتشات اللي بتتبعت مع بعض (عددها = عدد الطلبات المتوازية) */
    fun batchRows(): List<Pair<String, Boolean>> {
        val d = host.playerDuration()
        val c = (host.position() / ch).toInt().coerceAtLeast(0)
        val cap = pool.capacity(conf.parallelPerKey).coerceAtLeast(1)
        val out = ArrayList<Pair<String, Boolean>>()
        val fastest = HashMap<Int, Int>()   // الدفعة → رقم الباتش الأسرع
        val byWave = runs.entries.groupBy { it.key / cap }
        for ((wv, es) in byWave) if (es.size >= 2) fastest[wv] = es.minByOrNull { it.value.ms }!!.key
        if (speedLines.isNotEmpty()) out.add(Pair("🏎 اختبار الموديلات:\n  " + speedLines.joinToString("\n  "), false))
        for (i in maxOf(0, c - 1)..c + window + 1) {
            if (d > 0 && cStart(i) >= d) break
            val mark = when {
                i in inflight -> "⏳"
                (failed[i] ?: 0) >= MAX_FAILS -> "❌"
                i in incomplete -> "⚠"
                isDone(i, d) -> if (subCount(i, d) == 0) "🔇" else "✅"
                (failed[i] ?: 0) > 0 -> "🔁"
                else -> "▫"
            }
            val a = cStart(i).toInt(); val b = cEnd(i, d).toInt()
            val sb = StringBuilder()
            sb.append(if (i == c) "▶ " else "  ").append("باتش ").append(i + 1).append(" ")
                .append("%d:%02d–%d:%02d".format(a / 60, a % 60, b / 60, b % 60)).append(" ").append(mark)
            if (mark == "✅" || mark == "🔁" || mark == "🔇") sb.append(" ").append(subCount(i, d)).append(" جملة")
            val k = keyOf[i]
            val r = runs[i]
            var fast = false
            if (mark == "⏳" && k != null) {
                sb.append(" · 🔑").append(keyNo(k)).append(" ").append(shortModel(modelOv ?: conf.model))
                startedAt[i]?.let { sb.append(" · ").append(fmtSec(System.currentTimeMillis() - it)) }
            } else if (r != null && (mark == "✅" || mark == "🔇" || mark == "🔁")) {
                fast = fastest[i / cap] == i
                sb.append(" · 🔑").append(r.keyNo).append(" · ").append(fmtSec(r.ms)).append(" · ").append(shortModel(r.model)).append(if (fast) " ⚡" else "")
            }
            out.add(Pair(sb.toString(), fast))
        }
        return out
    }
    // ===== أشرطة التقدم (v56) =====
    /** جمل مستنية تتغيّر (تحويل لهجة / إعادة صياغة / تصحيح ضمائر) — مفتاحها start|original */
    private val pend = ConcurrentHashMap.newKeySet<String>()
    private fun pk(s: Sub) = "${s.start}|${s.original}"
    /** مجالات فيها جمل ترجمة فعلًا (الشريط الأخضر): بيتقطع عند الفجوات الحقيقية (> 2ث) مش عند حدود الباتشات */
    fun coverageSegs(): List<DoubleArray> = Coverage.spans(subs, 2.0)
    /** مجالات الجمل اللي لسه هتتغير (الشريط التالت)، فاضية لما كل الجمل تتغير */
    fun pendingSegs(): List<DoubleArray> { if (pend.isEmpty()) return emptyList(); return Coverage.spans(subs.filter { pk(it) in pend }, 3.0) }
    fun pendingCount(): Int = pend.size

    fun coveredSec() = done.total()
    fun durationSec(): Double = currentDur()
    fun chunkCount(): Int { val d = currentDur(); if (d <= 0) return 0; var i = 0; while (cStart(i) < d) i++; return i }
    fun awaitStopped(ms: Long) { val t0 = System.currentTimeMillis(); while (!stoppedFlag && System.currentTimeMillis() - t0 < ms) try { Thread.sleep(50) } catch (_: InterruptedException) { return } }

    class BatchInfo(val idx: Int, val start: Double, val end: Double, val mark: String, val count: Int = 0)
    /** كل الباتشات بحالتها (✅ خلص · ⏳ بيترجم · ❌ فشل · 🔁 بيعيد · ▫ لسه) — لقايمة إعادة الترجمة */
    fun batches(): List<BatchInfo> {
        val d = currentDur(); if (d <= 0) return emptyList()
        val out = ArrayList<BatchInfo>(); var i = 0
        while (cStart(i) < d) {
            val mark = when {
                i in inflight -> "⏳"
                (failed[i] ?: 0) >= MAX_FAILS -> "❌"
                i in incomplete -> "⚠"
                isDone(i, d) -> if (subCount(i, d) == 0) "🔇" else "✅"
                (failed[i] ?: 0) > 0 -> "🔁"
                else -> "▫"
            }
            out.add(BatchInfo(i, cStart(i), cEnd(i, d), mark, subCount(i, d))); i++
        }
        return out
    }
    /** الباتشات اللي ما اترجمتش (❌) أو رجعت ناقصة (⚠) وملهاش طلب شغال دلوقتي */
    fun problems(): List<BatchInfo> {
        // بتتنادي من الواجهة كل ~0.7ث: ما نلفّش على كل باتشات الفيديو ونعدّ الجمل لكل واحد — بس على الباتشات الفاشلة/الناقصة
        val idx = java.util.TreeSet<Int>()
        for ((k, v) in failed) if (v >= MAX_FAILS) idx.add(k)
        idx.addAll(incomplete)
        if (idx.isEmpty()) return emptyList()
        val d = currentDur(); if (d <= 0) return emptyList()
        return idx.filter { it !in inflight && cStart(it) < d }
            .map { BatchInfo(it, cStart(it), cEnd(it, d), if ((failed[it] ?: 0) >= MAX_FAILS) "❌" else "⚠", 0) }
    }
    // ===== إعادة تلقائية للباتشات الفاشلة/الناقصة (من غير ما المستخدم يدوس حاجة) =====
    private val autoTries = ConcurrentHashMap<Int, Int>()
    private val autoAt = ConcurrentHashMap<Int, Long>()
    private val announced = ConcurrentHashMap.newKeySet<Int>()
    private var lastAutoTick = 0L
    private fun autoRetryTick() {
        val now = System.currentTimeMillis()
        if (now - lastAutoTick < 4000L) return
        lastAutoTick = now
        val d = currentDur(); if (d <= 0) return
        // باتش اتبلّغ إنه فشل واتترجم دلوقتي → إشعار نجاح صغير
        for (i in announced.toList()) {
            if (i in inflight || i in incomplete || (failed[i] ?: 0) >= MAX_FAILS || !isDone(i, d)) continue
            announced.remove(i); autoTries.remove(i); autoAt.remove(i)
            host.notice("✅ تمت ترجمة باتش ${i + 1}")
            host.log("✅ باتش ${i + 1}: اتترجم بعد الإعادة التلقائية")
        }
        for (b in problems()) {
            val i = b.idx
            val zero = b.mark == "⚠" && subCount(i, d) == 0   // رجع من غير جمل: يتعاد مرة واحدة بس للتأكد
            val limit = if (zero) 1 else 3
            val n = autoTries[i] ?: 0
            if (n >= limit) {
                if (zero) { incomplete.remove(i); announced.remove(i); host.notice("🔇 باتش ${i + 1}: اتأكدت إن مفيهوش جمل"); host.log("🔇 باتش ${i + 1}: اتعاد وبرضه من غير جمل — اتأكد إنه فعلاً فاضي") }
                else if (announced.remove(i)) { host.notice("⛔ باتش ${i + 1} لسه فاشل بعد $n محاولات"); host.log("⛔ باتش ${i + 1}: الإعادة التلقائية خلصت ($n) وبرضه فاشل") }
                continue
            }
            if (now < (autoAt[i] ?: 0L)) continue
            autoTries[i] = n + 1; autoAt[i] = now + 12_000L * (n + 1)
            if (announced.add(i)) host.notice(if (zero) "⚠ باتش ${i + 1} رجع من غير جمل — بعيده للتأكد" else if (b.mark == "❌") "❌ باتش ${i + 1} فشل — بعيده تلقائي" else "⚠ باتش ${i + 1} رجع ناقص — بعيده تلقائي")
            host.log("🔁 إعادة تلقائية لباتش ${i + 1} (${n + 1}/$limit)")
            failed.remove(i); incomplete.remove(i); forceVad.add(i); priorityRetry.add(i)
        }
    }
    /** إعادة ترجمة باتش واحد على المفاتيح الاحتياطية */
    fun retryOnBackup(i: Int) = retryOnBackupSeq(listOf(i))

    /** إعادة مجموعة باتشات على المفاتيح الاحتياطية بالتوالي (واحد ورا التاني: الأقدم فالأحدث) في خيط واحد — مش كلهم في نفس الوقت */
    fun retryOnBackupSeq(list: List<Int>) {
        val todo = list.distinct().sorted().filter { inflight.add(it) }
        if (todo.isEmpty()) return
        host.log("🔁 إعادة ${todo.size} باتش على المفاتيح الاحتياطية بالتوالي (الأقدم فالأحدث): " + todo.joinToString("، ") { (it + 1).toString() })
        Thread {
            for (i in todo) {
                try {
                    if (!running) { continue }
                    incomplete.remove(i); failed.remove(i)
                    host.log("🔁 إعادة باتش ${i + 1} على المفاتيح الاحتياطية…")
                    val p = prepare(i, true)
                    if (p != null) { send(i, p, true); host.log("✅ باتش ${i + 1}: خلصت إعادة الترجمة") }
                    else host.log("⚠ باتش ${i + 1}: مفيش صوت اتفك — جرّب تاني بعد شوية")
                } catch (e: Exception) {
                    failed[i] = MAX_FAILS
                    host.log("⚠ إعادة باتش ${i + 1} فشلت: " + (e.message ?: e.toString()).take(160))
                } finally { inflight.remove(i); host.changed(); persist() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** باتشات فاشلة/ناقصة مطلوب إعادتها بالأولوية على المفاتيح الأساسية (لما تكون أكتر من 3) */
    private val priorityRetry = java.util.concurrent.ConcurrentSkipListSet<Int>()
    /** عدد الباتشات اللي مستنية دورها في الإعادة بالأولوية */
    fun priorityPending(): Int = priorityRetry.size

    /**
     * زرار «إعادة» في الشريط الأحمر: بيعيد كل الباتشات المشكلة.
     * - 3 أو أقل: على المفاتيح الاحتياطية بالتوالي (الأقدم فالأحدث).
     * - أكتر من 3: مش هتتزحم على الاحتياطي — الترجمة العادية بتقف عن إرسال باتشات جديدة (اللي شغّال بيخلص)،
     *   والفاشلين بيتترجموا الأول على المفاتيح الأساسية بالترتيب، وبعدها الترجمة بتكمّل باقي الباتشات.
     * بيرجّع عدد الباتشات.
     */
    fun retryProblems(): Int {
        val idx = problems().map { it.idx }.sorted()
        if (idx.isEmpty()) return 0
        if (idx.size <= 3 || !running) retryOnBackupSeq(idx)   // لو حلقة الترجمة مش شغالة مفيش مين يستلم الأولوية، فبالتوالي على الاحتياطي
        else {
            for (i in idx) { failed.remove(i); incomplete.remove(i); priorityRetry.add(i) }
            host.log("🔁 ${idx.size} باتش فاشل: هترجمهم الأول على المفاتيح الأساسية بالترتيب وبعدها أكمّل باقي الباتشات")
            host.changed(); persist()
        }
        return idx.size
    }

    /** اختيار لهجة التحويل. existing=true: بيحوّل كمان اللي اتترجم قبل كده — من مكان المشاهدة وللقدّام الأول، وبعدين اللي قبله. بيحفظ نسخة باللهجة القديمة قبل التحويل (🗂) */
    fun setConvDialect(d: String, existing: Boolean) {
        val prev = convDialect
        if (existing && subs.isNotEmpty() && prev != d) { val lb = if (prev.isBlank()) "فصحى" else prev; saveVersion("ترجمة $lb", lb) }
        convDialect = d
        if (d.isBlank() || d == "فصحى") { pend.clear(); return }
        lastConvFail = 0L; convFailN = 0
        if (existing) synchronized(lock) { subs = subs.map { it.copy(conv = false) }; pend.addAll(subs.map { pk(it) }) }
        maybeConvert(true)
    }
    /** تبديل اللهجة من غير تحويل (لما نرجّع نسخة محفوظة): الجمل الجديدة تتحوّل للهجة دي */
    fun adoptDialect(d: String) {
        convDialect = d
        synchronized(lock) { subs = subs.map { it.copy(conv = true) } }; pend.clear()
    }
    /** بيحوّل الجمل اللي لسه ما اتحوّلتش كل ~3 باتشات (أو الباقي لما الترجمة تهدى) على المفاتيح الاحتياطية */
    fun maybeConvert(force: Boolean = false) {
        val tgt = convDialect
        if (tgt.isBlank() || tgt == "فصحى" || tgt == conf.lang) return
        if (System.currentTimeMillis() - lastConvFail < minOf(60_000L, 10_000L shl minOf(convFailN, 3))) return
        val todo = synchronized(lock) { subs.filter { needsConv(it) } }
        if (todo.isEmpty()) return
        val span = todo.maxOf { it.end } - todo.minOf { it.start }
        if (!force && span < ch * 0.5) return
        if (!convBusy.compareAndSet(false, true)) return
        try { bg.submit { try { convertSubs(tgt) } finally { convBusy.set(false) } } } catch (_: Exception) { convBusy.set(false) }
    }
    private fun convertSubs(tgt: String): Int {
        var changed = 0
        val snap = synchronized(lock) { subs }
        val idx0 = snap.indices.filter { needsConv(snap[it]) }
        if (idx0.isEmpty()) return 0
        for (k in idx0) if (snap[k].conv) msaTried.add(pk(snap[k]))
        // من أول الباتش اللي المشاهد فيه ولقدّام الأول، وبعدين اللي قبله
        val focus = try { cStart(maxOf(0, (host.position() / ch).toInt())) } catch (_: Exception) { 0.0 }
        val idx = idx0.filter { snap[it].start >= focus - 0.05 } + idx0.filter { snap[it].start < focus - 0.05 }
        val batches = idx.chunked(40)
        host.log("🌐 تحويل ${idx.size} جملة للهجة $tgt (من ${(focus / 60).toInt()}:%02d وللقدّام الأول)…".format(focus.toInt() % 60))
        batches.forEachIndexed { bi, batch ->
            if (!running) return@forEachIndexed
            val list = JSONArray()
            for (k in batch) { val s = snap[k]; list.put(JSONObject().put("idx", k).put("original", s.original).put("translated", s.translated).put("speaker_gender", s.gender).put("addressee_gender", s.addressee)) }
            val prompt = "أنت محرر ترجمة محترف. الجمل دي مترجمة بالفصحى الحرفية. حوّل حقل translated في كل جملة للهجة $tgt الحقيقية (زي ما أهلها بيتكلموا فعلًا) مع الحفاظ الكامل على المعنى والأسماء والأرقام وجنس المتكلم speaker_gender والمخاطَب addressee_gender.\n" +
                "- ماتدمجش ولا تقسّم جمل ولا تغيّر عددها أو ترتيبها. original للمرجع بس (لو الفصحى فيها غلط في المعنى صحّحه من original).\n" +
                "- 🔴 لازم ترجّع كل الجمل (حتى لو الجملة أصلًا قريبة من اللهجة) بالصياغة النهائية باللهجة.\n" +
                pb.dialectBlock(tgt) + toneBlock() + "\nالجمل:\n$list\n\n" +
                "أرجع JSON فقط: {\"rewrites\":[{\"idx\":0,\"translated\":\"النص باللهجة\"}]}"
            try {
                val r = bgCall(prompt, 8192, 0.3, true, bi)
                val arr = (Parse.json(r.text) ?: throw BadReply("رد التحويل مش JSON")).optJSONArray("rewrites") ?: throw BadReply("رد التحويل من غير rewrites")
                synchronized(lock) {
                    val cur = subs.toMutableList(); val got = HashSet<Int>()
                    for (q in 0 until arr.length()) {
                        val c = arr.optJSONObject(q) ?: continue
                        val ix = c.optInt("idx", -1); if (ix !in batch) continue
                        val tr = c.optString("translated").trim(); if (tr.isEmpty()) continue
                        val t = snap[ix]
                        val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                        if (at >= 0) { cur[at] = cur[at].copy(translated = tr, conv = true); got.add(ix); if (tr != t.translated) changed++ }
                    }
                    // اللي ما رجعش في الرد يفضل غير محوّل (هيتحاول تاني)، مش بيتعلّم
                    subs = cur
                    for (ix in got) pend.remove(pk(snap[ix]))
                }
                host.changed(); convFailN = 0
            } catch (e: Exception) { lastConvFail = System.currentTimeMillis(); convFailN++; host.log("⚠ التحويل للهجة فشل: " + (e.message ?: "").take(130)) }
        }
        if (changed > 0) { persist(); host.log("✅ اتحوّلت $changed جملة للهجة $tgt") }
        return changed
    }
    fun chunkOfSec(sec: Double): Int { var i = 0; while (cStart(i + 1) <= sec) i++; return i }
    fun chunkStartSec(i: Int) = cStart(i)

    /** يمسح ترجمة الباتشات من..إلى (شاملة، to < 0 = لحد النهاية) عشان تتترجم من جديد. بيحفظ نسخة قبلها (🗂 ترجمات الفيديو) */
    fun redo(from: Int, to: Int, keep: Boolean = true) {
        val d = currentDur()
        val hi = if (to < 0) Int.MAX_VALUE else to
        val a = cStart(from); val b = if (to < 0) Double.MAX_VALUE else cEnd(to, d)
        if (keep && subs.isNotEmpty()) saveVersion("قبل إعادة الترجمة من باتش ${from + 1}")
        synchronized(lock) { subs = subs.filter { !(it.chunk in from..hi) && !(it.start >= a && it.start < b) } }
        done.remove(a, b); gapTried.remove(a, b)
        val last = if (to < 0) (if (d > 0) chunkCount() else from + 1000) else to
        for (i in from..last) { failed.remove(i); gapPass.remove(i); holeTried.remove(i); claimed.remove(i); rehomeQ.remove(i) }
        pool.clear(); lastGapTry = 0L; gapScanClean = false
        fullRefineStarted = false; fullRefineDone = false
        host.changed(); persist()
    }
    /** باتش ده وبعده كله (بيبدأ منه ويكمل) */
    fun redoFrom(i: Int, keep: Boolean = true) { redo(i, -1, keep); onlyChunks = null; forcedSticky = true; forcedCursor = i; paused = false }
    /** باتش ده لوحده وخلاص */
    fun redoOnly(i: Int, keep: Boolean = true) { redo(i, i, keep); forceVad.add(i); onlyChunks = i..i; paused = false }
    /** من الأول خالص */
    fun redoAll(keep: Boolean = true) { redo(0, -1, keep); onlyChunks = null; forcedSticky = true; forcedCursor = 0; paused = false }
    fun resumeAuto() { onlyChunks = null; paused = false }
    /** كمّل الترجمة من المكان ده (من غير مسح حاجة): بيقفز بالمؤشر لباتش الوقت ده ويلغي الإيقاف */
    fun translateFrom(sec: Double) { onlyChunks = null; forcedSticky = false; forcedCursor = chunkOfSec(sec.coerceAtLeast(0.0)); paused = false; userPaused = false }
    fun stop() { isClosed = true; running = false; bg.shutdownNow(); gapEx.shutdownNow(); exec?.shutdownNow(); try { hedgeEx.shutdownNow() } catch (_: Exception) {}; try { twinEx.shutdownNow() } catch (_: Exception) {}; try { holeEx.shutdownNow() } catch (_: Exception) {}; try { persistEx.shutdownNow() } catch (_: Exception) {} }
    /** (v122) إعدادات اتغيّرت من شاشة الإعدادات (لهجة / أسلوب / موديل / شخصيات…): تتطبّق على الباتشات والتحويلات الجاية من غير إعادة تشغيل المحرك */
    fun applyConf(c: Conf) { if (c.model != conf.model) { modelOv = null; speedDone.set(true) }; conf = c; dialConf.clear() }
    fun saveNow() = doPersist(true)
    /** استيراد ترجمة جاهزة (SRT) لفيديو من غير ترجمة */
    fun importSubs(l: List<Sub>) { subs = l }
    fun retryFailed() {
        pool.clear(); gapPass.clear(); gapTried.clear(); lastGapTry = 0L
        for (k in failed.keys) failed[k] = MAX_FAILS
        host.log("🔁 هعيد محاولة المقاطع الفاشلة (${failedCount()})")
    }
    fun charactersNow(): List<Chr> = effectiveChars()

    /**
     * (v151) زرار 🧑 في المشغّل: بيحلّل الشخصيات من الترجمة الحالية دلوقتي (حتى لو التحليل التلقائي مقفول، أو الفيديو اتترجم قبل كده
     * ومفيش ولا باتش جديد يشغّل التحليل). الشخصيات اليدوية من الإعدادات هي المرجع ومبتتلمسش.
     * done بتتنادى مرة واحدة بالقايمة النهائية (من خيط تاني).
     */
    fun analyzeCharsNow(report: (String) -> Unit, done: (List<Chr>) -> Unit) {
        if (conf.manualChars.isNotEmpty()) { done(conf.manualChars); return }
        if (subs.size < 4) { report("لسه مفيش جمل مترجمة كفاية للتحليل"); done(effectiveChars()); return }
        val go = synchronized(lock) { if (charsBusy) false else { charsBusy = true; true } }
        if (!go) { report("التحليل شغّال دلوقتي — استنى لحظة"); done(effectiveChars()); return }
        Thread {
            try { analyzeChars() } catch (e: Exception) {
                host.log("⚠ تحليل الشخصيات فشل: " + (e.message ?: e.toString()).take(100))
                report("التحليل فشل: " + (e.message ?: "").take(80))
            } finally { synchronized(lock) { charsBusy = false }; persist() }
            done(effectiveChars())
        }.apply { isDaemon = true }.start()
    }

    /** يسترجع التقدم المحفوظ. يرجع آخر مكان تشغيل (بالثواني) أو 0. */
    fun load(): Double {
        val s = store?.load() ?: return 0.0
        synchronized(lock) {
            subs = Subs.unifyOverlaps(Subs.dedup((if (s.chunkSec == conf.chunkSec) s.subs else s.subs.map { it.copy(chunk = -1) }).map { Subs.collapseSelfRepeat(it) }))
            for (r in s.done) done.add(r[0], r[1])
            for (r in s.failed) failed[Math.round(r[0] / ch).toInt()] = MAX_FAILS
            chars.addAll(s.chars); gloss.addAll(s.gloss); tplCache.putAll(s.tpl)
            voices.fromJson(s.voices)
            srcLang = s.srcLang; detDone = s.detDone; autoCharsAttempts = s.charsTried
            if (s.detDone) anyApplied = true
            pronUpTo = subs.size
            if (s.chunkSec == conf.chunkSec) bounds.putAll(s.bounds)
            for (r in s.gapTried) gapTried.add(r[0], r[1])
        }
        host.log("♻ استرجعت ${s.subs.size} جملة و${(done.total() / 60).toInt()} دقيقة مترجمة من الجلسة اللي فاتت")
        host.changed()
        return s.pos
    }

    // ===== الحلقة الرئيسية =====
    // ملحوظة: فك الصوت (والتقصير لأقرب صمت) بيتم بالترتيب في الخيط ده، وإرسال الطلبات لجيميناي بيتم بالتوازي.
    private fun cStart(i: Int): Double = bounds[i] ?: (i * ch)
    private fun cEnd(i: Int, d: Double): Double { val e = cStart(i + 1); return if (d > 0) minOf(e, d) else e }

    fun run() {
        if (conf.keys.isEmpty() && conf.backup.isEmpty()) { host.status("⚠ ادخل مفتاح Gemini في الإعدادات"); host.log("⚠ مفيش مفاتيح Gemini"); return }
        val cap = pool.capacity(conf.parallelPerKey)
        val ex = Executors.newFixedThreadPool(cap + 2) { r -> Thread(r).also { it.isDaemon = true } }   // +2: باتش اتنسخ واتحسب من نسخته الاحتياطية ممكن يفضل خيطه الأصلي شغال شوية من غير ما يحجز مكان باتش جديد
        exec = ex
        if (cap > 1) host.log("⚡ ترجمة متوازية: لحد $cap طلب في نفس الوقت")
        try {
            if (headless) try { src() } catch (e: Exception) { host.log("⚠ معرفتش أفتح الفيديو: " + (e.message ?: e.toString()).take(120)) }
            while (running) {
                val d = currentDur()
                if (headless && d <= 0 && (failed[0] ?: 0) >= MAX_FAILS) { fatal = "معرفتش أفتح الفيديو أو أقرأ مدته"; host.log("⛔ " + fatal); host.status("⛔ " + fatal); running = false; break }
                if (headless && !anyApplied && failedCount() >= 5) { fatal = "المقاطع بتفشل ورا بعض — راجع المفاتيح والنت"; host.log("⛔ " + fatal); host.status("⛔ " + fatal); running = false; break }
                if (userPaused) { host.status("⏸ الترجمة واقفة مؤقتًا — دوس «إلغاء الإيقاف» تكمّل"); nap(300); continue }
                if (paused) { host.status("⏸ مستني اختيارك: كمّل على الترجمة الحالية ولا ترجم من جديد"); nap(300); continue }
                hedgeTick()
                autoRetryTick()
                // (v151) باتش فشل: يتبعت الأول لأقرب مفتاح خلّص باتشه (مفيش باتشين على مفتاح واحد) وبعدين الترجمة تكمّل باقي الباتشات عادي
                if (rehomeQ.isNotEmpty()) {
                    val ri = rehomeQ.keys.minOrNull()
                    val re = ri?.let { rehomeQ[it] }
                    if (ri == null || re == null || ri in inflight || isDone(ri, d)) { if (ri != null) rehomeQ.remove(ri); continue }
                    var rk = pool.pickIdle(ri, re.avoid, perKey)
                    if (rk == null && System.currentTimeMillis() - re.at > 20_000L) rk = pool.pickIdle(ri, null, perKey)
                    if (rk == null) { nap(150); continue }
                    rehomeQ.remove(ri)
                    host.log("🔁 باتش ${ri + 1} (فاشل) → أقرب مفتاح فاضي ${pool.tail(rk)} — قبل باقي الباتشات")
                    dispatch(ri, ex, true, rk, re.prep); continue
                }
                // إعادة الباتشات الفاشلة بالأولوية (أكتر من 3): مفيش باتشات جديدة تتبعت لحد ما الفاشلين يتبعتوا، وبعدين الترجمة تكمّل عادي
                if (priorityRetry.isNotEmpty()) {
                    val nx = priorityRetry.firstOrNull { it !in inflight }
                    if (nx == null) { nap(150); continue }
                    if (inflight.size >= cap) { nap(150); continue }
                    priorityRetry.remove(nx)
                    host.log("🔁 باتش ${nx + 1} (أولوية) — على المفاتيح الأساسية، باقي ${priorityRetry.size}")
                    dispatch(nx, ex, true); continue
                }
                var c = if (headless) firstUndone(d) else (host.position() / ch).toInt().coerceAtLeast(0)
                val fc = forcedCursor
                if (fc >= 0) {
                    var k = fc
                    while (!(d > 0 && cStart(k) >= d) && (isDone(k, d) || (failed[k] ?: 0) >= MAX_FAILS)) k++
                    forcedCursor = k
                    if (d > 0 && cStart(k) >= d) forcedCursor = -1
                    else if (forcedSticky) { c = k; if (k > (host.position() / ch).toInt()) forcedCursor = -1 }
                    else if (c >= k) forcedCursor = -1 else c = k
                }
                val only = onlyChunks
                val lo = only?.first ?: c
                val hi = only?.last ?: (c + window - 1)
                var next = -1
                for (i in lo..hi) {
                    if (d > 0 && cStart(i) >= d) break
                    if (isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || i in inflight || rehomeQ.containsKey(i)) continue
                    next = i; break
                }
                if (next < 0 && only == null && keepGoing && d > 0) next = nextUndone(c + window, d)
                if (next < 0 && only == null && keepGoing && d > 0) next = nextUndone(0, d)
                if (next >= 0) {
                    // أول مقطع لوحده (عشان نعرف لغة الفيديو ونختار القالب الصح)، وبعدين بالتوازي
                    if (inflight.size >= cap || (!anyApplied && inflight.isNotEmpty())) { gapTick(); nap(150); continue }
                    dispatch(next, ex); continue
                }
                if (inflight.isEmpty() && retryGaps()) continue
                if (inflight.isEmpty() && repairForeignTick()) continue
                if (gapTick(headless || keepGoing)) continue
                if (inflight.isEmpty() && gapBusy.get() == 0 && holeBusy.get() == 0 && finalRefineTick()) { nap(300); continue }   // (v140) تنقيح نهائي قبل ما نقول «خلصت»
                // (v158) كل المقاطع خلصت: تنقيح كامل واحد بالفيديو كله (بعد التنقيح الجزئي مقطع مقطع)
                if (inflight.isEmpty() && gapBusy.get() == 0 && holeBusy.get() == 0 && conf.partialRefine && d > 0 && cStart(firstUndone(d)) >= d && !fullRefineStarted && subs.size >= 12 && toolBusy == null) {
                    fullRefineStarted = true; host.log("✍ تنقيح كامل نهائي (الفيديو كله في طلب واحد)…")
                    refineWholeNow({ host.status(it) }, { fullRefineDone = true }); nap(300); continue
                }
                if (fullRefineStarted && !fullRefineDone && toolBusy != null) { nap(300); continue }
                if (headless && inflight.isEmpty() && gapBusy.get() == 0 && holeBusy.get() == 0 && (gapScanClean || !conf.gapFill) && !hasRepairable() && d > 0 && cStart(firstUndone(d)) >= d) {
                    finished = true; host.status("✅ خلصت الترجمة"); break
                }
                if (inflight.isEmpty()) maybeConvert(true)
                idleStatus(d)
                nap(if (inflight.isEmpty()) 700 else 200)
            }
        } finally {
            ex.shutdownNow()
            try { source?.close() } catch (_: Exception) {}
            doPersist()
            stoppedFlag = true
            try { onFinished?.invoke(finished && fatal == null) } catch (_: Exception) {}
        }
    }

    /** (v203) الفيديو كله مترجم فعلًا (كل المقاطع مخلّصة، من غير الفاشل) — بيتستخدم عشان «▶ ترجمة» ما تعيدش شغل مخلّص */
    fun fullyDone(): Boolean {
        val d = currentDur(); if (d <= 0) return false
        var i = 0
        while (cStart(i) < d) { if (!isDone(i, d)) return false; i++ }
        return true
    }

    /** أول مقطع ناقص من i وطالع (بيتخطّى المخلّص والفاشل والشغّال دلوقتي) أو -1 */
    private fun nextUndone(from: Int, d: Double): Int {
        var i = maxOf(0, from)
        while (cStart(i) < d) {
            if (!(isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || i in inflight || rehomeQ.containsKey(i))) return i
            i++
        }
        return -1
    }

    /** أول مقطع لسه ناقص (الفاشلين بيتخطّوا وبتتعالج في retryGaps) — للترجمة في الخلفية */
    private fun firstUndone(d: Double): Int {
        if (d <= 0) return 0
        var i = 0
        while (true) {
            if (d > 0 && cStart(i) >= d) return i
            if (!(isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS)) return i
            i++
        }
    }

    /** عدد الجمل اللي رجعت لباتش (بالوقت مش بالـ tag عشان يشتغل بعد التحميل من الملف) */
    fun subCount(i: Int, d: Double): Int { val a = cStart(i) - 0.001; val b = cEnd(i, d) - 0.001; return subs.count { it.start >= a && it.start < b } }
    private fun isDone(i: Int, d: Double): Boolean = done.covers(cStart(i), cEnd(i, d))

    @Volatile private var durCache = 0.0
    private fun currentDur(): Double {
        if (durCache > 0) return durCache
        val a = try { source?.durationSec() ?: 0.0 } catch (_: Exception) { 0.0 }
        if (a > 0) durCache = a
        return if (a > 0) a else host.playerDuration()
    }

    private fun nap(ms: Long) {
        var t = 0L
        while (running && t < ms) { try { Thread.sleep(minOf(200L, ms - t)) } catch (_: InterruptedException) { return }; t += 200 }
    }

    private var lastIdle = ""
    private fun idleStatus(d: Double) {
        val pct = if (d > 0) (done.total() * 100 / d).toInt().coerceAtMost(100) else 0
        val f = failedCount()
        val s = "✅ مترجم $pct% — ${subs.size} جملة" + (if (f > 0) " — ⚠ $f مقطع فاشل" else "")
        if (s != lastIdle) { lastIdle = s; host.status(s) }
    }

    /** بينفّذ خطوة خاصة بمقطع ويسجّل الفشل. بيرجع true لو نجحت. */
    private fun guard(i: Int, block: () -> Unit): Boolean {
        try {
            block(); return true
        } catch (e: Unsupported) {
            fatal = e.message
            host.log("⛔ ${e.message}"); host.status("⛔ ${e.message}")
            running = false
        } catch (e: RehomeEx) {
            if (!running) return false
            rehomeQ[i] = Rehome(e.avoid, System.currentTimeMillis(), preps[i])
            host.log("↪ باتش ${i + 1}: مفتاح ${pool.tail(e.avoid ?: "")} فشل ومفيش مفتاح فاضي — الباتش مستني أقرب مفتاح يخلص باتشه")
        } catch (e: Exception) {
            if (!running) return false
            val n = (failed[i] ?: 0) + 1
            failed[i] = n
            val failedKey = keyOf[i]
            val where = e.stackTrace.filter { it.className.startsWith("com.tttt") || it.className.startsWith("android.media") }.take(3).joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            host.log("⚠ مقطع ${i + 1} فشل ($n/$MAX_FAILS): " + (e.message ?: e.javaClass.simpleName).take(160) + (if (where.isNotEmpty()) " [$where]" else ""))
            if (n >= MAX_FAILS) host.log("🕳 المقطع ${i + 1} اتسجل كفجوة — هعيد محاولته تلقائيًا بعد شوية")
            persist()
            // (v151) باتش فشل على مفتاح: يروح لأقرب مفتاح تاني خلّص باتشه، والمفتاح اللي فشل يكمّل باتشاته عادي
            if (n < MAX_FAILS && failedKey != null && pool.usableCount() >= 2) {
                rehomeQ[i] = Rehome(failedKey, System.currentTimeMillis(), preps[i])
                nap(1000)
            } else nap(4000)
        }
        return false
    }

    /** فك صوت المقطع + الإرسال لجيميناي كله في خيط من الحوض: عدة باتشات بتفك صوتها وتتبعت بالتوازي على كل المفاتيح (قبل كده الفك كان بالترتيب في حلقة واحدة فالباتشات كانت بتمشي واحد ورا التاني) */
    private fun dispatch(i: Int, ex: java.util.concurrent.ExecutorService, force: Boolean = false, firstKey: String? = null, prep0: Prep? = null) {
        if (!inflight.add(i)) return
        startedAt[i] = System.currentTimeMillis(); hedged.remove(i)
        // (v151) المفتاح بيتحجز للباتش من لحظة الإرسال (قبل فك الصوت) عشان باتشين ما يتحطوش على نفس المفتاح
        val fk = firstKey ?: pool.pickIdle(i, null, perKey)
        try {
            ex.submit {
                try {
                    var p: Prep? = prep0
                    val ok = if (prep0 != null) { preps[i] = prep0; true } else guard(i) { val got = prepare(i, force); p = got; if (got != null) preps[i] = got }
                    if (ok) {
                        val pp = p
                        if (pp == null) failed.remove(i)
                        else { startedAt[i] = System.currentTimeMillis(); if (guard(i) { send(i, pp, firstKey = fk) }) failed.remove(i) }
                    }
                } finally { inflight.remove(i); preps.remove(i); startedAt.remove(i); keyOf.remove(i); pool.unreserve(i); host.changed() }
            }
        } catch (_: RejectedExecutionException) { inflight.remove(i); preps.remove(i); startedAt.remove(i); pool.unreserve(i) }
    }

    /**
     * باتش اتأخر (شغال أطول من ~2× المعتاد) وفي باتش بعده خلص: يبقى غالبًا واقع على مفتاح بطيء.
     * بنبعت نسخة منه على مفتاح تاني — أول نسخة تخلص هي اللي بتتطبّق والتانية بتتجاهل. نسخة واحدة بس في نفس الوقت.
     */
    private fun hedgeTick() {
        if (inflight.isEmpty() || hedgeBusy.get() >= 1 || pool.usableCount() < 2) return
        val now = System.currentTimeMillis()
        val ms = recentMs.toList().sorted()
        val med = if (ms.isEmpty()) 0L else ms[ms.size / 2]
        val limit = (if (med > 0) med * 22 / 10 else 40_000L).coerceIn(25_000L, 120_000L)
        val i = inflight.filter { it < maxApplied && it !in hedged && preps.containsKey(it) && (startedAt[it]?.let { s -> now - s > limit } ?: false) }.minOrNull() ?: return
        val p = preps[i] ?: return
        if (!hedged.add(i)) return
        hedgeBusy.incrementAndGet()
        host.log("⚡ باتش ${i + 1} اتأخر عن اللي بعده — بعتّه على مفتاح تاني (أول نسخة تخلص هي اللي تتحسب)")
        try {
            hedgeEx.submit {
                try { send(i, p, avoidKey = keyOf[i], hedge = true) } catch (_: Exception) {} finally { hedgeBusy.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { hedgeBusy.decrementAndGet() }
    }

    /** إعادة محاولة المقاطع الفاشلة لما نخلص الشغل اللي قدام المشاهد. */
    private fun retryGaps(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastGapTry < 30_000) return false
        val cand = failed.filter { it.value >= MAX_FAILS && (gapPass[it.key] ?: 0) < GAP_PASSES }.keys.sorted()
        if (cand.isEmpty()) return false
        lastGapTry = now
        host.log("🕳 إعادة محاولة ${cand.size} مقطع فاشل…")
        for (i in cand) {
            if (!running) break
            gapPass[i] = (gapPass[i] ?: 0) + 1
            try { translateChunk(i); failed.remove(i); host.log("✅ المقطع ${i + 1} اتترجم في إعادة المحاولة") }
            catch (e: Unsupported) { fatal = e.message; host.log("⛔ ${e.message}"); running = false; break }
            catch (e: Exception) { host.log("⚠ المقطع ${i + 1} لسه فاشل: " + (e.message ?: e.toString()).take(120)) }
        }
        return true
    }

    // ===== ترجمة مقطع =====
    @Synchronized private fun src(): AudioSource = source ?: openSource().also { it.setPreRoll(if (conf.hiTiming) 3.0 else 0.0); source = it }

    private class Prep(val w: WavChunk, val rawStart: Double, val rawEnd: Double)

    private fun translateChunk(i: Int) { prepare(i)?.let { send(i, it) } }

    /** يفك الصوت (ويقصّر المقطع لأقرب لحظة صمت). null = مفيش حاجة تتبعت (صامت / مفيش صوت). */
    private fun prepare(i: Int, force: Boolean = false): Prep? {
        claimed.remove(i)   // محاولة جديدة للباتش ده من الأول
        val d = currentDur()
        // بنحجز الباتش ونقرا حدوده في خطوة واحدة: لو باتش قبله بيقصّر حدّه بالتوازي ماينفعش يسيب ثغرة
        val firstTime: Boolean; val rawStart: Double; var rawEnd: Double
        synchronized(trimLock) { firstTime = prepared.add(i); rawStart = cStart(i); rawEnd = cEnd(i, d) }
        val start = if (i == 0) 0.0 else maxOf(0.0, rawStart - OVERLAP)
        host.status("⏳ ترجمة المقطع ${i + 1}…")
        val t0 = System.currentTimeMillis()
        val s0 = src()
        var w = s0.wav(start, rawEnd)
        // فك الصوت ساعات بيرجع null مؤقتًا (المصدر لسه بيفتح / شبكة) — نعيد قبل ما نعتبر المقطع من غير صوت
        var retry = 0
        while (w == null && running && retry++ < 2) { host.log("… المقطع ${i + 1}: الصوت لسه ما طلعش — محاولة ${retry + 1}/3"); nap(700); w = s0.wav(start, rawEnd) }
        if (w == null) {
            host.log("… المقطع ${i + 1} مفيهوش صوت")
            // لو ده مش آخر ذيل الفيديو يبقى غالبًا فشل فك مش صمت حقيقي: نعلّمه ⚠ عشان يتعاد بدل ما يبان ✅ فاضي
            if (d <= 0 || (rawEnd - rawStart > 5.0 && rawStart < d - 3.0)) { incomplete.add(i); host.log("⚠ باتش ${i + 1}: مقدرتش أفك صوته — أعده من علامة التحذير") }
            done.add(rawStart, rawEnd); persist(); return null
        }
        host.log("🎧 صوت المقطع ${i + 1}: ${w.bytes.size / 1024}KB في ${System.currentTimeMillis() - t0}ms")
        val forced = force || forceVad.remove(i)
        if (conf.vad && w.silent && !forced && !conf.soundTags) { host.log("🔇 المقطع ${i + 1} صامت — اتخطى (لو غلط: «☝ ده بس» بيبعته غصب)"); done.add(rawStart, rawEnd); persist(); return null }
        val last = d > 0 && rawEnd >= d - 0.01
        if (conf.silenceTrim && !last && firstTime && !bounds.containsKey(i + 1) && !prepared.contains(i + 1)) {
            val cut = Silence.findCut(w.bytes)
            if (cut != null) {
                val adj = w.startSec + cut
                if (adj > rawStart + 2.0 && adj < rawEnd - 0.2) {
                    val took = synchronized(trimLock) { if (!bounds.containsKey(i + 1) && !prepared.contains(i + 1)) { bounds[i + 1] = adj; true } else false }
                    if (took) {
                        host.log("🌊 المقطع ${i + 1}: اتقصّر ${"%.1f".format(java.util.Locale.US, rawEnd - adj)}ث لأقرب لحظة صمت")
                        w = WavChunk(Silence.truncate(w.bytes, cut), w.startSec, cut, w.silent, w.gain)
                        rawEnd = adj
                        persist()
                    }
                }
            }
        }
        // (v166) نضغط الصوت هنا (نفس خيط التجهيز) عشان الرفع يبدأ بحجم أصغر ونشوف النسبة في اللوج
        try { val pk = AudioEnc.pack(w.bytes); host.log("📦 المقطع ${i + 1}: ${pk.srcSize / 1024}KB ← ${pk.bytes.size / 1024}KB (${pk.mime.removePrefix("audio/")})") } catch (_: Throwable) {}
        // (v170) تقسيم آلي مبدئي بالبصمة المحلية: أي صوت معروف بيتكلم إمتى (تلميح لجيميناي، مش أمر)
        if (!w.silent) try {
            val hk = Math.round(w.startSec * 100)
            val h = VoiceTrack.hint(voices, w)
            if (h.isNotEmpty()) voiceHints[hk] = h else voiceHints.remove(hk)
        } catch (_: Throwable) {}
        return Prep(w, rawStart, rawEnd)
    }

    private fun send(i: Int, p: Prep, backupFirst: Boolean = false, avoidKey: String? = null, hedge: Boolean = false, firstKey: String? = null) {
        val w = p.w; val rawStart = p.rawStart; val rawEnd = p.rawEnd
        val prior = subs
        // المفتاح الأقل شغلًا ثم الأسرع (مش تبادل بالدور): الباتشات بتمشي ورا بعض بدل ما الزوجية تتحبس على مفتاح بطيء
        fun nextKey(avoid: String?): String? = if (backupFirst) (pool.pickBackup(avoid) ?: pool.pickFree(avoid, rr.getAndIncrement())) else pool.pickFree(avoid, rr.getAndIncrement())
        // (v151) تغيير المفتاح أثناء الباتش: لأقرب مفتاح فاضي بس. لو كلهم مشغولين الباتش بيرجع للطابور (RehomeEx) بدل ما يتحط على مفتاح شغّال
        fun switchKey(cur: String): String? {
            if (hedge || backupFirst) return nextKey(cur)
            pool.pickIdle(i, cur, perKey)?.let { return it }
            if (nextKey(cur) == null) return null
            throw RehomeEx(cur)
        }
        var raced: RaceWin? = null
        if (!hedge && !backupFirst && conf.speedTest && speedDone.compareAndSet(false, true)) raced = try { speedRace(i, w) } catch (e: Exception) { host.log("⚠ اختبار السرعة فشل: " + (e.message ?: e.toString()).take(80)); null }
        var key = raced?.key ?: firstKey?.takeIf { pool.ok(it) } ?: nextKey(avoidKey) ?: throw Exception("كل المفاتيح معطلة مؤقتًا — راجع المفاتيح")
        // (v176) وضع التأكيد: نسخة تانية من نفس الباتش بتتبعت بالتوازي على مفتاح تاني، والنتيجتين بتتدمج
        var twinF: java.util.concurrent.Future<List<Sub>?>? = null
        if (conf.verify && !hedge && !backupFirst && pool.usableCount() >= 2) {
            val k2 = listOf(pool.pickBackup(key), pool.pickFree(key, rr.getAndIncrement())).firstOrNull { it != null && it != key }
            if (k2 != null) {
                host.log("🔁 باتش ${i + 1}: وضع التأكيد — نسخة تانية على ${pool.tail(k2)}")
                twinF = try { twinEx.submit<List<Sub>?> { twinCall(i, w, k2, key) } } catch (_: RejectedExecutionException) { null }
            }
        }
        var merged = false
        var partial = false
        var trunc = 0; var empties = 0; var bad = 0; var net = 0; var tries = 0; var langBad = 0
        while (running && tries++ < MAX_TRIES) {
            if (claimed.contains(i)) return   // نسخة تانية من الباتش ده خلصت وطُبّقت قبلنا
            if (!hedge) keyOf[i] = key
            val prompt = buildPrompt(w.durSec, w.startSec, langBad > 0, i)
            try {
                val t0 = System.currentTimeMillis()
                val rw = raced; raced = null
                val mdl = rw?.model ?: curModel()
                val r = if (rw != null) rw.res else {
                    pool.begin(key, i)
                    try { Api.generate(mdl, key, prompt, w.bytes, maxTokens = Api.outTokens(w.durSec)).also { pool.end(key, System.currentTimeMillis() - t0, true) } } catch (e: Throwable) { pool.end(key, 0, false); throw e }
                }
                val took = rw?.ms ?: (System.currentTimeMillis() - t0)
                if (isClosed) return   // الإنجن اتقفل أثناء الطلب (خرجت من الفيديو): ارمي الرد
                if (r.finish == "MAX_TOKENS" && trunc >= 2) partial = true
                if (r.finish == "MAX_TOKENS" && trunc < 2) { trunc++; host.log("⚠ الرد اتقطع — إعادة المحاولة"); nap(600); continue }
                if (r.finish.isNotEmpty() && r.finish != "STOP" && r.finish != "MAX_TOKENS") throw Exception("رد Gemini اتوقف: ${r.finish}")
                var j: JSONObject? = null
                if (r.text.isNotBlank()) { j = Parse.json(r.text); if (j == null) throw BadReply("رد Gemini مش JSON صالح") }
                var fresh = if (j == null) emptyList() else Parse.subs(j, w.startSec, w.durSec)
                // حارس اللغة: لو 30%+ من الجمل translated فيها مش بالعربي → إعادة المحاولة بتشديد، وبعدها إصلاح الباقي نصيًا
                val off = LangGuard.foreignOf(fresh)
                if (off.isNotEmpty() && off.size * 10 >= fresh.size * 3 && langBad < 2) {
                    langBad++; host.log("🌐 المقطع ${i + 1}: ${off.size}/${fresh.size} جملة مش بـ${conf.lang} — إعادة المحاولة")
                    nap(400); continue
                }
                if (off.isNotEmpty()) fresh = fixForeign(fresh)
                twinF?.let { f ->
                    twinF = null
                    val t2 = try { f.get(90, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { f.cancel(true); null }
                    if (t2 == null) host.log("⚠ باتش ${i + 1}: نسخة التأكيد فشلت — اتكمّل بالنسخة الأولى بس")
                    else {
                        val n1 = fresh.size
                        fresh = Subs.twinMerge(fresh, t2); merged = true
                        host.log("🔁 باتش ${i + 1}: دمج النسختين — الأولى $n1 جملة · التانية ${t2.size} · الناتج ${fresh.size} (من غير تكرار)")
                    }
                }
                if (fresh.isEmpty() && empties < (if (w.silent) 1 else 3)) {
                    empties++
                    if (!w.silent) host.log("⚠ المقطع ${i + 1} رجع من غير جمل رغم وجود صوت — إعادة محاولة ($empties/3)")
                    switchKey(key)?.let { key = it }
                    nap(400); continue
                }
                if (fresh.isEmpty() && !w.silent) { partial = true; host.log("⚠ المقطع ${i + 1} لسه من غير جمل — هيتحاول تاني كفجوة") }
                pool.good(key)
                if (!claimed.add(i)) { host.log("↩ باتش ${i + 1}: نسخة تانية خلصته قبلها — اتجاهلت دي"); return }
                if (i > maxApplied) maxApplied = i
                runs[i] = KeyRun(keyNo(key), mdl, took)
                applyChunk(i, rawStart, rawEnd, w, if (merged && j != null) JSONObject(j.toString()).also { it.remove("voices") } else j, fresh, prior)
                startedAt[i]?.let { recentMs.addLast(System.currentTimeMillis() - it); while (recentMs.size > 8) recentMs.pollFirst() }
                if (hedge) { inflight.remove(i); host.log("⚡ باتش ${i + 1}: النسخة الاحتياطية خلصت الأول") }
                scheduleHoleFill(i, rawStart, rawEnd, w, key)   // برّه خانة الباتش: الباتش الجاي مايستناش سدّ الثغرات
                if (partial) { incomplete.add(i); host.log("⚠ باتش ${i + 1} رجع ناقص — تقدر تعيده من علامة التحذير") }
                return
            } catch (e: BadReply) {
                if (bad++ < 2) { host.log("⚠ ${e.message} — إعادة المحاولة"); nap(600); continue }
                throw e
            } catch (e: ApiErr) {
                val invalid = e.code == 401 || e.code == 403 || (e.code == 400 && (e.message ?: "").contains("API key", true))
                if (e.code == 429) {
                    pool.block(key, 60_000)
                    val alt = switchKey(key)
                    if (alt != null) { host.log("⏳ ${(e.message ?: "").take(130)} على ${pool.tail(key)} — تحويل لمفتاح ${pool.tail(alt)}"); key = alt; continue }
                    val s = pool.streakUp(); val wait = minOf(60, 10 * s)
                    host.log("⏳ كل المفاتيح في كوتة (${(e.message ?: "").take(130)}) — انتظار ${wait}ث"); nap(wait * 1000L)
                    pool.clear(); continue
                }
                if (invalid) {
                    pool.block(key, 3_600_000)
                    val alt = switchKey(key)
                    if (alt != null) { host.log("🔑 ${(e.message ?: "").take(110)} — مفتاح ${pool.tail(key)} غير صالح — تحويل"); key = alt; continue }
                    throw Exception("مفتاح API غير صالح أو الموديل مش متاح — ${(e.message ?: "").take(120)}")
                }
                if (e.code >= 500) { host.log("⚠ خطأ من السيرفر (${e.code}) — إعادة المحاولة"); nap(3000); continue }
                throw e
            } catch (e: Unsupported) { throw e
            } catch (e: IOException) {
                if (++net > 4) throw e
                host.log("📶 مشكلة اتصال: ${(e.message ?: "").take(80)} — إعادة المحاولة"); nap(2000L * net)
            }
        }
        if (running) throw Exception("استنفدت المحاولات")
    }

    /** (v176) الطلب التاني في وضع التأكيد: محاولتين بالكتير، أي فشل = null والباتش يكمّل بالنسخة الأولى */
    private fun twinCall(i: Int, w: WavChunk, k0: String, primary: String): List<Sub>? {
        var key = k0
        for (attempt in 0 until 3) {
            if (!running || isClosed) return null
            val t0 = System.currentTimeMillis()
            try {
                val prompt = buildPrompt(w.durSec, w.startSec)
                pool.begin(key)
                val r = try { Api.generate(curModel(), key, prompt, w.bytes, maxTokens = Api.outTokens(w.durSec)).also { pool.end(key, System.currentTimeMillis() - t0, true) } } catch (e: Throwable) { pool.end(key, 0, false); throw e }
                if (r.text.isBlank()) return emptyList()
                val j = Parse.json(r.text) ?: continue
                var fresh = Parse.subs(j, w.startSec, w.durSec)
                if (LangGuard.foreignOf(fresh).isNotEmpty()) fresh = fixForeign(fresh)
                return fresh
            } catch (e: ApiErr) {
                val invalid = e.code == 401 || e.code == 403
                if (e.code == 429) pool.block(key, 60_000) else if (invalid) pool.block(key, 3_600_000)
                else if (e.code < 500) return null
                key = listOf(pool.pickBackup(key), pool.pickFree(key, rr.getAndIncrement())).firstOrNull { it != null && it != primary && it != key } ?: return null
            } catch (_: Unsupported) { return null
            } catch (_: Exception) { nap(1500) }
        }
        return null
    }

    /** لهجة الباتش اللي بيتبعت: لو اتختارت لهجة، الباتش بيتترجم بيها مباشرة (من غير فصحى ثم تحويل — توفير على المفاتيح) */
    private val dialOf = ConcurrentHashMap<Int, String>()
    private val dialConf = ConcurrentHashMap<String, Conf>()
    private fun buildPrompt(durSec: Double, start: Double, strict: Boolean = false, chunk: Int = -1, hole: Boolean = false, holeNear: Boolean = false): String {
        val dl = convDialect.let { if (it.isBlank() || it == "فصحى") "" else it }
        if (chunk >= 0) { if (dl.isEmpty()) dialOf.remove(chunk) else dialOf[chunk] = dl }
        val c = if (dl.isEmpty() || dl == conf.lang) conf else dialConf.getOrPut(dl) { conf.withLang(dl) }
        val case = pb.caseOf(srcLang, detDone)
        val tf = if (case == "other") synchronized(tplCache) { tplCache[tplKey(pb.templateId(c, "other"))] } else null
        val vt = if (hole) "" else voices.promptBlock() + (voiceHints[Math.round(start * 100)] ?: "")
        val built = toneBlock() + pb.build(c, srcLang, detDone, durSec, if (hole) "" else Subs.prevContext(subs, start), effectiveChars(), effectiveGloss(), tf, strict, hole, vt)
        return if (hole && holeNear) built.replace(PromptBuilder.HOLE_BLOCK, PromptBuilder.HOLE_NEAR_BLOCK) else built
    }

    /** إصلاح الجمل اللي translated بتاعتها مش عربي: ترجمة نصية سريعة من original (وpivot) للهجة المختارة */
    private fun fixForeign(list: List<Sub>): List<Sub> {
        val bad = list.indices.filter { LangGuard.foreign(list[it]) }
        if (bad.isEmpty()) return list
        return try {
            val arr = JSONArray()
            for (k in bad) arr.put(JSONObject().put("idx", k).put("original", list[k].original).put("english", list[k].pivot).put("speaker_gender", list[k].gender).put("addressee_gender", list[k].addressee))
            val target = if (conf.lang == "فصحى") "العربية الفصحى" else "اللهجة ${conf.lang}"
            val prompt = "ترجم كل جملة من الجمل دي إلى $target بالحروف العربية فقط (الأسلوب: ${conf.style}). ماتسيبش أي جملة بلغتها الأصلية ولا بالإنجليزي. حافظ على المعنى كامل وعلى جنس المتكلم speaker_gender والمخاطَب addressee_gender.\n\nالجمل:\n$arr\n\n" +
                "أرجع JSON فقط: {\"rewrites\":[{\"idx\":0,\"translated\":\"...\"}]}"
            val r = bgCall(prompt, 4096, 0.3, true, 0)
            val rw = (Parse.json(r.text) ?: return list).optJSONArray("rewrites") ?: return list
            val out = list.toMutableList(); var n = 0
            for (q in 0 until rw.length()) {
                val c = rw.optJSONObject(q) ?: continue
                val ix = c.optInt("idx", -1); if (ix !in bad) continue
                val tr = c.optString("translated").trim()
                if (tr.isEmpty()) continue
                val ns = out[ix].copy(translated = tr)
                if (!LangGuard.foreign(ns)) { out[ix] = ns; n++ }
            }
            if (n > 0) host.log("🌐 اتصلّحت $n جملة كانت مش بـ${conf.lang}")
            out
        } catch (e: Exception) { host.log("⚠ إصلاح اللغة فشل: " + (e.message ?: "").take(80)); list }
    }

    private fun tplKey(id: String) = "$srcLang|$id"

    private fun effectiveChars(): List<Chr> = if (conf.manualChars.isNotEmpty()) conf.manualChars else synchronized(lock) { chars.toList() }
    private fun effectiveGloss(): List<Gloss> = synchronized(lock) { gloss.toList() }

    private fun applyChunk(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, j: JSONObject?, fresh0: List<Sub>, prior: List<Sub>) {
        if (isClosed) return
        val fresh = voiceStep(w, j, fresh0)
        if (j != null && !detDone) {
            val d = j.optString("detected_source_language", "").trim()
            if (d.isNotEmpty()) { srcLang = d; detDone = true; host.log("🌐 لغة الفيديو الأصلية: $d"); maybeTranslateTemplate() }
        }
        if (j != null) applyPrevCorrections(j)
        val spansAbs = Speech.activeSpans(w.bytes, w.gain)?.let { Speech.absolute(it, w.startSec) }
        val direct = dialOf[i]?.let { it == convDialect } ?: false
        var tagged = Subs.splitAll(fresh).map { Subs.capPace(it).copy(chunk = i, conv = direct) }
        // (v183) مواءمة التوقيت: لو توقيت الجمل كلها متزحلق (إزاحة/تمدّد) عن الصوت الفعلي بنرجّعه — الجملة الظاهرة كانت بتسبق أو بتتأخر عن المتكلم
        if (spansAbs != null && spansAbs.isNotEmpty() && Cfg.bool("talign", true)) {
            try {
                Speech.align(tagged, spansAbs, w.startSec, w.startSec + w.durSec)?.let { al ->
                    val L = java.util.Locale.US
                    host.log("🎯 المقطع ${i + 1}: توقيت الجمل اتظبط على الصوت (إزاحة ${"%+.2f".format(L, al.shift)}ث · مقياس ${"%.2f".format(L, al.scale)} · تطابق ${"%.2f".format(L, al.before)} ← ${"%.2f".format(L, al.after)})")
                    tagged = al.subs
                }
            } catch (_: Throwable) {}
        }
        // (v177) لو الكاشف ماطلّعش ولا مجال صوت والمقطع مش صامت (صوت واطي جدًا) ماينفعش نشيل جمل على أساسه
        if (spansAbs != null && (spansAbs.isNotEmpty() || w.silent)) {
            val n0 = tagged.size
            tagged = tagged.mapNotNull { Speech.fit(it, spansAbs) }
            if (tagged.size < n0) host.log("🔕 المقطع ${i + 1}: اتشالت ${n0 - tagged.size} جملة كانت فوق صمت تام")
        }
        tagged = Subs.harmonize(tagged, subs)   // توحيد ترجمة سطور الكورَس المتكررة في الأغاني
        synchronized(lock) {
            tagged = Subs.dropOverlapZone(tagged, subs.filter { it.chunk != i }, rawStart)
            val keep = subs.filter { it.chunk != i && !(it.chunk == -1 && it.start >= rawStart && it.start < rawEnd) && !Subs.inMyZone(it, tagged, i, rawEnd) }
            subs = Subs.merge(Subs.dedup(keep + tagged)).sortedBy { it.start }
            // (v181) تعبئة الأسماء من بصمة الصوت بتشتغل بس لو المحرك العصبي شغّال (البصمة الخفيفة بتغلط)؛ الباقي بيتحسب بس
            subs = if (try { VoiceNet.useNet() } catch (_: Throwable) { false }) Subs.fillSpeakerNames(subs) else subs.also { Subs.refreshMain(it) }
        }
        // (v177) دفتر الفقد: لو جمل رجعت من Gemini واختفت بعد التنضيف (تكرار/تداخل) نسجّل العدد عشان أي رجوع للمشكلة يبان في اللوج
        try {
            val lost = tagged.count { t -> val k = t.original.trim().take(10); k.length >= 4 && subs.none { it.start in (t.start - 3.0)..(t.end + 3.0) && it.original.contains(k) } }
            if (lost > 0 || fresh.size != tagged.size) host.log("📊 المقطع ${i + 1}: Gemini رجّع ${fresh.size} · بعد الفلاتر ${tagged.size} · اختفى بالتنضيف ~$lost")
        } catch (_: Throwable) {}
        done.add(rawStart, rawEnd)
        failed.remove(i); incomplete.remove(i)
        anyApplied = true
        host.changed()
        Stats.addSec(rawEnd - rawStart)
        host.log("✅ المقطع ${i + 1}: ${fresh.size} جملة — الإجمالي ${subs.size}")
        persist()
        afterChunk(w, fresh, prior)
        maybeConvert(false)
    }

    /** (v170) بعد رد جيميناي: بصمة كل جملة من الصوت نفسه → كود صوت ثابت عبر المقاطع + تصحيح الجنس من جنس الصوت */
    private fun voiceStep(w: WavChunk, j: JSONObject?, fresh: List<Sub>): List<Sub> {
        voiceHints.remove(Math.round(w.startSec * 100))
        if (fresh.isEmpty()) return fresh
        return try {
            val r = VoiceTrack.apply(voices, w, fresh, j?.optJSONArray("voices"))
            if (r.matched + r.newVoices > 0) host.log("🎙 بصمة الصوت: ${r.matched} جملة اتطابقت · ${r.newVoices} صوت جديد · ${voices.size()} صوت في البنك" + (if (r.genderFixed > 0) " · اتصحّح جنس ${r.genderFixed} جملة" else ""))
            syncVoiceCast()
            r.subs
        } catch (e: Throwable) { host.log("⚠ بصمة الصوت: " + (e.message ?: e.toString()).take(80)); fresh }
    }

    /** أي صوت اتسمّى (بيعرّف نفسه أو حد بيناديه) وظهر 3 مرات على الأقل بيدخل جدول الشخصيات بجنس صوته */
    private fun syncVoiceCast() {
        if (conf.manualChars.isNotEmpty()) return
        val nv = voices.namedVoices(); if (nv.isEmpty()) return
        synchronized(lock) {
            for ((name, g, _) in nv) if (chars.none { it.name == name }) chars.add(Chr(name, g, ""))
        }
    }

    private fun applyPrevCorrections(j: JSONObject) {
        val pc = j.optJSONArray("prev_corrections") ?: return
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (k in 0 until pc.length()) {
                val c = pc.optJSONObject(k) ?: continue
                val snip = c.optString("original_snippet").trim()
                if (snip.length < 3) continue
                val idx = cur.indexOfLast { it.original.contains(snip) }
                if (idx < 0) continue
                var s = cur[idx]
                c.optString("gender").lowercase().takeIf { it == "male" || it == "female" }?.let { s = s.copy(gender = it) }
                c.optString("addressee").lowercase().takeIf { it in listOf("male", "female", "plural", "unknown") }?.let { s = s.copy(addressee = it) }
                c.optString("topic_gender").lowercase().takeIf { it in listOf("male", "female", "plural", "none") }?.let { s = s.copy(topicGender = it) }
                c.optString("translated").trim().takeIf { it.isNotEmpty() }?.let { s = s.copy(translated = it) }
                cur[idx] = s; n++
            }
            if (n > 0) subs = cur
        }
        if (n > 0) host.log("🩺 تصحيح $n جملة قديمة (جنس/ضمير)")
    }

    // ===== المهام الخلفية بعد كل مقطع =====
    private fun afterChunk(w: WavChunk, fresh: List<Sub>, prior: List<Sub>) {
        if (isClosed) return
        if (conf.crossReview && currentDur() >= CROSS_MIN_DUR && prior.isNotEmpty() && fresh.isNotEmpty() && reviews.get() < 2) {
            reviews.incrementAndGet()
            val off = w.startSec
            try { bg.submit { try { crossReview(prior, fresh, off) } catch (e: Exception) { host.log("⚠ المراجعة بين المقاطع فشلت: " + (e.message ?: "").take(100)) } finally { reviews.decrementAndGet() } } }
            catch (_: Exception) { reviews.decrementAndGet() }
        }
        synchronized(lock) { chunksDone++ }
        maybeAnalyze()
        maybePronouns()
        maybeGenre()
        maybeRefine()
    }

    // (v108) نوع العمل (أكشن/كوميدي/رعب…) من أول جمل مترجمة — للفلتر التلقائي
    @Volatile var genreWanted = false
    @Volatile var onGenre: ((String) -> Unit)? = null
    @Volatile private var genreBusy = false
    fun detectGenre() { genreWanted = true; maybeGenre() }
    private fun maybeGenre() {
        if (!genreWanted || genreBusy) return
        val sample: String
        synchronized(lock) {
            if (subs.size < 40) return
            genreBusy = true
            sample = subs.filter { !it.isSound && !it.isSong }.take(70).joinToString("\n") { it.translated.ifEmpty { it.original } }
        }
        try {
            bg.submit {
                try {
                    val prompt = "دي أول جمل من فيلم أو مسلسل مترجم:\n$sample\n\nحدد نوع العمل بدقة من القائمة دي بس: action (أكشن/قتال/مطاردات)، comedy (كوميدي)، horror (رعب/إثارة مظلمة)، romance (رومانسي)، scifi (خيال علمي/فانتازيا)، drama (دراما عادية)، other (مش واضح أو وثائقي).\nلو مش متأكد رجّع other. رد JSON فقط: {\"genre\":\"...\"}"
                    val r = bgCall(prompt, 128, 0.1)
                    val g = Parse.json(r.text)?.optString("genre", "")?.trim()?.lowercase() ?: ""
                    if (g in setOf("action", "comedy", "horror", "romance", "scifi", "drama", "other")) { genreWanted = false; host.log("🎨 نوع العمل: $g"); onGenre?.invoke(g) }
                } catch (e: Exception) { host.log("⚠ تحديد نوع العمل فشل: " + (e.message ?: "").take(80)) }
                finally { genreBusy = false }
            }
        } catch (_: Exception) { genreBusy = false }
    }

    private fun helperKey(i: Int = 0): String? {
        val pref = (conf.refine + conf.backup).distinct().ifEmpty { conf.keys }
        val ok = pref.filter { pool.ok(it) }
        if (ok.isNotEmpty()) return ok[Math.floorMod(i, ok.size)]
        return pool.helper()
    }

    private fun bgCall(prompt: String, maxTokens: Int, temp: Double, json: Boolean = true, i: Int = 0, search: Boolean = false): Api.Result {
        var key = helperKey(i) ?: throw Exception("مفيش مفتاح")
        var tries = 0
        while (true) {
            try { return Api.generate(curModel(), key, prompt, null, maxTokens, temp, json, search) }
            catch (e: ApiErr) {
                if (e.code >= 500) { if (++tries > 2) throw e; nap(2000L * tries); helperKey(i + tries)?.let { key = it }; continue }   // (v150) 503 مؤقت: جرّب تاني على مفتاح تاني
                if (e.code == 429) pool.block(key, 60_000) else if (e.code == 403) pool.block(key, 3_600_000) else throw e
                val alt = helperKey(i + 1)
                if (alt == null || alt == key || ++tries > 2) throw e
                key = alt
            }
        }
    }

    // --- تحليل الشخصيات تلقائيًا (_maybeAutoAnalyzeCharacters / analyzeCharacters) ---
    private fun maybeAnalyze() {
        if (!conf.autoChars || conf.manualChars.isNotEmpty()) return
        maybeLookup()
        maybeIdentify()
        synchronized(lock) {
            if (charsBusy || lookupState == 1 || subs.size < CHAR_MIN_LINES) return
            // أول تحليل: لما مفيش شخصيات. بعد كده: تحديث كل 5 باتشات (لو الترجمة زادت 20 جملة على الأقل)
            val first = chars.isEmpty() && autoCharsAttempts == 0
            val refresh = chunksDone - lastCharChunk >= 5 && subs.size >= charsTrySize + 20
            val retry = chars.isEmpty() && autoCharsAttempts in 1..1 && subs.size >= charsTrySize + 30
            if (!first && !refresh && !retry) return
            charsBusy = true; charsTrySize = subs.size; lastCharChunk = chunksDone
        }
        try {
            bg.submit {
                try { analyzeChars() } catch (e: Exception) { host.log("⚠ تحليل الشخصيات فشل: " + (e.message ?: "").take(100)) }
                finally { synchronized(lock) { charsBusy = false; autoCharsAttempts++ }; persist() }
            }
        } catch (_: Exception) { synchronized(lock) { charsBusy = false } }
    }

    /** (v107) بحث تلقائي عن شخصيات الفيلم/المسلسل بالاسم الكامل (بحث جوجل عبر Gemini). لو الاسم مش واضح أو مش متأكد: مفيش تخمين والشخصيات بتتطلع من الترجمة. */
    private fun maybeLookup() {
        val hint = titleHint.trim()
        synchronized(lock) {
            if (lookupState != 0 || hint.length < 3 || chars.isNotEmpty()) return
            lookupState = 1
        }
        try {
            bg.submit {
                try { lookupCast(hint) } catch (e: Exception) { host.log("⚠ البحث عن الشخصيات فشل: " + (e.message ?: "").take(100)) }
                finally { synchronized(lock) { lookupState = 2 }; persist() }
            }
        } catch (_: Exception) { synchronized(lock) { lookupState = 2 } }
    }

    private fun lookupCast(hint: String) {
        host.log("🔎 بدور على الشخصيات باسم: $hint")
        val prompt = "اسم ملف الفيديو واسم الفولدر بتاعه: «$hint».\n" +
            "مهمتك: تحدد لو ده اسم فيلم أو مسلسل أو أنمي كامل وواضح، وتدوّر عليه في جوجل وتجيب شخصياته الرئيسية.\n" +
            "🔴 ممنوع التخمين: لو الاسم ناقص أو عام أو فيه رقم حلقة بس أو لقيت أكتر من عمل بنفس الاسم ومش قادر تحدد بثقة، أو ملقيتش مصدر موثوق، رجّع found=false وخلاص.\n" +
            "لو لقيته بثقة: رجّع الشخصيات الرئيسية والمهمة (حد أقصى 15) بأسمائها المعروفة مكتوبة بالعربي، وجنس كل شخصية، ودورها في سطر قصير (حد أقصى 12 كلمة) من غير حرق أحداث.\n" +
            "رد JSON فقط بدون أي شرح أو markdown:\n" +
            "{\"found\":true,\"title\":\"الاسم الكامل للعمل\",\"characters\":[{\"name\":\"...\",\"gender\":\"male\",\"role\":\"...\"}]}\n" +
            "أو {\"found\":false}"
        val r = bgCall(prompt, 2048, 0.1, true, 0, true)
        val j = Parse.json(r.text)
        if (j == null || !j.optBoolean("found", false)) { host.log("🔎 الاسم مش واضح أو مش متأكد — هطلّع الشخصيات من الترجمة نفسها"); return }
        val n = applyCast(j)
        host.log("🔎 لقيت العمل «" + j.optString("title") + "» — اتضافت $n شخصية هتتحط في الـ prompt من المقطع الجاي")
    }

    /** يضيف شخصيات العمل اللي اتلقى (من البحث) لجدول الشخصيات. يرجع عدد الجديد. */
    private fun applyCast(j: JSONObject): Int {
        var n = 0
        synchronized(lock) {
            val ca = j.optJSONArray("characters")
            for (k in 0 until (ca?.length() ?: 0)) {
                val o = ca!!.optJSONObject(k) ?: continue
                val name = o.optString("name").trim(); if (name.isEmpty() || chars.any { it.name == name }) continue
                val g = if (o.optString("gender").lowercase().startsWith("f")) "female" else "male"
                chars.add(Chr(name, g, o.optString("role").trim())); n++
            }
            castFound = true
        }
        return n
    }

    /**
     * (v170) مفيش اسم ملف مفيد (أو البحث بالاسم ملقاش حاجة): نتعرّف على العمل من الحوار نفسه (أسماء الشخصيات والأحداث) ونطابقه بجوجل.
     * لو لقينا عمل بثقة عالية: شخصياته الرئيسية بتتضاف جاهزة بجنسها ودورها، ومن المقطع الجاي جيميناي بيربط الأصوات بيها.
     */
    private fun maybeIdentify() {
        synchronized(lock) {
            if (identTried || castFound || subs.size < 30) return
            val pending = titleHint.trim().length >= 3 && lookupState != 2   // لسه بنجرّب الاسم الأول
            if (pending) return
            identTried = true
        }
        try {
            bg.submit { try { identifyFromDialogue() } catch (e: Exception) { host.log("⚠ التعرف على العمل من الحوار فشل: " + (e.message ?: "").take(100)) } }
        } catch (_: Exception) {}
    }

    private fun identifyFromDialogue() {
        val snap = subs.filter { !it.isSound && it.original.isNotBlank() }
        if (snap.size < 20) return
        val step = maxOf(1, snap.size / 45)
        val lines = snap.filterIndexed { i, _ -> i % step == 0 }.take(45).joinToString("\n") { s ->
            "[" + (if (s.gender == "female") "أنثى" else "ذكر") + "] " + s.original.take(140) + (if (s.translated.isNotBlank()) "  ←  " + s.translated.take(140) else "")
        }
        val names = snap.flatMap { it.people }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).joinToString("، ") { it.key }
        val known = synchronized(lock) { chars.map { it.name } }.joinToString("، ")
        val hint = titleHint.trim()
        host.log("🔎 مفيش اسم عمل واضح — بحاول أتعرّف عليه من الحوار نفسه (${snap.size} جملة)…")
        val prompt = "ده حوار من فيديو (فيلم أو مسلسل أو أنمي؟) اتسمع واتترجم. لغة الصوت: ${srcLang.ifEmpty { "غير محددة" }}. اسم الملف/الفولدر (ممكن يكون ناقص أو عام): «${hint.ifEmpty { "مفيش" }}».\n" +
            "أسماء ظهرت في الحوار: ${names.ifEmpty { "مفيش" }}\n" + (if (known.isNotEmpty()) "شخصيات معروفة عندنا: $known\n" else "") +
            voices.promptBlock() +
            "\nمقتطفات من الحوار (متكلم + الأصل + الترجمة):\n$lines\n\n" +
            "مهمتك: تتعرّف على العمل ده، وتدوّر عليه في جوجل وتتأكد إنه عمل حقيقي وإن أسماء الشخصيات والأحداث دي بتاعته فعلًا.\n" +
            "🔴 ممنوع التخمين: لو مش متأكد بثقة عالية (أسماء شخصيات وأحداث متطابقة مع مصدر موثوق)، أو لقيت أكتر من عمل محتمل، أو ده شكله فيديو يوتيوب/مقطع شخصي/برنامج/بودكاست — رجّع found=false وخلاص.\n" +
            "لو لقيته بثقة: رجّع الشخصيات الرئيسية والمهمة (حد أقصى 15) بأسمائها العربية بنفس كتابتها في الترجمة (ولو الشخصية في «شخصيات معروفة عندنا» استخدم نفس الكتابة بالظبط)، وجنس كل شخصية، ودورها في سطر قصير (حد أقصى 12 كلمة) من غير حرق أحداث.\n" +
            "رد JSON فقط بدون أي شرح أو markdown:\n{\"found\":true,\"title\":\"الاسم الكامل للعمل\",\"characters\":[{\"name\":\"...\",\"gender\":\"male\",\"role\":\"...\"}]}\nأو {\"found\":false}"
        val r = bgCall(prompt, 2048, 0.1, true, 0, true)
        val j = Parse.json(r.text)
        if (j == null || !j.optBoolean("found", false)) { host.log("🔎 مقدرتش أتأكد من اسم العمل — هكمّل بالشخصيات اللي بتتطلّع من الترجمة والأصوات"); return }
        val n = applyCast(j)
        host.log("🔎 اتعرّفت على العمل من الحوار: «" + j.optString("title") + "» — اتضافت $n شخصية جاهزة")
        persist()
    }


    private fun analyzeChars() {
        val snap = subs
        val stride = if (snap.size > 400) Math.ceil(snap.size / 400.0).toInt() else 1
        val sample = snap.filterIndexed { i, _ -> i % stride == 0 }
        val dialogue = sample.joinToString("\n") { s ->
            val g = if (s.gender == "female") "أنثى" else "ذكر"
            val names = if (s.people.isNotEmpty()) " [أسماء مذكورة: ${s.people.joinToString("، ")}]" else ""
            "[متكلم:$g]$names ${s.translated.ifEmpty { s.original }}"
        }
        val known = synchronized(lock) { chars.map { it.name } }
        val dialogueK = if (known.isEmpty()) dialogue else "(شخصيات معروفة مسبقًا — لو نفس الشخصية اتذكرت استخدم نفس الاسم بالظبط ومتكررهاش: " + known.joinToString("، ") + ")\n" + dialogue
        host.log("🧑‍🤝‍🧑 بحلل الشخصيات من ${sample.size} جملة…")
        val r = bgCall(pb.read("prompts/analyze_chars.txt").replace("§DIALOGUE§", dialogueK), 3072, 0.2)
        val j = Parse.json(r.text) ?: throw Exception("رد غير صالح")
        var nc = 0; var ng = 0
        synchronized(lock) {
            val ca = j.optJSONArray("characters")
            for (k in 0 until (ca?.length() ?: 0)) {
                val o = ca!!.optJSONObject(k) ?: continue
                val name = o.optString("name").trim(); if (name.isEmpty()) continue
                val g = if (o.optString("gender").lowercase().startsWith("f")) "female" else "male"
                val role = o.optString("role").trim()
                val ix = chars.indexOfFirst { it.name == name }
                if (ix >= 0) chars[ix] = Chr(name, g, role.ifEmpty { chars[ix].role }) else chars.add(Chr(name, g, role))
                nc++
            }
            val ga = j.optJSONArray("glossary")
            for (k in 0 until (ga?.length() ?: 0)) {
                val o = ga!!.optJSONObject(k) ?: continue
                val term = (o.optString("term").ifEmpty { o.optString("name") }).trim(); if (term.isEmpty()) continue
                val note = (o.optString("note").ifEmpty { o.optString("translation") }).trim()
                if (gloss.none { it.term == term }) { gloss.add(Gloss(term, note)); ng++ }
            }
        }
        host.log("🧑‍🤝‍🧑 اتحددت $nc شخصية و$ng مصطلح — هتتحط في الـ prompt من المقطع الجاي")
    }

    // --- تصحيح الضمائر تلقائيًا (_maybeAutoCorrectPronouns) ---
    private fun maybePronouns() {
        if (!conf.autoPronouns) return
        if (effectiveChars().isEmpty()) return
        synchronized(lock) {
            if (pronBusy || subs.size < PRON_MIN_LINES || pronUpTo >= subs.size) return
            pronBusy = true
        }
        try {
            bg.submit {
                try { pronounPass() } catch (e: Exception) { host.log("⚠ تصحيح الضمائر فشل: " + (e.message ?: "").take(100)) }
                finally { synchronized(lock) { pronBusy = false } }
            }
        } catch (_: Exception) { synchronized(lock) { pronBusy = false } }
    }

    private fun pronounPass() {
        val cs = effectiveChars(); if (cs.isEmpty()) return
        val snap = subs
        val cand = (pronUpTo until snap.size).toList()
        var fixed = 0
        for (batch in cand.chunked(40)) {
            if (!running) return
            fixed += runPronounCorrection(snap, batch, cs)
        }
        if (fixed > 0) { host.log("🩺 تصحيح ضمائر: $fixed جملة"); host.changed(); persist() }
        pronUpTo = maxOf(pronUpTo, snap.size)
    }

    private fun runPronounCorrection(snap: List<Sub>, batch: List<Int>, cs: List<Chr>): Int {
        val want = LinkedHashSet<Int>()
        for (i in batch) for (k in i - 2..i + 2) if (k in snap.indices) want.add(k)
        val list = JSONArray()
        for (k in want) {
            val s = snap[k]
            list.put(JSONObject().put("idx", k).put("review", k in batch).put("original", s.original).put("translated", s.translated).put("speaker_gender", s.gender).put("addressee_gender", s.addressee))
        }
        val table = cs.joinToString("\n") { "- \"${it.name}\": ${if (it.gender == "female") "أنثى (مؤنث)" else "ذكر (مذكر)"}" }
        val prompt = pb.read("prompts/pronoun_fix.txt").replace("§TABLE§", table).replace("§LIST§", list.toString())
        val r = bgCall(prompt, 8192, 0.1)
        val j = Parse.json(r.text) ?: return 0
        val arr = j.optJSONArray("corrections") ?: return 0
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (q in 0 until arr.length()) {
                val c = arr.optJSONObject(q) ?: continue
                if (!c.has("idx")) continue
                val idx = c.optInt("idx", -1)
                if (idx !in batch) continue
                val tr = c.optString("translated").trim(); if (tr.isEmpty()) continue
                val target = snap[idx]
                val at = cur.indexOfFirst { Math.abs(it.start - target.start) < 0.05 && it.original == target.original }
                if (at >= 0 && cur[at].translated != tr) { cur[at] = cur[at].copy(translated = tr); n++ }
            }
            if (n > 0) subs = cur
        }
        return n
    }

    // --- المراجعة بين المقاطع (reviewCrossChunkContext) ---
    private fun crossReview(prior: List<Sub>, fresh: List<Sub>, offsetSec: Double) {
        nap(4500)
        if (!running) return
        val base = if (prior.size > PRIOR_CAP) prior.size - PRIOR_CAP else 0
        val capped = prior.subList(base, prior.size)
        val newList = JSONArray()
        for ((k, s) in fresh.withIndex()) newList.put(JSONObject().put("idx", k)
            .put("start", Math.round((s.start - offsetSec) * 100) / 100.0).put("end", Math.round((s.end - offsetSec) * 100) / 100.0)
            .put("original", s.original).put("translated", s.translated))
        val priorList = JSONArray()
        for ((k, s) in capped.withIndex()) priorList.put(JSONObject().put("idx", k).put("original", s.original).put("translated", s.translated)
            .put("gender", s.gender).put("addressee", s.addressee).put("topic_gender", s.topicGender))
        val east = "   طبّق نفس قواعد تحديد الجنس الآسيوية دي في المراجعة:\n" + pb.read("prompts/east_asian.txt")
        val prompt = pb.read("prompts/review_cross.txt").replace("§NEW§", newList.toString()).replace("§PRIOR§", priorList.toString()).replace("§EAST§", east)
        val r = bgCall(prompt, 6144, 0.1, true, (offsetSec / ch).toInt())
        val j = Parse.json(r.text) ?: return
        val arr = j.optJSONArray("corrections") ?: return
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (q in 0 until arr.length()) {
                val c = arr.optJSONObject(q) ?: continue
                if (!c.has("idx")) continue
                val idx = c.optInt("idx", -1)
                if (idx < 0 || idx >= capped.size) continue
                val target = capped[idx]
                val at = cur.indexOfFirst { Math.abs(it.start - target.start) < 0.05 && it.original == target.original }
                if (at < 0) continue
                var s = cur[at]; val before = s
                c.optString("gender").lowercase().takeIf { it == "male" || it == "female" }?.let { s = s.copy(gender = it) }
                c.optString("addressee").lowercase().takeIf { it in listOf("male", "female", "plural", "unknown") }?.let { s = s.copy(addressee = it) }
                c.optString("topic_gender").lowercase().takeIf { it in listOf("male", "female", "plural", "none") }?.let { s = s.copy(topicGender = it) }
                c.optString("translated").trim().takeIf { it.isNotEmpty() }?.let { s = s.copy(translated = it) }
                if (s != before) { cur[at] = s; n++ }
            }
            if (n > 0) subs = cur
        }
        if (n > 0) { host.log("🔎 مراجعة بين المقاطع: صححت $n جملة قديمة"); host.changed(); persist() }
    }

    // ===== (v109) تنقيح الترجمة بالسياق — (v140) اتحسّن: بيشتغل كل مقطعين بعدّاد مش بباقي القسمة (كان بيفوّت لما مقطعين يخلصوا مع بعض)،
    // وبيعمل تنقيح نهائي لما كل المقاطع تخلص (كان آخر مقاطع الفيديو عمرها ما بتتراجع)، وبيراجع الجمل الجديدة بس مع 25 جملة قبلها كسياق (كان بيبعت الفيديو كله كل مرة) =====
    private val refineEvery = 1   // (v154) كل مقطع يخلص (التنقيح الجزئي على مفتاح مخصوص مش بيزاحم الترجمة)
    @Volatile private var partialWarned = false
    @Volatile private var partialKeyTail = ""
    @Volatile private var refineBusy = false
    private var refineSaved = false
    private var refinedAt = 0
    private val refinedKeys = HashSet<String>()   // جمل اتراجعت وفي بعدها سياق كفاية (بتتحمي بـ lock)

    /** (v154) مفاتيح التنقيح الجزئي: الاحتياطي (وأي مفتاح إضافي اتحط على «احتياطي») الأول، وبعدين مفاتيح الصور — بس لو الوضع البصري مش شغّال دلوقتي. مفاتيح الترجمة الأساسية ماتتلمسش أبدًا. */
    private fun partialKeys(): List<String> {
        val rf = conf.refine.filter { it.length > 10 && pool.ok(it) }
        if (rf.isNotEmpty()) return rf   // (v158) مفاتيح التنقيح المخصوصة الأول
        val l = ArrayList<String>()
        l.addAll(conf.backup.filter { it.length > 10 && pool.ok(it) })
        if (!VisualMode.anyActive()) l.addAll(conf.visKeys.filter { it.length > 10 && pool.ok(it) })
        return l.distinct()
    }

    /** طلب تنقيح جزئي على المفاتيح المخصوصة بس (429 → يعطّل المفتاح ويجرّب اللي بعده، 5xx → يعيد) */
    private fun partialCall(prompt: String, maxTokens: Int, temp: Double, i: Int): Api.Result {
        var last: Exception? = null
        for (tries in 0 until 3) {
            val ks = partialKeys()
            if (ks.isEmpty()) throw last ?: Exception("مفيش مفتاح احتياطي/صور فاضي للتنقيح الجزئي")
            val key = ks.sortedBy { pool.loadOf(it) * 100 + Math.floorMod(ks.indexOf(it) - (i + tries), ks.size) }.first()
            try {
                partialKeyTail = pool.tail(key)
                pool.refineBegin(key)   // (v160) المفتاح يتحجز: مفيش باتش ترجمة جديد يتبعت عليه، والباتش الشغّال يخلص الأول
                var w = 0; while (pool.loadOf(key) > 0 && w < 90_000 && running) { nap(300); w += 300 }
                return Api.generate(curModel(), key, prompt, null, maxTokens, temp, true, false) }
            catch (e: ApiErr) {
                last = e
                if (e.code >= 500) nap(2000L * (tries + 1))
                else if (e.code == 429) pool.block(key, 60_000)
                else if (e.code == 401 || e.code == 403) pool.block(key, 3_600_000)
                else throw e
            } finally { pool.refineEnd(key) }
        }
        throw last ?: Exception("فشل التنقيح الجزئي")
    }

    private fun maybeRefine() {
        if (!conf.partialRefine) return
        val keysOk = partialKeys().isNotEmpty()
        if (!keysOk) {
            if (!partialWarned && chunksDone >= 2) { partialWarned = true; host.log("✍ التنقيح الجزئي واقف: محتاج 3 مفاتيح على الأقل عشان البرنامج يخصّص مفتاح للتنقيح — هيتنقّح في الآخر بالمفاتيح العادية") }
            return
        }
        synchronized(lock) {
            if (refineBusy || chunksDone - refinedAt < refineEvery || subs.size < 12) return
            refineBusy = true; refinedAt = chunksDone
        }
        runRefine(false)
    }

    private var finalAt = -1   // (v154) آخر chunksDone اتعمل عنده تنقيح نهائي (مرة واحدة لكل قيمة — مايتعادش لو فشل)
    /** من حلقة run() وقت الهدوء (مفيش باتش في الجو): لو في مقاطع خلصت بعد آخر تنقيح، نقّح الباقي. بيرجّع true لو التنقيح شغال — الحلقة تستنى */
    private fun finalRefineTick(): Boolean {
        if (isClosed) return false
        synchronized(lock) {
            if (refineBusy) return true
            if (subs.size < 8 || finalAt == chunksDone) return false
            // (v154) التنقيح الجزئي بيعلّم refinedAt مع كل مقطع، فلازم نشوف كمان لو لسه فيه جمل (آخر 8) ماخدتش سياق بعدها
            val pending = subs.any { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") && pk(it) !in refinedKeys }
            if (chunksDone <= refinedAt && !pending) return false
            refineBusy = true; refinedAt = chunksDone; finalAt = chunksDone
        }
        runRefine(true)
        return true
    }

    private fun runRefine(isFinal: Boolean) {
        try {
            bg.submit {
                try { refinePass(isFinal) } catch (e: Exception) { host.log("⚠ تنقيح الترجمة فشل: " + (e.message ?: "").take(100)) }
                finally { refineBusy = false }
            }
        } catch (_: Exception) { refineBusy = false }
    }

    private fun refinePass(isFinal: Boolean) {
        val snap = subs.filter { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") }.sortedBy { it.start }
        if (snap.size < 8) return
        val reviewed = synchronized(lock) { HashSet(refinedKeys) }
        val firstNew = snap.indexOfFirst { pk(it) !in reviewed }
        if (firstNew < 0) return
        val roster = pb.rosterText(effectiveChars(), effectiveGloss())
        val WIN = 400; val STEP = 360; val LEAD = 25
        var fixedTotal = 0; var from = maxOf(0, firstNew - LEAD); var win = 0
        var okAll = true
        while (from < snap.size && running) {
            val part = snap.subList(from, minOf(snap.size, from + WIN))
            val arr = JSONArray()
            for ((k, q) in part.withIndex()) {
                val prevEnd = if (k > 0) part[k - 1].end else if (from > 0) snap[from - 1].end else q.start
                val o = JSONObject().put("i", k).put("original", q.original).put("translated", q.translated)
                if (q.pivot.isNotBlank()) o.put("en", q.pivot)
                o.put("speaker", q.gender)
                if (q.addressee != "unknown") o.put("to", q.addressee)
                o.put("gap", Math.round(maxOf(0.0, q.start - prevEnd) * 10) / 10.0)
                arr.put(o)
            }
            val prompt = "أنت مراجع ترجمة محترف. دي جمل فيلم/مسلسل/فيديو بالترتيب. الترجمة الحالية (translated) اتعملت على مقاطع صوتية منفصلة، كل مقطع حوالي دقيقة اتترجم لوحده من غير ما يشوف اللي قبله واللي بعده كويس — يعني غالبًا فيها جمل اتترجمت حرفيًا أو بمعنى الكلمة لوحدها، ولما الجملة تتقرا وسط اللي حواليها معناها الحقيقي بيبقى مختلف.\n" +
                "لكل جملة: i = رقمها، original = الأصل، en = نقل إنجليزي حرفي (لو موجود)، translated = الترجمة الحالية، speaker = جنس المتكلم، to = المخاطَب، gap = ثواني الصمت قبلها (فجوة كبيرة غالبًا = مشهد أو موضوع جديد).\n" +
                (if (roster.isNotBlank()) "\nمعلومات الشخصيات والمصطلحات:\n$roster\n" else "") +
                "\nالجمل:\n$arr\n\n" +
                "طريقة الشغل: اقرا كل جملة مع 4-5 جمل قبلها وبعدها كأنك بتتفرج على المشهد، واسأل نفسك: هل الترجمة الحالية بتوصّل اللي المتكلم يقصده فعلًا هنا؟ صحّح الجملة لما تلاقي:\n" +
                "1) ترجمة حرفية معناها بيتغيّر مع السياق: تعبير اصطلاحي، سخرية، تلميح، مجاز، سؤال بلاغي، شتيمة أو مجاملة بتتقال بمعنى غير معناها الحرفي، أو رد على جملة سابقة.\n" +
                "2) فاعل أو مفعول أو ضمير محذوف في الأصل واتفهم غلط، أو مرجع الضمير (هو/هي/ده) مش هو اللي في المشهد.\n" +
                "3) جملة مكمّلة لجملة قبلها أو بعدها (اتقطعت بين مقطعين) وترجمتها مش متصلة بيها.\n" +
                "4) اسم أو مصطلح أو لقب متترجم بأشكال مختلفة، أو جنس/ضمير غلط، أو جملة مش مفهومة، أو بتتناقض مع اللي حواليها.\n" +
                "القواعد:\n" +
                "- حافظ على لهجة ${conf.lang} وأسلوب ${conf.style}، وخلّي الجملة قصيرة ومناسبة للعرض كترجمة (قد الأصلية تقريبًا).\n" +
                "- ماتغيّرش جملة سليمة ولا تحسّن أسلوب بس. التصحيح لازم يبقى لمعنى أدق أو أوضح من السياق.\n" +
                "- ممنوع تضيف أو تحذف أو تدمج جمل، والتوقيت مش بتاعك.\n" +
                "رجّع JSON فقط: {\"corrections\":[{\"i\":12,\"why\":\"السبب في كلمتين تلاتة\",\"translated\":\"النص المصحح الكامل للجملة\"}]} — الجمل السليمة ماتتحطش. لو كله سليم: {\"corrections\":[]}"
            refiningSegs = listOf(doubleArrayOf(part.first().start, part.last().end))
            val r = try { if (isFinal) bgCall(prompt, 8192, 0.1, true, win) else partialCall(prompt, 8192, 0.1, win) } catch (e: Exception) { okAll = false; refiningSegs = emptyList(); throw e }
            refiningSegs = emptyList()
            val ca = Parse.json(r.text)?.optJSONArray("corrections")
            if (ca != null && ca.length() > 0) {
                var n = 0
                synchronized(lock) {
                    val cur = subs.toMutableList()
                    if (!refineSaved) { refineSaved = true; versions.add(Version("قبل التنقيح", subs, convDialect.ifBlank { "فصحى" })); while (versions.size > 12) versions.removeAt(0) }
                    for (q in 0 until ca.length()) {
                        val c = ca.optJSONObject(q) ?: continue
                        val k = c.optInt("i", -1); if (k < 0 || k >= part.size) continue
                        val nt = c.optString("translated").trim(); if (nt.isEmpty()) continue
                        val t = part[k]
                        if (nt == t.translated || nt.length > t.translated.length * 3 + 20 || nt.length * 3 + 20 < t.translated.length) continue
                        val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                        if (at < 0 || cur[at].translated != t.translated) continue   // اتغيّرت في الأثناء (تعديل تاني): ماندوسش عليها
                        cur[at] = cur[at].copy(translated = nt); n++
                    }
                    if (n > 0) subs = cur
                }
                fixedTotal += n
            }
            win++
            if (from + WIN >= snap.size) break
            from += STEP; nap(2000)
        }
        // علّم الجمل اللي اتراجعت — وآخر 8 جمل مش بتتعلّم إلا في التنقيح النهائي (لسه ماعندهاش سياق بعدها)
        if (okAll && running) synchronized(lock) {
            val upto = if (isFinal) snap.size else maxOf(0, snap.size - 8)
            for (k in 0 until upto) refinedKeys.add(pk(snap[k]))
        }
        host.log((if (isFinal) "✍ تنقيح الترجمة بالسياق" else "✍ تنقيح جزئي (مفتاح $partialKeyTail)") + ": راجعت ${snap.size - maxOf(0, firstNew - LEAD)} جملة وصحّحت $fixedTotal")
        if (fixedTotal > 0) { host.changed(); persist() }
    }

    // --- ترجمة قالب الـ prompt للغات اللي ملهاش قالب جاهز (_translatePromptTemplate) ---
    // ===== أدوات يدوية من المشغّل (أزرار الشاشة الكاملة في نسخة الـ HTML) =====
    class Version(val name: String, val subs: List<Sub>, val dialect: String = "")
    val versions = java.util.concurrent.CopyOnWriteArrayList<Version>()
    fun saveVersion(name: String, dialect: String = convDialect.ifBlank { "فصحى" }) { versions.add(Version(name, subs, dialect)); while (versions.size > 12) versions.removeAt(0) }
    /** يرجّع نص الترجمة (translated) بس من نسخة محفوظة على الجمل المطابقة (نفس البداية والأصل)، والجمل الجديدة بتفضل زي ما هي */
    fun applyVersion(i: Int): Int {
        val v = versions.getOrNull(i) ?: return 0
        var n = 0
        synchronized(lock) {
            val cur = subs.toMutableList()
            for (k in cur.indices) {
                val m = v.subs.firstOrNull { Math.abs(it.start - cur[k].start) < 0.05 && it.original == cur[k].original } ?: continue
                if (m.translated != cur[k].translated) { cur[k] = cur[k].copy(translated = m.translated); n++ }
            }
            subs = cur
        }
        host.changed(); persist(); return n
    }

    @Volatile var toolBusy: String? = null
        private set

    /** 🔧 تصحيح الضمائر لكل الجمل (من جدول الشخصيات + جنس الصوت) */
    fun correctPronounsNow(report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        val cs = effectiveChars()
        if (cs.isEmpty()) { report("مفيش جدول شخصيات — ضيف شخصيات من الإعدادات ← الشخصيات الأول"); done(0); return }
        toolBusy = "تصحيح الضمائر"
        Thread {
            var fixed = 0
            val mine = HashSet<String>()
            try {
                saveVersion("قبل تصحيح الضمائر")
                val snap = subs
                val all = snap.indices.toList()
                for (k in all) mine.add(pk(snap[k])); pend.addAll(mine)
                val batches = all.chunked(40)
                batches.forEachIndexed { bi, batch ->
                    if (!running) return@forEachIndexed
                    report("🔧 ضمائر ${bi + 1}/${batches.size}")
                    fixed += try { runPronounCorrection(snap, batch, cs) } catch (e: Exception) { host.log("⚠ ضمائر: " + (e.message ?: "").take(80)); 0 }
                    for (k in batch) pend.remove(pk(snap[k]))
                }
                if (fixed > 0) { host.changed(); persist() }
            } finally { pend.removeAll(mine); toolBusy = null }
            done(fixed)
        }.start()
    }

    /** إعادة صياغة الترجمة الحالية بتعليمة (لهجة أقوى / عائلي / صريح / لهجة لايف) من نقطة معينة لآخر الفيديو */
    fun rewriteAll(label: String, instruction: String, fromSec: Double, report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        toolBusy = label
        Thread {
            var changed = 0
            val mine = HashSet<String>()
            try {
                saveVersion("قبل $label")
                val snap = subs
                val idx = snap.indices.filter { snap[it].end >= fromSec }
                for (k in idx) mine.add(pk(snap[k])); pend.addAll(mine)
                val table = effectiveChars().joinToString("\n") { "- \"${it.name}\": ${if (it.gender == "female") "أنثى" else "ذكر"}" }.ifEmpty { "(مفيش)" }
                val batches = idx.chunked(40)
                batches.forEachIndexed { bi, batch ->
                    if (!running) return@forEachIndexed
                    report("$label ${bi + 1}/${batches.size}")
                    val list = JSONArray()
                    for (k in batch) { val s = snap[k]; list.put(JSONObject().put("idx", k).put("original", s.original).put("translated", s.translated).put("speaker_gender", s.gender).put("addressee_gender", s.addressee)) }
                    val prompt = "أنت محرر ترجمة محترف. اللغة/اللهجة الحالية: ${conf.lang} — الأسلوب: ${conf.style}.\n" +
                        "المطلوب بالظبط: $instruction\n" +
                        "قواعد ثابتة: حافظ على المعنى وعلى عدد الجمل وترتيبها (ماتدمجش ولا تقسّم). ماتقلبش ذكر لأنثى ولا العكس: جنس المتكلم = speaker_gender، وجنس المخاطَب = addressee_gender، وجنس الشخصيات المسماة من الجدول:\n$table\n" +
                        "ماتلمسش الجملة لو مش محتاجة تغيير.\n\nالجمل:\n$list\n\n" +
                        "أرجع JSON فقط بالجمل اللي اتغيّرت: {\"rewrites\":[{\"idx\":0,\"translated\":\"النص الجديد كامل\"}]}"
                    try {
                        val r = bgCall(prompt, 8192, 0.4, true, bi)
                        val arr = (Parse.json(r.text) ?: return@forEachIndexed).optJSONArray("rewrites") ?: return@forEachIndexed
                        synchronized(lock) {
                            val cur = subs.toMutableList()
                            for (q in 0 until arr.length()) {
                                val c = arr.optJSONObject(q) ?: continue
                                val ix = c.optInt("idx", -1); if (ix !in batch) continue
                                val tr = c.optString("translated").trim(); if (tr.isEmpty()) continue
                                val t = snap[ix]
                                val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                                if (at >= 0 && cur[at].translated != tr) { cur[at] = cur[at].copy(translated = tr); changed++ }
                            }
                            subs = cur
                        }
                        host.changed()
                    } catch (e: Exception) { host.log("⚠ $label: " + (e.message ?: "").take(80)) }
                    finally { for (k in batch) pend.remove(pk(snap[k])) }
                }
                if (changed > 0) persist()
            } finally { pend.removeAll(mine); toolBusy = null }
            done(changed)
        }.start()
    }

    /**
     * (v151) ✍ تنقيح كامل بطلب واحد: الأصل المستخرج + الترجمة الحالية لكل الجمل المترجمة بتتبعت مرة واحدة (مش باتش باتش)
     * للمفتاح الاحتياطي، ولو مفيش فلأي مفتاح أساسي فاضي. الموديل بيصلّح الترجمة بالسياق (والكلمات الحرفية اللي معناها
     * بيتغيّر مع المشهد) وبيرجّع الجمل اللي اتغيّرت بس، وإحنا بنطبّقها مكانها. فيه نسخة محفوظة قبل التنقيح للرجوع.
     */
    fun refineWholeNow(report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        toolBusy = "تنقيح بالسياق"
        Thread {
            var fixed = 0
            try {
                val snap = subs.filter { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") }.sortedBy { it.start }
                if (snap.size < 3) report("مفيش جمل مترجمة كفاية للتنقيح")
                else {
                    saveVersion("قبل التنقيح بالسياق"); refiningSegs = listOf(doubleArrayOf(snap.first().start, snap.last().end))
                    val roster = pb.rosterText(effectiveChars(), effectiveGloss())
                    val parts = snap.chunked(3000)
                    for ((pi, part) in parts.withIndex()) {
                        report("✍ تنقيح ${pi + 1}/${parts.size} — ${part.size} جملة في طلب واحد")
                        val arr = JSONArray()
                        for ((k, q) in part.withIndex()) arr.put(JSONArray().put(k).put(q.original).put(q.translated).put(q.gender))
                        val prompt = "أنت مراجع ترجمة محترف. دي كل جمل فيلم/مسلسل/فيديو بالترتيب. كل عنصر: [رقم، الأصل، الترجمة الحالية، جنس المتكلم]. الترجمة الحالية اتعملت على مقاطع صوتية منفصلة من غير ما كل مقطع يشوف باقي الفيديو، فغالبًا فيها جمل حرفية أو غلط في السياق.\n" +
                            (if (roster.isNotBlank()) "\nمعلومات الشخصيات والمصطلحات:\n$roster\n" else "") +
                            "\nالجمل:\n$arr\n\n" +
                            "اقرا الفيديو كله كأنك بتتفرج عليه، وصحّح الترجمة (النقاط دي بس):\n" +
                            "1) كلمة أو تعبير اتترجم حرفي ومعناه في المشهد مختلف (اصطلاح، سخرية، تلميح، مجاز، رد على جملة قبلها).\n" +
                            "2) ضمير/فاعل/مفعول أو جنس غلط، أو اسم ومصطلح متترجم بأكتر من شكل.\n" +
                            "3) جملة مقطوعة بين مقطعين وترجمتها مش متصلة بجارتها، أو ترجمة بتتناقض مع اللي حواليها.\n" +
                            "القواعد: حافظ على لهجة ${conf.lang} وأسلوب ${conf.style}؛ الجملة قصيرة قد الأصلية تقريبًا؛ ماتغيّرش جملة سليمة ولا تحسّن أسلوب بس؛ ممنوع تضيف أو تحذف أو تدمج جمل.\n" +
                            "رجّع JSON فقط بالجمل اللي اتغيّرت: {\"corrections\":[{\"i\":12,\"translated\":\"النص المصحح الكامل\"}]} — لو كله سليم: {\"corrections\":[]}"
                        var key: String = (conf.refine + conf.backup).firstOrNull { pool.ok(it) } ?: pool.pickIdle(-1000, null, perKey) ?: helperKey() ?: throw Exception("مفيش مفتاح")
                        pool.unreserve(-1000)
                        var r: Api.Result? = null; var tries = 0
                        while (r == null) {
                            try { r = Api.generate(curModel(), key, prompt, null, 30000, 0.1, true, false) }
                            catch (e: Exception) {
                                val code = (e as? ApiErr)?.code ?: 0
                                if (++tries > 3 || code == 400 || e is Unsupported) throw e
                                if (code == 429) pool.block(key, 60_000) else if (code == 401 || code == 403) pool.block(key, 3_600_000)
                                nap(2000L * tries)
                                key = pool.pickIdle(-1000, key, perKey)?.also { pool.unreserve(-1000) } ?: helperKey(tries) ?: key
                            }
                        }
                        val ca = Parse.json(r!!.text)?.optJSONArray("corrections")
                        if (ca == null) { report("رد التنقيح مش صالح (ممكن اتقطع) — جرّب تاني"); continue }
                        var n = 0
                        synchronized(lock) {
                            val cur = subs.toMutableList()
                            for (q in 0 until ca.length()) {
                                val c = ca.optJSONObject(q) ?: continue
                                val k = c.optInt("i", -1); if (k < 0 || k >= part.size) continue
                                val nt = c.optString("translated").trim(); if (nt.isEmpty()) continue
                                val t = part[k]
                                if (nt == t.translated || nt.length > t.translated.length * 3 + 20 || nt.length * 3 + 20 < t.translated.length) continue
                                val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                                if (at < 0 || cur[at].translated != t.translated) continue
                                cur[at] = cur[at].copy(translated = nt); n++
                            }
                            if (n > 0) subs = cur
                            for (q in part) refinedKeys.add(pk(q))
                        }
                        fixed += n
                    }
                    if (fixed > 0) { host.changed(); persist() }
                    host.log("✍ تنقيح بالسياق (طلب واحد): ${snap.size} جملة — اتصحّح $fixed")
                }
            } catch (e: Exception) {
                host.log("⚠ التنقيح بالسياق فشل: " + (e.message ?: e.toString()).take(120)); report("التنقيح فشل: " + (e.message ?: "").take(80))
            } finally { toolBusy = null; refiningSegs = emptyList() }
            done(fixed)
        }.apply { isDaemon = true }.start()
    }

    /**
     * (v153) 🌍 ترجمة جوجل (الرابط غير الرسمي translate.googleapis.com — من غير مفتاح ولا API): بتترجم النص الأصلي لكل الجمل
     * (25 جملة في الطلب) وتحطه مكان الترجمة الحالية، فيتعرض على الفيديو عادي. جوجل بيترجم لـ«العربية» بس (مش لهجة).
     * بيتحفظ نسخة «قبل ترجمة جوجل» للرجوع.
     */
    private fun gtCall(texts: List<String>, tl: String): List<String>? {
        val body = "client=gtx&sl=auto&tl=" + tl + "&dt=t&q=" + java.net.URLEncoder.encode(texts.joinToString("\n"), "UTF-8")
        var last: Exception? = null
        for (t in 1..4) {
            try {
                val c = java.net.URL("https://translate.googleapis.com/translate_a/single").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 25000
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code == 429 || code >= 500) { c.disconnect(); nap(3000L * t); continue }
                if (code != 200) { c.disconnect(); throw IOException("HTTP $code") }
                val txt = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }; c.disconnect()
                val segs = JSONArray(txt).optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (k in 0 until segs.length()) sb.append(segs.optJSONArray(k)?.optString(0, "") ?: "")
                val parts = sb.toString().split("\n")
                return if (parts.size == texts.size) parts.map { it.trim() } else null
            } catch (e: Exception) { last = e; nap(1500L * t) }
        }
        if (last != null) throw last
        return null
    }

    fun googleTranslateAll(report: (String) -> Unit, done: (Int) -> Unit) {
        if (toolBusy != null) { report("في عملية شغالة: " + toolBusy); return }
        toolBusy = "ترجمة جوجل"
        Thread {
            var changed = 0
            try {
                val snap = subs.sortedBy { it.start }
                val idx = snap.indices.filter { !snap[it].isSound && snap[it].original.isNotBlank() }
                if (idx.isEmpty()) report("مفيش نص أصلي لسه للترجمة")
                else {
                    saveVersion("قبل ترجمة جوجل")
                    val out = HashMap<Int, String>()
                    val batches = idx.chunked(25)
                    var failedB = 0
                    for ((bi, b) in batches.withIndex()) {
                        if (!running && isClosed) break
                        if (bi % 8 == 0) report("🌍 جوجل ${bi + 1}/${batches.size}")
                        val src = b.map { snap[it].original.replace(Regex("\\s*\n\\s*"), " ").trim() }
                        try {
                            val r = gtCall(src, "ar")
                            if (r != null) { for ((q, k) in b.withIndex()) out[k] = r[q] }
                            else for ((q, k) in b.withIndex()) { gtCall(listOf(src[q]), "ar")?.firstOrNull()?.let { out[k] = it } }   // عدد الأسطر اختلف: جملة جملة
                        } catch (e: Exception) { failedB++; host.log("⚠ ترجمة جوجل: " + (e.message ?: e.toString()).take(80)); if (failedB >= 5) { report("جوجل واقف أو النت فاصل — وقفت"); break } }
                    }
                    synchronized(lock) {
                        val cur = subs.toMutableList()
                        for ((k, tr) in out) {
                            if (tr.isBlank()) continue
                            val t = snap[k]
                            val at = cur.indexOfFirst { Math.abs(it.start - t.start) < 0.05 && it.original == t.original }
                            if (at >= 0 && cur[at].translated != tr) { cur[at] = cur[at].copy(translated = tr); changed++ }
                        }
                        if (changed > 0) subs = cur
                    }
                    if (changed > 0) { host.changed(); persist() }
                    host.log("🌍 ترجمة جوجل: ${idx.size} جملة — اتغيّرت $changed")
                }
            } catch (e: Exception) {
                host.log("⚠ ترجمة جوجل فشلت: " + (e.message ?: e.toString()).take(120)); report("ترجمة جوجل فشلت: " + (e.message ?: "").take(80))
            } finally { toolBusy = null }
            done(changed)
        }.apply { isDaemon = true }.start()
    }

    /** 🧠 دمج الجمل المكررة المتداخلة زمنيًا (محلي بدون Gemini): جملتين متداخلتين ومتشابهتين جدًا = واحدة بأطول وقت */
    fun removeDuplicates(): Int {
        var removed = 0
        synchronized(lock) {
            val cur = subs.sortedBy { it.start }.toMutableList()
            fun toks(x: String) = x.replace(Regex("[\\p{P}\\s]+"), " ").trim().split(" ").filter { it.isNotEmpty() }.toSet()
            var i = 0
            while (i < cur.size - 1) {
                val a = cur[i]; val b = cur[i + 1]
                val ov = minOf(a.end, b.end) - maxOf(a.start, b.start)
                val ta = toks(a.translated); val tb = toks(b.translated)
                val inter = ta.intersect(tb).size; val uni = ta.union(tb).size
                val sim = if (uni == 0) 0.0 else inter.toDouble() / uni
                if (ov > 0.3 && a.faint == b.faint && !(a.overlap && b.overlap && a.gender != b.gender) && (sim >= 0.7 || a.translated.contains(b.translated) || b.translated.contains(a.translated))) {
                    val keep = if (b.translated.length >= a.translated.length) b else a
                    cur[i] = keep.copy(start = minOf(a.start, b.start), end = maxOf(a.end, b.end))
                    cur.removeAt(i + 1); removed++
                } else i++
            }
            val cleaned = Subs.dropEchoes(cur)
            removed += cur.size - cleaned.size
            if (removed > 0) subs = cleaned.map { Subs.collapseSelfRepeat(it) }
        }
        if (removed > 0) { saveVersion("بعد دمج المكرر"); host.changed(); persist() }
        return removed
    }

    /** 📜 ذكّرني: ملخص لكل الأحداث من أول الفيديو لحد المكان الحالي */
    fun recap(upToSec: Double, report: (String) -> Unit, done: (String) -> Unit) {
        Thread {
            try {
                val snap = subs.filter { it.start <= upToSec }
                if (snap.isEmpty()) { done("لسه مفيش ترجمة قبل المكان ده"); return@Thread }
                val stride = if (snap.size > 500) Math.ceil(snap.size / 500.0).toInt() else 1
                val text = snap.filterIndexed { i, _ -> i % stride == 0 }.joinToString("\n") { it.translated.ifEmpty { it.original } }
                report("📜 بلخّص…")
                val r = bgCall("ده حوار فيديو من أوله لحد اللحظة الحالية. لخّص بالعامية المصرية في 6 لـ 10 أسطر قصيرة أهم اللي حصل وإيه الشخصيات وإيه اللي كان بيحصل في آخر مشهد، من غير حرق للي هيجي بعد كده ومن غير مقدمات:\n\n$text", 1500, 0.4, false)
                done(r.text.trim().ifEmpty { "ملقيتش ملخص" })
            } catch (e: Exception) { done("⚠ فشل التلخيص: " + (e.message ?: "").take(100)) }
        }.start()
    }

    private fun maybeTranslateTemplate() {
        if (!conf.autoTemplate) return
        if (pb.caseOf(srcLang, detDone) != "other") return
        val lang = srcLang
        if (lang.contains("عرب")) return
        val id = pb.templateId(conf, "other")
        val k = "$lang|$id"
        synchronized(tplCache) { if (tplCache.containsKey(k) || tplBusy.contains(k) || (tplFails[k] ?: 0) >= 2) return; tplBusy.add(k) }
        try {
            bg.submit {
                try { translateTemplate(id, lang, k) }
                catch (e: Exception) { synchronized(tplCache) { tplFails[k] = (tplFails[k] ?: 0) + 1 }; host.log("⚠ ترجمة قالب الـ prompt فشلت: " + (e.message ?: "").take(100)) }
                finally { synchronized(tplCache) { tplBusy.remove(k) } }
            }
        } catch (_: Exception) { synchronized(tplCache) { tplBusy.remove(k) } }
    }

    private fun translateTemplate(id: String, lang: String, k: String) {
        val token = "[[ROSTER_BLOCK]]"
        val text = pb.fixedPart(id).replace("§ROSTER§", token)
        val prompt = pb.read("prompts/tpl_translate.txt").replace("§LANG§", lang).replace("§TEXT§", text)
        host.log("🈯 بترجم قالب الـ prompt للغة $lang…")
        val r = bgCall(prompt, 32768, 0.0, false)
        val t = r.text.trim()
        if (r.finish != "STOP" || t.length < 200) throw Exception("الترجمة ناقصة (${r.finish})")
        val fin = if (t.contains(token)) t.replace(token, "§ROSTER§") else "$t\n§ROSTER§"
        synchronized(tplCache) { tplCache[k] = fin }
        host.log("🈯 قالب الـ prompt بقى بلغة $lang من المقطع الجاي")
        persist()
    }

    // ===== إصلاح الجمل اللي فضلت بلغتها الأصلية (بتتفحص كل الجمل بعد كل مقطع، حتى القديمة المحفوظة) =====
    private val fixTries = ConcurrentHashMap<String, Int>()
    @Volatile private var lastFix = 0L
    private fun fixKey(s: Sub) = "${s.start}|${s.original}"
    private fun hasRepairable(): Boolean = synchronized(lock) { subs.any { LangGuard.foreign(it) && (fixTries[fixKey(it)] ?: 0) < 3 } }
    private fun repairForeignTick(): Boolean {
        if (!running) return false
        val now = System.currentTimeMillis()
        if (now - lastFix < 12_000) return false
        val bad = synchronized(lock) { subs.filter { LangGuard.foreign(it) && (fixTries[fixKey(it)] ?: 0) < 3 } }.take(30)
        if (bad.isEmpty()) return false
        lastFix = now
        bad.forEach { fixTries.merge(fixKey(it), 1, Int::plus) }
        host.log("🌐 بصلّح ${bad.size} جملة لسه مش بـ${conf.lang}…")
        val fixed = fixForeign(bad)
        val map = HashMap<String, Sub>()
        bad.indices.forEach {
            if (fixed[it].translated != bad[it].translated) {
                val wrapped = bad[it].translated.startsWith("«") && !fixed[it].translated.startsWith("«")
                map[fixKey(bad[it])] = if (wrapped) fixed[it].copy(translated = "«" + fixed[it].translated + "»") else fixed[it]
            }
        }
        if (map.isNotEmpty()) {
            synchronized(lock) { subs = subs.map { map[fixKey(it)] ?: it } }
            host.changed(); persist()
        }
        return true
    }

    // ===== سدّ الفجوات تلقائيًا أثناء المشاهدة (autoGapFill) =====
    /** مناطق اتترجمت بس مفيهاش جمل لمدة طويلة، قريبة من مكان التشغيل: بتتبعت بمفاتيح المراقبين/الاحتياطي وبتتعلّم بـ «» */
    private fun gapTick(all: Boolean = false): Boolean {
        if (!conf.gapFill || !running) return false
        val now = System.currentTimeMillis()
        if (now - lastGapScan < Gaps.COOLDOWN_MS) return false
        lastGapScan = now
        if (gapBusy.get() >= Gaps.MAX_PARALLEL) return false
        val d = currentDur(); val pos = if (headless) d else host.position()
        val atEnd = d > 0 && pos >= d - 2
        val elig = Gaps.find(subs, done.list(), Gaps.MIN_SEC).filter {
            !gapTried.covers(it[0], it[1]) && (atEnd || all || (pos >= it[0] - Gaps.LOOKAHEAD && pos < it[1]))
        }
        if (elig.isEmpty()) { if (all || atEnd) gapScanClean = true; return false }
        var started = false
        for (g in elig) {
            if (gapBusy.get() >= Gaps.MAX_PARALLEL || gapSession.total() >= Gaps.MAX_TOTAL * ch) break
            val key = pool.gapKey(gapKeys, rr.getAndIncrement()) ?: break
            gapTried.add(g[0], g[1]); gapSession.add(g[0], g[1]); gapKeys.add(key); gapBusy.incrementAndGet()
            host.log("🔁 سدّ فجوة تلقائي: ${"%.0f".format(java.util.Locale.US, g[0])}ث → ${"%.0f".format(java.util.Locale.US, g[1])}ث")
            try {
                gapEx.submit {
                    try { for (pt in Gaps.parts(g[0], g[1], minOf(ch, Coverage.PIECE_SEC))) { if (!running) break; fillGap(pt[0], pt[1], key) } }
                    catch (e: Exception) { host.log("⚠ سدّ الفجوة فشل: " + (e.message ?: "").take(100)) }
                    finally { gapKeys.remove(key); gapBusy.decrementAndGet() }
                }
                started = true
            } catch (_: RejectedExecutionException) { gapKeys.remove(key); gapBusy.decrementAndGet() }
        }
        if (all || atEnd) gapScanClean = !started
        return started
    }

    private val holeTried = ConcurrentHashMap.newKeySet<Int>()
    private val holeBusy = AtomicInteger(0)
    private val holeEx = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true; it.name = "holefill" } }
    /** سدّ الثغرات كان شغال جوه خانة الباتش (inflight) فكل باتش بيستنى طلبات الثغرات قبل ما الباتش اللي بعده يتبعت = تأخير. دلوقتي على خيط لوحده */
    private fun scheduleHoleFill(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, key: String) {
        if (holeTried.contains(i)) return
        holeBusy.incrementAndGet()
        try {
            holeEx.submit {
                try { holeFill(i, rawStart, rawEnd, w, key) }
                catch (e: Exception) { host.log("⚠ سدّ الثغرات في المقطع ${i + 1} فشل: " + (e.message ?: "").take(80)) }
                finally { holeBusy.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { holeBusy.decrementAndGet() }
    }

    /**
     * بعد ما المقطع يتطبّق: نقيس الصوت الفعلي ونشوف فيه كلام/غنا مسموع (>= HOLE_MIN ثانية) من غير ولا جملة فوقه.
     * الموديل بيرجّع جزء من المقطع وبيفوّت الباقي (خصوصًا الكورس المتكرر) — فبنعيد طلب الجزء الناقص بس
     * على أجزاء صغيرة (<= 20ث) وبسياق الجمل اللي قبله. مرة واحدة لكل مقطع.
     */
    private fun holeFill(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, key0: String) {
        val spans = Speech.activeSpans(w.bytes, w.gain)?.let { Speech.absolute(it, w.startSec) } ?: return
        if (!holeTried.add(i)) return
        val holes = Speech.holes(spans, subs, maxOf(rawStart, w.startSec), minOf(rawEnd, w.startSec + w.durSec), HOLE_MIN).take(HOLE_MAX)
        if (holes.isEmpty()) return
        host.log("🧩 المقطع ${i + 1}: ${holes.size} ثغرة فيها صوت من غير ترجمة — بعيد طلبها")
        var key = key0
        for (h in holes) {
            // (v177) ثغرة ملزوقة في جملة اتترجمت (≤1.5ث) = غالبًا كلام ناقص مش موسيقى: بنطلبها بتعليمات «كمّل الناقص» وبنقبل الخافت
            val near = nearSpeech(h[0], h[1])
            for (pt in Gaps.parts(maxOf(0.0, h[0] - 0.5), h[1] + 0.5, 20.0)) {
                if (!running) return
                val w2 = src().wav(pt[0], pt[1]) ?: continue
                if (w2.silent) continue
                var tries = 0; var got = false
                while (running && !got && tries++ < 3) {
                    try {
                        val r = Api.generate(curModel(), key, buildPrompt(w2.durSec, w2.startSec, hole = true, holeNear = near), w2.bytes)
                        val j = if (r.text.isBlank()) null else Parse.json(r.text)
                        // (v143) الثغرة غالبًا موسيقى/مؤثرات: الرد الفاضي إجابة صحيحة، وماينفعش نعيد الطلب لحد ما الموديل "يلاقي" كلام
                        var base = if (j == null) emptyList() else Subs.splitAll(Parse.subs(j, w2.startSec, w2.durSec)).filter { !it.isSound && (near || (!it.faint && !it.lowConf)) }.map { Subs.capPace(it) }
                        if (base.any { LangGuard.foreign(it) }) base = fixForeign(base)
                        val sp2 = Speech.activeSpans(w2.bytes, w2.gain)?.let { Speech.absolute(it, w2.startSec) }
                        if (sp2 != null) base = base.mapNotNull { Speech.fit(it, sp2) }
                        base = base.filter { val m = (it.start + it.end) / 2; m >= h[0] - 0.5 && m <= h[1] + 0.5 }.map { it.copy(chunk = i) }
                        if (base.isEmpty()) { got = true; host.log("… ثغرة ${"%.0f".format(java.util.Locale.US, pt[0])}ث: مفيهاش كلام — اتساب زي ما هي"); continue }
                        synchronized(lock) { subs = Subs.merge(Subs.dedup(subs + base)).sortedBy { it.start } }
                        pool.good(key); got = true
                        host.changed(); persist()
                        host.log("✅ ثغرة ${"%.0f".format(java.util.Locale.US, pt[0])}ث→${"%.0f".format(java.util.Locale.US, pt[1])}ث: اتضاف ${base.size} جملة")
                    } catch (e: ApiErr) {
                        if (e.code == 429) pool.block(key, 60_000) else if (e.code == 403) pool.block(key, 3_600_000) else { host.log("⚠ ثغرة: " + (e.message ?: "").take(80)); return }
                        key = pool.pick(key, rr.getAndIncrement()) ?: return
                    } catch (e: IOException) { host.log("📶 ثغرة: مشكلة اتصال"); return }
                }
            }
        }
    }

    /** (v177) في جملة مترجمة (مش وصف صوت) بتنتهي قبل بداية المجال أو بتبدأ بعد نهايته بـ ≤1.5ث؟ */
    private fun nearSpeech(a: Double, b: Double): Boolean = synchronized(lock) { subs.any { !it.isSound && (Math.abs(it.end - a) <= 1.5 || Math.abs(it.start - b) <= 1.5) } }

    private fun fillGap(a: Double, b: Double, key0: String) {
        val w = src().wav(a, b) ?: return
        if (w.silent) { host.log("🔇 الفجوة ${a.toInt()}ث صامتة — اتخطّيت"); return }
        var key = key0; var tries = 0
        val near = nearSpeech(a, b)
        while (running && tries++ < 3) {
            try {
                val r = Api.generate(curModel(), key, buildPrompt(w.durSec, w.startSec, hole = true, holeNear = near), w.bytes)
                val j = if (r.text.isBlank()) null else Parse.json(r.text)
                var base = if (j == null) emptyList() else Subs.splitAll(Parse.subs(j, w.startSec, w.durSec)).filter { !it.isSound && (near || (!it.faint && !it.lowConf)) }.map { Subs.capPace(it) }
                if (base.any { LangGuard.foreign(it) }) base = fixForeign(base)
                val sp = Speech.activeSpans(w.bytes, w.gain)?.let { Speech.absolute(it, w.startSec) }
                if (sp != null) base = base.mapNotNull { Speech.fit(it, sp) }
                val marked = base.map { it.copy(translated = "«" + it.translated + "»", chunk = -2) }
                if (marked.isEmpty()) { host.log("… فجوة ${a.toInt()}ث: مفيهاش كلام — اتساب زي ما هي"); return }
                synchronized(lock) { subs = Subs.merge(Subs.dedup(subs + marked)).sortedBy { it.start } }
                pool.good(key); host.changed(); persist()
                host.log("✅ فجوة ${a.toInt()}ث: اتسدّ ${marked.size} جملة")
                return
            } catch (e: ApiErr) {
                if (e.code == 429) pool.block(key, 60_000) else if (e.code == 403) pool.block(key, 3_600_000) else throw e
                key = pool.gapKey(gapKeys - key, rr.getAndIncrement()) ?: return
            }
        }
    }

    // ===== الحفظ =====
    /** حفظ مؤجّل: بيجمّع كل طلبات الحفظ في 3 ثواني في كتابة واحدة على خيط لوحده (بدل ما كل باتش يكتب الملف كله). الحفظ الإجباري (إغلاق/نهاية) بيروح لـ doPersist مباشرة */
    private val persistPending = AtomicBoolean(false)
    private val persistEx = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r).also { it.isDaemon = true; it.name = "persist" } }
    private fun persist() {
        if (store == null || isClosed) return
        if (!persistPending.compareAndSet(false, true)) return
        try { persistEx.schedule({ persistPending.set(false); try { doPersist() } catch (_: Exception) {} }, 3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: RejectedExecutionException) { persistPending.set(false); doPersist() }
    }
    private fun doPersist(force: Boolean = false) {
        val st = store ?: return
        if (persistBlocked) return
        if (isClosed && !force) return   // خيط متأخر من إنجن اتقفل: ممنوع يكتب
        val saved = synchronized(lock) {
            val fr = failed.filter { it.value >= MAX_FAILS }.keys.sorted().map { doubleArrayOf(cStart(it), cStart(it + 1)) }
            Saved(conf.chunkSec, srcLang, detDone, subs, done.list(), fr, chars.toList(), gloss.toList(), synchronized(tplCache) { HashMap(tplCache) }, host.position(), autoCharsAttempts, HashMap(bounds), gapTried.list(), voices.toJson())
        }
        try { st.save(saved) } catch (e: Exception) { host.log("⚠ تعذر حفظ التقدم: " + (e.message ?: "").take(80)) }
    }
}
