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
    override fun onCreate(b: Bundle?) {
        fromPlayer = intent?.getBooleanExtra("from_player", false) == true
        if (fromPlayer) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(b)
        UiWatchdog.start()
        Cfg.init(this); CrashLog.install(this)
        val th = Themes.byId(Cfg.str("theme", "mx"))
        val ui = Ui(this, th)
        applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(32), 0, ui.dp(110)); layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        val keys = ui.input("مفاتيح Gemini الأساسية (مفتاح في كل سطر)", Cfg.str("keys"), 3)
        val backup = ui.input("مفاتيح احتياطية (مفتاح في كل سطر)", Cfg.str("backup"), 2)
        val extra = ui.input("مفاتيح إضافية (بتتضاف للأساسية — مفتاح في كل سطر)", Cfg.str("extra"), 2)
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
                val del = TextView(this).apply { text = "✕"; textSize = 16f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(12), 0, ui.dp(4), 0)
                    setOnClickListener { list.removeView(wrap); if (list.childCount == 0) addRowL(""); sync(); refreshDups() } }
                row.addView(et, LinearLayout.LayoutParams(0, ui.dp(44), 1f)); row.addView(del)
                val warn = TextView(this).apply { textSize = 12f; setTextColor(th.danger); visibility = View.GONE; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(2)) }
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
            box.addView(TextView(this).apply {
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
        fun descOf(id: String) = (Models.builtin.firstOrNull { it.id == id }?.desc ?: "موديل يدوي") + " — استهلاك النهارده: " + Quota.used(id) + " / " + Models.quotaOf(id) + " (تقريبي، بيتصفّر 00:00 PT)"
        modelDesc.text = descOf(modelSel)
        lateinit var applyModel: (String) -> Unit
        val modelChips = ui.chips(modelNames, { model.text.toString().trim() }) { applyModel(it) }
        val fetchModelsBtn = ui.button("🔄 جلب كل الموديلات من جوجل واختيار واحد") {
            val k = (keys.text.toString() + "\n" + extra.text.toString() + "\n" + backup.text.toString()).lines().map { it.trim() }.firstOrNull { it.length > 10 }
            if (k == null) { Toast.makeText(this, "ضيف مفتاح API الأول", Toast.LENGTH_SHORT).show(); return@button }
            Toast.makeText(this, "⏳ بجيب الموديلات…", Toast.LENGTH_SHORT).show()
            Thread {
                try {
                    val rows = Api.listModels(k)
                    // الأحدث/الأهم فوق: gemini أولًا
                    val sorted = rows.sortedWith(compareBy({ !it.id.startsWith("gemini") }, { it.id }))
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (sorted.isEmpty()) { Toast.makeText(this, "مفيش موديلات رجعت", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                        val cur = model.text.toString().trim()
                        val labels = sorted.map { (if (it.id == cur) "✓ " else "") + it.id + (if (it.display.isNotEmpty() && it.display != it.id) "\n" + it.display else "") }.toTypedArray()
                        android.app.AlertDialog.Builder(this).setTitle("اختار الموديل (${sorted.size})")
                            .setItems(labels) { _, which -> applyModel(sorted[which].id) }
                            .setNegativeButton("إلغاء", null).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "⚠ فشل جلب الموديلات: " + (e.message ?: "").take(120), Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        var chunkSec = Cfg.int("chunk", 60).coerceIn(10, 600)
        val chunk = ui.slider("طول المقطع", chunkSec, 10, 600, " ثانية") { chunkSec = it }
        val ahead = ui.input("عدد المقاطع اللي بتترجم قدّام مكان التشغيل", Cfg.str("ahead", "3"))
        val atrack = ui.input("رقم مسار الصوت (لو الفيديو فيه أكتر من لغة)", Cfg.str("atrack", "1"))
        val roster = ui.input("جدول الشخصيات: اسم:male أو female:وصف (سطر لكل شخصية). لو فاضي والتحليل التلقائي شغال هيتعبّى لوحده", Cfg.str("roster"), 3)
        val gloss = ui.input("مسرد مصطلحات ثابت (كل سطر: الكلمة = ترجمتها)", Cfg.str("gloss"), 3)
        val flags = linkedMapOf("vad" to false, "cross" to true, "autochars" to true, "autopron" to true, "autotpl" to true, "strim" to true, "gapfill" to true, "hitiming" to false, "autosrt" to true, "soundtags" to true)
        val flagText = mapOf("vad" to "تخطي المقاطع الصامتة (فلتر الصمت)", "cross" to "مراجعة بين المقاطع (للفيديوهات أطول من 10 دقايق)",
            "autochars" to "تحليل الشخصيات تلقائيًا", "autopron" to "تصحيح الضمائر تلقائيًا", "autotpl" to "ترجمة قالب الـ prompt للغات اللي ملهاش قالب جاهز",
            "strim" to "تقصير حدود المقطع لأقرب لحظة صمت (بيقلل الجمل المقطوعة بين مقطعين)",
            "gapfill" to "سدّ الفجوات تلقائيًا أثناء المشاهدة (بمفاتيح المراقبين/الاحتياطي، والجمل المستردة بين «»)",
            "autosrt" to "حفظ ملف SRT جنب الفيديو تلقائي لما الترجمة تخلص (محتاج «إدارة كل الملفات»)",
            "hitiming" to "دقة توقيت أعلى (بيفك الصوت من قبل البداية بـ 3 ثواني — أبطأ شوية)",
            "soundtags" to "التقاط الأصوات الخلفية والهمهمات والموسيقى وعرضها كسطر وصف فوق الفيديو (بيعطّل تخطي المقاطع الصامتة)")
        val flagViews = flags.map { (k, d) -> ui.switchRow(flagText[k]!!, Cfg.bool(k, d)) { } }
        var parallel = Cfg.int("parallel", 2).coerceIn(1, 4)
        val parallelRow = ui.slider("طلبات متوازية لكل مفتاح", parallel, 1, 4, "×") { parallel = it }
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
        val langs = listOf("مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى")
        val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
        var lang = Cfg.str("lang", "فصحى"); var style = Cfg.str("style", "حرفي"); var themeId = th.id
        link = ui.input("رابط فيديو مباشر (mp4 / m3u8)", "")
        fun save() {
            val e = Cfg.p.edit().putString("keys", keys.text.toString()).putString("backup", backup.text.toString())
                .putString("model", model.text.toString().trim()).putInt("chunk", chunkSec).putString("extra", extra.text.toString())
                .putString("roster", roster.text.toString()).putString("gloss", gloss.text.toString())
                .putString("lang", lang).putString("style", style).putString("theme", themeId)
                .putInt("parallel", parallel).putString("keymodes", KeyModes.toJson(modes)).putString("viskeys", vkeys.text.toString())
            ahead.text.toString().trim().toIntOrNull()?.let { e.putInt("ahead", it) }
            atrack.text.toString().trim().toIntOrNull()?.let { e.putInt("atrack", it) }
            flags.keys.forEachIndexed { i, k -> e.putBoolean(k, (flagViews[i] as Switch).isChecked) }
            e.apply()
        }
        val themeChips = ui.chips(Themes.all.map { it.name }, { Themes.byId(themeId).name }) { n ->
            themeId = Themes.all.first { it.name == n }.id; save(); recreate()
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
                val del = TextView(this).apply {
                    text = "🗑"; textSize = 18f; gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
                    setOnClickListener {
                        try { recentFile.writeText(Recents.toJson(Recents.remove(Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }), r.id))) } catch (_: Exception) {}
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
                            else Toast.makeText(this@MainActivity, "الملف ده مبقاش متاح (الصلاحية اتفقدت) — افتحه تاني من \"فتح فيديو\"", Toast.LENGTH_LONG).show()
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
            val m0 = KeyModes.modeOf(modes, 0)
            keyChipTv.text = (if (ks.isEmpty()) "✗ " else "✓ ") + "🔀 " + (if (m0 == "backup") "احتياطي" else "الاتنين")
            keyTailTv.text = if (ks.isEmpty()) "مفيش مفتاح" else "AQ.A…" + ks[0].takeLast(5)
            val mid = model.text.toString().trim()
            val q = Models.quotaOf(mid).coerceAtLeast(1)
            keyPctTv.text = (Quota.used(mid) * 100 / q).toString() + "%"
            modelChipTv.text = "▾ " + mid.removePrefix("gemini-") + " ●"
        }
        applyModel = { id ->
            model.setText(id); modelSel = id; modelDesc.text = descOf(id)
            Cfg.p.edit().putString("model", id).apply()   // يتحفظ فورًا
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
            if (!lk.isSet) Toast.makeText(this, "اعمل نمط الأول", Toast.LENGTH_SHORT).show()
            else if (lk.bioOn) { lk.disableBio(); refreshLock() } else lk.enableBio { refreshLock() }
        }
        refreshLock()
        fun fl(vararg k: String): Array<View> = k.map { key -> flagViews[flags.keys.indexOf(key)] }.toTypedArray()
        val settingsDlg = TabbedDialog(this, ui, "⚙️ الإعدادات", listOf(
            TabDef("fonts", "🔤 الخطوط", sp.fonts, true, "نوع الخط ونمطه وحجم الترجمة"),
            TabDef("anim", "✨ الأنيميشن", sp.anim, true, "حركة ظهور الترجمة وسرعتها"),
            TabDef("look", "🎬 العرض والألوان", sp.look, true, "وضع العرض · اللون · تقسيم الجمل · الخلفية"),
            TabDef("general", "🌐 اللهجة والأسلوب", listOf<View>(
                ui.section("اللهجة", true, ui.chips(langs, { lang }) { lang = it }),
                ui.section("أسلوب الترجمة", true, ui.chips(styles, { style }) { style = it })), false, "لهجة الترجمة وأسلوبها"),
            TabDef("chars", "🧑 الشخصيات", listOf<View>(ui.charactersEditor(this, roster, gloss)), false, "جدول الشخصيات والمسرد"),
            TabDef("engine", "⚙ الترجمة والمحرك", listOf<View>(
                ui.section("🤖 الموديل", true, modelChips, fetchModelsBtn, model, modelDesc),
                ui.section("⏱ الأداء والتقطيع", false, chunk, parallelRow, ahead, atrack),
                ui.section("🔊 الصوت والتوقيت", false, *fl("soundtags", "vad", "strim", "hitiming")),
                ui.section("🧠 الذكاء التلقائي والمراجعة", false, *fl("autochars", "autopron", "autotpl", "cross", "gapfill")),
                ui.section("💾 الحفظ", false, *fl("autosrt"))), false, "الموديل · الأداء · الصوت · التصحيح التلقائي · الحفظ"),
            TabDef("keys", "🔑 المفاتيح", listOf<View>(
                ui.button("❓ إزاي أجيب مفتاح Gemini؟ (وألصقه)", true) {
                    showKeyGuide { k ->
                        val cur = keys.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                        val hit = dupHits(k, "", -1)
                        if (hit.isNotEmpty()) Toast.makeText(this, "⚠ المفتاح ده مستخدم قبل كده: " + hit.joinToString(" ، "), Toast.LENGTH_LONG).show()
                        else { keys.setText((cur + k).joinToString("\n")); keyLoaders.forEach { it() } }
                        Cfg.p.edit().putString("keys", keys.text.toString()).apply()
                    }
                },
                ui.button("📊 إحصائية الاستهلاك والكوتة") { StatsUi(this, ui, th).show() },
                ui.section("🔑 الأساسية", true, keysUi),
                ui.section("🛟 الاحتياطية", false, backupUi),
                ui.section("➕ الإضافية", false, extraUi),
                ui.section("🔀 أوضاع المفاتيح", false, modesBox, modesBtn),
                ui.section("👁 مفتاح الوضع البصري فقط", false,
                    ui.text("لو حطيت مفتاح هنا، الوضع البصري (👁) بيستخدمه هو بس ومايستهلكش مفاتيح الترجمة، والترجمة العادية ماتستخدمهوش.", 12f, th.muted), visKeysUi)), false, "مفاتيح Gemini · الاحتياطي · الإضافي · الأوضاع"),
            TabDef("bg", "🌙 الترجمة في الخلفية", listOf<View>(
                ui.section("▶ التشغيل", true,
                    ui.switchRow("كمّل الطابور تلقائيًا بعد إعادة تشغيل الجهاز", Cfg.bool("bg_autostart", true)) { Cfg.put("bg_autostart", if (it) "1" else "0") },
                    ui.text("الطابور بيتحفظ، وبيكمّل حتى لو قفلت البرنامج أو مسحته من الأخيرة. الفيديو اللي فوق في «مجلد الترجمة في الخلفية» بيترجم الأول.", 12f, th.muted),
                    ui.button("📋 افتح مجلد الترجمة في الخلفية") { settingsDlg0?.dialog?.dismiss(); qui0?.show() }),
                ui.section("🔋 عشان الجهاز ما يقتلش الخدمة", true,
                    ui.text("أجهزة شاومي / أوبو / فيفو / سامسونج / هواوي بتقتل الخدمات الخلفية. اعمل الخطوتين دول مرة واحدة:", 12f, th.muted),
                    ui.button("1) استثناء من توفير البطارية") { requestBatteryExemption() },
                    ui.button("2) السماح بالتشغيل التلقائي / الخلفية للتطبيق") { openAutoStartSettings() },
                    ui.text("وكمان: في قايمة التطبيقات الأخيرة اعمل «قفل 🔒» للتطبيق لو جهازك فيه الخيار ده.", 12f, th.muted))), false, "استمرار الترجمة بعد قفل البرنامج · التشغيل التلقائي · استثناء البطارية"),
            TabDef("sec", "🔒 الأمان", listOf<View>(lockStatus,
                ui.text("القفل بيحمي المجلد المخفي. لفتحه: اسحب لتحت في قايمة الفيديوهات وكمّل السحب لحد 🔒 وسيب.", 12f, th.muted),
                ui.button("🔑 تعيين / تغيير النمط") { lk.change { refreshLock() } }, bioBtn,
                ui.button("🗑 إزالة القفل") { lk.remove { refreshLock() } },
                ui.text("البصمة بتتحقق من بصمات جهازك المسجّلة في إعدادات الأندرويد (التطبيق مابيخزّنش بصمتك). لو نسيت النمط: «نسيت النمط؟» بيطلب قفل شاشة الجهاز.", 12f, th.muted)), false, "نمط وبصمة للمجلد المخفي"),
            TabDef("theme", "🎨 المظهر", listOf<View>(themeChips), false, "ثيم البرنامج")
        ), sp.holder) { save(); refreshChip(); if (fromPlayer) finish() }
        // رابط مباشر + المحفوظة (نافذة سفلية): حقل الرابط بيتحط هنا
        link.hint = "رابط الفيديو (MP4 / M3U8 ...)"; link.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val linkPlay = ui.button("▶ شغّل الرابط", true) {
            save(); val u = link.text.toString().trim()
            if (u.isEmpty()) Toast.makeText(this, "اكتب الرابط الأول", Toast.LENGTH_SHORT).show() else { recentDlg?.dismiss(); play(u, null) }
        }
        val linkBrowser = ui.button("🌐 افتح المتصفح (لصيد روابط الفيديو)") { save(); recentDlg?.dismiss(); startActivity(Intent(this, BrowserActivity::class.java)) }
        recentDlg = ui.sheet(this, "🔗 رابط · 📼 المحفوظة", listOf<View>(link, linkPlay, linkBrowser,
            ui.text("📼 فيديوهات محفوظة", 13f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }, recentBox), false)
        recentDlg!!.setOnShowListener { rebuildRecents() }
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
                    try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12) } catch (_: Exception) {}
                }
                var n = 0
                vs.forEach { v -> if (BgJobs.enqueue(this, BgJob(v.videoId, v.title, v.uri, null, emptyMap()))) n++ }
                Toast.makeText(this, if (n > 0) "🌙 بدأت الترجمة في الخلفية ($n) — التقدم في الإشعارات" else "الفيديو ده بيترجم في الخلفية بالفعل", Toast.LENGTH_LONG).show()
                libUi?.refreshRows()
            }
        }
        lib.onBg = { v -> startBg(listOf(v)) }
        lib.onBgStop = { v -> BgJobs.stop(v.videoId); Toast.makeText(this, "⏹ وقفت الترجمة في الخلفية (التقدم اتحفظ)", Toast.LENGTH_SHORT).show(); lib.refreshRows() }
        lib.bgJob = { v -> BgJobs.find(v.videoId) }
        lib.onBgFolder = { f ->
            val todo = f.videos.filter { !BgJobs.isActive(it.videoId) }
            android.app.AlertDialog.Builder(this).setTitle("🌙 ترجمة الفولدر في الخلفية")
                .setMessage("هترجم ${todo.size} فيديو ورا بعض (واحد واحد). ده بيستهلك كوتة المفاتيح. تكمّل؟")
                .setPositiveButton("ابدأ") { _, _ -> startBg(todo) }.setNegativeButton("إلغاء", null).show()
        }
        val ops = MediaOps(this, ui, th) { VideoScan.cache = null; scanFn() }
        mediaOps = ops
        lib.onRename = { v -> ops.rename(v) }
        lib.onMove = { v -> ops.move(v, lib.allFolders()) }
        lib.onShare = { v -> ops.share(v) }
        lib.onDelete = { v -> ops.delete(v) }
        lib.onDetails = { v -> ops.details(v, Recents.parse(try { recentFile.readText() } catch (_: Exception) { "" }).firstOrNull { it.id == v.videoId }) }
        val qui = QueueUi(this, ui, th); queueUi = qui; qui0 = qui; settingsDlg0 = settingsDlg
        // رجّع الطابور المحفوظ (لو التطبيق اتقفل/اتمسح) وكمّل الترجمة
        if (!fromPlayer && BgJobs.restore(this) > 0) { BgService.start(this); libUi?.refreshRows() }
        lib.onQueue = { qui.show() }
        BgJobs.onChange = { runOnUiThread { if (!isDestroyed) { libUi?.refreshRows(); if (qui.showing) qui.refresh() } } }
        lib.gate = { open -> lk.gate(open) }
        lib.onSettings = { settingsDlg.show() }
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

        val frame = FrameLayout(this).apply { setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        frame.addView(lib.root, FrameLayout.LayoutParams(-1, -1))
        if (fromPlayer) {
            setContentView(FrameLayout(this))
            settingsDlg.show(intent?.getStringExtra("tab"))   // من غير قسم محدد = قايمة الإعدادات كاملة
            return
        }
        setContentView(frame)
        // لو مفيش مفتاح Gemini: القايمة بتظهر مباشرة أول البرنامج، وبعدها الفحص
        val coldStart = b == null
        if (coldStart) showSplash()
        frame.post { permFlow { ensureKeys { keys.setText(Cfg.str("keys")); keyLoadersRef.forEach { it() }; refreshChip(); doScan() } } }
        if (intent?.action == Intent.ACTION_SEND) {
            val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Regex("https?://\\S+").find(t)?.let { link.setText(it.value); play(it.value, null) }
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
            prev.show(if (p.splitOn) PlayerLogic.splitParts(demo.translated, p.splitThresh).firstOrNull()?.let { demo.copy(translated = it) } ?: demo else demo)
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
        val dualNames = listOf("ترجمة فقط", "أصلي + ترجمة", "إنجليزي + عربي")
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
                sw("قسّم الجملة عند النقطة والفاصلة (كل جزء يظهر في وقته ويختفي)", "sub_punct", true),
                sw("تقسيم الجمل الطويلة لأجزاء بالتتابع (تقدير بعدد الكلمات — جيميناي بيقسّم عند الوقفات أصلًا)", "sub_split_on", false),
                slider("أقصى كلمات في الجزء", "sub_split", 8, 3, 30, "")),
            ui.section("🌫 الخلفية", false,
                sw("إخفاء الخلفية", "sub_nobg", false),
                slider("غمقان الخلفية (0 = شفافة)", "sub_bgopa", 45, 0, 100, "%"),
                slider("نعومة حواف الخلفية (blur) — 0 = بدون", "sub_blur", 0, 0, 20, ""))
        )
        prev.style = st(); holder.post { showDemo() }
        return StyleParts(holder, fontsV, animV, lookV)
    }
    // ===== سبلاش: اللوجو كامل من غير قص (بدل سبلاش النظام اللي بيقصه في دايرة) =====
    private fun showSplash() {
        val ui = Ui(this, Themes.byId(Cfg.str("theme", "mx")))
        val ov = FrameLayout(this).apply { setBackgroundColor(0xFF0F1114.toInt()); isClickable = true }
        val sz = ui.dp(260)
        val iv = ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dp(44).toFloat()) } }
        }
        ov.addView(iv, FrameLayout.LayoutParams(sz, sz, Gravity.CENTER))
        addContentView(ov, ViewGroup.LayoutParams(-1, -1))
        ov.postDelayed({ ov.animate().alpha(0f).setDuration(350).withEndAction { (ov.parent as? ViewGroup)?.removeView(ov) }.start() }, 650)
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
        android.app.AlertDialog.Builder(this).setTitle("🔐 صلاحيات البرنامج")
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
            if (pm.isIgnoringBatteryOptimizations(packageName)) { Toast.makeText(this, "✅ الاستثناء شغّال بالفعل", Toast.LENGTH_SHORT).show(); return }
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
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
            try { startActivity(Intent().setClassName(pkg, cls)); return } catch (_: Exception) {}
        }
        Toast.makeText(this, "مفيش صفحة مخصوصة لجهازك — هفتحلك إعدادات التطبيق: فعّل «التشغيل التلقائي» و«بدون قيود» للبطارية", Toast.LENGTH_LONG).show()
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    fun pickVideo() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 1) }
    /** landscape: الفيديو من الجهاز بيفتح لاندسكيب مباشرة (زي MX) إلا لو معروف إنه طولي */
    fun play(u: String, uri: Uri?, landscape: Boolean = uri != null, fresh: Boolean = false, noSub: Boolean = false, ask: Boolean = false) {
        if (u.isBlank() && uri == null) return
        if (noSub) startPlayerNow(u, uri, landscape, fresh, true)   // فرجة من غير ترجمة: مش محتاج مفتاح
        else ensureKeys { startPlayerNow(u, uri, landscape, fresh, false, ask) }
    }
    /** قبل ما تدخل الفيديو: لو له ترجمة محفوظة (ومش بيترجم في الخلفية دلوقتي) اسأل: كمّل ولا ابدأ من الأول وجديد */
    fun playChecked(v: VideoItem) {
        if (BgJobs.isActive(v.videoId)) { play("", Uri.parse(v.uri), v.landscape); return }
        Thread {
            var subsN = 0; var covered = 0.0
            try {
                val sv = Store(File(filesDir, "progress"), Store.keyFor(v.videoId)).load()
                if (sv != null) { subsN = sv.subs.size; covered = sv.done.fold(0.0) { a, r -> a + (r[1] - r[0]) } }
            } catch (_: Throwable) {}
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (subsN == 0 && covered <= 0.0) { play("", Uri.parse(v.uri), v.landscape); return@runOnUiThread }
                play("", Uri.parse(v.uri), v.landscape, false, false, true)   // يدخل الفيديو على طول، والاختيار (كمّل / من الأول / بدون ترجمة) بيظهر كشريط جوه المشغّل
            }
        }.apply { isDaemon = true }.start()
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
        if (lockUi?.onResult(r, c == RESULT_OK) == true) return
        if (mediaOps?.onResult(r, c == RESULT_OK) == true) return
        if (r == 14) { nextPerm(); return }
        if (r == 1 && c == RESULT_OK) d?.data?.let { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; play("", it) }
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 13) { scanFn(); nextPerm(); return }
        if (rc != 11) return
        if (res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) { scanFn(); return }
        Toast.makeText(this, "من غير إذن الفيديوهات مش هقدر أعرض فولدرات الجهاز", Toast.LENGTH_LONG).show()
        // لو اتمنع نهائيًا: افتح إعدادات التطبيق
        if (!shouldShowRequestPermissionRationale(VideoScan.permission())) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }
    override fun onResume() {
        super.onResume()
        // الرجوع من المشغّل أو من إعدادات الإذن: حدّث العرض (تقدم الترجمة) أو أعد الفحص
        if (!fromPlayer) CrashLog.showIfAny(this)
        // المخفي بيتقفل تاني لو التطبيق قعد في الخلفية أكتر من دقيقة
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) libUi?.relock()
        stoppedAt = 0L
        if (!fromPlayer && libStarted) libUi?.let { if (it.hasData) it.render() else scanFn() }
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (!fromPlayer && libUi?.back() == true) return; super.onBackPressed() }
}

class BrowserActivity : Activity() {
    lateinit var wv: WebView
    lateinit var found: Button
    var foundUrl = ""
    val h = Handler(Looper.getMainLooper())
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val th = Themes.byId(try { getSharedPreferences("p", 0).getString("theme", "mx") } catch (_: Exception) { "mx" })
        val ui = Ui(this, th)
        applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); setPadding(0, ui.dp(28), 0, 0) }
        val addr = ui.input("رابط الموقع أو كلمة بحث", "").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val go = ui.button("اذهب", true) { }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }
        val row = LinearLayout(this).apply { addView(addr); addView(go) }
        wv = WebView(this)
        found = Button(this).apply { visibility = View.GONE; setTextColor(if (th.isLight) Color.WHITE else Color.BLACK); background = ui.box(th.primary, th.primary, 8); setOnClickListener { openPlayer() } }
        root.addView(row); root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(found)
        setContentView(root)
        wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        val rx = Regex("\\.(mp4|m3u8|webm|mkv)(\\?|$)", RegexOption.IGNORE_CASE)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (rx.containsMatchIn(u)) h.post { onFound(u) }
                return null
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = false
        }
        fun load() {
            var u = addr.text.toString().trim()
            if (!u.startsWith("http")) u = if (u.contains(".") && !u.contains(" ")) "https://$u" else "https://www.google.com/search?q=" + Uri.encode(u)
            wv.loadUrl(u)
        }
        go.setOnClickListener { load() }
        addr.setOnEditorActionListener { _, _, _ -> load(); true }
        wv.loadUrl("https://www.google.com")
        scan()
    }
    fun scan() {
        wv.evaluateJavascript("(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):''})()") { r ->
            val s = r?.trim('"') ?: ""
            if (s.startsWith("http")) onFound(s)
        }
        h.postDelayed({ scan() }, 2000)
    }
    fun onFound(u: String) {
        foundUrl = u
        found.text = "🎬 لقيت فيديو — اضغط للترجمة"
        found.visibility = View.VISIBLE
    }
    fun openPlayer() = ensureKeys { openPlayerNow() }
    fun openPlayerNow() {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("url", foundUrl); putExtra("ref", wv.url ?: "")
            putExtra("cookie", CookieManager.getInstance().getCookie(foundUrl) ?: "")
            putExtra("ua", wv.settings.userAgentString)
        })
    }
    override fun onBackPressed() { if (wv.canGoBack()) wv.goBack() else super.onBackPressed() }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); wv.destroy(); super.onDestroy() }
}

class PlayerActivity : Activity(), Host {
    @Volatile var cur = 0L
    @Volatile var durMs = 0L
    @Volatile var status = ""
    @Volatile var dirty = true
    private var lastBatch = 0L
    lateinit var player: ExoPlayer
    lateinit var sub: SubtitleView
    lateinit var visual: VisualMode
    lateinit var visOv: VisualOverlay
    lateinit var st: TextView
    lateinit var floatBar: View
    lateinit var batchTv: TextView
    lateinit var logDrawer: LinearLayout
    lateinit var logHandle: TextView
    var logOn = false   // اللوج مخفي افتراضيًا — اللسان ▸ بيفرده
    lateinit var logTv: TextView
    lateinit var logSv: ScrollView
    lateinit var engine: Engine
    lateinit var th: Theme
    lateinit var ui: Ui
    val logBuf = StringBuilder()
    val h = Handler(Looper.getMainLooper())
    var url: String? = null
    var uri: Uri? = null
    val hdr = HashMap<String, String>()
    var touching = false
    lateinit var conf: Conf
    // قايمة الجمل (نسخة مرتبة + مصفوفات للبحث الثنائي)
    var list: List<Sub> = emptyList()
    var starts = LongArray(0); var ends = LongArray(0)
    // الحوار والأصوات الخلفية بيتفصلوا: الحوار تحت، وصف الصوت (همهمة/موسيقى…) فوق الفيديو
    var spMap = IntArray(0); var spStarts = LongArray(0); var spEnds = LongArray(0)
    var sdMap = IntArray(0); var sdStarts = LongArray(0); var sdEnds = LongArray(0)
    lateinit var soundTv: TextView; var soundKey = ""
    var curIdx = -1; var curKey = ""; var lastRefresh = 0L
    var offsetMs = 0L; var speed = 1f; var fit = 0; var fsFit = 2; var ccOn = true; var fitFsB: TextView? = null
    fun isLandNow() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** أفقي: وضع الشاشة الكاملة (الافتراضي تمديد) — رأسي: وضع منفصل (الافتراضي احتواء) */
    fun curFit() = if (isLandNow()) fsFit else fit
    lateinit var svRef: android.view.SurfaceView
    @Volatile var visNow = 0.0
    var vidW = 0; var vidH = 0
    lateinit var listView: ListView
    lateinit var counters: TextView
    lateinit var adapter: BaseAdapter
    lateinit var extras: View
    lateinit var videoBoxRef: FrameLayout
    lateinit var fsBadge: TransBadge
    lateinit var fsBtnV: TextView
    lateinit var fsBarFs: TextView
    lateinit var fsBtnLp: FrameLayout.LayoutParams
    lateinit var centerPlay: TextView
    lateinit var ctl: Ctl
    lateinit var mem: MemRow
    lateinit var fsProgV: DualProgress
    // أشرطة التقدم: كاش مجالات التغطية والجمل المستنية (بيتحدّث لما الجمل أو عدد المستني يتغيّر بس)
    private var segSubsRef: List<Sub>? = null; private var segPendN = -1
    private var segCov: List<DoubleArray> = emptyList(); private var segPend: List<DoubleArray> = emptyList()
    lateinit var fsEl: TextView
    lateinit var fsDu: TextView
    lateinit var fsPlayB: TextView
    lateinit var sentDlg: android.app.Dialog
    lateinit var logDlg: android.app.Dialog
    var chromeShown = false
    var fsOnly: List<View> = emptyList()
    lateinit var assistMenuV: View
    var lastMem = 0L
    var applyChromeFn: () -> Unit = {}
    var dismissPopFn: () -> Unit = {}
    var restyleFn: () -> Unit = {}
    private var resumeAfterSettings = false
    val hideChrome = Runnable { chromeShown = false; applyChromeFn() }
    var fullMode = false
    private var uiLocked = false
    private lateinit var lockOv: TextView
    private val hideLockOv = Runnable { if (::lockOv.isInitialized) lockOv.visibility = View.GONE }
    private var askSaved = false
    private var stripV: View? = null
    private val hideStrip = Runnable { dismissStrip() }
    fun dismissStrip() { h.removeCallbacks(hideStrip); stripV?.let { try { (it.parent as? ViewGroup)?.removeView(it) } catch (_: Exception) {} }; stripV = null }
    /** شريط زي «الاستمرار من حيث توقفت» تحت الفيديو: ✕ + كمّل الترجمة / من الأول / بدون ترجمة. أي لمسة تانية على الشاشة بتخفيه. */
    private fun showResumeStrip() {
        dismissStrip()
        val blue = 0xFF8AB4F8.toInt()
        fun pill(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(blue); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setSingleLine()
            setPadding(ui.dp(9), 0, ui.dp(9), 0); setOnClickListener { f() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); background = ui.box(0xEB202124.toInt(), 0x1FFFFFFF, 26); isClickable = true
        }
        row.addView(ui.fsCircle("✕") { dismissStrip() }.apply { textSize = 14f }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
        row.addView(pill("▶ كمّل") { dismissStrip(); beginTranslate() }, LinearLayout.LayoutParams(-2, ui.dp(40)))
        row.addView(pill("🔁 من الأول") { dismissStrip(); if (!engineStarted) freshOnce = true; beginTranslate() }, LinearLayout.LayoutParams(-2, ui.dp(40)))
        row.addView(pill("👁 بدون ترجمة") { dismissStrip(); noSub = true; if (ccOn) ccToggleFn() }, LinearLayout.LayoutParams(-2, ui.dp(40)))
        videoBoxRef.addView(row, FrameLayout.LayoutParams(-2, ui.dp(44), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(128)) })
        stripV = row; h.postDelayed(hideStrip, 15000)
    }
    fun setLock(b: Boolean) {
        uiLocked = b
        if (b) { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn(); say("🔒 الشاشة مقفولة — المس الشاشة واضغط على القفل لفتحها") }
        else { lockOv.visibility = View.GONE; showChrome() }
    }
    fun cycleFitNow() {
        if (isLandNow()) { fsFit = (fsFit + 1) % 3; Cfg.p.edit().putString("fs_fit", fsFit.toString()).apply() }
        else { fit = (fit + 1) % 3; Cfg.p.edit().putString("fit", fit.toString()).apply() }
        fitFsB?.text = "⬛ " + PlayerLogic.fitNames[curFit()]; applyFit(svRef, videoBoxRef)
        say("⬛ " + PlayerLogic.fitNames[curFit()])
    }
    var vid = ""
    // ===== استكمال/إعادة ترجمة + ترجمة في الخلفية =====
    lateinit var resumeCard: LinearLayout
    lateinit var resumeTv: TextView
    private var resumePending = false
    private var handedOff = false
    fun applyCard() { if (::resumeCard.isInitialized) resumeCard.visibility = if (resumePending && !pipNow()) View.VISIBLE else View.GONE }
    private fun placeCard() {
        if (!::resumeCard.isInitialized) return
        val lp = resumeCard.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = ui.dp(if (fullMode) 96 else 6); resumeCard.layoutParams = lp
    }
    /** الاختيار (كمّل / من الأول) بيتعمل قبل دخول الفيديو من المكتبة — هنا الترجمة بتكمّل تلقائي من غير كارت ولا وقفة */
    private fun maybePrompt(wasBg: Boolean) {
        engine.keepGoing = Cfg.bool("keepgoing", true)
        engine.paused = false
        resumePending = false; applyCard()
        status = "دوس «ترجمة» لما تحب تبدأ"
        if (wasBg) say("🌙 كان بيترجم في الخلفية — دوس «ترجمة» تكمّل من مكانه")
    }

    // ===== الترجمة بإيد المستخدم: مفيش بداية تلقائية. زرار «ترجمة» أحمر ← بيتبدّل بـ ⏸ إيقاف مؤقت / ▶ إلغاء الإيقاف =====
    private var engineStarted = false
    private var noSub = false                     // دخلت بـ «فرجة من غير ترجمة»: الترجمة مخفية والمحرك واقف لحد ما تدوس «ترجمة»
    var ccToggleFn: () -> Unit = {}
    private val trUpdaters = ArrayList<() -> Unit>()
    fun updateTr() { trUpdaters.forEach { try { it() } catch (_: Exception) {} } }
    fun beginTranslate() {
        dismissStrip()
        if (noSub) { noSub = false; if (!ccOn) ccToggleFn() }   // بدأت ترجمة: رجّع إظهار الترجمة
        if (!engineStarted) { engineStarted = true; engine.userPaused = false; startEngine() } else engine.userPaused = false
        updateTr()
    }
    private var trChipV: View? = null
    private var trChipDismissed = false
    private fun updateTrChip() { trChipV?.visibility = if (engineStarted || trChipDismissed || pipNow()) View.GONE else View.VISIBLE }
    fun pauseTranslate() { if (engineStarted) { engine.userPaused = true; updateTr() } }
    /** صف الأزرار: [▶ ترجمة] (أحمر) قبل البداية — وبعد الضغط يختفي ويظهر مكانه [⏸ إيقاف مؤقت] [▶ إلغاء الإيقاف] */
    private fun makeTrRow(compact: Boolean): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        fun b(t: String, fill: Int, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = if (compact) 12f else 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(if (compact) 10 else 8), 0, ui.dp(if (compact) 10 else 8), 0)
            background = ui.box(fill, 0x33FFFFFF, if (compact) 8 else 10); setOnClickListener { f() }
        }
        val hh = ui.dp(if (compact) 32 else 44); val m = ui.dp(if (compact) 3 else 4)
        fun lp(w: Int) = LinearLayout.LayoutParams(w, hh).apply { setMargins(m, m, m, m) }
        val tr = b("▶ ترجمة", 0xFFE53935.toInt()) { beginTranslate() }
        val ps = b("⏸ إيقاف مؤقت", 0xFF424B57.toInt()) { pauseTranslate() }
        val rs = b("▶ إلغاء الإيقاف", 0xFF2E7D32.toInt()) { beginTranslate() }
        row.addView(tr, if (compact) lp(-2) else lp(-1)); row.addView(ps, if (compact) lp(-2) else lp(0).apply { width = 0; weight = 1f })
        row.addView(rs, if (compact) lp(-2) else lp(0).apply { width = 0; weight = 1f })
        var lgRef: TextView? = null
        val lgB = b("🌐 لغة الترجمة ▾", 0xFF37474F.toInt()) { lgRef?.let { langPopup(it) } }
        lgRef = lgB
        row.addView(lgB, if (compact) lp(-2) else lp(0).apply { width = 0; weight = 1.3f })
        trUpdaters.add {
            val paused = ::engine.isInitialized && engine.userPaused
            tr.visibility = if (engineStarted) View.GONE else View.VISIBLE
            ps.visibility = if (engineStarted) View.VISIBLE else View.GONE
            rs.visibility = if (engineStarted) View.VISIBLE else View.GONE
            ps.alpha = if (paused) 0.4f else 1f; rs.alpha = if (paused) 1f else 0.4f
        }
        updateTr()
        return row
    }
    private val hideCard = Runnable { resumePending = false; applyCard() }
    private fun fmtMS(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

    private var batchPanel: View? = null
    lateinit var batchBtn: View
    fun closeBatchPanel() {
        batchPanel?.let { p -> try { (p.parent as? ViewGroup)?.removeView(p) } catch (_: Exception) {} }
        batchPanel = null
    }
    /** زرار 🔄 العايم: لوحة صغيرة فوق الفيديو — «من الأول خالص» + باتشات الفيديو (📍 يوديك للباتش) + «ابدأ من هنا» / «ده بس» */
    fun batchDialog() {
        if (batchPanel != null) { closeBatchPanel(); return }
        val bl = engine.batches()
        if (bl.isEmpty()) { Toast.makeText(this, "مدة الفيديو لسه مش معروفة — استنى ثانية وجرّب تاني", Toast.LENGTH_SHORT).show(); return }
        val curC = try { engine.chunkOfSec(player.currentPosition / 1000.0) } catch (_: Exception) { 0 }
        var sel = curC
        val rows = ArrayList<Pair<Int, LinearLayout>>()
        val selTv = ui.text("", 12f, th.primary, true)
        // الشغل التقيل (مسح + حفظ على القرص) بعيد عن الـ UI thread — ده اللي كان بيهنّج ويقفل البرنامج
        fun go(label: String, work: () -> Unit) {
            closeBatchPanel(); resumePending = false; applyCard()
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
            engine.paused = true
            Thread {
                try { work() } catch (e: Throwable) { log("⚠ " + (e.message ?: e.toString()).take(120)); engine.paused = false }
                dirty = true; curIdx = -2
                runOnUiThread { beginTranslate() }
            }.apply { isDaemon = true }.start()
        }
        fun paint() {
            rows.forEach { (i, r) -> r.background = ui.box(if (i == sel) (th.primary and 0x00FFFFFF) or 0x33000000 else th.card, th.border, 8) }
            selTv.text = "المختار: باتش ${sel + 1}  (${fmtMS(bl[sel.coerceIn(0, bl.size - 1)].start)})"
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(10)); background = ui.box(0xF2101418.toInt(), 0x33FFFFFF, 14); elevation = ui.dp(10).toFloat()
            isClickable = true
        }
        val head = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.text("🔄 ترجم باتش معين", 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply { text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(ui.dp(10), ui.dp(2), ui.dp(4), ui.dp(2)); setOnClickListener { closeBatchPanel() } })
        col.addView(head)
        col.addView(ui.button("🔁 ترجم من الأول خالص", true) { player.seekTo(0); go("🔄 بترجم من الأول…") { engine.redoAll() } })
        col.addView(ui.text("📍 دوس على الباتش يوديك له، وبعدين اختار ابدأ", 11f, th.muted))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        var curRow: View? = null
        for (b in bl) {
            val i = b.idx
            val row = LinearLayout(this).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7))
                addView(TextView(this@PlayerActivity).apply { text = "📍"; textSize = 16f; setPadding(0, 0, ui.dp(8), 0) })
                addView(ui.text("باتش ${i + 1} · ${fmtMS(b.start)}–${fmtMS(b.end)} ${b.mark}" + (if (b.count > 0) " · ${b.count} جملة" else "") + (if (i == curC) "  ◀ هنا" else ""), 12f, th.text, i == curC), LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { sel = i; try { player.seekTo((engine.chunkStartSec(i) * 1000).toLong()) } catch (_: Exception) {}; paint() }
            }
            rows.add(i to row); list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
            if (i == curC) curRow = row
        }
        val content = findViewById<ViewGroup>(android.R.id.content)
        val maxH = (content.height * 0.26).toInt().coerceAtLeast(ui.dp(84))
        val sv = ScrollView(this).apply { addView(list) }
        col.addView(sv, LinearLayout.LayoutParams(-1, maxH))
        col.addView(selTv)
        val btns = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        btns.addView(ui.button("▶ ابدأ من الباتش ده") { val i = sel; go("🔄 بترجم من باتش ${i + 1} وبعده…") { engine.redoFrom(i) } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        btns.addView(ui.button("☝ ده بس") { val i = sel; go("🔄 بترجم باتش ${i + 1} لوحده…") { engine.redoOnly(i) } }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(btns)
        paint()
        val w = minOf(ui.dp(250), content.width - ui.dp(24)).coerceAtLeast(ui.dp(200))
        // شفافة بتغطي الشاشة: أي ضغطة بره القايمة بتقفلها، والفيديو باين وراها. القايمة صغيرة على الحافة (مش نص الشاشة)
        val scrim = FrameLayout(this).apply { isClickable = true; setOnClickListener { closeBatchPanel() } }
        scrim.addView(col, FrameLayout.LayoutParams(w, -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { setMargins(0, ui.dp(8), ui.dp(70), ui.dp(8)) })   // RIGHT صريحة (END كانت بتتقلب لشمال جنب اللوج) + جنب زرار 🔄 مش فوقه
        content.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        batchPanel = scrim
        curRow?.let { r -> sv.post { sv.scrollTo(0, (r.top - ui.dp(40)).coerceAtLeast(0)) } }
    }

    /** يوقف المشغّل خالص، يسلّم الترجمة لخدمة الخلفية (إشعار بالتقدم والوقت المتبقي) ويطلع بره التطبيق من غير ما يقفله */
    fun translateInBackground() {
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty() && conf.backup.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        if (handedOff) return
        handedOff = true
        closeSide(); resumePending = false
        say("🌙 هكمّل الترجمة في الخلفية — التقدم في الإشعارات")
        try { player.pause() } catch (_: Exception) {}
        saveRecentForce()
        val vidNow = vid; val uriS = uri?.toString(); val urlS = url; val hdrC = HashMap(hdr)
        val app = applicationContext
        val eng = engine
        Thread {
            try { eng.stop(); eng.awaitStopped(4000); eng.saveNow() } catch (_: Exception) {}
            BgJobs.enqueue(app, BgJob(vidNow, Recents.titleOf(vidNow), uriS, urlS, hdrC))
        }.start()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12) } catch (_: Exception) {}
        }
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        finish()
    }

    // ===== حفظ SRT تلقائي جنب الفيديو لما الترجمة تخلص (أو لما تعدّل التزامن بعدها) =====
    var offFsB: TextView? = null
    private var lastSrtN = -1
    private var lastSrtOff = Long.MIN_VALUE
    private val srtRun = Runnable { srtCheck() }
    fun srtSoon() { h.removeCallbacks(srtRun); h.postDelayed(srtRun, 2500) }
    private fun srtCheck() {
        val u = uri?.toString() ?: return
        if (durMs <= 0 || engine.subs.isEmpty() || !Cfg.bool("autosrt", true)) return
        if (PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0) < 97) return
        val n = engine.subs.size
        if (n == lastSrtN && offsetMs == lastSrtOff) return
        lastSrtN = n; lastSrtOff = offsetMs
        val subsNow = engine.subs; val off = offsetMs; val app = applicationContext
        Thread { SrtWriter.save(app, u, subsNow, off)?.let { p -> runOnUiThread { log("💾 اتحفظ SRT: $p"); say("💾 اتحفظ SRT جنب الفيديو") } } }.start()
    }

    // ===== PiP =====
    private var inPip = false
    private var resumedNow = false
    private var pipOv: PipSubBar? = null
    private val pipExitCheck = Runnable { if (!resumedNow && !isFinishing) closeAfterPip() }
    fun pipNow() = inPip || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
    private fun closeAfterPip() {
        try { player.pause() } catch (_: Exception) {}
        pipOv?.hide(); pipOv = null
        finishAndRemoveTask()
    }
    // القايمة الجانبية (الأفقي)
    lateinit var sideMenuV: FrameLayout
    lateinit var sidePanel: LinearLayout
    var sideOpen = false
    fun openSide() {
        if (!fullMode || !::sideMenuV.isInitialized) return
        val lp = sidePanel.layoutParams as FrameLayout.LayoutParams
        lp.width = if (isLandNow()) minOf((resources.displayMetrics.widthPixels * 0.5f).toInt(), ui.dp(400)) else (resources.displayMetrics.widthPixels * 0.9f).toInt(); sidePanel.layoutParams = lp
        h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn()
        sideOpen = true; sideMenuV.visibility = View.VISIBLE
    }
    fun closeSide() { sideOpen = false; if (::sideMenuV.isInitialized) sideMenuV.visibility = View.GONE }

    // ===== Host =====
    override fun log(s: String) {
        status = s
        synchronized(logBuf) {
            logBuf.append(fmtMs(cur)).append("  ").append(s).append('\n')
            if (logBuf.length > 30000) logBuf.delete(0, 10000)
        }
        LogStore.add(fmtMs(cur) + "  " + s)
    }
    private var lastBeat = 0L
    private var logMode = 0
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); LogStore.add("⚠ onTrimMemory level=$level · ${LogStore.heapLine()}") }
    override fun onLowMemory() { super.onLowMemory(); LogStore.add("⚠ onLowMemory · ${LogStore.heapLine()}") }
    override fun status(s: String) { status = s }
    override fun changed() { dirty = true; srtSoon() }
    override fun position() = cur / 1000.0
    override fun playerDuration() = durMs / 1000.0

    private fun refreshList() {
        val l = engine.subs.sortedBy { it.start }
        list = l; starts = LongArray(l.size) { (l[it].start * 1000).toLong() }; ends = LongArray(l.size) { (l[it].end * 1000).toLong() }
        // متحدثين مختلفين ورا بعض (فاصل ≤ 0.35ث): الأول بيفضل ظاهر لحد ما التاني يخلص، فيبانوا في سطرين (- ... / - ...) بدل ما واحد يختفي ويتبدّل
        for (j in 0 until l.size - 1) {
            val a = l[j]; val b = l[j + 1]
            val gap = starts[j + 1] - ends[j]
            val differ = (a.speakerTag.isNotBlank() && b.speakerTag.isNotBlank() && a.speakerTag != b.speakerTag) || a.gender != b.gender
            if (gap in 0..350 && differ && !b.isContinuation && !a.isSong && !b.isSong && !a.isSound && !b.isSound && !a.translated.startsWith("«") && !b.translated.startsWith("«") &&
                ends[j] - starts[j] <= 4000 && ends[j + 1] - starts[j + 1] in 600..5000) ends[j] = ends[j + 1]
        }
        val sp = l.indices.filter { !l[it].isSound }; val sd = l.indices.filter { l[it].isSound }
        spMap = sp.toIntArray(); spStarts = LongArray(sp.size) { starts[sp[it]] }; spEnds = LongArray(sp.size) { ends[sp[it]] }
        sdMap = sd.toIntArray(); sdStarts = LongArray(sd.size) { starts[sd[it]] }; sdEnds = LongArray(sd.size) { ends[sd[it]] }
        if (::sentDlg.isInitialized && sentDlg.isShowing) adapter.notifyDataSetChanged()   // القايمة مش ظاهرة = مفيش داعي نرسمها كل ثانية
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        UiWatchdog.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Cfg.init(this); CrashLog.install(this); conf = Cfg.snapshot()
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        applyBars(th)
        speed = Cfg.str("speed", "1").toFloatOrNull() ?: 1f; fit = Cfg.int("fit", 0).coerceIn(0, 2); fsFit = Cfg.int("fs_fit", 2).coerceIn(0, 2); offsetMs = 0L
        uri = intent.data; url = intent.getStringExtra("url")
        noSub = intent.getBooleanExtra("nosub", false); askSaved = intent.getBooleanExtra("ask", false)
        intent.getStringExtra("ua")?.takeIf { it.isNotEmpty() }?.let { hdr["User-Agent"] = it }
        intent.getStringExtra("ref")?.takeIf { it.isNotEmpty() }?.let { hdr["Referer"] = it }
        intent.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { hdr["Cookie"] = it }

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val videoBox = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = ui.dp(14).toFloat() }; clipToOutline = true; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        videoBoxRef = videoBox
        val sv = SurfaceView(this); svRef = sv
        videoBox.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        sub = SubtitleView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; style = SubStyle.load { k, d -> Cfg.str(k, d) } }
        val subLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ui.dp(12) }
        visOv = VisualOverlay(this); videoBox.addView(visOv, FrameLayout.LayoutParams(-1, -1))
        visOv.area = { if (sv.width > 0 && sv.height > 0) android.graphics.RectF(sv.left.toFloat(), sv.top.toFloat(), sv.right.toFloat(), sv.bottom.toFloat()) else android.graphics.RectF(0f, 0f, videoBox.width.toFloat(), videoBox.height.toFloat()) }
        videoBox.addView(sub, subLp)
        sub.backdrop = sv
        st = TextView(this).apply { setTextColor(th.primary); textSize = 11f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        videoBox.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.TOP))
        soundTv = TextView(this).apply {
            setTextColor(0xFFE8EAED.toInt()); textSize = 13f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
            gravity = Gravity.CENTER; maxLines = 2; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); background = ui.box(0x99000000.toInt(), 0x00000000, 14); visibility = View.GONE
        }
        videoBox.addView(soundTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(28) })
        // طبقة إيماءات شفافة فوق الفيديو والترجمة وتحت كل الأزرار (لازم تتضاف قبل درج اللوج وزرار 👁 وإلا بتبلع لمسهم)
        val gestureLayer = View(this)
        videoBox.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // أزرار عايمة فوق الفيديو: 📋 اللوج (يخفي/يظهر حالة الترجمة) و 👁 بصري
        batchTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8).toFloat() }
            layoutDirection = View.LAYOUT_DIRECTION_RTL; typeface = android.graphics.Typeface.MONOSPACE
            setOnClickListener { toggleLog() }
        }
        // درج اللوج: اللوج + لسان صغير في نص حافته. الضغط على اللسان بيدخّل اللوج أقصى الشمال ويفرده تاني
        logHandle = TextView(this).apply {
            text = "◂"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = 0.85f
            background = ui.box(0xCC14171C.toInt(), 0x33FFFFFF, 8); setOnClickListener { toggleLog() }
        }
        logHandle.text = if (logOn) "◂" else "▸"
        logDrawer = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            addView(batchTv, LinearLayout.LayoutParams(-2, -2)); addView(logHandle, LinearLayout.LayoutParams(ui.dp(22), ui.dp(46)).apply { marginStart = ui.dp(2) }) }
        batchTv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!logOn) logDrawer.translationX = -(batchTv.width + ui.dp(2)).toFloat() }
        videoBox.addView(logDrawer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(0, ui.dp(26), 0, 0) })
        // 👁 بصري: دايرة عايمة فوق دايرة ✦
        floatBar = ui.fsCircle("👁") { visualSnap() }.apply {
            textSize = 20f; alpha = 0.95f; setOnLongClickListener { visualDialog(); true }
        }
        videoBox.addView(floatBar, FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(8), 0) })
        // 🔄 ترجم باتش معين: دايرة عايمة فوق 👁
        batchBtn = ui.fsCircle("🔄") { batchDialog() }.apply { textSize = 20f; alpha = 0.88f; translationY = -ui.dp(60).toFloat() }
        videoBox.addView(batchBtn, FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(8), 0) })
        // ▶ ترجمة خفيف وثابت من أول دخول الفيديو (من غير ما يحتاج الشريط يكون ظاهر) + ✕ جنبه تخفيه — بيختفي لوحده لما تدوس ترجمة
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            alpha = 0.82f; background = ui.box(0x99000000.toInt(), 0x22FFFFFF, 20); setPadding(ui.dp(4), ui.dp(3), ui.dp(4), ui.dp(3))
        }
        chip.addView(ui.fsCircle("✕") { trChipDismissed = true; updateTrChip() }.apply { textSize = 12f }, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
        chip.addView(TextView(this).apply {
            text = "▶ ترجمة"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(12), 0, ui.dp(12), 0); background = ui.box(0xFFE53935.toInt(), 0x33FFFFFF, 14); setOnClickListener { beginTranslate() }
        }, LinearLayout.LayoutParams(-2, ui.dp(30)).apply { marginStart = ui.dp(4) })
        videoBox.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { setMargins(ui.dp(10), 0, 0, 0) })
        trChipV = chip; trUpdaters.add { updateTrChip() }; updateTrChip()
        probBar = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5)); background = ui.box(0xE6B71C1C.toInt(), 0x33FFFFFF, 14); visibility = View.GONE
            setOnClickListener { askRetryProblem() }
        }
        videoBox.addView(probBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, ui.dp(8), 0, 0) })

        // زرار التشغيل الأوسط (بيظهر وقت الإيقاف) + شارة النسبة (شاشة كاملة) + فلاش السيك/الصوت/السطوع
        centerPlay = TextView(this).apply {
            text = "▶"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE00F0F12.toInt()); setStroke(ui.dp(1), 0x29FFFFFF) }
            setOnClickListener { togglePlay() }
        }
        videoBox.addView(centerPlay, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER))
        fsBadge = TransBadge(this, th).apply { visibility = View.GONE }
        videoBox.addView(fsBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(14), ui.dp(14), 0) })
        val gi = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(0x99000000.toInt(), Color.TRANSPARENT, 24); visibility = View.GONE
        }
        videoBox.addView(gi, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val volInd = VertInd(this, "🔊"); val briInd = VertInd(this, "☀️")
        videoBox.addView(volInd, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(24), 0) })
        videoBox.addView(briInd, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(ui.dp(24), 0, 0, 0) })
        val indHide = Runnable { volInd.visibility = View.GONE; briInd.visibility = View.GONE }
        fun indShow(v: VertInd, f: Float) { v.set(f); h.removeCallbacks(indHide); h.postDelayed(indHide, 900) }
        // فقاعة الأدوات (Assistive Touch): ✦ تفتح عمود دوائر
        fun roundBtn(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; includeFontPadding = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2140.toInt()); setStroke(ui.dp(2), 0xFF6B4FA0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { setMargins(0, ui.dp(5), 0, ui.dp(5)) }
            setOnClickListener { f(); showChrome() }
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(ui.dp(6), ui.dp(5), ui.dp(6), ui.dp(5)); background = ui.box(0xB814171C.toInt(), 0x2EFFFFFF, 26)
        }
        menu.addView(roundBtn("📝") { sentDlg.show() })
        menu.addView(roundBtn("🕳") { engine.retryFailed(); Toast.makeText(this, "بحاول أسد الفجوات", Toast.LENGTH_SHORT).show() })
        menu.addView(roundBtn("📥") { doImport() })
        menu.addView(roundBtn("📂") { doOpen() })
        videoBox.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(68), 0) })
        fsOnly = listOf<View>(fsBadge); assistMenuV = menu
        val giHide = Runnable { gi.visibility = View.GONE }
        fun giShow(t: String, g: Int) {
            gi.text = t
            val lp = gi.layoutParams as FrameLayout.LayoutParams
            lp.gravity = g; val edge = (videoBox.width * 0.18f).toInt(); lp.setMargins(edge, 0, edge, 0); gi.layoutParams = lp
            gi.visibility = View.VISIBLE; h.removeCallbacks(giHide); h.postDelayed(giHide, 800)
        }

        // ---- نفس خطوات الأصل: تحكم الشاشة الكاملة (أزرار الترجمة فوق + كبسولة التقدم تحت) ----
        fun curStyle() = SubStyle.load { k, d -> Cfg.str(k, d) }
        fun restyle() { sub.style = curStyle(); curIdx = -2 }
        restyleFn = { restyle() }
        fun fontLabel(): String { val f = curStyle().font; return SubStyle.fonts.firstOrNull { it.id == f }?.label ?: f }
        fun entLabel(): String { val a = curStyle().anim; return SubStyle.entrances.firstOrNull { it.id == a }?.label ?: "افتراضي" }
        giShowFn = { m -> giShow(m, Gravity.CENTER) }
        fun scaleBy(dv: Int) { val n = (Cfg.int("sub_scale", 100) + dv).coerceIn(60, 200); Cfg.put("sub_scale", n.toString()); restyle(); giShow("📏 $n%", Gravity.CENTER) }
        fun fb(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); showChrome() }
        var fsSpeedB: TextView? = null
        var ccFsB: TextView? = null
        fun cycleSpeed() {
            speed = PlayerLogic.nextSpeed(speed); player.setPlaybackSpeed(speed)
            Cfg.p.edit().putString("speed", speed.toString()).apply()
            fsSpeedB?.text = "⚙️ " + PlayerLogic.speedLabel(speed)
            giShow("⏱ " + PlayerLogic.speedLabel(speed), Gravity.CENTER)
        }
        fun toggleCc() {
            ccOn = !ccOn; val a = if (ccOn) 1f else 0.4f
            ccFsB?.alpha = a; ctl.cc.alpha = a
            if (!ccOn) sub.show(null) else curIdx = -2
        }
        fun setOff(d: Long) {
            offsetMs += d; Cfg.p.edit().putString("sub_offset_ms:$vid", offsetMs.toString()).apply(); srtSoon()
            offFsB?.text = String.format("%+.1fs", offsetMs / 1000.0); curIdx = -2
        }

        ctl = ui.controls()
        // صفّين ثابتين من اليمين (نفس البداية ونفس الارتفاع 32dp، والمسافة بينهم 6dp = هامش 3+3)
        var popup: android.widget.PopupWindow? = null; var popupOwner: View? = null
        fun dismissPop() { try { popup?.dismiss() } catch (_: Exception) {}; popup = null; popupOwner = null }
        dismissPopFn = { dismissPop() }
        fun after(keep: Boolean) { if (!keep) dismissPop(); showChrome(); if (popup != null) h.removeCallbacks(hideChrome) }
        fun pk(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(true) }.apply { minimumWidth = ui.dp(150) }
        fun pd(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(false) }.apply { minimumWidth = ui.dp(150) }
        fun hRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        fun gCol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(0xF2141418.toInt(), 0x33FFFFFF, 12) }
        val gText = gCol(); val gAi = gCol(); val gTool = gCol()
        fun tRow() = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }   // بيلفّ لسطر جديد في الرأسي بدل ما الأزرار تتقص
        val tb1 = tRow(); val tb2 = tRow()
        val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        tb.addView(tb1, LinearLayout.LayoutParams(-2, -2)); tb.addView(tb2, LinearLayout.LayoutParams(-2, -2))
        fun grp(label: String, col: LinearLayout): TextView = ui.fsBtn("$label ▾") { v ->
            val had = popupOwner === v; dismissPop()
            if (!had) {
                (col.parent as? android.view.ViewGroup)?.removeView(col)
                val pw = android.widget.PopupWindow(col, -2, -2, false)
                pw.isOutsideTouchable = true; pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
                pw.showAsDropDown(v, 0, ui.dp(2)); popup = pw; popupOwner = v
            }
            showChrome(); if (popup != null) h.removeCallbacks(hideChrome)
        }
        gText.addView(pk("🔤 " + fontLabel()) { v ->
            val fs = SubStyle.fonts; val cf = curStyle().font
            val n = fs[(fs.indexOfFirst { it.id == cf } + 1) % fs.size]
            Cfg.put("sub_font", n.id); restyle(); v.text = "🔤 " + n.label
        })
        gText.addView(pk("✨ " + entLabel()) { v ->
            val es = SubStyle.entrances; val ca = curStyle().anim
            val n = es[(es.indexOfFirst { it.id == ca } + 1) % es.size]
            Cfg.put("sub_anim", n.id); restyle(); v.text = "✨ " + n.label
        })
        val spB = pk("⚙️ " + PlayerLogic.speedLabel(speed)) { cycleSpeed() }
        fsSpeedB = spB; gText.addView(spB)
        run {
            val cs = curStyle()
            val bgTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
            fun bgLabel(p: Int) = "🌫 غمقان الخلفية: " + (if (p == 0) "شفافة" else "$p%")
            val p0 = if (cs.noBg) 0 else cs.bgOpa
            bgTv.text = bgLabel(p0)
            val bgSb = SeekBar(this).apply {
                max = 100; progress = p0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                        bgTv.text = bgLabel(p)
                        if (!u) return
                        Cfg.put("sub_bgopa", p.toString()); Cfg.put("sub_nobg", "0"); restyle()
                        showChrome(); h.removeCallbacks(hideChrome)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) { h.removeCallbacks(hideChrome) }
                    override fun onStopTrackingTouch(sb: SeekBar?) { showChrome(); h.removeCallbacks(hideChrome) }
                })
            }
            gText.addView(bgTv, LinearLayout.LayoutParams(ui.dp(150), -2))
            gText.addView(bgSb, LinearLayout.LayoutParams(ui.dp(150), -2))
        }
        val ccB = fb("CC") { toggleCc() }
        ccFsB = ccB; tb1.addView(ccB)
        ccToggleFn = { toggleCc() }
        if (noSub) { ccOn = false; ccB.alpha = 0.4f; ctl.cc.alpha = 0.4f }
        tb1.addView(fb("A−") { scaleBy(-10) })
        tb1.addView(fb("A+") { scaleBy(10) })
        val fitB = fb("⬛ " + PlayerLogic.fitNames[curFit()]) { v ->
            if (isLandNow()) { fsFit = (fsFit + 1) % 3; Cfg.p.edit().putString("fs_fit", fsFit.toString()).apply() }
            else { fit = (fit + 1) % 3; Cfg.p.edit().putString("fit", fit.toString()).apply() }
            v.text = "⬛ " + PlayerLogic.fitNames[curFit()]; applyFit(sv, videoBox)
        }
        fitFsB = fitB; tb1.addView(fitB)
        tb1.addView(fb("تقديم −0.1") { setOff(-100) })
        val offB = fb(String.format("%+.1fs", offsetMs / 1000.0)) { setOff(-offsetMs) }   // ضغطة على القيمة = رجوع للصفر
        offFsB = offB; tb1.addView(offB)
        tb1.addView(fb("تأخير +0.1") { setOff(100) })
        gTool.addView(pd("📤 تصدير SRT") { doExport() })
        gTool.addView(pd("🔁 سد الفجوات") { engine.retryFailed(); Toast.makeText(this, "بحاول أسد الفجوات", Toast.LENGTH_SHORT).show() })
        gTool.addView(pd("⧉ نافذة صغيرة") { enterPip() })
        gTool.addView(pd("⚙️ الإعدادات") { openSettings() })

        gTool.addView(pd("🗂 ترجمات") { versionsDialog() })
        gTool.addView(pd("🔄 إعادة ترجمة") { batchDialog() })
        gTool.addView(pd("🌙 ترجمة بالخلفية") { translateInBackground() })
        gAi.addView(pd("🔥 لهجة") { runTool("زيادة شدة اللهجة", "زوّد شدة اللهجة الشعبية في كل جملة درجة واحدة: ألفاظ وتعبيرات الشارع والعامية المحلية (${conf.lang}) بدل الفصحى والكلام الرسمي، من غير ما تغيّر المعنى أو الجنس.", true) })
        gAi.addView(pd("😐 عائلي/صريح") { familyDialog() })
        gAi.addView(pd("🌐 لهجة لايف") { liveDialectDialog() })
        gAi.addView(pd("🔧 ضمائر") { pronounsNow() })
        gAi.addView(pd("🧠 دمج مكرر") { val n = engine.removeDuplicates(); Toast.makeText(this, if (n > 0) "اتدمجت $n جملة مكررة" else "مفيش جمل مكررة متداخلة", Toast.LENGTH_SHORT).show(); curIdx = -2 })
        gTool.addView(pd("📜 ذكّرني") { recapDialog() })
        tb2.addView(ui.fsBtn("☰ القائمة") { openSide() })
        tb2.addView(grp("🔤 النص", gText)); tb2.addView(grp("✨ لهجة", gAi)); tb2.addView(grp("🧰 أدوات", gTool))
        tb2.addView(makeTrRow(true))
        tb2.addView(ui.fsBtn("✦ سريعة") { menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE; showChrome() })

        fsPlayB = TextView(this).apply {
            text = "▶"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
            setOnClickListener { togglePlay(); showChrome() }
        }
        fsEl = ui.text("0:00", 12f, Color.WHITE); fsDu = ui.text("0:00", 12f, Color.WHITE)
        fsProgV = DualProgress(this, th)
        // زي MX: صف الوقت+التقدم فوق، وتحته: 🔓 قفل (شمال) | ⏮ ⏯ ⏭ (النص) | ⛶ احتواء/تمديد + ⧉ نافذة صغيرة (يمين)
        val fsBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(ui.dp(14), ui.dp(26), ui.dp(14), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xB0000000.toInt()))
        }
        val timeRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        timeRow.addView(fsEl, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        timeRow.addView(fsProgV, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(fsDu, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        fsBar.addView(timeRow, LinearLayout.LayoutParams(-1, ui.dp(30)))
        val btnRow = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        btnRow.addView(ui.fsCircle("🔓") { setLock(true) }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
        val mid = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER }
        mid.addView(ui.fsCircle("⏮") { stepEpisode(-1); showChrome() }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
        mid.addView(fsPlayB, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { setMargins(ui.dp(14), 0, ui.dp(14), 0) })
        mid.addView(ui.fsCircle("⏭") { stepEpisode(1); showChrome() }, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)))
        btnRow.addView(mid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val rightB = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        fsBarFs = ui.fsCircle("⛶") { cycleFitNow(); showChrome() }
        rightB.addView(ui.fsCircle("↻") { toggleFs(); showChrome() }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) })   // بورتريت/لاندسكيب في الشريط السفلي مع ⛶ و ⧉
        rightB.addView(fsBarFs, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        rightB.addView(ui.fsCircle("⧉") { enterPip() }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(8) })
        btnRow.addView(rightB, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        fsBar.addView(btnRow, LinearLayout.LayoutParams(-1, ui.dp(56)))
        val chromeFrame = FrameLayout(this).apply { visibility = View.GONE; tag = "chromeFrame" }
        // في الرأسي الصفوف بتلفّ لسطر جديد (FlowRow) — من غير HorizontalScrollView لأنه بيدّي عرض لا نهائي فالصف عمره ما بيلفّ
        chromeFrame.addView(tb, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(12), ui.dp(12), ui.dp(93), 0) })
        chromeFrame.addView(fsBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(0, 0, 0, ui.dp(6)) })
        // زرار القفل الصغير: بيظهر لما تلمس الشاشة وهي مقفولة
        lockOv = ui.fsCircle("🔒") { setLock(false) }.apply { visibility = View.GONE }
        videoBox.addView(lockOv, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(22), 0, 0, ui.dp(22)) })
        videoBox.addView(chromeFrame, FrameLayout.LayoutParams(-1, -1))
        // زرار ⛶ (.fullscreen-btn): أسفل يسار الفيديو 10dp في الرأسي، 18dp في الشاشة الكاملة
        fsBtnV = ui.fsCircle("⛶") { toggleFs(); showChrome() }
        fsBtnLp = FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.BOTTOM or Gravity.LEFT)
        videoBox.addView(fsBtnV, fsBtnLp)
        applyChromeFn = {
            val on = fullMode && chromeShown && !pipNow()
            chromeFrame.visibility = if (on) View.VISIBLE else View.GONE; if (!on) dismissPop()
            val cv = (!fullMode || chromeShown) && !pipNow()
            floatBar.visibility = if (!pipNow()) View.VISIBLE else View.GONE
            batchBtn.visibility = if (cv) View.VISIBLE else View.GONE
            updateTrChip()
            logHandle.visibility = if (cv) View.VISIBLE else View.GONE
            fsBtnV.visibility = if (!fullMode) View.VISIBLE else View.GONE   // في الشاشة الكاملة ⛶ جوه الشريط السفلي
            subLp.bottomMargin = if (on) ui.dp(128) else ui.dp(12); sub.requestLayout()
        }

        // ---- لمس الفيديو: لمسة = إظهار/إخفاء الشريط (أو تشغيل/إيقاف في الوضع الرأسي)، لمستين = ±10ث، سحب رأسي = صوت (يمين) / سطوع (شمال) ----
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var volF = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var briF = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
        var scrollLogged = false
        var hScrub = false; var scrubBase = 0L; var scrubTarget = 0L; var fast2x = false
        // ---- تحريك الفيديو لحظيًا مع الصباع (سحب أفقي على الفيديو أو سحب شريط التقدم) ----
        var lastScrub = 0L
        fun fastSeek(on: Boolean) { try { player.setSeekParameters(if (on) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT) } catch (_: Exception) {} }
        fun scrubTo(posMs: Long) { val now = System.currentTimeMillis(); if (now - lastScrub >= 90) { lastScrub = now; player.seekTo(posMs) } }
        fun deltaTxt(ms: Long) = (if (ms >= 0) "+" else "−") + PlayerLogic.clock(Math.abs(ms))
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scrollLogged = false; hScrub = false; scrubBase = player.currentPosition; dismissPop(); dismissStrip(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (hScrub || fast2x) return
                fast2x = true; try { player.setPlaybackSpeed(2f) } catch (_: Exception) {}
                log("👆 ضغطة مطولة: 2×")
                giShow("2× ⏩", Gravity.CENTER)
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!fullMode) { log("👆 ضغطة: تشغيل/إيقاف"); togglePlay() }
                else {
                    // في الأفقي: ضغطة على الوسط = تشغيل/إيقاف، وأي مكان تاني = إظهار/إخفاء الشريط
                    val center = Math.abs(e.x - videoBox.width / 2f) < ui.dp(48) && Math.abs(e.y - videoBox.height / 2f) < ui.dp(48)
                    if (center) { log("👆 ضغطة وسط: تشغيل/إيقاف"); togglePlay() }
                    else if (chromeShown) { log("👆 ضغطة: إخفاء الشريط"); h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
                    else { log("👆 ضغطة: إظهار الشريط"); showChrome() }
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val right = e.x > videoBox.width / 2f
                log(if (right) "👆👆 ضغطتين يمين: +10ث" else "👆👆 ضغطتين شمال: -10ث")
                player.seekTo((player.currentPosition + (if (right) 10000 else -10000)).coerceAtLeast(0))
                giShow(if (right) "10 ⏩" else "⏪ 10", (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (fast2x) return true
                if (e1 == null) return false
                // سحب أفقي على الفيديو: الفيديو بيتحرك معاك وانت ماسك (عرض الشاشة كله ≈ 3 دقايق)، ويثبت لما ترفع صباعك
                if (hScrub || !scrollLogged && Math.abs(e2.x - e1.x) > ui.dp(16) && Math.abs(e2.x - e1.x) > Math.abs(e2.y - e1.y) * 1.2f && durMs > 0) {
                    if (!hScrub) { hScrub = true; scrubBase = player.currentPosition; fastSeek(true); h.removeCallbacks(hideChrome); log("↔ سحب أفقي: تقديم/ترجيع") }
                    val span = minOf(durMs, 180_000L)
                    scrubTarget = (scrubBase + (e2.x - e1.x) / videoBox.width.coerceAtLeast(1) * span).toLong().coerceIn(0L, maxOf(0L, durMs))
                    scrubTo(scrubTarget)
                    giShow(PlayerLogic.clock(scrubTarget) + "  (" + deltaTxt(scrubTarget - scrubBase) + ")", Gravity.CENTER)
                    return true
                }
                if (Math.abs(dy) < Math.abs(dx)) return false
                val d = dy / videoBox.height.coerceAtLeast(1) * 1.3f
                if (!scrollLogged) { scrollLogged = true; log(if (e1.x > videoBox.width / 2f) "↕ سحب: الصوت" else "↕ سحب: السطوع") }
                if (e1.x > videoBox.width / 2f) {
                    volF = (volF + d).coerceIn(0f, 1f)
                    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volF * mx), 0)
                    indShow(volInd, volF)
                } else {
                    briF = (briF + d).coerceIn(0.02f, 1f)
                    val wl = window.attributes; wl.screenBrightness = briF; window.attributes = wl
                    indShow(briInd, briF)
                }
                return true
            }
        })
        // ===== سحب التزامن: دوس على الترجمة واسحب يمين = تتأخر، شمال = تتقدّم (كل ~18dp = 0.1 ث) — بيتحفظ لكل فيديو =====
        var syncCand = false; var syncing = false; var sx = 0f; var syncStart = 0L
        fun hitSub(x: Float, y: Float): Boolean {
            if (pipNow() || !ccOn || sub.visibility != View.VISIBLE || sub.height <= 0) return false
            val slop = ui.dp(28)
            val cx = (sub.left + sub.right) / 2f; val half = maxOf(sub.boxWidthPx() / 2f, ui.dp(60).toFloat()) + slop
            return Math.abs(x - cx) <= half && y >= sub.top - slop && y <= sub.bottom + slop
        }
        gestureLayer.setOnTouchListener { _, ev ->
            if (uiLocked) {
                if (ev.actionMasked == MotionEvent.ACTION_UP && ev.eventTime - ev.downTime < 300) { lockOv.visibility = View.VISIBLE; h.removeCallbacks(hideLockOv); h.postDelayed(hideLockOv, 2500) }
                return@setOnTouchListener true
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (fast2x) { fast2x = false; try { player.setPlaybackSpeed(speed) } catch (_: Exception) {}; gi.visibility = View.GONE }
                if (hScrub) { hScrub = false; fastSeek(false); player.seekTo(scrubTarget); if (ev.actionMasked == MotionEvent.ACTION_UP && !syncing) showChrome() }
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { syncing = false; syncCand = hitSub(ev.x, ev.y); sx = ev.x; syncStart = offsetMs }
                MotionEvent.ACTION_MOVE -> if (syncCand && (syncing || Math.abs(ev.x - sx) > ui.dp(14))) {
                    if (!syncing) {
                        syncing = true; log("↔ سحب على الترجمة: تزامن")
                        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; gd.onTouchEvent(c); c.recycle()
                        h.removeCallbacks(hideChrome)
                    }
                    val steps = Math.round((ev.x - sx) / ui.dp(18).toFloat())
                    val target = (syncStart + steps * 100L).coerceIn(-60000L, 60000L)
                    if (target != offsetMs) setOff(target - offsetMs)
                    giShow(String.format("⏱ %+.1fs", offsetMs / 1000.0) + (if (offsetMs > syncStart) "  تأخير" else if (offsetMs < syncStart) "  تقديم" else ""), Gravity.CENTER)
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (syncing) { syncing = false; syncCand = false; if (fullMode) showChrome(); return@setOnTouchListener true }
            }
            gd.onTouchEvent(ev); true
        }

        // ---- الكبسولة تحت الفيديو (الوضع الرأسي) ----
        ctl.play.setOnClickListener { togglePlay() }
        ctl.speed.setOnClickListener { cycleSpeed() }
        ctl.cc.setOnClickListener { toggleCc() }
        ctl.exp.setOnClickListener { doExport() }
        ctl.rot.setOnClickListener { toggleFs() }
        ctl.prev.setOnClickListener { val t = player.currentPosition - offsetMs; val i = starts.indexOfLast { it < t - 1500 }; player.seekTo(if (i >= 0) starts[i] + offsetMs else 0L) }
        ctl.next.setOnClickListener { val t = player.currentPosition - offsetMs; val i = starts.indexOfFirst { it > t + 200 }; if (i >= 0) player.seekTo(starts[i] + offsetMs) }
        ctl.vol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) player.volume = p / 100f }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        fun seekFrac(f: Float) { if (durMs > 0) player.seekTo((f * durMs).toLong()) }
        // سحب شريط التقدم: الفيديو بيتحرك معاك لحظيًا + بيكتب الوقت والفرق، ويثبت لما ترفع صباعك
        var barStart = 0L
        val barDrag: (Boolean) -> Unit = { on -> if (on) { barStart = player.currentPosition; fastSeek(true); h.removeCallbacks(hideChrome) } else fastSeek(false) }
        val barScrub: (Float) -> Unit = { f -> if (durMs > 0) { val t = (f * durMs).toLong(); scrubTo(t); giShow(PlayerLogic.clock(t) + "  (" + deltaTxt(t - barStart) + ")", Gravity.CENTER) } }
        ctl.prog.onDrag = barDrag; ctl.prog.onScrub = barScrub
        fsProgV.onDrag = barDrag; fsProgV.onScrub = barScrub
        ctl.prog.onSeek = { f -> seekFrac(f) }
        fsProgV.onSeek = { f -> seekFrac(f); showChrome() }

        // ---- الجمل واللوجز (نوافذ سفلية بدل اللوحة اللي تحت الفيديو) ----
        logTv = ui.text("", 11f, th.text).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
        logSv = ScrollView(this).apply { addView(logTv); layoutParams = LinearLayout.LayoutParams(-1, -1) }
        adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
                val q = list[i]
                val row = LinearLayout(this@PlayerActivity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(2), 0, ui.dp(2)) }
                val bar = View(this@PlayerActivity).apply { setBackgroundColor(if (q.gender == "female") SubStyle.FEMALE else SubStyle.MALE) }
                val col = LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
                col.addView(ui.text(PlayerLogic.clock((q.start * 1000).toLong()), 10f, th.muted))
                col.addView(ui.text(q.translated.ifEmpty { q.original }, 14f, th.text))
                if (q.original.isNotBlank() && q.original != q.translated) col.addView(ui.text(q.original, 11f, th.muted))
                row.addView(bar, LinearLayout.LayoutParams(ui.dp(4), -1)); row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                row.setBackgroundColor(if (i == curIdx) (th.primary and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
                return row
            }
        }
        listView = ListView(this).apply {
            this.adapter = this@PlayerActivity.adapter; divider = android.graphics.drawable.ColorDrawable(th.border); dividerHeight = 1
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            setOnItemClickListener { _, _, i, _ -> player.seekTo(starts[i] + offsetMs) }
        }
        counters = ui.text("", 10f, th.muted).apply { setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(2)) }
        sentDlg = ui.sheet(this, "📝 الجمل", listOf<View>(listView), true)
        sentDlg.setOnShowListener { adapter.notifyDataSetChanged() }
        val logCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = LinearLayout.LayoutParams(-1, -1) }
        fun logChip(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(th.text); gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6); topMargin = ui.dp(4) }; setOnClickListener { f() }
        }
        fun logShow(m: Int) {
            logMode = m
            logTv.text = when (m) {
                1 -> LogStore.prevText(80000).ifBlank { "مفيش لوج لجلسة سابقة" }
                2 -> LogStore.crashText().ifBlank { "مفيش كراش متسجّل" }
                else -> synchronized(logBuf) { logBuf.toString() }
            }
            logSv.post { if (m == 0) logSv.fullScroll(View.FOCUS_DOWN) else logSv.scrollTo(0, 0) }
        }
        fun logCopy() {
            val body = when (logMode) { 1 -> LogStore.prevText(150000); 2 -> LogStore.crashText(); else -> LogStore.currentText(150000) }
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("log", LogStore.header(this) + "\n\n" + body))
            Toast.makeText(this, "📋 اللوج اتنسخ", Toast.LENGTH_SHORT).show()
        }
        val logRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) }
        logRow.addView(logChip("📋 نسخ") { logCopy() }); logRow.addView(logChip("الحالي") { logShow(0) })
        logRow.addView(logChip("🕘 الجلسة اللي فاتت") { logShow(1) }); logRow.addView(logChip("💥 آخر كراش") { logShow(2) })
        logCol.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(logRow) }, LinearLayout.LayoutParams(-1, -2))
        logSv.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); logCol.addView(logSv)
        logDlg = ui.sheet(this, "📜 اللوجز", listOf<View>(logCol), true)
        logDlg.setOnShowListener { logShow(0) }
        mem = ui.memRow()

        fun menuItems(mk: (String, String, () -> Unit) -> View): List<View> = listOf<View>(
            mk("📝", "الجمل") { sentDlg.show() },
            mk("📜", "اللوجز") { logDlg.show() },
            mk("🕳", "سد الفجوات") { engine.retryFailed(); Toast.makeText(this, "بحاول أسد الفجوات", Toast.LENGTH_SHORT).show() },
            mk("📤", "تصدير SRT") { doExport() },
            mk("📥", "استيراد SRT") { doImport() },
            mk("📂", "فتح فيديو") { doOpen() },
            mk("⧉", "نافذة عائمة") { enterPip() },
            mk("⚙️", "الإعدادات") { openSettings() },
            mk("🗂", "ترجمات الفيديو") { versionsDialog() },
            mk("🔄", "إعادة ترجمة") { batchDialog() },
            mk("🌙", "ترجمة بالخلفية") { translateInBackground() },
            mk("🔥", "لهجة أقوى") { runTool("زيادة شدة اللهجة", "زوّد شدة اللهجة الشعبية في كل جملة درجة واحدة: ألفاظ وتعبيرات الشارع والعامية المحلية (${conf.lang}) بدل الفصحى والكلام الرسمي، من غير ما تغيّر المعنى أو الجنس.", true) },
            mk("😐", "عائلي / صريح") { familyDialog() },
            mk("🌐", "لهجة لايف") { liveDialectDialog() },
            mk("🔧", "صحّح الضمائر") { pronounsNow() },
            mk("🧠", "دمج مكرر") { val n = engine.removeDuplicates(); Toast.makeText(this, if (n > 0) "اتدمجت $n جملة مكررة" else "مفيش جمل مكررة متداخلة", Toast.LENGTH_SHORT).show(); curIdx = -2 },
            mk("📜", "ذكّرني") { recapDialog() }
        )
        // ---- القايمة الجانبية (أفقي): نفس أزرار اللي تحت المشغل، 3 في الصف وتحتهم صف تاني وهكذا. أي زرار بيفتح صفحة كاملة، والرجوع بيرجّع خطوة ----
        val keepOpen = setOf("إعادة ترجمة", "الجمل", "اللوجز", "ترجمات الفيديو", "عائلي / صريح", "لهجة لايف", "ذكّرني", "الوضع البصري", "الإعدادات")
        val sideGrid = ui.grid(menuItems { i, l, f -> ui.sideBtn(i, l) { if (l !in keepOpen) closeSide(); f() } }, 3)
        sidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10)); background = ui.box(th.card, th.border, 0)
            isClickable = true
        }
        val sideHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, ui.dp(8)) }
        sideHead.addView(ui.text("☰ القائمة", 16f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        sideHead.addView(TextView(this).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); setOnClickListener { closeSide() } })
        sidePanel.addView(sideHead, LinearLayout.LayoutParams(-1, -2))
        sidePanel.addView(ScrollView(this).apply { addView(sideGrid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sideMenuV = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0x99000000.toInt()); isClickable = true; setOnClickListener { closeSide() } }
        sideMenuV.addView(sidePanel, FrameLayout.LayoutParams(ui.dp(300), -1, Gravity.RIGHT))
        val extrasCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(2), 0, ui.dp(20)) }
        extrasCol.addView(ctl.root)
        extrasCol.addView(makeTrRow(false), LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
        val pRow = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(ui.button("🔄 إعادة ترجمة") { batchDialog() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
            addView(ui.button("🌙 ترجمة بالخلفية") { translateInBackground() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        }
        extrasCol.addView(pRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
        extrasCol.addView(counters); extrasCol.addView(mem.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        val scroll = ScrollView(this).apply { addView(extrasCol) }
        extras = scroll
        page.addView(videoBox, LinearLayout.LayoutParams(-1, ui.dp(220)))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val rootFrame = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        rootFrame.addView(page, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(sideMenuV, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)
        applyFull(true)   // شاشة كاملة دايمًا (أفقي ورأسي) — الأزرار في الشريط العلوي وزرار ☰ القائمة
        // فيديو اتفتح من فولدرات الجهاز: يشتغل لاندسكيب مباشرة زي MX Player
        // الاتجاه الأولي من شكل الفيديو (من المكتبة) — وبعدها بيتصحح لوحده أول ما الأبعاد الحقيقية تظهر (onVideoSizeChanged)
        if (intent.getBooleanExtra("landscape", false) && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        videoBox.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) applyFit(sv, videoBox) }

        buildPlayer()

        // المحرك + استرجاع التقدم المحفوظ
        freshOnce = intent.getBooleanExtra("fresh", false)
        initEngine()
        if (Build.VERSION.SDK_INT >= 33) { try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7) } catch (_: Exception) {} }
        KeepAliveService.start(this)

        h.post(object : Runnable {
            override fun run() {
                cur = player.currentPosition
                val d = player.duration
                if (d > 0) durMs = d
                val frac = if (durMs > 0) (cur.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                val cov = if (durMs > 0) (engine.coveredSec() * 1000.0 / durMs).toFloat().coerceIn(0f, 1f) else 0f
                val pr = if (fullMode) fsProgV else ctl.prog
                if (!pr.dragging) pr.played = frac
                pr.translated = cov
                if (durMs > 0) {
                    val sr = engine.subs; val pn = engine.pendingCount()
                    if (sr !== segSubsRef || pn != segPendN) { segSubsRef = sr; segPendN = pn; segCov = engine.coverageSegs(); segPend = engine.pendingSegs() }
                }
                pr.durSec = durMs / 1000.0; pr.cov = segCov; pr.pend = segPend
                pr.invalidate()
                val tEl = PlayerLogic.clock(cur); val tDu = PlayerLogic.clock(durMs)
                if (fullMode) { fsEl.text = tEl; fsDu.text = tDu } else { ctl.tEl.text = tEl; ctl.tDur.text = tDu }
                val now = System.currentTimeMillis()
                if (dirty && now - lastRefresh > 1000) { dirty = false; lastRefresh = now; refreshList(); curIdx = -2 }
                visNow = (cur - offsetMs) / 1000.0
                visOv.showBoxes(visual.boxesAt(visNow))
                val act = PlayerLogic.activeIndices(spStarts, spEnds, cur, offsetMs).map { spMap[it] }
                val sact = PlayerLogic.activeIndices(sdStarts, sdEnds, cur, offsetMs, 400L, 2).map { sdMap[it] }
                val idx = act.lastOrNull() ?: -1
                val gs = PlayerLogic.orderSpeakers(act.map { list[it] })
                // جملة طويلة واحدة: بتتقسم لأجزاء بتظهر بالتتابع على مدة الجملة (التوقيت الأصلي ثابت)
                var parts: List<String> = emptyList(); var part = 0
                val ssn = sub.style
                if (ccOn && gs.size == 1) {
                    var byChars = false
                    if (ssn.punctOn) { parts = PlayerLogic.splitPunct(gs[0].translated); byChars = true }
                    if (parts.size <= 1 && ssn.splitOn) { parts = PlayerLogic.splitParts(gs[0].translated, ssn.splitThresh); byChars = false }
                    if (parts.size > 1) parts = PlayerLogic.adaptParts(parts, ends[idx] - starts[idx], byChars)
                    if (parts.size > 1) part = PlayerLogic.partIndex(starts[idx], ends[idx], cur - offsetMs, parts, byChars)
                }
                val key = act.joinToString(",") + ":" + part
                if (idx != curIdx || key != curKey) {
                    curIdx = idx; curKey = key
                    val shown: Sub? = when {
                        !ccOn || gs.isEmpty() -> null
                        parts.size > 1 -> gs[0].copy(translated = parts[part], isContinuation = gs[0].isContinuation && part == parts.size - 1)
                        else -> PlayerLogic.combine(gs)
                    }
                    sub.show(shown, gs)
                    if (pipNow()) pipOv?.set(if (shown == null) "" else sub.style.mainText(shown))
                    if (sentDlg.isShowing) adapter.notifyDataSetChanged()
                    if (idx >= 0 && sentDlg.isShowing && !listView.isPressed) listView.smoothScrollToPositionFromTop(idx, ui.dp(30))
                }
                val sTxt = if (!ccOn || sact.isEmpty()) "" else sact.joinToString("   ") { list[it].translated }
                if (sTxt != soundKey) { soundKey = sTxt; soundTv.text = sTxt; soundTv.visibility = if (sTxt.isEmpty()) View.GONE else View.VISIBLE }
                st.text = status
                if (now - lastBatch > 700) { lastBatch = now; batchTv.text = engine.batchLines(); updateProblems() }
                if (now - lastBeat > 20000) { lastBeat = now; LogStore.add("💓 ${LogStore.heapLine()} · ${if (player.isPlaying) "بيشتغل" else "واقف"} @${fmtMs(cur)} · مترجم ${(engine.coveredSec() / 60).toInt()}د · ${engine.subs.size} جملة") }
                if (fullMode) fsBadge.set(PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toString() + "%", list.size.toString() + " جملة")
                if (now - lastMem > 4000) { lastMem = now; if (!fullMode) mem.update(this@PlayerActivity) }
                counters.text = "جمل ${list.size} · تغطية ${PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0)}% · فجوات ${engine.failedCount()} · كوتة ${Quota.used(conf.model)}/${Models.quotaOf(conf.model)}"
                if (logDlg.isShowing && logMode == 0) logTv.text = synchronized(logBuf) { logBuf.toString() }
                h.postDelayed(this, 200)
            }
        })
        engineStarted = false; updateTr()
        if (askSaved) videoBoxRef.post { showResumeStrip() }
    }

    private fun buildPlayer() {
        val sv = svRef; val videoBox = videoBoxRef
        val factory = if (uri != null) DefaultMediaSourceFactory(this)
        else DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true))
        // MKV وغيره: لو الديكودر الأول فشل (HEVC / 10-bit) جرّب اللي بعده بدل شاشة سودا
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf).setMediaSourceFactory(factory).build()
        player.setVideoSurfaceView(sv)
        player.setPlaybackSpeed(speed)
        var firstFrame = false
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    vidW = v.width; vidH = v.height; videoBox.post { applyFit(sv, videoBox) }
                    // الفيديو يفتح على شكله الحقيقي: طولي → رأسي، عريض → أفقي (مرة واحدة، ومالهاش دعوة لو إنت بدّلت بإيدك)
                    if (!userRot && !autoRotDone && !pipNow()) {
                        autoRotDone = true
                        val wantLand = v.width >= v.height
                        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        if (wantLand != isLand) requestedOrientation = if (wantLand) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
            }
            override fun onIsPlayingChanged(p: Boolean) {
                val t = if (p) "⏸" else "▶"; ctl.play.text = t; fsPlayB.text = t; centerPlay.visibility = if (p || pipNow()) View.GONE else View.VISIBLE
                // تشخيص الشاشة السودا: صوت شغّال ومفيش ولا فريم فيديو اتعرض بعد ٥ ثواني
                if (p && !firstFrame) h.postDelayed({ if (!firstFrame && !isFinishing) log("⚠️ مفيش فريم فيديو اتعرض بعد ٥ ثواني — غالبًا كودك/بروفايل الفيديو مش مدعوم على الجهاز (مثلًا HEVC 10-bit)") }, 5000)
            }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onTracksChanged(t: Tracks) {
                for (g in t.groups) {
                    if (g.type != C.TRACK_TYPE_VIDEO || g.length == 0) continue
                    val f = g.getTrackFormat(0)
                    val desc = "${f.sampleMimeType ?: "?"} ${f.width}x${f.height}"
                    if (g.isSelected) log("🎞 فيديو: $desc")
                    else if (!g.isSupported) { log("⚠️ كودك الفيديو ($desc) مش مدعوم على الجهاز ده — هيشتغل صوت من غير صورة"); say("كودك الفيديو ($desc) مش مدعوم على جهازك") }
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                log("❌ خطأ في التشغيل: ${e.errorCodeName} — ${e.cause?.message ?: e.message}")
                say("خطأ في تشغيل الفيديو — التفاصيل في 📜 اللوجز")
            }
        })
        player.setMediaItem(MediaItem.fromUri(uri ?: Uri.parse(url!!)))
        player.prepare(); player.playWhenReady = true
    }

    private var freshOnce = false
    /** يشغّل المحرك. لو الدخول كان «ابدأ من الأول وجديد»: المسح والحفظ على خيط الخلفية قبل ما الحلقة تبدأ (مش على الـ UI) */
    private fun startEngine() {
        val fresh = freshOnce; freshOnce = false
        if (fresh) { try { player.seekTo(0) } catch (_: Exception) {}; cur = 0L }
        Thread {
            if (fresh) try { engine.redoAll() } catch (e: Throwable) { log("⚠ " + (e.message ?: e.toString()).take(120)) }
            engine.run()
        }.apply { isDaemon = true }.start()
    }

    private fun initEngine() {
        vid = videoId()
        offsetMs = Cfg.str("sub_offset_ms:$vid", "0").toLongOrNull() ?: 0L
        runCatching { offFsB?.text = String.format("%+.1fs", offsetMs / 1000.0) }
        lastSrtN = -1
        val wasBg = BgJobs.isActive(vid)
        if (wasBg) BgJobs.stopAndWait(vid, 2500)
        val store = Store(File(filesDir, "progress"), Store.keyFor(vid))
        val pb = PromptBuilder { p -> assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        engine = Engine(conf, { makeSource() }, store, this, pb)
        engine.convDialect = Cfg.str("conv_dialect", "")
        Live.engine = engine
        visual = VisualMode(conf, { player.currentPosition / 1000.0 }, { makeRetriever() }, { m -> if (m.startsWith("ضيف مفتاح") || m.contains("مش مدعوم")) runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }, { })
        val savedPos = engine.load()
        if (savedPos > 5.0 && !freshOnce) { player.seekTo((savedPos * 1000).toLong()); cur = (savedPos * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}") }
        refreshList()
        LogStore.add("🎬 فتح فيديو $vid · ${LogStore.heapLine()}")
        maybePrompt(wasBg)
    }

    fun toggleLog() {
        logOn = !logOn
        logDrawer.animate().translationX(if (logOn) 0f else -(batchTv.width + ui.dp(2)).toFloat()).setDuration(220).start()
        logHandle.text = if (logOn) "◂" else "▸"
    }

    /** الحلقة اللي بعدها (+1) أو اللي قبلها (-1) من نفس الفولدر: بيوقف ترجمة الحالية ويبدأ ترجمة الجديدة */
    fun stepEpisode(d: Int) {
        val curU = uri?.toString()
        val all = VideoScan.cache
        if (curU == null || all == null) { say("الفيديو ده مش من مكتبة الجهاز"); return }
        val me = all.firstOrNull { it.uri == curU } ?: run { say("الفيديو ده مش من مكتبة الجهاز"); return }
        val sib = VideoLib.sortVideos(all.filter { it.folderKey == me.folderKey }, VideoLib.SORT_NAME)
        val t = sib.getOrNull(sib.indexOfFirst { it.uri == curU } + d) ?: run { say(if (d > 0) "دي آخر حلقة في الفولدر" else "دي أول حلقة في الفولدر"); return }
        Cfg.put("lib_last", t.uri); Cfg.put("lib_last_f:" + t.folderKey, t.uri)
        say("▶ " + t.title)
        swapVideo(Uri.parse(t.uri))
    }

    /** بدّل الفيديو في نفس الشاشة من غير ما تفتح Activity جديدة */
    fun swapVideo(newUri: Uri) {
        saveRecent()
        try { visual.stop() } catch (_: Exception) {}
        try { engine.stop() } catch (_: Exception) {}
        try { engine.saveNow() } catch (_: Exception) {}
        try { player.release() } catch (_: Exception) {}
        uri = newUri; url = null; hdr.clear()
        cur = 0L; durMs = 0L; vidW = 0; vidH = 0; curIdx = -2; curKey = ""; dirty = true
        synchronized(logBuf) { logBuf.setLength(0) }
        status = ""; conf = Cfg.snapshot()
        try { sub.show(null) } catch (_: Exception) {}
        visOv.showBoxes(emptyList())
        resumePending = false; applyCard()
        engineStarted = false; autoRotDone = false
        buildPlayer(); initEngine()
        updateTr()
    }

    /** أفقي = ملء الشاشة (شريط الأزرار العلوي + كبسولة التقدم تظهر بلمسة)، رأسي = فيديو فوق والكبسولة وشبكة الأزرار تحت */
    private fun applyFull(f: Boolean) {
        if (pipNow()) return   // في PiP الشاشة بتتغير مقاسها (config) وماينفعش نرجّع الأزرار
        fullMode = f
        if (!f) closeSide()
        fitFsB?.text = "⬛ " + PlayerLogic.fitNames[curFit()]; if (::svRef.isInitialized) applyFit(svRef, videoBoxRef)
        extras.visibility = if (f) View.GONE else View.VISIBLE
        val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams
        val dm = resources.displayMetrics
        lp.height = if (f) -1 else (minOf(dm.widthPixels, dm.heightPixels) - ui.dp(32)) * 9 / 16
        if (f) lp.setMargins(0, 0, 0, 0) else lp.setMargins(ui.dp(16), ui.dp(32), ui.dp(16), 0)
        videoBoxRef.layoutParams = lp
        videoBoxRef.background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = if (f) 0f else ui.dp(14).toFloat() }
        fsBtnLp.setMargins(ui.dp(10), 0, 0, ui.dp(10)); fsBtnV.layoutParams = fsBtnLp
        fsOnly.forEach { it.visibility = if (f) View.VISIBLE else View.GONE }
        if (!f) assistMenuV.visibility = View.GONE
        st.visibility = if (f) View.GONE else View.VISIBLE
        if (::logDrawer.isInitialized) { (logDrawer.layoutParams as FrameLayout.LayoutParams).topMargin = if (f) ui.dp(64) else ui.dp(26); logDrawer.visibility = View.VISIBLE; logDrawer.requestLayout() }
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = if (f) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else (if (Build.VERSION.SDK_INT >= 23 && th.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        placeCard()
        if (f) showChrome() else { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
    }
    fun showChrome() { if (pipNow() || uiLocked) return; chromeShown = true; applyChromeFn(); h.removeCallbacks(hideChrome); h.postDelayed(hideChrome, 3500) }
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }
    private var userRot = false
    private var autoRotDone = false
    fun toggleFs() {
        userRot = true
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    /** مكان وحجم نافذة الـ PiP على الشاشة [x, y, w, h] (من الـ decor بتاع الأكتيفيتي) */
    fun pipRect(): IntArray? = try {
        val v = window.decorView; val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (v.width > 0 && v.height > 0) intArrayOf(loc[0], loc[1], v.width, v.height) else null
    } catch (_: Exception) { null }
    fun canOverlay() = Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)
    fun enterPip(fromUser: Boolean = true) {
        // شريط الترجمة بره نافذة PiP محتاج إذن "العرض فوق التطبيقات" (مرة واحدة)
        if (fromUser && !canOverlay() && !Cfg.str("pip_overlay_asked", "0").equals("1")) {
            Cfg.put("pip_overlay_asked", "1")
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات» لمترجم الفيديو عشان الترجمة تظهر فوق، وارجع دوس ⧉ تاني", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 26) try {
            val pb = PictureInPictureParams.Builder()
            if (vidW > 0 && vidH > 0) pb.setAspectRatio(Rational((vidW.toFloat() / vidH).coerceIn(0.45f, 2.3f).times(1000).toInt(), 1000))
            if (Build.VERSION.SDK_INT >= 31) try { pb.setSeamlessResizeEnabled(true) } catch (_: Throwable) {}
            enterPictureInPictureMode(pb.build())
        } catch (_: Exception) { Toast.makeText(this, "PiP مش مدعوم على الجهاز ده", Toast.LENGTH_SHORT).show() }
    }
    private var internalNav = false
    override fun startActivity(i: Intent?) { internalNav = true; super.startActivity(i) }
    @Suppress("DEPRECATION")
    override fun startActivityForResult(i: Intent?, rc: Int) { internalNav = true; super.startActivityForResult(i, rc) }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!internalNav && !isFinishing && Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode && try { player.isPlaying } catch (_: Exception) { false }) enterPip(false)
    }
    fun doExport() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "subtitles.srt") }, 7) }
    fun doImport() { startActivityForResult(filePicker("*/*", "اختار ملف SRT", false), 9) }
    fun doOpen() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 8) }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); if (!pipNow()) applyFull(true) }
    override fun onPictureInPictureModeChanged(inPipNow: Boolean, c: Configuration) {
        super.onPictureInPictureModeChanged(inPipNow, c)
        inPip = inPipNow
        if (inPipNow) { try {
            closeSide(); dismissPopFn()
            h.removeCallbacks(hideChrome); chromeShown = false
            extras.visibility = View.GONE; st.visibility = View.GONE; floatBar.visibility = View.GONE; batchBtn.visibility = View.GONE; trChipV?.visibility = View.GONE; closeBatchPanel(); logDrawer.visibility = View.GONE; logHandle.visibility = View.GONE
            fsOnly.forEach { it.visibility = View.GONE }; assistMenuV.visibility = View.GONE; centerPlay.visibility = View.GONE
            visOv.showBoxes(emptyList())
            val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams; lp.height = -1; lp.setMargins(0, 0, 0, 0); videoBoxRef.layoutParams = lp; fsBtnV.visibility = View.GONE
            videoBoxRef.findViewWithTag<View>("chromeFrame")?.visibility = View.GONE
            sub.suppressed = true; applyCard()
            applyFit(svRef, videoBoxRef)
            // الترجمة في شريط صغير فوق الشاشة (تحت الستاتس بار) بدل جوه الفيديو
            if (canOverlay()) { pipOv = PipSubBar(this) { pipRect() }.also { it.show() } }
            curIdx = -2
        } catch (_: Exception) {} }
        else {
            pipOv?.hide(); pipOv = null
            sub.suppressed = false; curIdx = -2; applyCard()
            applyFull(true)
            // لو الخروج من PiP كان بالـ ✕ (الأكتيفيتي مش ظاهرة) نقفل الفيديو ونخرج؛ لو كان توسيع، onResume بيلغي الفحص
            h.removeCallbacks(pipExitCheck); h.postDelayed(pipExitCheck, 500)
        }
    }

    private fun applyFit(sv: SurfaceView, box: View) {
        if (vidW == 0 || box.width == 0) return
        val (w, hh) = PlayerLogic.fitSize(box.width, box.height, vidW, vidH, if (pipNow()) 0 else curFit())
        sv.layoutParams = FrameLayout.LayoutParams(w, hh, Gravity.CENTER)
    }

    private fun videoId(): String {
        val u = uri
        if (u != null) {
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1)
                }
            } catch (_: Exception) {}
            return "f:$u"
        }
        return "u:" + (url ?: "").substringBefore('?')
    }

    private fun makeSource(): AudioSource {
        val lg: (String) -> Unit = { log(it) }
        val u = url
        return if (uri == null && u != null && u.contains(".m3u8", true)) HlsSource(applicationContext, u, hdr, conf.audioTrack, lg)
        else FileSource(applicationContext, uri, u, hdr, conf.audioTrack, lg)
    }

    fun fmtMs(ms: Long) = String.format("%02d:%02d:%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 8 && c == RESULT_OK) d?.data?.let { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            swapVideo(u)
        }
        if (r == 9 && c == RESULT_OK) d?.data?.let { u ->
            val parsed = try { PlayerLogic.parseSrt(contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "") } catch (_: Exception) { emptyList() }
            if (parsed.isEmpty()) Toast.makeText(this, "الملف مش SRT صالح", Toast.LENGTH_SHORT).show()
            else if (engine.subs.isNotEmpty()) Toast.makeText(this, "فيه ترجمة موجودة بالفعل — الاستيراد بيشتغل على فيديو من غير ترجمة بس", Toast.LENGTH_LONG).show()
            else {
                engine.importSubs(parsed.map { Sub(it.first, it.second, it.third, it.third, "male", "unknown", "none", emptyList(), emptyList(), false, false) })
                dirty = true; Thread { engine.saveNow() }.start()
                Toast.makeText(this, "اتستورد ${parsed.size} جملة", Toast.LENGTH_SHORT).show()
            }
        }
        if (r == 7 && c == RESULT_OK) d?.data?.let { u ->
            contentResolver.openOutputStream(u)?.use { it.write(PlayerLogic.toSrt(engine.subs, offsetMs).toByteArray()) }
            Toast.makeText(this, "تم حفظ SRT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveRecent() { if (!handedOff) saveRecentForce() }
    private fun saveRecentForce() {
        try {
            val f = File(filesDir, "recent.json")
            val old = Recents.parse(try { f.readText() } catch (_: Exception) { "" })
            val r = Recent(vid, Recents.titleOf(vid), url ?: "", uri?.toString() ?: "", cur / 1000.0, durMs / 1000.0, engine.subs.size, engine.coveredSec(), System.currentTimeMillis())
            f.writeText(Recents.toJson(Recents.upsert(old, r, 300)))
        } catch (_: Exception) {}
    }
    /** أفقي: يرجع للرأسي من غير ما يقفل. رأسي: يحفظ التقدم ويوقف الفيديو ويقفل. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sideOpen) { closeSide(); return }
        try { player.pause() } catch (_: Exception) {}
        saveRecent(); Thread { engine.saveNow() }.start()
        super.onBackPressed()
    }
    // ===== أدوات الـ HTML اللي كانت ناقصة في النسخة النيتف =====
    private fun say(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    private fun touchSubs() = runOnUiThread { curIdx = -2; dirty = true }

    fun runTool(label: String, instruction: String, all: Boolean) {
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        say("بدأ: $label…")
        engine.rewriteAll(label, instruction, if (all) 0.0 else player.currentPosition / 1000.0, { m -> runOnUiThread { giShowFn(m) } }) { n ->
            say(if (n > 0) "✅ $label: اتغيّرت $n جملة (تقدر ترجع من 🗂 ترجمات الفيديو)" else "$label: مفيش جمل اتغيّرت"); touchSubs()
        }
    }
    var giShowFn: (String) -> Unit = {}

    fun pronounsNow() {
        say("🔧 بصحّح الضمائر…")
        engine.correctPronounsNow({ m -> runOnUiThread { giShowFn(m) } }) { n -> say(if (n > 0) "✅ اتصحّحت $n جملة" else "الضمائر سليمة (أو مفيش جدول شخصيات)"); touchSubs() }
    }

    fun familyDialog() {
        val d = android.app.Dialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.button("🧹 عائلي — نضّف الألفاظ الخارجة والإيحاءات") { d.dismiss(); runTool("عائلي", "نضّف الجملة من الألفاظ الخارجة والإيحاءات الجنسية وخليها عائلية ومناسبة لكل الأعمار مع الحفاظ على المعنى العام.", true) })
        box.addView(ui.button("🔞 صريح — طابق صراحة النص الأصلي بالظبط") { d.dismiss(); runTool("صريح", "رجّع الترجمة لمطابقة صراحة النص الأصلي بالظبط (الألفاظ والإيحاءات زي ما هي في الأصل من غير تلطيف ولا حذف).", true) })
        box.addView(ui.button("↩ رجّع آخر نسخة قبل التعديل") { d.dismiss(); versionsDialog() })
        d.setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18); addView(ui.text("😐 عادي / عائلي / صريح", 17f, th.primary, true)); addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    private var langPop: android.widget.PopupWindow? = null
    /** 🌐 لغة الترجمة: قايمة صغيرة تحت الزرار بنفس شكل قوايم الشريط. فصحى = ترجمة حرفية بس، غير كده = حرفية + تحويل تلقائي كل ~3 باتشات */
    fun langPopup(anchor: View) {
        langPop?.let { if (it.isShowing) { it.dismiss(); langPop = null; return } }
        val langs = listOf("فصحى", "مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني")
        val curL = Cfg.str("conv_dialect", "").ifBlank { "فصحى" }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(0xF2141418.toInt(), 0x33FFFFFF, 12)
        }
        for (l in langs) col.addView(ui.fsBtn((if (l == curL) "✓ " else "") + (if (l == "فصحى") "فصحى (حرفية)" else l)) { _ ->
            langPop?.dismiss()
            val v = if (l == "فصحى") "" else l
            Cfg.p.edit().putString("conv_dialect", v).apply()
            engine.setConvDialect(v, true)
            say(if (l == "فصحى") "الترجمة بالفصحى الحرفية" else "هتتحوّل الترجمة للهجة $l (كل ~3 باتشات) — وبيبدأ تحويل اللي اتترجم")
            touchSubs()
        }.apply { minimumWidth = ui.dp(150) })
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        col.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(170), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val avail = (resources.displayMetrics.heightPixels - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val pw = android.widget.PopupWindow(scroll, ui.dp(170), minOf(col.measuredHeight, avail), true)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (langPop === pw) langPop = null; showChrome() }
        pw.showAsDropDown(anchor, 0, ui.dp(2)); langPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)   // الشريط يفضل ظاهر وإنت بتختار
    }

    /** بانر تحذير: باتش ما اترجمش / رجع ناقص + زرار إعادة على المفاتيح الاحتياطية */
    lateinit var probBar: TextView
    private var probIdx = -1
    fun updateProblems() {
        val p = try { engine.problems() } catch (_: Exception) { emptyList() }
        if (p.isEmpty()) { probIdx = -1; probBar.visibility = View.GONE; return }
        val b = p[0]; probIdx = b.idx
        val what = if (b.mark == "❌") "ما اترجمش" else "رجع ناقص"
        probBar.text = "⚠ باتش ${b.idx + 1} $what" + (if (p.size > 1) " (+${p.size - 1})" else "") + " · 🔁 إعادة؟"
        probBar.visibility = View.VISIBLE
    }
    fun askRetryProblem() {
        val i = probIdx; if (i < 0) return
        android.app.AlertDialog.Builder(this).setTitle("إعادة ترجمة باتش ${i + 1}؟")
            .setMessage("هيتبعت على المفاتيح الاحتياطية.")
            .setPositiveButton("موافق") { _, _ -> engine.retryOnBackup(i); say("🔁 بعيد ترجمة باتش ${i + 1}…") }
            .setNegativeButton("لاحقًا", null).show()
    }

    fun liveDialectDialog() {
        val langs = listOf("مصري", "شامي", "لبناني", "خليجي", "مغربي", "عراقي", "سوداني", "فصحى")
        val strengths = listOf("خفيفة", "متوسطة", "شديدة")
        val styles = listOf("حرفي", "شعبي", "جرئ", "+18")
        var l = conf.lang; var st = conf.style; var sg = "متوسطة"; var scopeAll = false
        val d = android.app.Dialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("🌐 لهجة لايف — بيترجم النص الأصلي من جديد", 16f, th.primary, true))
        box.addView(ui.text("اللهجة", 13f, th.muted)); box.addView(ui.chips(langs, { l }) { l = it })
        box.addView(ui.text("الشدة", 13f, th.muted)); box.addView(ui.chips(strengths, { sg }) { sg = it })
        box.addView(ui.text("الأسلوب", 13f, th.muted)); box.addView(ui.chips(styles, { st }) { st = it })
        box.addView(ui.text("النطاق", 13f, th.muted)); box.addView(ui.chips(listOf("من هنا لآخر الفيديو", "الفيديو كله"), { if (scopeAll) "الفيديو كله" else "من هنا لآخر الفيديو" }) { scopeAll = it == "الفيديو كله" })
        box.addView(ui.button("طبّق", true) {
            d.dismiss()
            runTool("لهجة لايف ($l · $sg · $st)", "أعد كتابة translated من الصفر بالاعتماد على original (النص الأصلي) بلهجة $l وبشدة $sg وبأسلوب $st (حرفي = أقرب للمعنى، شعبي = كلام شارع، جرئ = أجرأ وأكتر حرية، +18 = صريح بلا تلطيف). حافظ على جنس المتكلم والمخاطَب.", scopeAll)
        })
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    fun versionsDialog() {
        val d = android.app.Dialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("🗂 ترجمات الفيديو (نسخ محفوظة في الجلسة)", 16f, th.primary, true))
        box.addView(ui.text("أي إعادة صياغة بتحفظ نسخة قبلها تلقائيًا. اضغط على نسخة عشان ترجّع نص الترجمة بتاعها.", 12f, th.muted))
        box.addView(ui.button("💾 احفظ النسخة الحالية") { engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); d.dismiss(); versionsDialog() })
        val vs = engine.versions.toList()
        if (vs.isEmpty()) box.addView(ui.text("مفيش نسخ محفوظة لسه", 13f, th.muted))
        vs.forEachIndexed { i, v ->
            box.addView(ui.button("${v.name} — ${v.subs.size} جملة") { val n = engine.applyVersion(i); say("اتطبّقت النسخة (اتغيّرت $n جملة)"); touchSubs(); d.dismiss() })
        }
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    fun recapDialog() {
        say("📜 بجهّز الملخص…")
        engine.recap(player.currentPosition / 1000.0, { m -> runOnUiThread { giShowFn(m) } }) { txt ->
            runOnUiThread {
                val d = android.app.Dialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
                box.addView(ui.text("📜 ذكّرني بالأحداث", 16f, th.primary, true))
                box.addView(ui.text(txt, 14f, th.text).apply { setPadding(0, ui.dp(8), 0, ui.dp(8)); setTextIsSelectable(true) })
                box.addView(ui.button("تمام") { d.dismiss() })
                d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
                d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
                ui.fullPage(d)
                d.show()
            }
        }
    }

    fun makeRetriever(): android.media.MediaMetadataRetriever? {
        return try {
            val r = android.media.MediaMetadataRetriever(); val u = uri; val l = url
            if (u != null) r.setDataSource(this, u) else if (l != null && !l.contains(".m3u8", true)) r.setDataSource(l, HashMap(hdr)) else return null
            r
        } catch (_: Exception) { null }
    }

    fun visualSnap() {
        if (!::svRef.isInitialized || svRef.width <= 0 || svRef.height <= 0) { Toast.makeText(this, "مفيش فيديو شغّال", Toast.LENGTH_SHORT).show(); return }
        if (visBusy) { Toast.makeText(this, "👁 لسه بترجم اللقطة اللي فاتت…", Toast.LENGTH_SHORT).show(); return }
        visBusy = true
        val tok = ++visTok
        h.postDelayed({ if (visBusy && visTok == tok) { visBusy = false;  } }, 60_000)
        val wasPlaying = try { player.playWhenReady } catch (_: Exception) { false }
        try { player.pause() } catch (_: Exception) {}
        val curUs = player.currentPosition * 1000
        val t0 = (player.currentPosition - offsetMs) / 1000.0
                val done = { runOnUiThread { visBusy = false; if (wasPlaying && !isFinishing && !isDestroyed) try { player.play() } catch (_: Exception) {} } }
        fun fallback() {
            Thread {
                val r = makeRetriever()
                val b = try { r?.getFrameAtTime(curUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                try { r?.release() } catch (_: Exception) {}
                if (b != null) visual.snap(b, { t0 }, done) else { done() }
            }.start()
        }
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res -> if (res == android.view.PixelCopy.SUCCESS) visual.snap(bmp, { t0 }, done) else fallback() }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { fallback() }
    }
    private var visBusy = false
    private var visTok = 0

    fun visualDialog() {
        val d = android.app.Dialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("👁 الوضع البصري", 17f, th.primary, true))
        box.addView(ui.text("بياخد فريم كل ثانيتين قدّام مكان التشغيل ويبعته لـ Gemini، ويعرض النصوص المترجمة في مكانها فوق الفيديو. محتاج مفتاح API وفيديو ملف/رابط mp4 (مش m3u8).", 12f, th.muted))
        visual.mode = "scene"
        val st = ui.text(if (visual.running) "الحالة: شغّال — " + visual.status else "الحالة: واقف", 13f, th.text)
        box.addView(st)
        box.addView(ui.button(if (visual.running) "⏹ إيقاف" else "▶ تشغيل", true) {
            if (visual.running) visual.stop() else {
                visual.clear(); visual.start()
            }
            d.dismiss()
        })
        box.addView(ui.button("🗑 مسح النتائج") { visual.clear(); visOv.showBoxes(emptyList()); d.dismiss() })
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    /** الإعدادات من المشغّل: بتفتح شاشة الإعدادات فوق الفيديو من غير ما تقفله — الرجوع (Back) بيرجّعك للفيديو */
    fun openSettings(tab: String? = null) {
        resumeAfterSettings = try { player.isPlaying } catch (_: Exception) { false }
        try { player.pause() } catch (_: Exception) {}
        saveRecent(); Thread { engine.saveNow() }.start()
        startActivity(Intent(this, MainActivity::class.java).putExtra("from_player", true).apply { if (tab != null) putExtra("tab", tab) })
    }
    override fun onResume() {
        super.onResume(); internalNav = false; resumedNow = true; h.removeCallbacks(pipExitCheck)
        if (Cfg.str("theme", "mx") != th.id) { recreate(); return }
        restyleFn()
        if (resumeAfterSettings) { resumeAfterSettings = false; try { player.play() } catch (_: Exception) {} }
    }
    override fun onPause() { super.onPause(); LogStore.add("⏸ onPause · ${LogStore.heapLine()}"); resumedNow = false; if (!handedOff) { saveRecent(); Thread { engine.saveNow() }.start() } }
    override fun onStop() {
        super.onStop(); saveRecent()
        // ✕ على نافذة PiP: النظام بيوقف الأكتيفيتي وهي لسه في وضع PiP — نقفل الفيديو ونخرج (إلا لو الشاشة اتقفلت)
        val interactive = try { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive } catch (_: Exception) { true }
        if (pipNow() && interactive && !isFinishing) closeAfterPip()
    }
    override fun onDestroy() {
        LogStore.add("🔚 onDestroy (isFinishing=$isFinishing)")
        h.removeCallbacksAndMessages(null)
        pipOv?.hide(); pipOv = null
        saveRecent()
        try { visual.stop() } catch (_: Exception) {}
        Live.engine = null
        engine.stop()
        if (!handedOff) try { engine.saveNow() } catch (_: Exception) {}
        player.release(); KeepAliveService.stop(this); super.onDestroy()
    }
}
