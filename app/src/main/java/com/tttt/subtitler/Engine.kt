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

class Engine(
    private val conf: Conf,
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
        const val CROSS_MIN_DUR = 600.0  // المراجعة بين المقاطع بس للفيديوهات الطويلة (زي الأصل)
        const val PRIOR_CAP = 400        // أقصى عدد جمل قديمة نبعتها للمراجعة
        const val CHAR_MIN_LINES = 12
        const val PRON_MIN_LINES = 20
        const val HOLE_MIN = 1.6         // أقل ثغرة صوتية من غير ترجمة نعيد طلبها (ثواني)
        const val HOLE_MAX = 6           // أقصى عدد ثغرات بنسدّها لكل مقطع
    }

    private val lock = Any()
    /** المشغّل بيحمّل الترجمة المحفوظة في الخلفية: لحد ما يخلص ممنوع أي حفظ (عشان مانكتبش حالة فاضية فوق الترجمة المحفوظة) */
    @Volatile var persistBlocked = false
    @Volatile var subs: List<Sub> = emptyList()
        private set
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
    private val hedgeBusy = AtomicInteger(0)
    private val recentMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()   // أزمنة آخر باتشات خلصت
    @Volatile private var maxApplied = -1                           // أعلى باتش اتطبّق (لو في باتش قبله لسه شغال يبقى متأخر)
    private val hedgeEx = Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
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
    fun batchLines(): String {
        val d = host.playerDuration()
        val c = (host.position() / ch).toInt().coerceAtLeast(0)
        val sb = StringBuilder()
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
            sb.append(if (i == c) "▶ " else "  ").append("باتش ").append(i + 1).append(" ")
                .append("%d:%02d–%d:%02d".format(a / 60, a % 60, b / 60, b % 60)).append(" ").append(mark).let { if (mark == "✅" || mark == "🔁" || mark == "🔇") it.append(" ").append(subCount(i, d)).append(" جملة") else it }.append('\n')
        }
        return sb.toString().trimEnd()
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
        lastConvFail = 0L
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
        if (System.currentTimeMillis() - lastConvFail < 60_000) return
        val todo = synchronized(lock) { subs.filter { !it.conv } }
        if (todo.isEmpty()) return
        val span = todo.maxOf { it.end } - todo.minOf { it.start }
        if (!force && span < ch * 0.5) return
        if (!convBusy.compareAndSet(false, true)) return
        try { bg.submit { try { convertSubs(tgt) } finally { convBusy.set(false) } } } catch (_: Exception) { convBusy.set(false) }
    }
    private fun convertSubs(tgt: String): Int {
        var changed = 0
        val snap = synchronized(lock) { subs }
        val idx0 = snap.indices.filter { !snap[it].conv }
        if (idx0.isEmpty()) return 0
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
                host.changed()
            } catch (e: Exception) { lastConvFail = System.currentTimeMillis(); host.log("⚠ التحويل للهجة فشل: " + (e.message ?: "").take(130)) }
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
        for (i in from..last) { failed.remove(i); gapPass.remove(i); holeTried.remove(i); claimed.remove(i) }
        pool.clear(); lastGapTry = 0L; gapScanClean = false
        host.changed(); persist()
    }
    /** باتش ده وبعده كله (بيبدأ منه ويكمل) */
    fun redoFrom(i: Int, keep: Boolean = true) { redo(i, -1, keep); onlyChunks = null; forcedCursor = i; paused = false }
    /** باتش ده لوحده وخلاص */
    fun redoOnly(i: Int, keep: Boolean = true) { redo(i, i, keep); forceVad.add(i); onlyChunks = i..i; paused = false }
    /** من الأول خالص */
    fun redoAll(keep: Boolean = true) { redo(0, -1, keep); onlyChunks = null; forcedCursor = 0; paused = false }
    fun resumeAuto() { onlyChunks = null; paused = false }
    /** كمّل الترجمة من المكان ده (من غير مسح حاجة): بيقفز بالمؤشر لباتش الوقت ده ويلغي الإيقاف */
    fun translateFrom(sec: Double) { onlyChunks = null; forcedCursor = chunkOfSec(sec.coerceAtLeast(0.0)); paused = false; userPaused = false }
    fun stop() { running = false; bg.shutdownNow(); gapEx.shutdownNow(); exec?.shutdownNow(); try { hedgeEx.shutdownNow() } catch (_: Exception) {}; try { holeEx.shutdownNow() } catch (_: Exception) {}; try { persistEx.shutdownNow() } catch (_: Exception) {} }
    fun saveNow() = doPersist()
    /** استيراد ترجمة جاهزة (SRT) لفيديو من غير ترجمة */
    fun importSubs(l: List<Sub>) { subs = l }
    fun retryFailed() {
        pool.clear(); gapPass.clear(); gapTried.clear(); lastGapTry = 0L
        for (k in failed.keys) failed[k] = MAX_FAILS
        host.log("🔁 هعيد محاولة المقاطع الفاشلة (${failedCount()})")
    }
    fun charactersNow(): List<Chr> = effectiveChars()

    /** يسترجع التقدم المحفوظ. يرجع آخر مكان تشغيل (بالثواني) أو 0. */
    fun load(): Double {
        val s = store?.load() ?: return 0.0
        synchronized(lock) {
            subs = Subs.unifyOverlaps(Subs.dedup((if (s.chunkSec == conf.chunkSec) s.subs else s.subs.map { it.copy(chunk = -1) }).map { Subs.collapseSelfRepeat(it) }))
            for (r in s.done) done.add(r[0], r[1])
            for (r in s.failed) failed[Math.round(r[0] / ch).toInt()] = MAX_FAILS
            chars.addAll(s.chars); gloss.addAll(s.gloss); tplCache.putAll(s.tpl)
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
                    if (c >= k || (d > 0 && cStart(k) >= d)) forcedCursor = -1 else c = k
                }
                val only = onlyChunks
                val lo = only?.first ?: c
                val hi = only?.last ?: (c + window - 1)
                var next = -1
                for (i in lo..hi) {
                    if (d > 0 && cStart(i) >= d) break
                    if (isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || i in inflight) continue
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

    /** أول مقطع ناقص من i وطالع (بيتخطّى المخلّص والفاشل والشغّال دلوقتي) أو -1 */
    private fun nextUndone(from: Int, d: Double): Int {
        var i = maxOf(0, from)
        while (cStart(i) < d) {
            if (!(isDone(i, d) || (failed[i] ?: 0) >= MAX_FAILS || i in inflight)) return i
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
        } catch (e: Exception) {
            if (!running) return false
            val n = (failed[i] ?: 0) + 1
            failed[i] = n
            val where = e.stackTrace.filter { it.className.startsWith("com.tttt") || it.className.startsWith("android.media") }.take(3).joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            host.log("⚠ مقطع ${i + 1} فشل ($n/$MAX_FAILS): " + (e.message ?: e.javaClass.simpleName).take(160) + (if (where.isNotEmpty()) " [$where]" else ""))
            if (n >= MAX_FAILS) host.log("🕳 المقطع ${i + 1} اتسجل كفجوة — هعيد محاولته تلقائيًا بعد شوية")
            persist()
            nap(4000)
        }
        return false
    }

    /** فك صوت المقطع + الإرسال لجيميناي كله في خيط من الحوض: عدة باتشات بتفك صوتها وتتبعت بالتوازي على كل المفاتيح (قبل كده الفك كان بالترتيب في حلقة واحدة فالباتشات كانت بتمشي واحد ورا التاني) */
    private fun dispatch(i: Int, ex: java.util.concurrent.ExecutorService, force: Boolean = false) {
        if (!inflight.add(i)) return
        startedAt[i] = System.currentTimeMillis(); hedged.remove(i)
        try {
            ex.submit {
                try {
                    var p: Prep? = null
                    val ok = guard(i) { val got = prepare(i, force); p = got; if (got != null) preps[i] = got }
                    if (ok) {
                        val pp = p
                        if (pp == null) failed.remove(i)
                        else { startedAt[i] = System.currentTimeMillis(); if (guard(i) { send(i, pp) }) failed.remove(i) }
                    }
                } finally { inflight.remove(i); preps.remove(i); startedAt.remove(i); keyOf.remove(i); host.changed() }
            }
        } catch (_: RejectedExecutionException) { inflight.remove(i); preps.remove(i); startedAt.remove(i) }
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
                        w = WavChunk(Silence.truncate(w.bytes, cut), w.startSec, cut, w.silent)
                        rawEnd = adj
                        persist()
                    }
                }
            }
        }
        return Prep(w, rawStart, rawEnd)
    }

    private fun send(i: Int, p: Prep, backupFirst: Boolean = false, avoidKey: String? = null, hedge: Boolean = false) {
        val w = p.w; val rawStart = p.rawStart; val rawEnd = p.rawEnd
        val prior = subs
        // المفتاح الأقل شغلًا ثم الأسرع (مش تبادل بالدور): الباتشات بتمشي ورا بعض بدل ما الزوجية تتحبس على مفتاح بطيء
        fun nextKey(avoid: String?): String? = if (backupFirst) (pool.pickBackup(avoid) ?: pool.pickFree(avoid, rr.getAndIncrement())) else pool.pickFree(avoid, rr.getAndIncrement())
        var key = nextKey(avoidKey) ?: throw Exception("كل المفاتيح معطلة مؤقتًا — راجع المفاتيح")
        var partial = false
        var trunc = 0; var empties = 0; var bad = 0; var net = 0; var tries = 0; var langBad = 0
        while (running && tries++ < MAX_TRIES) {
            if (claimed.contains(i)) return   // نسخة تانية من الباتش ده خلصت وطُبّقت قبلنا
            if (!hedge) keyOf[i] = key
            val prompt = buildPrompt(w.durSec, w.startSec, langBad > 0, i)
            try {
                val t0 = System.currentTimeMillis()
                pool.begin(key)
                val r = try { Api.generate(conf.model, key, prompt, w.bytes).also { pool.end(key, System.currentTimeMillis() - t0, true) } } catch (e: Throwable) { pool.end(key, 0, false); throw e }
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
                if (fresh.isEmpty() && empties < (if (w.silent) 1 else 3)) {
                    empties++
                    if (!w.silent) host.log("⚠ المقطع ${i + 1} رجع من غير جمل رغم وجود صوت — إعادة محاولة ($empties/3)")
                    nextKey(key)?.let { key = it }
                    nap(400); continue
                }
                if (fresh.isEmpty() && !w.silent) { partial = true; host.log("⚠ المقطع ${i + 1} لسه من غير جمل — هيتحاول تاني كفجوة") }
                pool.good(key)
                if (!claimed.add(i)) { host.log("↩ باتش ${i + 1}: نسخة تانية خلصته قبلها — اتجاهلت دي"); return }
                if (i > maxApplied) maxApplied = i
                applyChunk(i, rawStart, rawEnd, w, j, fresh, prior)
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
                    val alt = nextKey(key)
                    if (alt != null) { host.log("⏳ ${(e.message ?: "").take(130)} على ${pool.tail(key)} — تحويل لمفتاح ${pool.tail(alt)}"); key = alt; continue }
                    val s = pool.streakUp(); val wait = minOf(60, 10 * s)
                    host.log("⏳ كل المفاتيح في كوتة (${(e.message ?: "").take(130)}) — انتظار ${wait}ث"); nap(wait * 1000L)
                    pool.clear(); continue
                }
                if (invalid) {
                    pool.block(key, 3_600_000)
                    val alt = nextKey(key)
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

    /** لهجة الباتش اللي بيتبعت: لو اتختارت لهجة، الباتش بيتترجم بيها مباشرة (من غير فصحى ثم تحويل — توفير على المفاتيح) */
    private val dialOf = ConcurrentHashMap<Int, String>()
    private val dialConf = ConcurrentHashMap<String, Conf>()
    private fun buildPrompt(durSec: Double, start: Double, strict: Boolean = false, chunk: Int = -1): String {
        val dl = convDialect.let { if (it.isBlank() || it == "فصحى") "" else it }
        if (chunk >= 0) { if (dl.isEmpty()) dialOf.remove(chunk) else dialOf[chunk] = dl }
        val c = if (dl.isEmpty() || dl == conf.lang) conf else dialConf.getOrPut(dl) { conf.withLang(dl) }
        val case = pb.caseOf(srcLang, detDone)
        val tf = if (case == "other") synchronized(tplCache) { tplCache[tplKey(pb.templateId(c, "other"))] } else null
        return toneBlock() + pb.build(c, srcLang, detDone, durSec, Subs.prevContext(subs, start), effectiveChars(), effectiveGloss(), tf, strict)
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

    private fun applyChunk(i: Int, rawStart: Double, rawEnd: Double, w: WavChunk, j: JSONObject?, fresh: List<Sub>, prior: List<Sub>) {
        if (j != null && !detDone) {
            val d = j.optString("detected_source_language", "").trim()
            if (d.isNotEmpty()) { srcLang = d; detDone = true; host.log("🌐 لغة الفيديو الأصلية: $d"); maybeTranslateTemplate() }
        }
        if (j != null) applyPrevCorrections(j)
        val spansAbs = Speech.activeSpans(w.bytes)?.let { Speech.absolute(it, w.startSec) }
        val direct = dialOf[i]?.let { it == convDialect } ?: false
        var tagged = Subs.splitAll(fresh).map { Subs.capPace(it).copy(chunk = i, conv = direct) }
        if (spansAbs != null) {
            val n0 = tagged.size
            tagged = tagged.mapNotNull { Speech.fit(it, spansAbs) }
            if (tagged.size < n0) host.log("🔕 المقطع ${i + 1}: اتشالت ${n0 - tagged.size} جملة كانت فوق صمت تام")
        }
        tagged = Subs.harmonize(tagged, subs)   // توحيد ترجمة سطور الكورَس المتكررة في الأغاني
        synchronized(lock) {
            tagged = Subs.dropOverlapZone(tagged, subs.filter { it.chunk != i }, rawStart)
            val keep = subs.filter { it.chunk != i && !(it.chunk == -1 && it.start >= rawStart && it.start < rawEnd) && !Subs.inMyZone(it, tagged, i, rawEnd) }
            subs = Subs.merge(Subs.dedup(keep + tagged)).sortedBy { it.start }
        }
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
        val pref = if (conf.backup.isNotEmpty()) conf.backup else conf.keys
        val ok = pref.filter { pool.ok(it) }
        if (ok.isNotEmpty()) return ok[Math.floorMod(i, ok.size)]
        return pool.helper()
    }

    private fun bgCall(prompt: String, maxTokens: Int, temp: Double, json: Boolean = true, i: Int = 0, search: Boolean = false): Api.Result {
        var key = helperKey(i) ?: throw Exception("مفيش مفتاح")
        var tries = 0
        while (true) {
            try { return Api.generate(conf.model, key, prompt, null, maxTokens, temp, json, search) }
            catch (e: ApiErr) {
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
        var n = 0
        synchronized(lock) {
            val ca = j.optJSONArray("characters")
            for (k in 0 until (ca?.length() ?: 0)) {
                val o = ca!!.optJSONObject(k) ?: continue
                val name = o.optString("name").trim(); if (name.isEmpty() || chars.any { it.name == name }) continue
                val g = if (o.optString("gender").lowercase().startsWith("f")) "female" else "male"
                chars.add(Chr(name, g, o.optString("role").trim())); n++
            }
        }
        host.log("🔎 لقيت العمل «" + j.optString("title") + "» — اتضافت $n شخصية هتتحط في الـ prompt من المقطع الجاي")
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

    // ===== (v109) تنقيح الترجمة: كل 3 باتشات بنبعت للمفتاح الاحتياطي كل الجمل (الأصل + الترجمة الحالية) عشان يفهم السياق ويصلّح المعاني الغلط =====
    private val refineEvery = 3
    @Volatile private var refineBusy = false
    private var refineSaved = false
    private fun maybeRefine() {
        synchronized(lock) {
            if (refineBusy || chunksDone % refineEvery != 0 || subs.size < 20) return
            refineBusy = true
        }
        try {
            bg.submit {
                try { refinePass() } catch (e: Exception) { host.log("⚠ تنقيح الترجمة فشل: " + (e.message ?: "").take(100)) }
                finally { refineBusy = false }
            }
        } catch (_: Exception) { refineBusy = false }
    }

    private fun refinePass() {
        val snap = subs.filter { !it.isSound && it.original.isNotBlank() && it.translated.isNotBlank() && !it.translated.startsWith("«") }.sortedBy { it.start }
        if (snap.size < 20) return
        val roster = pb.rosterText(effectiveChars(), effectiveGloss())
        val WIN = 600; val STEP = 560
        var fixedTotal = 0; var from = 0; var win = 0
        while (from < snap.size && running) {
            val part = snap.subList(from, minOf(snap.size, from + WIN))
            val arr = JSONArray()
            for ((k, q) in part.withIndex()) arr.put(JSONObject().put("i", k).put("original", q.original).put("translated", q.translated).put("gender", q.gender))
            val prompt = "أنت مراجع ترجمة محترف. دي كل جمل فيلم/مسلسل بالترتيب، لكل جملة النص الأصلي (original) والترجمة الحالية (translated) — الترجمة الحالية ممكن تكون اتنقّحت قبل كده.\n" +
                (if (roster.isNotBlank()) "معلومات الشخصيات والمصطلحات:\n$roster\n" else "") +
                "\nالجمل:\n$arr\n\n" +
                "مهمتك تنقيح الترجمة: افهم معنى كل كلمة في الأصل ومعناها في السياق اللي حواليها (الكلمة ممكن تتقال بمعنى لوحدها ومعنى تاني وسط الكلام، أو تكون مثل أو سخرية أو تلميح أو اسم)، وصحّح بس الجمل اللي فيها خطأ حقيقي: كلمة مترجمة بمعنى غلط، أو معنى متغيّر، أو اسم/مصطلح مش ثابت، أو ضمير/جنس غلط، أو جملة مش مفهومة.\n" +
                "- حافظ على لهجة ${conf.lang} وأسلوب ${conf.style} زي الترجمة الحالية. ماتغيّرش جملة سليمة ولا تحسّن أسلوب بس.\n" +
                "- ممنوع تضيف أو تحذف جمل، والتوقيت مش بتاعك.\n" +
                "رجّع JSON فقط: {\"corrections\":[{\"i\":12,\"translated\":\"النص المصحح الكامل للجملة\"}]} — الجمل السليمة ماتتحطش. لو كله سليم: {\"corrections\":[]}"
            val r = bgCall(prompt, 8192, 0.1, true, win)
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
            from += STEP; nap(3000)
        }
        host.log("✍ تنقيح الترجمة: راجعت ${snap.size} جملة بالأصل والسياق وصحّحت $fixedTotal")
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
        val spans = Speech.activeSpans(w.bytes)?.let { Speech.absolute(it, w.startSec) } ?: return
        if (!holeTried.add(i)) return
        val holes = Speech.holes(spans, subs, maxOf(rawStart, w.startSec), minOf(rawEnd, w.startSec + w.durSec), HOLE_MIN).take(HOLE_MAX)
        if (holes.isEmpty()) return
        host.log("🧩 المقطع ${i + 1}: ${holes.size} ثغرة فيها صوت من غير ترجمة — بعيد طلبها")
        var key = key0
        for (h in holes) {
            for (pt in Gaps.parts(maxOf(0.0, h[0] - 0.5), h[1] + 0.5, 20.0)) {
                if (!running) return
                val w2 = src().wav(pt[0], pt[1]) ?: continue
                if (w2.silent) continue
                var tries = 0; var got = false
                while (running && !got && tries++ < 3) {
                    try {
                        val r = Api.generate(conf.model, key, buildPrompt(w2.durSec, w2.startSec), w2.bytes)
                        val j = if (r.text.isBlank()) null else Parse.json(r.text)
                        var base = if (j == null) emptyList() else Subs.splitAll(Parse.subs(j, w2.startSec, w2.durSec)).map { Subs.capPace(it) }
                        if (base.any { LangGuard.foreign(it) }) base = fixForeign(base)
                        val sp2 = Speech.activeSpans(w2.bytes)?.let { Speech.absolute(it, w2.startSec) }
                        if (sp2 != null) base = base.mapNotNull { Speech.fit(it, sp2) }
                        base = base.filter { val m = (it.start + it.end) / 2; m >= h[0] - 0.5 && m <= h[1] + 0.5 }.map { it.copy(chunk = i) }
                        if (base.isEmpty()) { key = pool.pick(key, rr.getAndIncrement()) ?: key; continue }
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

    private fun fillGap(a: Double, b: Double, key0: String) {
        val w = src().wav(a, b) ?: return
        if (w.silent) { host.log("🔇 الفجوة ${a.toInt()}ث صامتة — اتخطّيت"); return }
        var key = key0; var tries = 0
        while (running && tries++ < 3) {
            try {
                val r = Api.generate(conf.model, key, buildPrompt(w.durSec, w.startSec), w.bytes)
                val j = if (r.text.isBlank()) null else Parse.json(r.text)
                var base = if (j == null) emptyList() else Subs.splitAll(Parse.subs(j, w.startSec, w.durSec)).map { Subs.capPace(it) }
                if (base.any { LangGuard.foreign(it) }) base = fixForeign(base)
                val marked = base.map { it.copy(translated = "«" + it.translated + "»", chunk = -2) }
                if (marked.isEmpty()) {
                    if (tries < 3) { gapKeys.remove(key); key = pool.gapKey(gapKeys, rr.getAndIncrement()) ?: key; gapKeys.add(key); continue }
                    host.log("… فجوة ${a.toInt()}ث: الموديل مرجّعش جمل (3 محاولات)"); return
                }
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
        if (store == null) return
        if (!persistPending.compareAndSet(false, true)) return
        try { persistEx.schedule({ persistPending.set(false); try { doPersist() } catch (_: Exception) {} }, 3, java.util.concurrent.TimeUnit.SECONDS) }
        catch (_: RejectedExecutionException) { persistPending.set(false); doPersist() }
    }
    private fun doPersist() {
        val st = store ?: return
        if (persistBlocked) return
        val saved = synchronized(lock) {
            val fr = failed.filter { it.value >= MAX_FAILS }.keys.sorted().map { doubleArrayOf(cStart(it), cStart(it + 1)) }
            Saved(conf.chunkSec, srcLang, detDone, subs, done.list(), fr, chars.toList(), gloss.toList(), synchronized(tplCache) { HashMap(tplCache) }, host.position(), autoCharsAttempts, HashMap(bounds), gapTried.list())
        }
        try { st.save(saved) } catch (e: Exception) { host.log("⚠ تعذر حفظ التقدم: " + (e.message ?: "").take(80)) }
    }
}
