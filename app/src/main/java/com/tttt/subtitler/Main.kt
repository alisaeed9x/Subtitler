@file:OptIn(UnstableApi::class)
package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational

/** اختيار ملف من أي مدير ملفات (MiXplorer وغيره): GET_CONTENT + OPEN_DOCUMENT كخيار إضافي في نفس الـ chooser */
fun filePicker(mime: String, title: String, persist: Boolean): Intent {
    val get = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = mime; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val open = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = mime
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (persist) Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION else 0))
    }
    return Intent.createChooser(get, title).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(open))
}

class MainActivity : Activity() {
    lateinit var link: EditText
    private var fromPlayer = false
    private var libUi: LibraryUi? = null
    private var mediaOps: MediaOps? = null
    private var queueUi: QueueUi? = null
    private var lockUi: LockUi? = null
    private var stoppedAt = 0L
    private var keyLoadersRef: List<() -> Unit> = emptyList()
    private var permDone: (() -> Unit)? = null
    private var scanFn: () -> Unit = {}
    private var libStarted = false
    private var curTab = 0                       // (v117) 0 = الفيديوهات · 1 = يوتيوب
    private var ytUi: YoutubeUi? = null
    private var navUi: BottomNav? = null
    private var musicUi: MusicUi? = null            // (v189) صفحة الموسيقى
    private var tasksUiRef: TasksUi? = null
    private var showTabFn: (Int) -> Unit = {}
    private var tasksDlgRefresh: (() -> Unit)? = null
    /** (v189) صفحة المهام في نافذة كاملة (من ⚙️ في المكتبة أو من الويدجت/الإشعار) */
    private fun showTasksDialog() {
        val tu = tasksUiRef ?: return
        val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(this, th)
        (tu.root.parent as? android.view.ViewGroup)?.removeView(tu.root)
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
        box.addView(ui.button("✕ إغلاق") { dlg.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(8), ui.dp(12), 0) })
        box.addView(tu.root, LinearLayout.LayoutParams(-1, 0, 1f))
        dlg.setContentView(box)
        tasksDlgRefresh = { tu.refresh() }
        dlg.setOnDismissListener { tasksDlgRefresh = null; (tu.root.parent as? android.view.ViewGroup)?.removeView(tu.root) }
        tu.refresh(); dlg.show(); dlg.window?.setLayout(-1, -1)
    }
    private var toolsUiM: ToolsUi? = null
    private var pendingTool = ""
    private var pendingSrc: ToolSrc? = null
    override fun onCreate(b: Bundle?) {
        fromPlayer = intent?.getBooleanExtra("from_player", false) == true
        if (fromPlayer) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(b)
        if (fromPlayer) requestedOrientation = if (intent?.getBooleanExtra("land", false) == true) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // الإعدادات من المشغّل: بنفس اتجاه المشغّل
        UiWatchdog.start()
        Cfg.init(this); CrashLog.install(this)
        try { NeuralEngine.auto(this) } catch (_: Throwable) {}   // (v181) تحميل المحرك العصبي في الخلفية أول مرة
        val th = Themes.byId(Cfg.str("theme", "mx"))
        val ui = Ui(this, th)
        applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(32), 0, ui.dp(110)); layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        val keys = ui.input("مفاتيح Gemini (مفتاح في كل سطر)", Cfg.allMainKeys().joinToString("\n"), 3)
        val backup = ui.input("مفاتيح احتياطية (مفتاح في كل سطر)", "", 2)
        val extra = ui.input("مفاتيح إضافية (بتتضاف للأساسية — مفتاح في كل سطر)", "", 2)
        val vkeys = ui.input("مفاتيح الوضع البصري فقط (مفتاح في كل سطر)", Cfg.str("viskeys"), 2)
        // مفاتيح: كل مفتاح في خانة لوحده + زرار ＋ لإضافة أي عدد (الـ EditText الأصلي بيفضل هو مصدر الحقيقة ومش ظاهر)
        val keyLoaders = ArrayList<() -> Unit>()
        keyLoadersRef = keyLoaders
        // تنبيه تكرار المفتاح: لو نفس المفتاح في أي مكان تاني (أساسي/احتياطي/إضافي) بيظهر فوقه رقم المفتاح ومكانه
        val dupSources = listOf("الأساسية" to keys, "الاحتياطية" to backup, "الإضافية" to extra)
        val dupRefs = ArrayList<() -> Unit>()
        fun refreshDups() { dupRefs.forEach { it() } }
        fun dupHits(k: String, selfLabel: String, selfOrd: Int): List<String> {
            val out = ArrayList<String>()
            for ((lbl, et) in dupSources) {
                et.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, v ->
                    if (v == k && !(lbl == selfLabel && i + 1 == selfOrd)) out.add("$lbl رقم ${i + 1}")
                }
            }
            return out
        }
        fun keyRows(title: String, src: EditText, label: String): LinearLayout {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            var loading = false
            var addRowL: (String) -> Unit = {}
            fun sync() {
                if (loading) return
                src.setText((0 until list.childCount).map { (((list.getChildAt(it) as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(0) as EditText).text.toString().trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            }
            fun refresh() {
                var ord = 0
                for (r in 0 until list.childCount) {
                    val w = list.getChildAt(r) as LinearLayout
                    val warn = w.getChildAt(0) as TextView
                    val k = ((w.getChildAt(1) as LinearLayout).getChildAt(0) as EditText).text.toString().trim()
                    if (k.isNotEmpty()) ord++
                    val hits = if (k.length > 10) dupHits(k, label, ord) else emptyList()
                    if (hits.isEmpty()) warn.visibility = View.GONE
                    else { warn.text = "⚠ المفتاح ده مستخدم قبل كده: " + hits.joinToString(" ، "); warn.visibility = View.VISIBLE }
                }
            }
            dupRefs.add { refresh() }
            fun addRow(v: String) {
                val et = EditText(this).apply {
                    hint = "مفتاح Gemini API"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 13f; setText(v)
                    setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), 0, ui.dp(12), 0); background = ui.box(th.surface, th.border, 10)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(e: android.text.Editable?) { sync(); refreshDups() }
                        override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                        override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                    })
                }
                val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                val del = IconTextView(this).apply { text = "✕"; textSize = 16f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(12), 0, ui.dp(4), 0)
                    setOnClickListener { list.removeView(wrap); if (list.childCount == 0) addRowL(""); sync(); refreshDups() } }
                row.addView(et, LinearLayout.LayoutParams(0, ui.dp(44), 1f)); row.addView(del)
                val warn = IconTextView(this).apply { textSize = 12f; setTextColor(th.danger); visibility = View.GONE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(2)) }
                wrap.addView(warn); wrap.addView(row)
                list.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
            }
            addRowL = { v -> addRow(v) }
            fun load() {
                loading = true; list.removeAllViews()
                src.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { addRow(it) }
                if (list.childCount == 0) addRow("")
                loading = false
                refreshDups()
            }
            keyLoaders.add { load() }
            box.addView(ui.text(title, 13f, th.muted, true).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
            box.addView(list)
            box.addView(IconTextView(this).apply {
                text = "＋  إضافة مفتاح"; textSize = 14f; setTextColor(th.text); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = ui.box(th.surface, th.border, 12)
                setOnClickListener { addRow(""); ((list.getChildAt(list.childCount - 1) as LinearLayout).getChildAt(1) as LinearLayout).getChildAt(0).requestFocus() }
            }, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(6) })
            load()
            return box
        }
        val keysUi = keyRows("🔑 مفاتيح Gemini الأساسية", keys, "الأساسية")
        val backupUi = keyRows("🛟 مفاتيح احتياطية", backup, "الاحتياطية")
        val extraUi = keyRows("➕ مفاتيح إضافية (بتتضاف للأساسية)", extra, "الإضافية")
        val visKeysUi = keyRows("👁 مفتاح الوضع البصري", vkeys, "البصري")
        var modelSel = Cfg.str("model", Models.DEFAULT)
        val model = ui.input("الموديل (اكتب يدوي أو اختار من فوق)", modelSel)
        val modelNames = Models.builtin.map { it.id }
        val modelDesc = ui.text("", 12f, th.muted)
        fun descOf(id: String) = (Models.builtin.firstOrNull { it.id == id }?.desc ?: "موديل يدوي") + " — استهلاك النهارده: " + Quota.used(id) + " / " + Models.quotaOf(id) + " (تقريبي، بيتصفّر 00:00 PT)" +
            ModelWatch.pending().let { if (it.isEmpty()) "" else "\n🆕 موديل flash-lite أحدث اتلقى: " + it.joinToString("، ") + " — دوس «جلب كل الموديلات» واختاره" }
        modelDesc.text = descOf(modelSel)
        lateinit var applyModel: (String) -> Unit
        val modelChips = ui.chips(modelNames, { model.text.toString().trim() }) { applyModel(it) }
        val fetchModelsBtn = ui.button("🔄 جلب كل الموديلات من جوجل واختيار واحد") {
            val k = (keys.text.toString() + "\n" + extra.text.toString() + "\n" + backup.text.toString()).lines().map { it.trim() }.firstOrNull { it.length > 10 }
            if (k == null) { Notice.show(this, ("ضيف مفتاح API الأول").toString(), 2300L); return@button }
            Notice.show(this, ("⏳ بجيب الموديلات…").toString(), 2300L)
            Thread {
                try {
                    val rows = Api.listModels(k)
                    // الأحدث/الأهم فوق: gemini أولًا
                    val fresh = ModelWatch.candidates(rows.map { it.id }).toSet()
                    val sorted = rows.sortedWith(compareBy({ it.id !in fresh }, { !it.id.startsWith("gemini") }, { it.id }))
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (sorted.isEmpty()) { Notice.show(this, ("مفيش موديلات رجعت").toString(), 3600L); return@runOnUiThread }
                        val cur = model.text.toString().trim()
                        val labels = sorted.map { (if (it.id == cur) "✓ " else if (it.id in fresh) "🆕 " else "") + it.id + (if (it.display.isNotEmpty() && it.display != it.id) "\n" + it.display else "") }.toTypedArray()
                        GAlert(this).setTitle("اختار الموديل (${sorted.size})")
                            .setItems(labels) { _, which -> applyModel(sorted[which].id) }
                            .setNegativeButton("إلغاء", null).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Notice.show(this, ("⚠ فشل جلب الموديلات: " + (e.message ?: "").take(120)).toString(), 3600L) }
                }
            }.start()
        }
        var chunkSec = Cfg.int("chunk", 60).coerceIn(10, 600)
        // (v184) طول المقطع بقى أزرار ثابتة (30 / 60 / 90 / 120) بدل السلايدر — قيمة قديمة مش في الأزرار بتظهر على أقرب زرار ومابتتغيّرش إلا لو دوست
        val chunkOpts = listOf(30, 60, 90, 120)
        fun chunkLbl(v: Int) = "$v ثانية"
        val chunk = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text("طول المقطع", 14f, th.text, true))
            addView(ui.chips(chunkOpts.map { chunkLbl(it) }, { chunkLbl(chunkOpts.minByOrNull { Math.abs(it - chunkSec) }!!) }) { pick -> chunkSec = chunkOpts.first { chunkLbl(it) == pick } })
        }
        val ahead = ui.input("عدد المقاطع اللي بتترجم قدّام مكان التشغيل", Cfg.str("ahead", "3"))
        val hlsAhead = ui.input("روابط m3u8: عدد الباتشات اللي بتتحمّل مقدّمًا قدّام الترجمة (الباقي مابيتحمّلش لحد ما توصله)", Cfg.str("hls_ahead", "3"))
        val atrack = ui.input("رقم مسار الصوت (لو الفيديو فيه أكتر من لغة)", Cfg.str("atrack", "1"))
        val roster = ui.input("جدول الشخصيات: اسم:male أو female:وصف (سطر لكل شخصية). لو فاضي والتحليل التلقائي شغال هيتعبّى لوحده", Cfg.str("roster"), 3)
        val gloss = ui.input("مسرد مصطلحات ثابت (كل سطر: الكلمة = ترجمتها)", Cfg.str("gloss"), 3)
        val flags = linkedMapOf("vad" to false, "cross" to true, "autochars" to true, "autopron" to true, "autotpl" to true, "strim" to true, "gapfill" to true, "hitiming" to false, "autosrt" to true, "soundtags" to true, "speedtest" to true, "prefine" to true, "verify" to false, "talign" to true)
        val flagText = mapOf("vad" to "تخطي المقاطع الصامتة (فلتر الصمت)", "cross" to "مراجعة بين المقاطع (للفيديوهات أطول من 10 دقايق)",
            "autochars" to "تحليل الشخصيات تلقائيًا", "autopron" to "تصحيح الضمائر تلقائيًا", "autotpl" to "ترجمة قالب الـ prompt للغات اللي ملهاش قالب جاهز",
            "strim" to "تقصير حدود المقطع لأقرب لحظة صمت (بيقلل الجمل المقطوعة بين مقطعين)",
            "gapfill" to "سدّ الفجوات تلقائيًا أثناء المشاهدة (بمفاتيح المراقبين/الاحتياطي، والجمل المستردة بين «»)",
            "autosrt" to "حفظ ملف SRT جنب الفيديو تلقائي لما الترجمة تخلص (محتاج «إدارة كل الملفات»)",
            "speedtest" to "اختبار سرعة الموديلات: أول باتش يتبعت على كل المفاتيح (الموديل المختار وflash-lite-latest) والأسرع يتثبّت للباقي — بيستهلك كام طلب زيادة مرة واحدة كل 3 ساعات",
            "verify" to "🔁 وضع التأكيد: كل باتش يتبعت لمفتاحين بالتوازي (مفتاح أساسي + مفتاح تاني) والنتيجتين بتتدمج: الجملة اللي في الاتنين بتتاخد مرة واحدة، واللي في واحدة بس بتتضاف — فمفيش كلام ينضاع لو نسخة فاتها. بيستهلك ضعف الطلبات (كوتة أسرع) ومحتاج مفتاحين شغالين على الأقل",
            "prefine" to "تنقيح جزئي أثناء الترجمة: كل مقطع يخلص، الجمل الجديدة (مع 25 جملة قبلها كسياق) بتتبعت بنصها الأصلي وترجمتها للتنقيح وتتصحّح الترجمة الحرفية — على مفاتيح تنقيح مخصوصة (البرنامج بيخصّصها لوحده من قايمة المفاتيح، ومفتاح الصور احتياطي لها) من غير ما ياخد من مفاتيح الترجمة؛ وفي الآخر بيحصل تنقيح كامل واحد",
            "talign" to "🎯 مواءمة التوقيت بالصوت: لو توقيت جمل المقطع متزحلق عن الكلام الفعلي (الجملة الظاهرة سابقة أو متأخرة) بيتظبط من الصوت نفسه — مابيلمسش المقطع السليم",
            "hitiming" to "دقة توقيت أعلى (بيفك الصوت من قبل البداية بـ 3 ثواني — أبطأ شوية)",
            "soundtags" to "التقاط الأصوات الخلفية والهمهمات والموسيقى وعرضها كسطر وصف فوق الفيديو (بيعطّل تخطي المقاطع الصامتة)")
        val flagViews = flags.map { (k, d) -> ui.switchRow(flagText[k]!!, Cfg.bool(k, d)) { } }
        val modes = KeyModes.parse(Cfg.str("keymodes")).toMutableList()
        val modesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun keyList(t: String) = t.lines().map { it.trim() }.filter { it.length > 10 }
        fun rebuildModes() {
            modesBox.removeAllViews()
            val ks = (keyList(keys.text.toString()) + keyList(extra.text.toString())).distinct()
            if (ks.isEmpty()) { modesBox.addView(ui.text("أوضاع المفاتيح بتظهر هنا بعد ما تكتب مفاتيح", 12f, th.muted)); return }
            ks.forEachIndexed { i, k ->
                val bk = KeyModes.modeOf(modes, i) == "backup"
                modesBox.addView(ui.text("…" + k.takeLast(5) + "  —  " + (if (bk) "🔁 احتياطي" else "🔀 أساسي (متوازي)") + "   (اضغط للتبديل)", 13f, if (bk) th.primary else th.text).apply {
                    setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) }
                    setOnClickListener { while (modes.size <= i) modes.add("both"); modes[i] = KeyModes.next(modes[i]); rebuildModes() }
                })
            }
        }
        rebuildModes()
        val modesBtn = ui.button("↻ حدّث قايمة أوضاع المفاتيح") { rebuildModes() }
        // ===== (v149) اختبار سرعة رد الـ AI من الإعدادات — زي لوج الباتشات في المشغّل: كل مفتاح × كل موديل + زمن الرد والأسرع =====
        class SpRes(val keyNo: Int, val model: String, val ms: Long, val ok: Boolean, val note: String)
        val speedOut = ui.text("", 12f, th.text).apply {
            typeface = android.graphics.Typeface.MONOSPACE; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.surface, th.border, 8); visibility = View.GONE
        }
        var speedWinner: String? = null
        var speedRunning = false
        val speedUseBtn = ui.button("🏁 استخدم الموديل الأسرع") {
            val w = speedWinner
            if (w != null) { applyModel(w); Notice.show(this, ("🏁 الموديل اتغيّر لـ " + w.removePrefix("gemini-")).toString(), 2600L) }
        }.apply { visibility = View.GONE }
        val speedBtn = ui.button("🏎 اختبر سرعة رد الـ AI (كل مفتاح × كل موديل)") {
            if (speedRunning) { Notice.show(this, ("الاختبار شغّال لسه…").toString(), 1800L); return@button }
            val ks = (keyList(keys.text.toString()) + keyList(extra.text.toString()) + keyList(backup.text.toString())).distinct().take(6)
            if (ks.isEmpty()) { Notice.show(this, ("ضيف مفتاح API الأول").toString(), 2300L); return@button }
            // (v149) الموديل اللي مختاره + flash-lite-latest، وكل المفاتيح على الاتنين
            val ms = listOf(model.text.toString().trim().ifEmpty { Models.DEFAULT }, "gemini-flash-lite-latest").distinct()
            val total = ks.size * ms.size
            val res = java.util.ArrayList<SpRes>()
            val left = java.util.concurrent.atomic.AtomicInteger(total)
            speedRunning = true; speedWinner = null
            speedUseBtn.visibility = View.GONE; speedOut.visibility = View.VISIBLE
            fun sec(t: Long) = String.format(java.util.Locale.US, "%.1f", t / 1000.0) + "ث"
            fun render(done: Boolean) {
                val rs = synchronized(res) { res.toList() }
                val okRs = rs.filter { it.ok }.sortedBy { it.ms }
                val sb = android.text.SpannableStringBuilder()
                if (!done) sb.append("⏳ شغّال… ").append(rs.size.toString()).append(" / ").append(total.toString())
                okRs.forEachIndexed { i, r ->
                    if (sb.isNotEmpty()) sb.append('\n')
                    val a = sb.length
                    sb.append("🔑").append(r.keyNo.toString()).append(" · ").append(r.model.removePrefix("gemini-")).append(": ").append(sec(r.ms))
                    if (i == 0) { sb.append(" 🏆⚡"); sb.setSpan(android.text.style.ForegroundColorSpan(0xFFF5B041.toInt()), a, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                for (r in rs.filter { !it.ok }.sortedWith(compareBy({ it.model }, { it.keyNo }))) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("🔑").append(r.keyNo.toString()).append(" · ").append(r.model.removePrefix("gemini-")).append(": ❌ ").append(r.note)
                }
                if (done && okRs.isNotEmpty()) {
                    sb.append("\n\n📊 متوسط كل موديل:")
                    okRs.groupBy { it.model }.entries.sortedBy { e -> e.value.map { it.ms }.average() }.forEach { e ->
                        sb.append("\n  ").append(e.key.removePrefix("gemini-")).append(": ").append(sec(e.value.map { it.ms }.average().toLong())).append(" (").append(e.value.size.toString()).append(" مفتاح)")
                    }
                }
                if (done && okRs.isEmpty()) sb.append("\n\nمفيش رد سليم من أي مفتاح — راجع المفاتيح أو النت")
                speedOut.text = sb
            }
            render(false)
            for (mi in ms.indices) for (ki in ks.indices) {
                val md = ms[mi]; val key = ks[ki]
                Thread {
                    val t0 = System.currentTimeMillis()
                    val r = try {
                        val o = Api.generate(md, key, "رد بكلمة واحدة بس: تمام", null, 200, 0.0, false)
                        val took = System.currentTimeMillis() - t0
                        if (o.text.isBlank()) SpRes(ki + 1, md, took, false, "رد فاضي " + o.finish) else SpRes(ki + 1, md, took, true, "")
                    } catch (e: Throwable) { SpRes(ki + 1, md, 0L, false, (e.message ?: e.toString()).take(60)) }
                    synchronized(res) { res.add(r) }
                    val fin = left.decrementAndGet() == 0
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (fin) {
                            speedRunning = false
                            val w = synchronized(res) { res.filter { it.ok }.minByOrNull { it.ms } }
                            speedWinner = w?.model
                            if (w != null) speedUseBtn.visibility = View.VISIBLE
                        }
                        render(fin)
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        val langs = listOf("مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى")
        val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
        var lang = Cfg.str("lang", "فصحى"); var style = Cfg.str("style", "حرفي"); var themeId = th.id
        link = ui.input("رابط فيديو مباشر (mp4 / m3u8)", "")
        fun save() {
            val e = Cfg.p.edit().putString("keys", keys.text.toString()).putString("backup", backup.text.toString())
                .putString("model", model.text.toString().trim()).putInt("chunk", chunkSec).putString("extra", extra.text.toString())
                .putString("roster", roster.text.toString()).putString("gloss", gloss.text.toString())
                .putString("lang", lang).putString("style", style).putString("theme", themeId)
                .putString("keymodes", KeyModes.toJson(modes)).putString("viskeys", vkeys.text.toString())
            ahead.text.toString().trim().toIntOrNull()?.let { e.putInt("ahead", it) }
            atrack.text.toString().trim().toIntOrNull()?.let { e.putInt("atrack", it) }
            hlsAhead.text.toString().trim().toIntOrNull()?.let { e.putInt("hls_ahead", it.coerceIn(1, 8)) }
            flags.keys.forEachIndexed { i, k -> e.putBoolean(k, (flagViews[i] as Switch).isChecked) }
            e.apply()
        }
        val themeChips = ui.chips(Themes.all.map { it.name }, { Themes.byId(themeId).name }) { n ->
            themeId = Themes.all.first { it.name == n }.id; save()
            Cfg.p.edit().putString("theme", themeId).commit()
            if (!fromPlayer) intent?.putExtra("reopen_tab", "theme")
            recreate()
        }
        // ===== الواجهة بشكل نسخة الـ HTML: دوائر علوية + كارت فيديو + كبسولة + شبكة أزرار + زرار الترجمة =====
        var recentDlg: android.app.Dialog? = null
        val recentFile = File(filesDir, "recent.json")
        val recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun uriOk(u: String): Boolean = try { contentResolver.openInputStream(Uri.parse(u))?.close(); true } catch (_: Exception) { false }
        fun rebuildRecents() {
            recentBox.removeAllViews()
            val recents = Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" })
            if (recents.isEmpty()) { recentBox.addView(ui.text("مفيش فيديوهات محفوظة لسه", 13f, th.muted)); return }
            recents.forEach { r ->
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                info.addView(ui.text(r.title, 14f, th.text, true))
                info.addView(ui.text("جمل ${r.subs} · تغطية ${r.percent}% · وقف عند ${PlayerLogic.clock((r.posSec * 1000).toLong())}", 11f, th.muted))
                val del = IconTextView(this).apply {
                    text = "🗑"; textSize = 18f; gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                    setOnClickListener {
                        try { recentFile.writeText(Recents.toJson(Recents.remove(Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }), r.id))) } catch (e: Exception) { LogStore.err("Main:264", e) }
                        rebuildRecents()
                    }
                }
                val row = LinearLayout(this).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(10), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.surface, th.border, 8)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) }
                    addView(info, LinearLayout.LayoutParams(0, -2, 1f)); addView(del)
                    setOnClickListener {
                        save()
                        if (r.uri.isNotEmpty()) {
                            if (uriOk(r.uri)) { recentDlg?.dismiss(); play("", Uri.parse(r.uri)) }
                            else Notice.show(this@MainActivity, ("الملف ده مبقاش متاح (الصلاحية اتفقدت) — افتحه تاني من \"فتح فيديو\"").toString(), 3600L)
                        } else if (r.url.isNotEmpty()) { recentDlg?.dismiss(); play(r.url, null) }
                    }
                }
                recentBox.addView(row)
            }
        }
        val keyChipTv = ui.text("", 13f, th.text, true)
        val keyTailTv = ui.text("", 12f, th.muted)
        val keyPctTv = ui.text("", 13f, th.primary, true)
        val modelChipTv = ui.pillChip("") { }
        fun refreshChip() {
            val ks = (keyList(keys.text.toString()) + keyList(extra.text.toString())).distinct()
            keyChipTv.text = (if (ks.isEmpty()) "✗ " else "✓ ") + "🔀 " + ks.size
            keyTailTv.text = if (ks.isEmpty()) "مفيش مفتاح" else "AQ.A…" + ks[0].takeLast(5)
            val mid = model.text.toString().trim()
            val q = Models.quotaOf(mid).coerceAtLeast(1)
            keyPctTv.text = (Quota.used(mid) * 100 / q).toString() + "%"
            modelChipTv.text = "▾ " + mid.removePrefix("gemini-") + " ●"
        }
        applyModel = { id ->
            model.setText(id); modelSel = id; modelDesc.text = descOf(id)
            Cfg.p.edit().putString("model", id).apply()   // يتحفظ فورًا
            ModelWatch.refreshPending(); modelDesc.text = descOf(id)
            refreshChip()
        }
        val sp = styleParts(ui, th)
        var settingsDlg0: TabbedDialog? = null; var qui0: QueueUi? = null
        val lk = LockUi(this, ui, th); lockUi = lk
        val lockStatus = ui.text("", 13f, th.text)
        lateinit var bioBtn: android.widget.Button
        fun refreshLock() {
            lockStatus.text = if (lk.isSet) "🔒 القفل شغّال (نمط" + (if (lk.bioOn) " + بصمة" else "") + ")" else "🔓 مفيش قفل لسه — بيتعمل أول مرة تفتح المخفي"
            bioBtn.text = if (!lk.bioAvailable) "👆 البصمة: مش متاحة على جهازك" else if (lk.bioOn) "👆 فتح بالبصمة: مفعّل (اضغط للإيقاف)" else "👆 فتح بالبصمة: مقفول (اضغط للتفعيل)"
            bioBtn.alpha = if (lk.isSet && lk.bioAvailable) 1f else 0.5f
        }
        bioBtn = ui.button("") {
            if (!lk.isSet) Notice.show(this, ("اعمل نمط الأول").toString(), 2300L)
            else if (lk.bioOn) { lk.disableBio(); refreshLock() } else lk.enableBio { refreshLock() }
        }
        refreshLock()
        fun fl(vararg k: String): Array<View> = k.map { key -> flagViews[flags.keys.indexOf(key)] }.toTypedArray()
        // (v167) بريسيت جودة الصوت المُرسَل للـ AI: سريع (AAC 32kbps) / نورمال (WAV خام)
        val audioPresetTitle = ui.text("جودة الصوت المُرسَل للـ AI", 13f, th.muted)
        val audioPresetDesc = ui.text("", 12f, th.muted)
        val apFast = "⚡ سريع"; val apNormal = "🎚 نورمال"
        fun apDesc() { audioPresetDesc.text = if (AudioEnc.preset() == AudioEnc.NORMAL) "WAV خام 16kHz من غير ضغط (≈32KB في الثانية) — أعلى دقة، لكن الرفع أبطأ (حوالي 8 أضعاف الحجم)." else "ضغط AAC بمعدل 32kbps (≈4KB في الثانية) — أصغر حوالي 8 مرات، والرفع أسرع. مناسب لمعظم الأفلام." }
        val audioPresetChips = ui.chips(listOf(apFast, apNormal), { if (AudioEnc.preset() == AudioEnc.NORMAL) apNormal else apFast }) {
            Cfg.put(AudioEnc.PRESET_KEY, if (it == apNormal) AudioEnc.NORMAL else AudioEnc.FAST); apDesc()
        }
        apDesc()
        val vmLight = "🪶 خفيفة فقط"; val vmNeural = "🧠 عصبي فقط"; val vmCombo = "🤝 الاتنين معًا"
        val voiceModeChips = ui.chips(listOf(vmLight, vmNeural, vmCombo), { when (VoiceNet.mode()) { "light" -> vmLight; "neural" -> vmNeural; else -> vmCombo } }) {
            Cfg.p.edit().putString("voice_mode", when (it) { vmLight -> "light"; vmNeural -> "neural"; else -> "combo" }).apply()
        }
        val vf1 = "1 فريم/ث"; val vf2 = "2 فريم/ث"; val vf3 = "3 فريم/ث"
        val visFpsChips = ui.chips(listOf(vf1, vf2, vf3), { when (VisualMode.fps()) { 1 -> vf1; 3 -> vf3; else -> vf2 } }) {
            Cfg.put("vis_fps", when (it) { vf1 -> "1"; vf3 -> "3"; else -> "2" })
        }
        val vsClip = "🎬 مقاطع فيديو (10ث)"; val vsFrames = "🖼 فريمات"
        val visSrcChips = ui.chips(listOf(vsClip, vsFrames), { if (VisualMode.useClip()) vsClip else vsFrames }) { Cfg.put("vis_src", if (it == vsFrames) "frames" else "clip") }
        val vaOn = "⚡ تلقائي مع كل فيديو"; val vaOff = "✋ يدوي (من زرار 👁)"
        val visAutoChips = ui.chips(listOf(vaOn, vaOff), { if (VisualMode.auto()) vaOn else vaOff }) { Cfg.put("vis_auto", if (it == vaOn) "1" else "0") }
        val neuralStatus = ui.text(NeuralEngine.status(this), 12f, th.muted)
        val actNeural = this
        fun neuralTick() { neuralStatus.text = NeuralEngine.status(actNeural); if (neuralStatus.isAttachedToWindow) neuralStatus.postDelayed({ neuralTick() }, 1500L) }
        neuralStatus.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { neuralTick() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        val neuralDl = ui.button("⬇ تحميل المحرك العصبي (26MB)") {
            NeuralEngine.manual(actNeural); neuralStatus.text = NeuralEngine.status(actNeural)
            try { Notice.show(actNeural, "⬇ بيتحمّل — تابعه من ⚙️ ← ⏳ المهام", 2600L) } catch (_: Throwable) {}
        }
        val neuralDel = ui.button("🗑 حذف المحرك العصبي (يرجّع 26MB)") {
            NeuralEngine.delete(actNeural); neuralStatus.text = NeuralEngine.status(actNeural)
        }
        val settingsDlg = TabbedDialog(this, ui, "⚙️ الإعدادات", listOf(
            // (v187) الإعدادات اتجمّعت حسب الموضوع: الترجمة · شكل الترجمة · البصري · الصوت · الشخصيات · المفاتيح · أخرى
            TabDef("general", "🌐 الترجمة", listOf<View>(
                                ui.section("اللهجة", true, ui.chips(langs, { lang }) { lang = it; save() }),
                ui.section("أسلوب الترجمة", true, ui.chips(styles, { style }) { style = it; save() }),
                                ui.section("🤖 الموديل", true, modelChips, fetchModelsBtn, model, modelDesc),
                                ui.section("🏎 اختبار سرعة رد الـ AI", false,
                    ui.text("بيبعت طلب صغير على كل مفتاح بالموديل المختار وflash-lite-latest ويوريك زمن الرد والأسرع (🏆). بيستهلك طلبات قليلة.", 12f, th.muted), speedBtn, speedOut, speedUseBtn),
                                ui.section("⏱ الأداء والتقطيع", false, chunk, ahead, hlsAhead, atrack),
                                ui.section("🧠 الذكاء التلقائي والمراجعة", false, *fl("autochars", "autopron", "autotpl", "cross", "gapfill", "prefine", "speedtest", "verify")),
                ui.section("💾 الحفظ", false, *fl("autosrt"))), false, "اللهجة · الأسلوب · الموديل · السرعة · الأداء · التصحيح التلقائي · الحفظ"),
            TabDef("look", "🎬 شكل الترجمة", listOf<View>(
                ui.section("🔤 الخطوط", true, *sp.fonts.toTypedArray()),
                ui.section("✨ الأنيميشن", false, *sp.anim.toTypedArray()),
                ui.section("🎬 العرض والألوان", false, *sp.look.toTypedArray())), true, "الخطوط · الأنيميشن · وضع العرض · الألوان · الخلفية"),
            TabDef("visual", "👁 الوضع البصري", listOf<View>(
                                ui.section("👁 محرك الوضع البصري", false, visAutoChips, visSrcChips, visFpsChips,
                    ui.text("الوضع البصري بيعتمد على Gemini فقط (مفتاح «الوضع البصري فقط» تحت). «مقاطع فيديو» = بيبعت مقطع 10 ثواني وجيميناي بيحدد مكان كل نص ووقته وحركته (الأدق). «فريمات» = بيبعت صور متتالية (أخف على الأجهزة والمصادر اللي مابتتقصّش). الكثافة بتتحكم في عدد الفريمات في الثانية اللي جيميناي بياخدها. النتايج بتتحفظ مع الفيديو. بين كل نافذة والتانية راحة تلقائية لو جيميناي رفض (429). بيتطبق من أول تشغيل جديد.", 12f, th.muted)),
                                ui.section("👁 مفتاح الوضع البصري فقط", false,
                    ui.text("الوضع البصري (👁) بيشتغل بالمفاتيح اللي هنا بس، ومش بياخد أبدًا من مفاتيح الترجمة. لو سبتها فاضية الوضع البصري مش هيشتغل. ولما الوضع البصري مايكونش شغّال المفتاح ده بيشتغل كمفتاح احتياطي للترجمة والتنقيح.", 12f, th.muted), visKeysUi)), false, "تلقائي/يدوي · مقاطع أو فريمات · الكثافة · مفتاح الوضع البصري"),
            TabDef("audio", "🔊 الصوت والبصمة", listOf<View>(
                                ui.section("🔊 الصوت والتوقيت", false, audioPresetTitle, audioPresetChips, audioPresetDesc, *fl("soundtags", "vad", "strim", "hitiming", "talign")),
                                ui.section("🎙 محرك بصمة الصوت", false, voiceModeChips, neuralStatus, neuralDl, neuralDel,
                    ui.text("خفيفة: معادلات بسيطة (طبقة + ألوان الصوت) · عصبي: موديل WeSpeaker لوحده · الاتنين معًا: متوسط المقياسين. التغيير بيسري على الفيديو الجاي (البصمات القديمة المحفوظة بتفضل زي ما هي).", 12f, th.muted))), false, "جودة الصوت · كشف الكلام · التوقيت · بصمة المتكلم"),
            TabDef("chars", "🧑 الشخصيات", listOf<View>(ui.charactersEditor(this, roster, gloss)), false, "جدول الشخصيات والمسرد"),
            TabDef("keys", "🔑 المفاتيح", listOf<View>(
                ui.button("❓ إزاي أجيب مفتاح Gemini؟ (وألصقه)", true) {
                    showKeyGuide { k ->
                        val cur = keys.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val hit = dupHits(k, "", -1)
                        if (hit.isNotEmpty()) Notice.show(this, ("⚠ المفتاح ده مستخدم قبل كده: " + hit.joinToString(" ، ")).toString(), 3600L)
                        else { keys.setText((cur + k).joinToString("\n")); keyLoaders.forEach { it() } }
                        Cfg.p.edit().putString("keys", keys.text.toString()).apply()
                    }
                },
                ui.button("📊 إحصائية الاستهلاك والكوتة") { StatsUi(this, ui, th).show() },
                                ui.section("🔑 كل المفاتيح", true,
                    ui.text("حط كل مفاتيحك هنا في قايمة واحدة — البرنامج بيوزّعها لوحده: مفاتيح للترجمة (أغلبها)، ومفتاح أو اتنين للتنقيح الجزئي أثناء الترجمة، ومفتاح احتياطي لو مفتاح اتعطّل (من 6 مفاتيح). التنقيح الجزئي بيبدأ من 3 مفاتيح.", 12f, th.muted), keysUi)), false, "مفاتيح Gemini (توزيع تلقائي) · الاستهلاك والكوتة · طريقة جلب مفتاح"),
            TabDef("more", "🛠 الخلفية · الأمان · المظهر", listOf<View>(
                                ui.section("▶ التشغيل", true,
                    ui.switchRow("كمّل الطابور تلقائيًا بعد إعادة تشغيل الجهاز", Cfg.bool("bg_autostart", true)) { Cfg.put("bg_autostart", if (it) "1" else "0") },
                    ui.text("الطابور بيتحفظ، وبيكمّل حتى لو قفلت البرنامج أو مسحته من الأخيرة. الفيديو اللي فوق في «مجلد الترجمة في الخلفية» بيترجم الأول.", 12f, th.muted),
                    ui.button("📋 افتح مجلد الترجمة في الخلفية") { settingsDlg0?.dialog?.dismiss(); qui0?.show() }),
                                ui.section("🔋 عشان الجهاز ما يقتلش الخدمة", true,
                    ui.text("أجهزة شاومي / أوبو / فيفو / سامسونج / هواوي بتقتل الخدمات الخلفية. اعمل الخطوتين دول مرة واحدة:", 12f, th.muted),
                    ui.button("1) استثناء من توفير البطارية") { requestBatteryExemption() },
                    ui.button("2) السماح بالتشغيل التلقائي / الخلفية للتطبيق") { openAutoStartSettings() },
                    ui.text("وكمان: في قايمة التطبيقات الأخيرة اعمل «قفل 🔒» للتطبيق لو جهازك فيه الخيار ده.", 12f, th.muted)),
                            ui.section("🔒 الأمان", false, lockStatus,
                ui.text("القفل بيحمي المجلد المخفي. لفتحه: اسحب لتحت في قايمة الفيديوهات وكمّل السحب لحد 🔒 وسيب.", 12f, th.muted),
                ui.button("🔑 تعيين / تغيير النمط") { lk.change { refreshLock() } }, bioBtn,
                ui.button("🗑 إزالة القفل") { lk.remove { refreshLock() } },
                ui.text("البصمة بتتحقق من بصمات جهازك المسجّلة في إعدادات الأندرويد (التطبيق مابيخزّنش بصمتك). لو نسيت النمط: «نسيت النمط؟» بيطلب قفل شاشة الجهاز.", 12f, th.muted)),
                ui.section("🎨 المظهر", false, themeChips)), false, "الترجمة في الخلفية · استثناء البطارية · قفل المجلد · ثيم البرنامج"),
            TabDef("credits", "🙏 شكر وتقدير", listOf<View>(
                ui.text("موديل بصمة الصوت (التعرف على المتكلم) المستخدم في التطبيق:", 13f, th.text, true),
                ui.text("WeSpeaker · voxceleb_resnet34_LM (ResNet34 متدرّب على VoxCeleb) — من مشروع WeSpeaker مفتوح المصدر. الشكر لفريق WeSpeaker ولمنشور الموديل على Hugging Face. رخصة الموديل وشروطه حسب صفحته الأصلية.", 12f, th.muted),
                ui.button("🔗 صفحة الموديل") { try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://huggingface.co/Wespeaker/wespeaker-voxceleb-resnet34-LM"))) } catch (_: Exception) {} },
                ui.text("تشغيل الموديل على الجهاز: ONNX Runtime (Microsoft).", 12f, th.muted)), false, "مصادر الموديلات والمكتبات المستخدمة")
        ), sp.holder) { save(); refreshChip(); if (fromPlayer) finish() }
        // رابط مباشر + المحفوظة (نافذة سفلية): حقل الرابط بيتحط هنا
        link.hint = "الصق أي لينك: فيديو مباشر / يوتيوب / صفحة فيها فيديو"; link.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val linkPlay = ui.button("▶ شغّل / اصطد الرابط", true) {
            save(); val u = link.text.toString().trim()
            if (u.isEmpty()) Notice.show(this, ("الصق الرابط الأول").toString(), 2300L) else { recentDlg?.dismiss(); openLink(u) }
        }
        val linkBrowser = ui.button("🌐 افتح المتصفح (لصيد روابط الفيديو)") { save(); recentDlg?.dismiss(); startActivity(Intent(this, BrowserActivity::class.java)) }
        recentDlg = ui.sheet(this, "🔗 رابط · 📼 المحفوظة", listOf<View>(link, linkPlay, linkBrowser,
            ui.text("📼 فيديوهات محفوظة", 13f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, recentBox), false)
        recentDlg!!.setOnShowListener {
            rebuildRecents()
            // لو في الكليبورد لينك: حطه في الخانة لوحده
            try {
                val t = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (link.text.isNullOrBlank()) Regex("https?://\\S+").find(t)?.let { link.setText(it.value) }
            } catch (e: Exception) { LogStore.err("Main:382", e) }
        }
        refreshChip()

        // ===== الشاشة الرئيسية: متصفح فيديوهات الجهاز (فولدرات ← فيديوهات بصور مصغّرة) زي MX Player =====
        val lib = LibraryUi(this, ui, th, { Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).associateBy { it.id } }) { v ->
            save(); playChecked(v)
        }
        libUi = lib
        // ===== ترجمة في الخلفية: من ⋮ على الفيديو/الفولدر =====
        fun startBg(vs: List<VideoItem>) {
            ensureKeys {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12) } catch (e: Exception) { LogStore.err("Main:395", e) }
                }
                var n = 0
                vs.forEach { v -> if (BgJobs.enqueue(this, BgJob(v.videoId, v.title, v.uri, null, emptyMap()), "المكتبة (ترجمة بالخلفية يدوي)")) n++ }
                Notice.show(this, (if (n > 0) "🌙 بدأت الترجمة في الخلفية ($n) — التقدم في الإشعارات" else "الفيديو ده بيترجم في الخلفية بالفعل").toString(), 3600L)
                libUi?.refreshRows()
            }
        }
        lib.onBg = { v -> startBg(listOf(v)) }
        lib.onBgStop = { v -> BgJobs.stop(v.videoId); Notice.show(this, ("⏹ وقفت الترجمة في الخلفية (التقدم اتحفظ)").toString(), 2300L); lib.refreshRows() }
        lib.bgJob = { v -> BgJobs.find(v.videoId) }
        lib.onBgFolder = { f ->
            val todo = f.videos.filter { !BgJobs.isActive(it.videoId) }
            GAlert(this).setTitle("🌙 ترجمة الفولدر في الخلفية")
                .setMessage("هترجم ${todo.size} فيديو ورا بعض (واحد واحد). ده بيستهلك كوتة المفاتيح. تكمّل؟")
                .setPositiveButton("ابدأ") { _, _ -> startBg(todo) }.setNegativeButton("إلغاء", null).show()
        }
        val ops = MediaOps(this, ui, th) { VideoScan.cache = null; scanFn() }
        mediaOps = ops
        lib.onRename = { v -> ops.rename(v) }
        lib.onMove = { v -> ops.move(v, lib.allFolders()) }
        lib.onShare = { v -> ops.share(v) }
        lib.onShareMany = { vs -> ops.shareMany(vs) }
        lib.onPlayWeb = { w -> save(); playWeb(w) }
        lib.onDelete = { v -> ops.delete(v) }
        lib.onRenameFolder = { f -> ops.renameFolder(f) }
        lib.onDeleteFolder = { f -> ops.deleteFolder(f) }
        lib.onDetails = { v -> ops.details(v, Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).firstOrNull { it.id == v.videoId }) }
        val qui = QueueUi(this, ui, th); queueUi = qui; qui0 = qui; settingsDlg0 = settingsDlg
        // رجّع الطابور المحفوظ (لو التطبيق اتقفل/اتمسح) وكمّل الترجمة
        if (!fromPlayer && BgJobs.restore(this) > 0) { BgService.start(this); libUi?.refreshRows() }
        lib.onQueue = { qui.show() }
        BgJobs.onChange = { runOnUiThread { if (!isDestroyed) { libUi?.refreshRows(); if (qui.showing) qui.refresh(); TaskCenter.changed() } } }
        lib.gate = { open -> lk.gate(open) }
        // (v87) ⚙️ في المكتبة: قايمة صغيرة منسدلة بالأقسام، دوسة على قسم تفتحه دايركت (بدل شاشة القايمة الكبيرة)
        lib.onSettings = { anchor ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.card, th.border, 14); elevation = ui.dp(8).toFloat() }
            val pw = android.widget.PopupWindow(col, -2, -2, true)
            pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            col.addView(IconTextView(this).apply {
                val n = TaskCenter.active() + BgJobs.jobs.count { it.active }
                text = if (n > 0) "⏳ المهام ($n)" else "⏳ المهام"; textSize = 14f; setTextColor(th.text); minHeight = ui.dp(42); gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); minimumWidth = ui.dp(190)
                setOnClickListener { pw.dismiss(); showTasksDialog() }
            })
            for (t in settingsDlg.tabs) col.addView(IconTextView(this).apply {
                text = t.label; textSize = 14f; setTextColor(th.text); minHeight = ui.dp(42); gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); minimumWidth = ui.dp(190)
                setOnClickListener { pw.dismiss(); settingsDlg.show(t.id) }
            })
            try { pw.showAsDropDown(anchor, 0, ui.dp(2)) } catch (_: Throwable) { settingsDlg.show() }
        }
        lib.onLink = { recentDlg?.show() }
        lib.onPick = { save(); pickVideo() }
        lib.onGrant = { requestPermissions(arrayOf(VideoScan.permission()), 11) }
        fun doScan() {
            libStarted = true
            if (!VideoScan.hasPermission(this)) { lib.showNoPermission(); return }
            val cached = VideoScan.cache
            if (cached != null) lib.showVideos(cached) else if (!lib.hasData) lib.showScanning()
            Thread {
                val res = try { VideoScan.scan(this) } catch (_: Exception) { emptyList<VideoItem>() }
                runOnUiThread { if (!isDestroyed && !isFinishing) lib.showVideos(res) }
            }.apply { isDaemon = true }.start()
        }
        scanFn = { doScan() }
        // ريفريش: يمسح الفولدرات والصور المصغّرة المحفوظة ويفحص التخزين من الأول
        lib.onPull = { VideoScan.cache = null; doScan() }
        lib.onRefresh = { VideoScan.cache = null; Thumbs.clear(); lib.showScanning(); doScan() }

        // (v124) الشريط السفلي: بوابتين — الفيديوهات (المكتبة) · المتصفح (تبويب يوتيوب اتشال)
        // (v135) بوابة تالتة: «المهام» (قص · صوت · GIF · ضغط · ترجمة ثابتة)
        toolsUiM = ToolsUi(this, ui, th)
        val tasksUi = TasksUi(this, ui, th) { k -> pickTool(k) }; tasksUiRef = tasksUi
        val music = MusicUi(this, ui, th); musicUi = music; music.root.visibility = View.GONE
        music.onGrant = { requestPermissions(arrayOf(MusicScan.permission()), 12) }
        // (v189) البوابات: 0 الفيديوهات · 1 الموسيقى · 2 المتصفح (صفحة المهام بقت زرار ⏳ في المشغّل + قايمة ⚙️ في المكتبة)
        showTabFn = { i ->
            if (i == 0) { curTab = 0; lib.root.visibility = View.VISIBLE; music.root.visibility = View.GONE; navUi?.set(0) }
            else if (i == 1) { curTab = 1; lib.root.visibility = View.GONE; music.root.visibility = View.VISIBLE; music.ensureLoaded(); navUi?.set(1) }
            else if (i == 3) showTasksDialog()
        }
        val nav = BottomNav(this, ui, th, 0) { i -> if (i == 2) { save(); startActivity(Intent(this, BrowserActivity::class.java)) } else showTabFn(i) }
        navUi = nav
        if (!fromPlayer) {
            TaskCenter.listener = { if (!isDestroyed && !isFinishing) { tasksDlgRefresh?.invoke() } }
        }
        val pane = FrameLayout(this)
        pane.addView(lib.root, FrameLayout.LayoutParams(-1, -1))
        pane.addView(music.root, FrameLayout.LayoutParams(-1, -1))
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        shell.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(nav.view, LinearLayout.LayoutParams(-1, -2))
        val frame = FrameLayout(this).apply { setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        frame.addView(shell, FrameLayout.LayoutParams(-1, -1))
        if (fromPlayer) {
            setContentView(FrameLayout(this))
            settingsDlg.show(intent?.getStringExtra("tab"))   // من غير قسم محدد = قايمة الإعدادات كاملة
            return
        }
        setContentView(frame)
        intent?.getStringExtra("reopen_tab")?.let { t -> intent?.removeExtra("reopen_tab"); frame.post { if (!isFinishing && !isDestroyed) settingsDlg.show(t) } }
        // لو مفيش مفتاح Gemini: القايمة بتظهر مباشرة أول البرنامج، وبعدها الفحص
        val coldStart = b == null
        if (coldStart) showSplash()
        frame.post { permFlow { ensureKeys { keys.setText(Cfg.allMainKeys().joinToString("\n")); keyLoadersRef.forEach { it() }; refreshChip(); doScan() } } }
        if (intent?.action == Intent.ACTION_SEND) {
            val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Regex("https?://\\S+").find(t)?.let { link.setText(it.value); openLink(it.value) }
        }
    }
    class StyleParts(val holder: View, val fonts: List<View>, val anim: List<View>, val look: List<View>)
    private fun styleParts(ui: Ui, th: Theme): StyleParts {
        val prev = SubtitleView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val demo = Sub(0.0, 5.0, "I met Ahmed in Cairo yesterday and we talked for hours about everything", "قابلت أحمد في القاهرة امبارح واتكلمنا ساعات عن كل حاجة في الدنيا",
            "female", "unknown", "none", listOf("أحمد"), listOf("القاهرة"), false, false, -1, "", false, "", false, "I met Ahmed in Cairo yesterday")
        val holder = FrameLayout(this).apply { setBackgroundColor(0xFF1B2733.toInt()); setPadding(0, ui.dp(30), 0, ui.dp(10)); addView(prev, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        fun st() = SubStyle.load { k, d -> Cfg.str(k, d) }
        fun showDemo() {
            val p = st()
            prev.show(demo)
        }
        fun put(k: String, v: String) { Cfg.put(k, v); prev.style = st(); prev.show(null); showDemo() }
        fun slider(label: String, k: String, d: Int, lo: Int, hi: Int, unit: String): LinearLayout {
            val tv = ui.text("$label: ${Cfg.int(k, d)}$unit", 13f, th.text)
            val sb = SeekBar(this).apply {
                max = hi - lo; progress = Cfg.int(k, d).coerceIn(lo, hi) - lo
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = "$label: ${p + lo}$unit"; if (u) put(k, (p + lo).toString()) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(sb) }
        }
        fun sw(t: String, k: String, d: Boolean) = ui.switchRow(t, Cfg.bool(k, d)) { put(k, if (it) "1" else "0") }
        val dualNames = listOf("ترجمة فقط", "ترجمة فوق + أصلي تحت", "إنجليزي + عربي", "نص أصلي فقط", "أصلي فوق + ترجمة تحت")
        val anims = SubStyle.entrances
        val fonts = SubStyle.fonts
        val fontsV = listOf<View>(
            ui.text("خط الترجمة (كل الـ 22 خط متضمنين)", 13f, th.muted),
            ui.chips(fonts.map { it.label }, { fonts.firstOrNull { f -> f.id == st().font }?.label ?: "" }) { n -> put("sub_font", fonts.first { it.label == n }.id) },
            ui.text("نمط الخط", 13f, th.muted),
            ui.chips(SubStyle.fontStyles.map { it.second }, { SubStyle.fontStyles.first { f -> f.first == st().fontStyle }.second }) { n -> put("sub_fontstyle", SubStyle.fontStyles.first { it.second == n }.first) },
            slider("حجم النص", "sub_scale", 100, 60, 200, "%")
        )
        val animV = listOf<View>(
            ui.text("أنيميشن ظهور الترجمة", 13f, th.muted), ui.chips(anims.map { it.label }, { anims.first { a -> a.id == st().anim }.label }) { n -> put("sub_anim", anims.first { it.label == n }.id) },
            slider("سرعة الأنيميشن", "sub_aspeed", 250, 50, 600, "ms")
        )
        val lookV = listOf<View>(
            ui.section("🎬 وضع العرض", true,
                ui.chips(dualNames, { dualNames[st().dual] }) { put("sub_dual", dualNames.indexOf(it).toString()) },
                sw("نص أبيض عادي بحجم ثابت (بدون ألوان الجنس والأسماء وكلمة التأكيد والتكبير التلقائي)", "sub_plain", false)),
            ui.section("🎨 اللون", false,
                sw("لون نص موحّد", "sub_uni_on", false),
                ui.chips(SubStyle.unifiedPalette, { st().uniColor }) { put("sub_uni_color", it) }),
            ui.section("✂️ تقسيم الجمل", false,
            sw("الجملة الطويلة على سطرين فوق بعض (عند الفاصلة/النقطة، أو بالنص لو عدّت 70% من عرض الفيديو)", "sub_two_lines", true)),
            ui.section("🌫 الخلفية", false,
                sw("إخفاء الخلفية", "sub_nobg", false),
                slider("غمقان الخلفية (0 = شفافة)", "sub_bgopa", 45, 0, 100, "%"),
                slider("نعومة حواف الخلفية (blur) — 0 = بدون", "sub_blur", 0, 0, 20, ""))
        )
        prev.style = st(); holder.post { showDemo() }
        return StyleParts(holder, fontsV, animV, lookV)
    }
    // ===== سبلاش: صورة البرنامج كاملة + 3 نقط لودنج صغيرة تحت الروبوت =====
    private fun showSplash() {
        val ui = Ui(this, Themes.byId(Cfg.str("theme", "mx")))
        val ov = FrameLayout(this).apply { setBackgroundColor(0xFFF7F0E0.toInt()); isClickable = true }
        val iv = ImageView(this).apply { setImageResource(R.drawable.splash_art); scaleType = ImageView.ScaleType.CENTER_CROP }
        ov.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val anims = ArrayList<android.animation.Animator>()
        for (i in 0 until 3) {
            val d = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFF8B6B4A.toInt()) }
                alpha = 0.3f
            }
            dots.addView(d, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
            anims += android.animation.ObjectAnimator.ofFloat(d, "alpha", 0.3f, 1f, 0.3f).apply {
                duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; startDelay = i * 150L
            }
        }
        ov.addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        // مكان النقط: تحت الروبوت مباشرة (الروبوت بينتهي عند ~81% من ارتفاع الصورة) مع حساب الـ center-crop
        ov.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = (r - l).toFloat(); val h = (b - t).toFloat()
            if (w <= 0f || h <= 0f) return@addOnLayoutChangeListener
            val sc = maxOf(w / 1080f, h / 2400f)
            val dh = 2400f * sc
            dots.translationY = (h - dh) / 2f + dh * 0.835f
        }
        addContentView(ov, ViewGroup.LayoutParams(-1, -1))
        anims.forEach { it.start() }
        ov.postDelayed({
            ov.animate().alpha(0f).setDuration(400).withEndAction { anims.forEach { it.cancel() }; (ov.parent as? ViewGroup)?.removeView(ov) }.start()
        }, 1800)
    }

    // ===== صلاحيات: بتتطلب كلها أول ما تفتح البرنامج (زي MX Player) — كل صلاحية مرة واحدة بس =====
    private class PermStep(val id: String, val title: String, val why: String, val granted: () -> Boolean, val launch: () -> Unit)
    private fun permSteps(): List<PermStep> {
        val l = ArrayList<PermStep>()
        val media = if (Build.VERSION.SDK_INT >= 33) arrayOf(VideoScan.permission(), "android.permission.POST_NOTIFICATIONS") else arrayOf(VideoScan.permission())
        l += PermStep("media", "الوصول للفيديوهات والإشعارات", "عشان تظهر فولدرات فيديوهاتك وإشعار تقدم الترجمة في الخلفية",
            { media.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED } }, { requestPermissions(media, 13) })
        if (Build.VERSION.SDK_INT >= 30) l += PermStep("allfiles", "إدارة كل الملفات", "عشان تغيّر اسم الفيديو أو تنقله أو تحذفه من غير ما النظام يسألك كل مرة",
            { android.os.Environment.isExternalStorageManager() },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")), 14) })
        l += PermStep("overlay", "العرض فوق التطبيقات الأخرى", "عشان الترجمة تظهر تحت نافذة الـ PiP لما تخرج بره البرنامج",
            { Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), 14) })
        l += PermStep("writesettings", "تعديل إعدادات النظام", "عشان التحكم في السطوع من الفيديو يشتغل",
            { Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")), 14) })
        l += PermStep("battery", "تجاهل توفير البطارية", "عشان الترجمة في الخلفية ماتتقفلش لما الشاشة تتقفل",
            { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) },
            { startActivityForResult(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")), 14) })
        return l
    }
    private var permQueue: ArrayList<PermStep> = ArrayList()
    fun permFlow(done: () -> Unit) {
        val todo = permSteps().filter { !it.granted() && !Cfg.bool("perm_asked_" + it.id, false) }
        if (todo.isEmpty()) { done(); return }
        permDone = done
        permQueue = ArrayList(todo)
        GAlert(this).setTitle("🔐 صلاحيات البرنامج")
            .setMessage("البرنامج هيطلب كام صلاحية مرة واحدة بس عشان يشتغل كامل زي MX Player:\n\n" + todo.joinToString("\n") { "• " + it.title + " — " + it.why } + "\n\nفي كل شاشة فعّل المفتاح ورجّع للبرنامج.")
            .setCancelable(false).setPositiveButton("تمام، يلا") { _, _ -> nextPerm() }.setNegativeButton("بعدين") { _, _ -> permQueue.clear(); permDone?.invoke(); permDone = null }.show()
    }
    private fun nextPerm() {
        while (permQueue.isNotEmpty()) {
            val st = permQueue.removeAt(0)
            if (st.granted()) continue
            Cfg.put("perm_asked_" + st.id, "1")
            try { st.launch(); return } catch (_: Exception) { continue }
        }
        val d = permDone; permDone = null; d?.invoke()
    }

    fun requestBatteryExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) { Notice.show(this, ("✅ الاستثناء شغّال بالفعل").toString(), 2300L); return }
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { LogStore.err("Main:602", e) }
        }
    }
    /** صفحة «التشغيل التلقائي» عند الشركات اللي بتقتل الخلفية؛ لو مش لاقيها بيفتح إعدادات التطبيق */
    fun openAutoStartSettings() {
        val c = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity"
        )
        for ((pkg, cls) in c) {
            try { startActivity(Intent().setClassName(pkg, cls)); return } catch (e: Exception) { LogStore.err("Main:618", e) }
        }
        Notice.show(this, ("مفيش صفحة مخصوصة لجهازك — هفتحلك إعدادات التطبيق: فعّل «التشغيل التلقائي» و«بدون قيود» للبطارية").toString(), 3600L)
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { LogStore.err("Main:621", e) }
    }

    // ===== (v137) أدوات الفيديو من تبويب «المهام»: الأداة → اختيار الفيديو → نافذة الإعدادات → تبدأ =====
    fun pickTool(kind: String) {
        pendingTool = kind
        // (v139) قايمة الفيديوهات اللي جوه التطبيق نفسه؛ مدير الملفات بقى زرار احتياطي جواها
        VideoPicker.show(this, "اختار الفيديو", { v -> onToolVideo(kind, v) }, { pickToolFromFiles() })
    }
    private fun pickToolFromFiles() {
        try { startActivityForResult(filePicker("video/*", "اختار الفيديو", true), 52) } catch (e: Exception) { Notice.show(this, "مفيش تطبيق ملفات يفتح", 2400L) }
    }
    private fun onToolVideo(kind: String, v: VideoItem) {
        Notice.show(this, "بقرا الفيديو…", 1500L)
        Thread {
            val subs = try { Store(File(filesDir, "progress"), Store.keyFor(v.videoId)).load()?.subs ?: emptyList() } catch (_: Throwable) { emptyList<Sub>() }
            var dur = v.durMs; var w = v.w; var h = v.h
            if (dur <= 0 || w <= 0 || h <= 0) {
                val pr = Tools.probe(applicationContext, v.uri)
                if (dur <= 0) dur = pr.durMs; if (w <= 0) w = pr.w; if (h <= 0) h = pr.h
            }
            val src = ToolSrc(v.uri, null, v.title, dur, w, h, 0L, subs)
            runOnUiThread { if (!isDestroyed && !isFinishing) runTool(kind, src) }
        }.apply { isDaemon = true }.start()
    }
    private fun onToolPicked(u: Uri) {
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val kind = pendingTool
        Notice.show(this, "بقرا الفيديو…", 1500L)
        Thread {
            var name = "video"; var size = 0L
            try { contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } } } catch (_: Exception) {}
            val pr = Tools.probe(applicationContext, u.toString())
            val subs = try { Store(File(filesDir, "progress"), Store.keyFor("f:$name:$size")).load()?.subs ?: emptyList() } catch (_: Throwable) { emptyList<Sub>() }
            val src = ToolSrc(u.toString(), null, name.substringBeforeLast('.', name), pr.durMs, pr.w, pr.h, 0L, subs)
            runOnUiThread { if (!isDestroyed && !isFinishing) runTool(kind, src) }
        }.start()
    }
    private fun runTool(kind: String, src: ToolSrc) {
        val tu = toolsUiM ?: return
        when (kind) {
            "trim" -> tu.trim(src); "audio" -> tu.audio(src); "gif" -> tu.gif(src); "compress" -> tu.compress(src)
            "hardsub" -> if (src.subs.none { it.translated.isNotBlank() || it.original.isNotBlank() }) askSrt(src) else tu.hardsub(src)
        }
    }
    private fun askSrt(src: ToolSrc) {
        GAlert(this).setTitle("🎬 مفيش ترجمة محفوظة للفيديو ده")
            .setMessage("اختار ملف ترجمة SRT عشان يتحرق في الفيديو، أو ترجم الفيديو في المشغّل الأول.")
            .setPositiveButton("اختار ملف SRT") { _, _ -> pendingSrc = src; try { startActivityForResult(filePicker("*/*", "اختار ملف الترجمة (SRT)", false), 53) } catch (_: Exception) {} }
            .setNegativeButton("إلغاء", null).show()
    }
    private fun onSrtPicked(u: Uri) {
        val src = pendingSrc ?: return; pendingSrc = null
        val text = try { contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } } catch (_: Exception) { null } ?: ""
        val subs = PlayerLogic.parseSrt(text.removePrefix("\uFEFF")).map { (a, b, t) -> Sub(a, b, t, t, "", "", "", emptyList(), emptyList(), false, false) }
        if (subs.isEmpty()) { Notice.show(this, "الملف ده مش SRT صالح", 2600L); return }
        toolsUiM?.hardsub(ToolSrc(src.uri, null, src.name, src.durMs, src.w, src.h, 0L, subs))
    }

    fun pickVideo() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 1) }
    /** landscape: الفيديو من الجهاز بيفتح لاندسكيب مباشرة (زي MX) إلا لو معروف إنه طولي */
    /** لينك فيديو مباشر → يشتغل على طول؛ أي لينك تاني (يوتيوب / صفحة) → شاشة الصيد بتفتح الصفحة وتلقط الفيديوهات */
    fun openLink(u0: String) {
        val u = u0.trim()
        if (Sniff.isDirect(u)) play(u, null) else startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", u))
    }
    fun play(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false) {
        try { musicUi?.pause() } catch (_: Throwable) {}
        if (u.isBlank() && uri == null) return
        if (noSub) startPlayerNow(u, uri, landscape, fresh, true)   // فرجة من غير ترجمة: مش محتاج مفتاح
        else ensureKeys { startPlayerNow(u, uri, landscape, fresh, false, ask) }
    }
    /** (v117) دخول فوري للمشغّل: مفيش قراءة لملف الترجمة هنا خالص — المشغّل بيحمّلها هو في الخلفية واللودنج بيظهر جواه */
    fun playChecked(v: VideoItem) {
        play("", Uri.parse(v.uri), v.landscape, false, false, !BgJobs.isActive(v.videoId))
    }
    fun startPlayerNow(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else putExtra("url", u)
            putExtra("landscape", landscape); putExtra("fresh", fresh); putExtra("nosub", noSub); putExtra("ask", ask)
            // لو فتحنا الإعدادات من المشغّل واخترنا فيديو جديد: امسح المشغّل القديم بدل ما يفضل تحته
            if (intent?.getBooleanExtra("from_player", false) == true) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 52) { if (c == RESULT_OK) d?.data?.let { onToolPicked(it) }; return }
        if (r == 53) { if (c == RESULT_OK) d?.data?.let { onSrtPicked(it) } else pendingSrc = null; return }
        if (r == 47) { if (c == RESULT_OK) d?.data?.let { ytUi?.importUri(it) }; return }   // (v118) ملف اشتراكات يوتيوب
        if (lockUi?.onResult(r, c == RESULT_OK) == true) return
        if (mediaOps?.onResult(r, c == RESULT_OK) == true) return
        if (r == 14) { nextPerm(); return }
        if (r == 1 && c == RESULT_OK) d?.data?.let { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { LogStore.err("Main:665", e) }; play("", it) }
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 13) { scanFn(); nextPerm(); return }
        if (rc == 12) { if (res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) musicUi?.load() else Notice.show(this, "من غير إذن الصوتيات مش هقدر أعرض الموسيقى", 3200L); return }
        if (rc != 11) return
        if (res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) { scanFn(); return }
        Notice.show(this, ("من غير إذن الفيديوهات مش هقدر أعرض فولدرات الجهاز").toString(), 3600L)
        // لو اتمنع نهائيًا: افتح إعدادات التطبيق
        if (!shouldShowRequestPermissionRationale(VideoScan.permission())) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { LogStore.err("Main:675", e) }
        }
    }
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis(); WebMute.pauseAll() }
    /** أندرويد 10+ مابيسمحش بقراءة الكليبورد غير والتطبيق عليه الفوكس — فالفحص بيتعمل هنا */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !fromPlayer) window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) checkClipboard { playClip(it) } }, 900)
    }
    /** «نعم» من بوب-أب الكليبورد: فيديو مباشر يشتغل على طول، يوتيوب بنجيب له أحسن رابط فيه صوت وصورة، وغير كده شاشة الصيد */
    /** فيديو من سجل «المصطادة»: يفتح بنفس الرابط والهيدرز ويكمّل من آخر مكان وقفت عنده. روابط يوتيوب بتنتهي — فبنفتح صفحة الفيديو */
    fun playWeb(w: WebVid) {
        if (w.kind == "YT") { if (w.id.startsWith("yt:")) playYt(w.id.removePrefix("yt:"), w.title, true, false) else if (w.ref.isNotEmpty()) openLink(w.ref) else Notice.show(this, ("رابط يوتيوب انتهت صلاحيته — افتحه من المتصفح تاني").toString(), 3600L); return }
        ensureKeys {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra("url", w.url); putExtra("ref", w.ref); putExtra("ua", w.ua); putExtra("cookie", w.cookie)
                putExtra("title", w.title); putExtra("autotr", false)   // (v157) الترجمة بإيدك بس: زرار «ترجمة» في المشغّل
            })
        }
    }
    /** (v117) دخول مباشر لفيديو يوتيوب من معرّفه: بيجيب أحسن لينك فيه صوت وصورة ويفتح المشغّل (لو فشل بيفتح صفحته في المتصفح) */
    fun playYt(id: String, title: String, translate: Boolean = true, auto: Boolean = translate) {
        Notice.show(this, ("⏳ بجيب الفيديو…").toString(), 2300L)
        Thread {
            val best = try { YtExtract.fetchPick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (best == null) {
                    // (v119) مفيش رمي أوتوماتيك على المتصفح: بنقولك السبب وإنت تختار (تعيد / تفتحه في المتصفح / تقفل)
                    val why = YtExtract.lastWhy.ifBlank { "السبب مش معروف (راجع اللوج)" }
                    GAlert(this).setTitle("⚠ ما قدرتش أجيب الفيديو")
                        .setMessage("يوتيوب ما رضيش يدّي لينك للفيديو ده من التطبيق.\n\n" + why)
                        .setPositiveButton("🔁 جرّب تاني") { _, _ -> playYt(id, title, translate, auto) }
                        .setNeutralButton("🌐 افتحه في المتصفح") { _, _ -> startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", YtHistory.watchUrl(id)).putExtra("noauto", true)) }
                        .setNegativeButton("إغلاق", null).show()
                    return@runOnUiThread
                }
                val ttl = title.ifBlank { best.title }
                val go = {
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra("url", best.url); putExtra("aurl", best.audio ?: ""); putExtra("qlist", best.optsJson()); putExtra("ref", "https://www.youtube.com/"); putExtra("ua", best.ua)
                        putExtra("title", ttl); putExtra("ytid", id)
                        putExtra("nosub", !translate); putExtra("autotr", auto)
                    })
                }
                if (translate) ensureKeys { go() } else go()
            }
        }.apply { isDaemon = true }.start()
    }
    fun playClip(c: ClipVideo) {
        fun go(u: String, ref: String, ua: String) {
            if (ref.isEmpty() && ua.isEmpty()) { play(u, null); return }
            ensureKeys { startActivity(Intent(this, PlayerActivity::class.java).apply { putExtra("url", u); putExtra("ref", ref); putExtra("ua", ua) }) }
        }
        if (c.playUrl.isNotEmpty()) { go(c.playUrl, c.ref, c.ua); return }
        val id = YtExtract.videoId(c.url)
        if (id == null) { openLink(c.url); return }
        Notice.show(this, ("بجيب الفيديو…").toString(), 2300L)
        Thread {
            val best = try { YtExtract.fetch(id).filter { it.kind != "HLS" }.maxByOrNull { Regex("(\\d+)p").find(it.kind)?.groupValues?.get(1)?.toIntOrNull() ?: 0 } } catch (_: Throwable) { null }
            runOnUiThread { if (best != null) go(best.url, best.ref, best.ua) else playYt(id, "", true, false) }
        }.apply { isDaemon = true }.start()
    }
    override fun onResume() {
        super.onResume()
        // الرجوع من المشغّل أو من إعدادات الإذن: حدّث العرض (تقدم الترجمة) أو أعد الفحص
        if (!fromPlayer) CrashLog.showIfAny(this)
        if (!fromPlayer) ModelWatch.maybeCheck(this)   // (v141) فحص يومي لموديل flash-lite أحدث
        // المخفي بيتقفل تاني لو التطبيق قعد في الخلفية أكتر من دقيقة
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) libUi?.relock()
        stoppedAt = 0L
        if (!fromPlayer && libStarted) libUi?.let { if (it.hasData) it.render() else scanFn() }
        if (!fromPlayer && curTab == 1) ytUi?.refresh()
    }
    override fun onDestroy() { if (!fromPlayer) TaskCenter.listener = null; try { musicUi?.destroy() } catch (_: Throwable) {}; super.onDestroy() }
    /** (v117) الرجوع من المتصفح بشريط البوابات: بيفتح البوابة اللي اخترتها */
    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        when (i?.getStringExtra("tab")) { "videos" -> showTabFn(0); "music" -> showTabFn(1); "tasks" -> showTabFn(3) }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!fromPlayer && curTab == 1 && ytUi?.back() == true) return   // (v118) رجوع جوه يوتيوب الأول
        if (!fromPlayer && curTab == 1 && musicUi?.onBack() == true) return   // (v193) يقفل مشغّل الأسطوانة/الفولدر الأول
        if (!fromPlayer && curTab != 0) { showTabFn(0); return }
        if (!fromPlayer && libUi?.back() == true) return
        super.onBackPressed()
    }
    override fun startActivity(i: Intent?) { super.startActivity(i); try { overridePendingTransition(R.anim.act_enter, R.anim.act_stay) } catch (e: Exception) { LogStore.err("Main:720", e) } }
    override fun finish() { super.finish(); try { if (fromPlayer) overridePendingTransition(0, 0) else overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Main:721", e) } }
}
