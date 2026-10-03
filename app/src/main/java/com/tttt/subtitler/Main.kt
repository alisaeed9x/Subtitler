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
    private var scanFn: () -> Unit = {}
    private var libStarted = false
    override fun onCreate(b: Bundle?) {
        fromPlayer = intent?.getBooleanExtra("from_player", false) == true
        if (fromPlayer) setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(b)
        Cfg.init(this)
        val th = Themes.byId(Cfg.str("theme", "default"))
        val ui = Ui(this, th)
        window.statusBarColor = th.bg; window.navigationBarColor = th.bg
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(32), 0, ui.dp(110)); layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        val keys = ui.input("مفاتيح Gemini الأساسية (مفتاح في كل سطر)", Cfg.str("keys"), 3)
        val backup = ui.input("مفاتيح احتياطية (مفتاح في كل سطر)", Cfg.str("backup"), 2)
        val extra = ui.input("مفاتيح إضافية (بتتضاف للأساسية — مفتاح في كل سطر)", Cfg.str("extra"), 2)
        var modelSel = Cfg.str("model", Models.DEFAULT)
        val model = ui.input("الموديل (اكتب يدوي أو اختار من فوق)", modelSel)
        val modelNames = Models.builtin.map { it.id }
        val modelDesc = ui.text("", 12f, th.muted)
        fun descOf(id: String) = (Models.builtin.firstOrNull { it.id == id }?.desc ?: "موديل يدوي") + " — استهلاك النهارده: " + Quota.used(id) + " / " + Models.quotaOf(id) + " (تقريبي، بيتصفّر 00:00 PT)"
        modelDesc.text = descOf(modelSel)
        val modelChips = ui.chips(modelNames, { model.text.toString().trim() }) { model.setText(it); modelSel = it; modelDesc.text = descOf(it) }
        var chunkSec = Cfg.int("chunk", 60).coerceIn(60, 600)
        val chunk = ui.slider("طول المقطع", chunkSec / 60, 1, 10, " دقيقة") { chunkSec = it * 60 }
        val ahead = ui.input("عدد المقاطع اللي بتترجم قدّام مكان التشغيل", Cfg.str("ahead", "3"))
        val atrack = ui.input("رقم مسار الصوت (لو الفيديو فيه أكتر من لغة)", Cfg.str("atrack", "1"))
        val roster = ui.input("جدول الشخصيات: اسم:male أو female:وصف (سطر لكل شخصية). لو فاضي والتحليل التلقائي شغال هيتعبّى لوحده", Cfg.str("roster"), 3)
        val gloss = ui.input("مسرد مصطلحات ثابت (كل سطر: الكلمة = ترجمتها)", Cfg.str("gloss"), 3)
        val flags = linkedMapOf("vad" to false, "cross" to true, "autochars" to true, "autopron" to true, "autotpl" to true, "strim" to true, "gapfill" to true, "hitiming" to false)
        val flagText = mapOf("vad" to "تخطي المقاطع الصامتة (فلتر الصمت)", "cross" to "مراجعة بين المقاطع (للفيديوهات أطول من 10 دقايق)",
            "autochars" to "تحليل الشخصيات تلقائيًا", "autopron" to "تصحيح الضمائر تلقائيًا", "autotpl" to "ترجمة قالب الـ prompt للغات اللي ملهاش قالب جاهز",
            "strim" to "تقصير حدود المقطع لأقرب لحظة صمت (بيقلل الجمل المقطوعة بين مقطعين)",
            "gapfill" to "سدّ الفجوات تلقائيًا أثناء المشاهدة (بمفاتيح المراقبين/الاحتياطي، والجمل المستردة بين «»)",
            "hitiming" to "دقة توقيت أعلى (بيفك الصوت من قبل البداية بـ 3 ثواني — أبطأ شوية)")
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
        var lang = Cfg.str("lang", "مصري"); var style = Cfg.str("style", "حرفي"); var themeId = th.id
        link = ui.input("رابط فيديو مباشر (mp4 / m3u8)", "")
        fun save() {
            val e = Cfg.p.edit().putString("keys", keys.text.toString()).putString("backup", backup.text.toString())
                .putString("model", model.text.toString().trim()).putInt("chunk", chunkSec).putString("extra", extra.text.toString())
                .putString("roster", roster.text.toString()).putString("gloss", gloss.text.toString())
                .putString("lang", lang).putString("style", style).putString("theme", themeId)
                .putInt("parallel", parallel).putString("keymodes", KeyModes.toJson(modes))
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
        val sp = styleParts(ui, th)
        val settingsDlg = TabbedDialog(this, ui, "⚙️ الإعدادات", listOf(
            TabDef("fonts", "🔤 الخطوط", sp.fonts, true),
            TabDef("anim", "✨ الأنيميشن", sp.anim, true),
            TabDef("look", "🎬 العرض والألوان", sp.look, true),
            TabDef("chars", "🧑 الشخصيات", listOf<View>(ui.charactersEditor(this, roster, gloss))),
            TabDef("general", "🌐 اللهجة والأسلوب", listOf<View>(
                ui.text("اللهجة", 13f, th.muted), ui.chips(langs, { lang }) { lang = it },
                ui.text("أسلوب الترجمة", 13f, th.muted), ui.chips(styles, { style }) { style = it })),
            TabDef("keys", "🔑 المفاتيح", listOf<View>(keys, backup, extra, ui.text("أوضاع المفاتيح (الأساسية + الإضافية):", 13f, th.muted), modesBox, modesBtn)),
            TabDef("model", "🤖 الموديل", listOf<View>(modelChips, model, modelDesc)),
            TabDef("engine", "⏱ المحرك", listOf<View>(chunk, parallelRow, ahead, atrack) + flagViews),
            TabDef("theme", "🎨 المظهر", listOf<View>(themeChips))
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
            save(); play("", Uri.parse(v.uri), v.landscape)
        }
        libUi = lib
        lib.onSettings = { settingsDlg.show("keys") }
        lib.onLink = { recentDlg?.show() }
        lib.onPick = { save(); pickVideo() }
        lib.onGrant = { requestPermissions(arrayOf(VideoScan.permission()), 11) }
        fun doScan() {
            libStarted = true
            if (!VideoScan.hasPermission(this)) { lib.showNoPermission(); return }
            val cached = VideoScan.cache
            if (cached != null) lib.showVideos(cached) else lib.showScanning()
            Thread {
                val res = try { VideoScan.scan(this) } catch (_: Exception) { emptyList<VideoItem>() }
                runOnUiThread { if (!isDestroyed && !isFinishing) lib.showVideos(res) }
            }.apply { isDaemon = true }.start()
        }
        scanFn = { doScan() }
        // ريفريش: يمسح الفولدرات والصور المصغّرة المحفوظة ويفحص التخزين من الأول
        lib.onRefresh = { VideoScan.cache = null; Thumbs.clear(); lib.showScanning(); doScan() }

        val frame = FrameLayout(this).apply { setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        frame.addView(lib.root, FrameLayout.LayoutParams(-1, -1))
        if (fromPlayer) {
            setContentView(FrameLayout(this))
            settingsDlg.show(intent?.getStringExtra("tab") ?: "fonts")
            return
        }
        setContentView(frame)
        // لو مفيش مفتاح Gemini: القايمة بتظهر مباشرة أول البرنامج، وبعدها الفحص
        frame.post { ensureKeys { keys.setText(Cfg.str("keys")); refreshChip(); doScan() } }
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
            ui.text("وضع العرض", 13f, th.muted), ui.chips(dualNames, { dualNames[st().dual] }) { put("sub_dual", dualNames.indexOf(it).toString()) },
            sw("نص أبيض عادي بحجم ثابت (بدون ألوان الجنس والأسماء وكلمة التأكيد والتكبير التلقائي)", "sub_plain", false),
            sw("لون نص موحّد", "sub_uni_on", false),
            ui.chips(SubStyle.unifiedPalette, { st().uniColor }) { put("sub_uni_color", it) },
            sw("قسّم الجملة عند النقطة والفاصلة (كل جزء يظهر في وقته ويختفي)", "sub_punct", true),
            sw("تقسيم الجمل الطويلة لأجزاء بالتتابع (تقدير بعدد الكلمات — جيميناي بيقسّم عند الوقفات أصلًا)", "sub_split_on", false), slider("أقصى كلمات في الجزء", "sub_split", 8, 3, 30, ""),
            sw("إخفاء الخلفية", "sub_nobg", false), slider("شفافية الخلفية", "sub_bgopa", 45, 0, 90, "%"), slider("نعومة حواف الخلفية (blur) — 0 = بدون", "sub_blur", 0, 0, 20, "")
        )
        prev.style = st(); holder.post { showDemo() }
        return StyleParts(holder, fontsV, animV, lookV)
    }
    fun pickVideo() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 1) }
    /** landscape: الفيديو من الجهاز بيفتح لاندسكيب مباشرة (زي MX) إلا لو معروف إنه طولي */
    fun play(u: String, uri: Uri?, landscape: Boolean = uri != null) {
        if (u.isBlank() && uri == null) return
        ensureKeys { startPlayerNow(u, uri, landscape) }
    }
    fun startPlayerNow(u: String, uri: Uri?, landscape: Boolean = uri != null) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else putExtra("url", u)
            putExtra("landscape", landscape)
            // لو فتحنا الإعدادات من المشغّل واخترنا فيديو جديد: امسح المشغّل القديم بدل ما يفضل تحته
            if (intent?.getBooleanExtra("from_player", false) == true) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 1 && c == RESULT_OK) d?.data?.let { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; play("", it) }
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc != 11) return
        if (res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) { scanFn(); return }
        Toast.makeText(this, "من غير إذن الفيديوهات مش هقدر أعرض فولدرات الجهاز", Toast.LENGTH_LONG).show()
        // لو اتمنع نهائيًا: افتح إعدادات التطبيق
        if (!shouldShowRequestPermissionRationale(VideoScan.permission())) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }
    override fun onResume() {
        super.onResume()
        // الرجوع من المشغّل أو من إعدادات الإذن: حدّث العرض (تقدم الترجمة) أو أعد الفحص
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
        val th = Themes.byId(try { getSharedPreferences("p", 0).getString("theme", "default") } catch (_: Exception) { "default" })
        val ui = Ui(this, th)
        window.statusBarColor = th.bg; window.navigationBarColor = th.bg
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
    lateinit var batchTv: TextView
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
    var curIdx = -1; var curKey = ""; var lastRefresh = 0L
    var offsetMs = 0L; var speed = 1f; var fit = 0; var fsFit = 2; var ccOn = true; var fitFsB: TextView? = null
    fun curFit() = if (fullMode) fsFit else fit
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
    var restyleFn: () -> Unit = {}
    private var resumeAfterSettings = false
    val hideChrome = Runnable { chromeShown = false; applyChromeFn() }
    var fullMode = false
    var vid = ""
    // القايمة الجانبية (الأفقي)
    lateinit var sideMenuV: FrameLayout
    lateinit var sidePanel: LinearLayout
    var sideOpen = false
    fun openSide() {
        if (!fullMode || !::sideMenuV.isInitialized) return
        val lp = sidePanel.layoutParams as FrameLayout.LayoutParams
        lp.width = minOf((resources.displayMetrics.widthPixels * 0.5f).toInt(), ui.dp(400)); sidePanel.layoutParams = lp
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
    }
    override fun status(s: String) { status = s }
    override fun changed() { dirty = true }
    override fun position() = cur / 1000.0
    override fun playerDuration() = durMs / 1000.0

    private fun refreshList() {
        val l = engine.subs.sortedBy { it.start }
        list = l; starts = LongArray(l.size) { (l[it].start * 1000).toLong() }; ends = LongArray(l.size) { (l[it].end * 1000).toLong() }
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Cfg.init(this); conf = Cfg.snapshot()
        th = Themes.byId(Cfg.str("theme", "default")); ui = Ui(this, th)
        window.statusBarColor = th.bg; window.navigationBarColor = th.bg
        speed = Cfg.str("speed", "1").toFloatOrNull() ?: 1f; fit = Cfg.int("fit", 0).coerceIn(0, 2); fsFit = Cfg.int("fs_fit", 2).coerceIn(0, 2); offsetMs = Cfg.str("sub_offset_ms", "0").toLongOrNull() ?: 0L
        uri = intent.data; url = intent.getStringExtra("url")
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
        // لوج الباتشات العايم (بيتخفّى بالضغط عليه)
        batchTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8).toFloat() }
            layoutDirection = View.LAYOUT_DIRECTION_RTL; typeface = android.graphics.Typeface.MONOSPACE
            setOnClickListener { visibility = View.GONE }
        }
        videoBox.addView(batchTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(8), ui.dp(26), 0, 0) })
        // طبقة إيماءات شفافة فوق الفيديو والترجمة وتحت كل الأزرار (كل اللمس بتاع الفيديو بيعدي عليها)
        val gestureLayer = View(this)
        videoBox.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))

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
        val fab = TextView(this).apply {
            text = "✦"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE; alpha = 0.6f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE01E2228.toInt()); setStroke((1.5f * resources.displayMetrics.density).toInt(), 0x66F5A623) }
            setOnClickListener { val o = menu.visibility != View.VISIBLE; menu.visibility = if (o) View.VISIBLE else View.GONE; alpha = if (o) 1f else 0.6f }
        }
        videoBox.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(60), 0) })
        videoBox.addView(fab, FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(8), 0) })
        fsOnly = listOf<View>(fsBadge, fab); assistMenuV = menu
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
        var offFsB: TextView? = null
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
            offsetMs += d; Cfg.p.edit().putString("sub_offset_ms", offsetMs.toString()).apply()
            offFsB?.text = String.format("%+.1fs", offsetMs / 1000.0); curIdx = -2
        }

        ctl = ui.controls()
        // صفّين ثابتين من اليمين (نفس البداية ونفس الارتفاع 32dp، والمسافة بينهم 6dp = هامش 3+3)
        var popup: android.widget.PopupWindow? = null; var popupOwner: View? = null
        fun dismissPop() { try { popup?.dismiss() } catch (_: Exception) {}; popup = null; popupOwner = null }
        fun after(keep: Boolean) { if (!keep) dismissPop(); showChrome(); if (popup != null) h.removeCallbacks(hideChrome) }
        fun pk(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(true) }.apply { minimumWidth = ui.dp(150) }
        fun pd(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(false) }.apply { minimumWidth = ui.dp(150) }
        fun hRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        fun gCol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(0xF2141418.toInt(), 0x33FFFFFF, 12) }
        val gText = gCol(); val gAi = gCol(); val gTool = gCol()
        fun tRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
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
        val ccB = fb("CC") { toggleCc() }
        tb1.addView(fb("👁 بصري") { visualSnap() }.apply { setOnLongClickListener { visualDialog(); true } })
        ccFsB = ccB; tb1.addView(ccB)
        tb1.addView(fb("A−") { scaleBy(-10) })
        tb1.addView(fb("A+") { scaleBy(10) })
        val fitB = fb("⬛ " + PlayerLogic.fitNames[curFit()]) { v ->
            if (fullMode) { fsFit = (fsFit + 1) % 3; Cfg.p.edit().putString("fs_fit", fsFit.toString()).apply() }
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
        gAi.addView(pd("🔥 لهجة") { runTool("زيادة شدة اللهجة", "زوّد شدة اللهجة الشعبية في كل جملة درجة واحدة: ألفاظ وتعبيرات الشارع والعامية المحلية (${conf.lang}) بدل الفصحى والكلام الرسمي، من غير ما تغيّر المعنى أو الجنس.", true) })
        gAi.addView(pd("😐 عائلي/صريح") { familyDialog() })
        gAi.addView(pd("🌐 لهجة لايف") { liveDialectDialog() })
        gAi.addView(pd("🔧 ضمائر") { pronounsNow() })
        gAi.addView(pd("🧠 دمج مكرر") { val n = engine.removeDuplicates(); Toast.makeText(this, if (n > 0) "اتدمجت $n جملة مكررة" else "مفيش جمل مكررة متداخلة", Toast.LENGTH_SHORT).show(); curIdx = -2 })
        gTool.addView(pd("📜 ذكّرني") { recapDialog() })
        tb2.addView(ui.fsBtn("☰ القائمة") { openSide() })
        tb2.addView(grp("🔤 النص", gText)); tb2.addView(grp("✨ لهجة", gAi)); tb2.addView(grp("🧰 أدوات", gTool))

        fsPlayB = TextView(this).apply {
            text = "▶"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFF17130A.toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.primary, 0xFFD4870F.toInt())).apply { shape = GradientDrawable.OVAL }
            setOnClickListener { togglePlay(); showChrome() }
        }
        fsEl = ui.text("0:00", 12f, Color.WHITE); fsDu = ui.text("0:00", 12f, Color.WHITE)
        fsProgV = DualProgress(this, th)
        // الشريط LTR صريح (الوقت والتقدم بيتقروا من الشمال): ⛶ | الوقت | التقدم | المدة | ▶ (أقصى اليمين)
        val fsBar = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), 0, ui.dp(8), 0); background = ui.box(0xE00F1114.toInt(), 0x1AFFFFFF, 30)
        }
        fsBarFs = ui.fsCircle("⛶") { toggleFs(); showChrome() }
        fsBar.addView(fsBarFs, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
        fsBar.addView(fsEl, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(10), 0, ui.dp(10), 0) })
        fsBar.addView(fsProgV, LinearLayout.LayoutParams(0, -2, 1f))
        fsBar.addView(fsDu, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(10), 0, ui.dp(10), 0) })
        fsBar.addView(fsPlayB, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
        val chromeFrame = FrameLayout(this).apply { visibility = View.GONE }
        chromeFrame.addView(tb, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(12), ui.dp(12), ui.dp(93), 0) })
        chromeFrame.addView(fsBar, FrameLayout.LayoutParams(-1, ui.dp(52), Gravity.BOTTOM).apply { setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(14)) })
        videoBox.addView(chromeFrame, FrameLayout.LayoutParams(-1, -1))
        // زرار ⛶ (.fullscreen-btn): أسفل يسار الفيديو 10dp في الرأسي، 18dp في الشاشة الكاملة
        fsBtnV = ui.fsCircle("⛶") { toggleFs(); showChrome() }
        fsBtnLp = FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.BOTTOM or Gravity.LEFT)
        videoBox.addView(fsBtnV, fsBtnLp)
        applyChromeFn = {
            val on = fullMode && chromeShown
            chromeFrame.visibility = if (on) View.VISIBLE else View.GONE; if (!on) dismissPop()
            fsBtnV.visibility = if (!fullMode) View.VISIBLE else View.GONE   // في الشاشة الكاملة ⛶ جوه الشريط السفلي
            subLp.bottomMargin = if (on) ui.dp(84) else ui.dp(12); sub.requestLayout()
        }

        // ---- لمس الفيديو: لمسة = إظهار/إخفاء الشريط (أو تشغيل/إيقاف في الوضع الرأسي)، لمستين = ±10ث، سحب رأسي = صوت (يمين) / سطوع (شمال) ----
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var volF = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var briF = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
        var scrollLogged = false
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scrollLogged = false; dismissPop(); return true }
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
                if (e1 == null || Math.abs(dy) < Math.abs(dx)) return false
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
        gestureLayer.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }

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
        logDlg = ui.sheet(this, "📜 اللوجز", listOf<View>(logSv), true)
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
            mk("🔥", "لهجة أقوى") { runTool("زيادة شدة اللهجة", "زوّد شدة اللهجة الشعبية في كل جملة درجة واحدة: ألفاظ وتعبيرات الشارع والعامية المحلية (${conf.lang}) بدل الفصحى والكلام الرسمي، من غير ما تغيّر المعنى أو الجنس.", true) },
            mk("😐", "عائلي / صريح") { familyDialog() },
            mk("🌐", "لهجة لايف") { liveDialectDialog() },
            mk("🔧", "صحّح الضمائر") { pronounsNow() },
            mk("🧠", "دمج مكرر") { val n = engine.removeDuplicates(); Toast.makeText(this, if (n > 0) "اتدمجت $n جملة مكررة" else "مفيش جمل مكررة متداخلة", Toast.LENGTH_SHORT).show(); curIdx = -2 },
            mk("📜", "ذكّرني") { recapDialog() },
            mk("👁", "الوضع البصري") { visualDialog() }
        )
        val grid = ui.grid(menuItems { i, l, f -> ui.gridBtn(i, l, f) })
        // ---- القايمة الجانبية (أفقي): نفس أزرار اللي تحت المشغل، 3 في الصف وتحتهم صف تاني وهكذا. أي زرار بيفتح صفحة كاملة، والرجوع بيرجّع خطوة ----
        val keepOpen = setOf("الجمل", "اللوجز", "ترجمات الفيديو", "عائلي / صريح", "لهجة لايف", "ذكّرني", "الوضع البصري", "الإعدادات")
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
        extrasCol.addView(ctl.root); extrasCol.addView(counters); extrasCol.addView(mem.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        extrasCol.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(13), ui.dp(10), ui.dp(13), 0) })
        val scroll = ScrollView(this).apply { addView(extrasCol) }
        extras = scroll
        page.addView(videoBox, LinearLayout.LayoutParams(-1, ui.dp(220)))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val rootFrame = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        rootFrame.addView(page, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(sideMenuV, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootFrame)
        applyFull(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        // فيديو اتفتح من فولدرات الجهاز: يشتغل لاندسكيب مباشرة زي MX Player
        if (intent.getBooleanExtra("landscape", false) && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        videoBox.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) applyFit(sv, videoBox) }

        buildPlayer()

        // المحرك + استرجاع التقدم المحفوظ
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
                pr.translated = cov; pr.invalidate()
                val tEl = PlayerLogic.clock(cur); val tDu = PlayerLogic.clock(durMs)
                if (fullMode) { fsEl.text = tEl; fsDu.text = tDu } else { ctl.tEl.text = tEl; ctl.tDur.text = tDu }
                val now = System.currentTimeMillis()
                if (dirty && now - lastRefresh > 1000) { dirty = false; lastRefresh = now; refreshList(); curIdx = -2 }
                visNow = (cur - offsetMs) / 1000.0
                visOv.showBoxes(visual.boxesAt(visNow))
                val act = PlayerLogic.activeIndices(starts, ends, cur, offsetMs)
                val idx = act.lastOrNull() ?: -1
                val gs = PlayerLogic.orderSpeakers(act.map { list[it] })
                // جملة طويلة واحدة: بتتقسم لأجزاء بتظهر بالتتابع على مدة الجملة (التوقيت الأصلي ثابت)
                var parts: List<String> = emptyList(); var part = 0
                val ssn = sub.style
                if (ccOn && gs.size == 1) {
                    var byChars = false
                    if (ssn.punctOn) { parts = PlayerLogic.splitPunct(gs[0].translated); byChars = true }
                    if (parts.size <= 1 && ssn.splitOn) { parts = PlayerLogic.splitParts(gs[0].translated, ssn.splitThresh); byChars = false }
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
                    adapter.notifyDataSetChanged()
                    if (idx >= 0 && sentDlg.isShowing && !listView.isPressed) listView.smoothScrollToPositionFromTop(idx, ui.dp(30))
                }
                st.text = status
                if (now - lastBatch > 700) { lastBatch = now; batchTv.text = engine.batchLines() }
                if (fullMode) fsBadge.set(PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toString() + "%", list.size.toString() + " جملة")
                if (now - lastMem > 4000) { lastMem = now; if (!fullMode) mem.update(this@PlayerActivity) }
                counters.text = "جمل ${list.size} · تغطية ${PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0)}% · فجوات ${engine.failedCount()} · كوتة ${Quota.used(conf.model)}/${Models.quotaOf(conf.model)}"
                if (logDlg.isShowing) logTv.text = synchronized(logBuf) { logBuf.toString() }
                h.postDelayed(this, 200)
            }
        })
        Thread { engine.run() }.apply { isDaemon = true }.start()
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
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) { vidW = v.width; vidH = v.height; videoBox.post { applyFit(sv, videoBox) } } }
            override fun onIsPlayingChanged(p: Boolean) {
                val t = if (p) "⏸" else "▶"; ctl.play.text = t; fsPlayB.text = t; centerPlay.visibility = if (p) View.GONE else View.VISIBLE
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

    private fun initEngine() {
        vid = videoId()
        val store = Store(File(filesDir, "progress"), Store.keyFor(vid))
        val pb = PromptBuilder { p -> assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        engine = Engine(conf, { makeSource() }, store, this, pb)
        Live.engine = engine
        visual = VisualMode(conf, { player.currentPosition / 1000.0 }, { makeRetriever() }, { m -> runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }, { })
        val savedPos = engine.load()
        if (savedPos > 5.0) { player.seekTo((savedPos * 1000).toLong()); cur = (savedPos * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}") }
        refreshList()
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
        buildPlayer(); initEngine()
        Thread { engine.run() }.apply { isDaemon = true }.start()
    }

    /** أفقي = ملء الشاشة (شريط الأزرار العلوي + كبسولة التقدم تظهر بلمسة)، رأسي = فيديو فوق والكبسولة وشبكة الأزرار تحت */
    private fun applyFull(f: Boolean) {
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
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = if (f) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else 0
        if (f) showChrome() else { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
    }
    fun showChrome() { chromeShown = true; applyChromeFn(); h.removeCallbacks(hideChrome); h.postDelayed(hideChrome, 3500) }
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }
    fun toggleFs() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) try {
            val pb = PictureInPictureParams.Builder()
            if (vidW > 0 && vidH > 0) pb.setAspectRatio(Rational((vidW.toFloat() / vidH).coerceIn(0.45f, 2.3f).times(1000).toInt(), 1000))
            enterPictureInPictureMode(pb.build())
        } catch (_: Exception) { Toast.makeText(this, "PiP مش مدعوم على الجهاز ده", Toast.LENGTH_SHORT).show() }
    }
    fun doExport() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "subtitles.srt") }, 7) }
    fun doImport() { startActivityForResult(filePicker("*/*", "اختار ملف SRT", false), 9) }
    fun doOpen() { startActivityForResult(filePicker("video/*", "اختار فيديو", true), 8) }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); applyFull(c.orientation == Configuration.ORIENTATION_LANDSCAPE) }
    override fun onPictureInPictureModeChanged(inPip: Boolean, c: Configuration) {
        super.onPictureInPictureModeChanged(inPip, c)
        if (inPip) {
            closeSide()
            h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn()
            extras.visibility = View.GONE; st.visibility = View.GONE; fsOnly.forEach { it.visibility = View.GONE }; assistMenuV.visibility = View.GONE; centerPlay.visibility = View.GONE
            val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams; lp.height = -1; lp.setMargins(0, 0, 0, 0); videoBoxRef.layoutParams = lp; fsBtnV.visibility = View.GONE
        } else applyFull(c.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applyFit(sv: SurfaceView, box: View) {
        if (vidW == 0 || box.width == 0) return
        val (w, hh) = PlayerLogic.fitSize(box.width, box.height, vidW, vidH, curFit())
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

    private fun saveRecent() {
        try {
            val f = File(filesDir, "recent.json")
            val old = Recents.parse(try { f.readText() } catch (_: Exception) { "" })
            val r = Recent(vid, Recents.titleOf(vid), url ?: "", uri?.toString() ?: "", cur / 1000.0, durMs / 1000.0, engine.subs.size, engine.coveredSec(), System.currentTimeMillis())
            f.writeText(Recents.toJson(Recents.upsert(old, r)))
        } catch (_: Exception) {}
    }
    /** أفقي: يرجع للرأسي من غير ما يقفل. رأسي: يحفظ التقدم ويوقف الفيديو ويقفل. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sideOpen) { closeSide(); return }
        if (fullMode) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; return }
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
        val curUs = player.currentPosition * 1000
        Toast.makeText(this, "📸 بلقط الشاشة وبترجم… الفيديو مكمّل", Toast.LENGTH_SHORT).show()
        fun fallback() {
            Thread {
                val r = makeRetriever()
                val b = try { r?.getFrameAtTime(curUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                try { r?.release() } catch (_: Exception) {}
                if (b != null) visual.snap(b) { visNow } else runOnUiThread { Toast.makeText(this, "👁 معرفتش ألقط الفريم", Toast.LENGTH_SHORT).show() }
            }.start()
        }
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res -> if (res == android.view.PixelCopy.SUCCESS) visual.snap(bmp) { visNow } else fallback() }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { fallback() }
    }

    fun visualDialog() {
        val d = android.app.Dialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("👁 الوضع البصري", 17f, th.primary, true))
        box.addView(ui.text("بياخد فريم كل ثانيتين قدّام مكان التشغيل ويبعته لـ Gemini، ويعرض النصوص المترجمة في مكانها فوق الفيديو. محتاج مفتاح API وفيديو ملف/رابط mp4 (مش m3u8).", 12f, th.muted))
        val names = listOf("📸 نصوص المشهد (لافتات وعناوين)", "📝 ترجمة هاردسب موجودة")
        box.addView(ui.chips(names, { if (visual.mode == "hardsub") names[1] else names[0] }) { visual.mode = if (it == names[1]) "hardsub" else "scene" })
        val lang = ui.input("لغة الهاردسب (فاضي = كشف تلقائي)", Cfg.str("hardsub_lang", "")).apply { setSingleLine() }
        box.addView(lang)
        val st = ui.text(if (visual.running) "الحالة: شغّال — " + visual.status else "الحالة: واقف", 13f, th.text)
        box.addView(st)
        box.addView(ui.button(if (visual.running) "⏹ إيقاف" else "▶ تشغيل", true) {
            if (visual.running) visual.stop() else {
                visual.hardLang = lang.text.toString().trim(); Cfg.put("hardsub_lang", visual.hardLang)
                visual.clear(); visual.start(); say("👁 الوضع البصري شغّال")
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
    fun openSettings(tab: String = "fonts") {
        resumeAfterSettings = try { player.isPlaying } catch (_: Exception) { false }
        try { player.pause() } catch (_: Exception) {}
        saveRecent(); Thread { engine.saveNow() }.start()
        startActivity(Intent(this, MainActivity::class.java).putExtra("from_player", true).putExtra("tab", tab))
    }
    override fun onResume() {
        super.onResume()
        if (Cfg.str("theme", "default") != th.id) { recreate(); return }
        restyleFn()
        if (resumeAfterSettings) { resumeAfterSettings = false; try { player.play() } catch (_: Exception) {} }
    }
    override fun onPause() { super.onPause(); saveRecent(); Thread { engine.saveNow() }.start() }
    override fun onStop() { super.onStop(); saveRecent() }
    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        saveRecent()
        try { visual.stop() } catch (_: Exception) {}
        Live.engine = null
        engine.stop()
        try { engine.saveNow() } catch (_: Exception) {}
        player.release(); KeepAliveService.stop(this); super.onDestroy()
    }
}
