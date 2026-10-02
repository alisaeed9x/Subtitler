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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build

class LegacyMainActivity : Activity() {
    lateinit var link: EditText
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        val th = Themes.byId(Cfg.str("theme", "default"))
        val ui = Ui(this, th)
        window.statusBarColor = th.bg; window.navigationBarColor = th.bg
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(36), ui.dp(12), ui.dp(24)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val keys = ui.input("مفاتيح Gemini الأساسية (مفتاح في كل سطر)", Cfg.str("keys"), 3)
        val backup = ui.input("مفاتيح احتياطية (مفتاح في كل سطر)", Cfg.str("backup"), 2)
        val extra = ui.input("مفاتيح إضافية (بتتضاف للأساسية — مفتاح في كل سطر)", Cfg.str("extra"), 2)
        var modelSel = Cfg.str("model", "gemini-2.5-flash")
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
        val recents = Recents.parse(try { File(filesDir, "recent.json").readText() } catch (_: Exception) { "" })
        val recentViews: List<View> = if (recents.isEmpty()) listOf(ui.text("مفيش فيديوهات محفوظة لسه", 13f, th.muted)) else recents.map { r ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.surface, th.border, 8)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) }
                addView(ui.text(r.title, 14f, th.text, true))
                addView(ui.text("جمل ${r.subs} · تغطية ${r.percent}% · وقف عند ${PlayerLogic.clock((r.posSec * 1000).toLong())}", 11f, th.muted))
                setOnClickListener { save(); recentDlg?.dismiss(); if (r.uri.isNotEmpty()) play("", Uri.parse(r.uri)) else play(r.url, null) }
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
        val keyDlg = ui.sheet(this, "🔑 مفاتيح Gemini", listOf<View>(keys, backup, extra, ui.text("أوضاع المفاتيح (الأساسية + الإضافية):", 13f, th.muted), modesBox, modesBtn), false) { save(); refreshChip() }
        val modelDlg = ui.sheet(this, "🤖 الموديل", listOf<View>(modelChips, model, modelDesc), false) { save(); refreshChip() }
        val engDlg = ui.sheet(this, "⏱ المحرك", listOf<View>(chunk, parallelRow, ahead, atrack) + flagViews, false) { save() }
        val themeDlg = ui.sheet(this, "🎨 المظهر", listOf<View>(themeChips), false)
        val styleDlg = ui.sheet(this, "🎬 ستايل الترجمة", listOf<View>(styleSection(ui, th)), false)
        val setDlg = ui.sheet(this, "⚙️ الإعدادات", listOf<View>(
            ui.text("اللهجة", 13f, th.muted), ui.chips(langs, { lang }) { lang = it },
            ui.text("أسلوب الترجمة", 13f, th.muted), ui.chips(styles, { style }) { style = it },
            ui.text("جدول الشخصيات", 13f, th.muted), roster, ui.text("مسرد المصطلحات", 13f, th.muted), gloss), false) { save() }
        recentDlg = ui.sheet(this, "📼 فيديوهات محفوظة", recentViews, false)
        refreshChip()

        // الشريط العلوي (من اليمين: مفتاح، محرك، موديل، مظهر، إعدادات، ستايل)
        val topRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(ui.circleBtn("🔑", true) { keyDlg.show() })
        topRow.addView(ui.circleBtn("⏱") { engDlg.show() })
        topRow.addView(modelChipTv.apply { setOnClickListener { modelDlg.show() } })
        topRow.addView(ui.circleBtn("🎨") { themeDlg.show() })
        topRow.addView(ui.circleBtn("⚙️") { setDlg.show() })
        topRow.addView(ui.circleBtn("🎛") { styleDlg.show() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(topRow) })

        // شيب حالة المفتاح
        val chipTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(keyChipTv); addView(keyTailTv) }
        val keyChip = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.box(th.card, th.border, 14)
            addView(chipTexts); addView(keyPctTv, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(16) })
            setOnClickListener { keyDlg.show() }
        }
        root.addView(keyChip, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), ui.dp(6), 0, ui.dp(8)) })

        // كارت الفيديو (NO SIGNAL)
        link.hint = "رابط الفيديو (MP4 / M3U8 ...)"; link.layoutDirection = View.LAYOUT_DIRECTION_LTR
        link.setTextColor(Color.WHITE); link.setHintTextColor(0x99FFFFFF.toInt()); link.textSize = 13f
        link.background = ui.box(0x26FFFFFF, 0x4DFFFFFF, 10)
        link.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val loadBtn = TextView(this).apply {
            text = "تحميل"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ui.onPrimary()); typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(th.primary, th.primary, 10)
            setOnClickListener { save(); play(link.text.toString().trim(), null) }
        }
        val urlRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), 0, ui.dp(14), 0) }
        urlRow.addView(loadBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) }); urlRow.addView(link)
        val folder = TextView(this).apply {
            text = "📁"; textSize = 30f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(ui.dp(2), th.primary, ui.dp(4).toFloat(), ui.dp(3).toFloat()) }
            setOnClickListener { save(); pickVideo() }
        }
        val orRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        orRow.addView(View(this).apply { setBackgroundColor(0x30FFFFFF) }, LinearLayout.LayoutParams(ui.dp(40), 1))
        orRow.addView(ui.text("أو", 12f, 0x99FFFFFF.toInt()).apply { setPadding(ui.dp(10), 0, ui.dp(10), 0) })
        orRow.addView(View(this).apply { setBackgroundColor(0x30FFFFFF) }, LinearLayout.LayoutParams(ui.dp(40), 1))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, ui.dp(14), 0, ui.dp(16))
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = ui.dp(20).toFloat(); setStroke(ui.dp(1), th.border, ui.dp(5).toFloat(), ui.dp(4).toFloat()) }
        }
        card.addView(ui.text("NO SIGNAL", 11f, th.muted).apply { letterSpacing = 0.25f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, 0, 0, ui.dp(12)) })
        card.addView(folder, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)))
        card.addView(ui.text("اختار فيديو", 20f, Color.WHITE, true).apply { setPadding(0, ui.dp(10), 0, 0) })
        card.addView(ui.text("MP4 · MOV · WebM · AVI", 13f, 0x99FFFFFF.toInt()).apply { setPadding(0, ui.dp(4), 0, ui.dp(10)) })
        card.addView(orRow, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(10) })
        card.addView(urlRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(card, LinearLayout.LayoutParams(-1, -2))

        // شريط التقدم + الكبسولة (شكل بس لحد ما فيديو يتفتح) + الرام/الكاش
        val ctl = ui.controls(); ctl.root.alpha = 0.45f
        root.addView(ctl.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        val mem = ui.memRow(); mem.update(this)
        root.addView(mem.root)

        // شبكة الأزرار
        root.addView(ui.grid(listOf<View>(
            ui.gridBtn("🌐", "المتصفح") { save(); startActivity(Intent(this, BrowserActivity::class.java)) },
            ui.gridBtn("📼", "فيديوهات محفوظة") { recentDlg?.show() },
            ui.gridBtn("🔤", "معاينة الخطوط") { styleDlg.show() },
            ui.gridBtn("⚙️", "إعدادات") { setDlg.show() },
            ui.gridBtn("📂", "فتح فيديو") { save(); pickVideo() },
            ui.gridBtn("🗑", "مسح التقدم") {
                val n = Store.clearAll(File(filesDir, "progress"))
                Toast.makeText(this, "اتمسح تقدم $n فيديو", Toast.LENGTH_SHORT).show()
            }
        )))
        root.addView(ui.bigAction("✨ ترجم الفيديو") {
            save(); val u = link.text.toString().trim()
            if (u.isNotEmpty()) play(u, null) else pickVideo()
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(th.bg); addView(root) })
        if (intent?.action == Intent.ACTION_SEND) {
            val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            Regex("https?://\\S+").find(t)?.let { link.setText(it.value); play(it.value, null) }
        }
    }
    private fun styleSection(ui: Ui, th: Theme): LinearLayout {
        val prev = SubtitleView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val demo = Sub(0.0, 5.0, "I met Ahmed in Cairo yesterday and we talked for hours about everything", "قابلت أحمد في القاهرة امبارح واتكلمنا ساعات عن كل حاجة في الدنيا",
            "female", "unknown", "none", listOf("أحمد"), listOf("القاهرة"), false, false, -1, "", false, "", false, "I met Ahmed in Cairo yesterday")
        val holder = FrameLayout(this).apply { setBackgroundColor(0xFF1B2733.toInt()); setPadding(0, ui.dp(30), 0, ui.dp(10)); addView(prev, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        fun st() = SubStyle.load { k, d -> Cfg.str(k, d) }
        fun put(k: String, v: String) { Cfg.put(k, v); prev.style = st(); prev.show(null); prev.show(demo) }
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
        val body = listOf<View>(holder,
            ui.text("وضع العرض", 13f, th.muted), ui.chips(dualNames, { dualNames[st().dual] }) { put("sub_dual", dualNames.indexOf(it).toString()) },
            ui.text("أنيميشن ظهور الترجمة", 13f, th.muted), ui.chips(anims.map { it.label }, { anims.first { a -> a.id == st().anim }.label }) { n -> put("sub_anim", anims.first { it.label == n }.id) },
            slider("سرعة الأنيميشن", "sub_aspeed", 250, 50, 600, "ms"),
            ui.text("خط الترجمة (كل الـ 22 خط متضمنين)", 13f, th.muted),
            ui.chips(fonts.map { it.label }, { fonts.firstOrNull { f -> f.id == st().font }?.label ?: "" }) { n -> put("sub_font", fonts.first { it.label == n }.id) },
            ui.text("نمط الخط", 13f, th.muted),
            ui.chips(SubStyle.fontStyles.map { it.second }, { SubStyle.fontStyles.first { f -> f.first == st().fontStyle }.second }) { n -> put("sub_fontstyle", SubStyle.fontStyles.first { it.second == n }.first) },
            slider("حجم النص", "sub_scale", 100, 60, 200, "%"),
            sw("نص أبيض عادي (بدون ألوان الجنس والأسماء)", "sub_plain", false),
            sw("لون نص موحّد", "sub_uni_on", false),
            ui.chips(SubStyle.unifiedPalette, { st().uniColor }) { put("sub_uni_color", it) },
            sw("تقسيم الجمل الطويلة", "sub_split_on", false), slider("حد الكلمات في السطر", "sub_split", 8, 3, 30, ""),
            sw("إخفاء الخلفية", "sub_nobg", false), slider("شفافية الخلفية", "sub_bgopa", 45, 0, 90, "%"), slider("نعومة حواف الخلفية (blur)", "sub_blur", 6, 0, 20, ""))
        val sec = ui.section("🎬 ستايل الترجمة", true, *body.toTypedArray())
        prev.style = st(); holder.post { prev.show(demo) }
        return sec
    }
    fun pickVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 1)
    }
    fun play(u: String, uri: Uri?) {
        if (u.isBlank() && uri == null) return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            if (uri != null) { data = uri; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) } else putExtra("url", u)
        })
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 1 && c == RESULT_OK) d?.data?.let { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; play("", it) }
    }
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
    fun openPlayer() {
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
    lateinit var player: ExoPlayer
    lateinit var sub: SubtitleView
    lateinit var st: TextView
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
    var offsetMs = 0L; var speed = 1f; var fit = 0; var ccOn = true
    var vidW = 0; var vidH = 0
    lateinit var listView: ListView
    lateinit var counters: TextView
    lateinit var adapter: BaseAdapter
    lateinit var extras: View
    lateinit var videoBoxRef: FrameLayout
    lateinit var fsBadge: TextView
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
    val hideChrome = Runnable { chromeShown = false; applyChromeFn() }
    var fullMode = false
    var vid = ""

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
        speed = Cfg.str("speed", "1").toFloatOrNull() ?: 1f; fit = Cfg.int("fit", 0).coerceIn(0, 2); offsetMs = Cfg.str("sub_offset_ms", "0").toLongOrNull() ?: 0L
        uri = intent.data; url = intent.getStringExtra("url")
        intent.getStringExtra("ua")?.takeIf { it.isNotEmpty() }?.let { hdr["User-Agent"] = it }
        intent.getStringExtra("ref")?.takeIf { it.isNotEmpty() }?.let { hdr["Referer"] = it }
        intent.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { hdr["Cookie"] = it }

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val videoBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        videoBoxRef = videoBox
        val sv = SurfaceView(this)
        videoBox.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        sub = SubtitleView(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; style = SubStyle.load { k, d -> Cfg.str(k, d) } }
        val subLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = ui.dp(12) }
        videoBox.addView(sub, subLp)
        sub.backdrop = sv
        st = TextView(this).apply { setTextColor(th.primary); textSize = 11f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        videoBox.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.TOP))

        // زرار التشغيل الأوسط (بيظهر وقت الإيقاف) + شارة النسبة (شاشة كاملة) + فلاش السيك/الصوت/السطوع
        centerPlay = TextView(this).apply {
            text = "▶"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            setOnClickListener { togglePlay() }
        }
        videoBox.addView(centerPlay, FrameLayout.LayoutParams(ui.dp(68), ui.dp(68), Gravity.CENTER))
        fsBadge = TextView(this).apply {
            textSize = 12f; setTextColor(th.primary); gravity = Gravity.END; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12); visibility = View.GONE
        }
        videoBox.addView(fsBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(14), ui.dp(14), 0) })
        val gi = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(0x99000000.toInt(), Color.TRANSPARENT, 24); visibility = View.GONE
        }
        videoBox.addView(gi, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val volInd = VertInd(this, "🔊"); val briInd = VertInd(this, "☀️")
        videoBox.addView(volInd, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(78), 0) })
        videoBox.addView(briInd, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(ui.dp(40), 0, 0, 0) })
        val indHide = Runnable { volInd.visibility = View.GONE; briInd.visibility = View.GONE }
        fun indShow(v: VertInd, f: Float) { v.set(f); h.removeCallbacks(indHide); h.postDelayed(indHide, 900) }
        // فقاعة الأدوات (Assistive Touch): ✦ تفتح عمود دوائر
        fun roundBtn(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2140.toInt()); setStroke(ui.dp(2), 0xFF6B4FA0.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { setMargins(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)) }
            setOnClickListener { f(); showChrome() }
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)); background = ui.box(0xE0141418.toInt(), 0x33FFFFFF, 30)
        }
        menu.addView(roundBtn("📝") { sentDlg.show() })
        menu.addView(roundBtn("🕳") { engine.retryFailed(); Toast.makeText(this, "بحاول أسد الفجوات", Toast.LENGTH_SHORT).show() })
        menu.addView(roundBtn("📥") { doImport() })
        menu.addView(roundBtn("📂") { doOpen() })
        val fab = TextView(this).apply {
            text = "✦"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE0141418.toInt()); setStroke(ui.dp(2), th.primary) }
            setOnClickListener { menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        videoBox.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(14), ui.dp(250)) })
        videoBox.addView(fab, FrameLayout.LayoutParams(ui.dp(54), ui.dp(54), Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(14), 0) })
        fsOnly = listOf<View>(fsBadge, fab); assistMenuV = menu
        val giHide = Runnable { gi.visibility = View.GONE }
        fun giShow(t: String, g: Int) {
            gi.text = t
            val lp = gi.layoutParams as FrameLayout.LayoutParams
            lp.gravity = g; lp.setMargins(ui.dp(60), 0, ui.dp(60), 0); gi.layoutParams = lp
            gi.visibility = View.VISIBLE; h.removeCallbacks(giHide); h.postDelayed(giHide, 800)
        }

        // ---- نفس خطوات الأصل: تحكم الشاشة الكاملة (أزرار الترجمة فوق + كبسولة التقدم تحت) ----
        fun curStyle() = SubStyle.load { k, d -> Cfg.str(k, d) }
        fun restyle() { sub.style = curStyle(); curIdx = -2 }
        fun fontLabel(): String { val f = curStyle().font; return SubStyle.fonts.firstOrNull { it.id == f }?.label ?: f }
        fun entLabel(): String { val a = curStyle().anim; return SubStyle.entrances.firstOrNull { it.id == a }?.label ?: "افتراضي" }
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
        fun divider() = View(this).apply { setBackgroundColor(0x2EFFFFFF); layoutParams = LinearLayout.LayoutParams(ui.dp(1), ui.dp(22)).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) } }

        ctl = ui.controls()
        val tb = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val tb2 = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        tb.addView(fb("A−") { scaleBy(-10) })
        tb.addView(fb("A+") { scaleBy(10) })
        tb.addView(fb("🔤 " + fontLabel()) { v ->
            val fs = SubStyle.fonts; val cf = curStyle().font
            val n = fs[(fs.indexOfFirst { it.id == cf } + 1) % fs.size]
            Cfg.put("sub_font", n.id); restyle(); v.text = "🔤 " + n.label
        })
        tb.addView(fb("✨ " + entLabel()) { v ->
            val es = SubStyle.entrances; val ca = curStyle().anim
            val n = es[(es.indexOfFirst { it.id == ca } + 1) % es.size]
            Cfg.put("sub_anim", n.id); restyle(); v.text = "✨ " + n.label
        })
        tb.addView(fb("⬛ " + PlayerLogic.fitNames[fit]) { v ->
            fit = (fit + 1) % 3; Cfg.p.edit().putString("fit", fit.toString()).apply()
            v.text = "⬛ " + PlayerLogic.fitNames[fit]; applyFit(sv, videoBox)
        })
        val spB = fb("⚙️ " + PlayerLogic.speedLabel(speed)) { cycleSpeed() }
        fsSpeedB = spB; tb.addView(spB)
        tb.addView(divider())
        val ccB = fb("CC") { toggleCc() }
        ccFsB = ccB; tb2.addView(ccB)
        tb2.addView(fb("−") { setOff(-500) })
        val offB = ui.fsBtn(String.format("%+.1fs", offsetMs / 1000.0)) { }
        offFsB = offB; tb2.addView(offB)
        tb2.addView(fb("+") { setOff(500) })
        tb2.addView(fb("📤") { doExport() })
        tb2.addView(fb("🔁") { engine.retryFailed(); Toast.makeText(this, "بحاول أسد الفجوات", Toast.LENGTH_SHORT).show() })
        tb2.addView(fb("⧉") { enterPip() })

        fsPlayB = TextView(this).apply {
            text = "▶"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFF17130A.toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.primary, 0xFFD4870F.toInt())).apply { shape = GradientDrawable.OVAL }
            setOnClickListener { togglePlay(); showChrome() }
        }
        fsEl = ui.text("0:00", 12f, Color.WHITE); fsDu = ui.text("0:00", 12f, Color.WHITE)
        fsProgV = DualProgress(this, th)
        val fsBar = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), 0, ui.dp(16), 0); background = ui.box(0xE00F1114.toInt(), 0x1AFFFFFF, 30)
        }
        fsBar.addView(fsPlayB, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)))
        fsBar.addView(fsEl, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(12), 0, ui.dp(8), 0) })
        fsBar.addView(fsProgV, LinearLayout.LayoutParams(0, -2, 1f))
        fsBar.addView(fsDu, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, ui.dp(4), 0) })
        fsBar.addView(fb("⛶") { toggleFs() }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { setMargins(ui.dp(4), 0, 0, 0) })
        val chromeFrame = FrameLayout(this).apply { visibility = View.GONE }
        val tbCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        tbCol.addView(tb, LinearLayout.LayoutParams(-1, ui.dp(40))); tbCol.addView(tb2, LinearLayout.LayoutParams(-1, ui.dp(40)).apply { topMargin = ui.dp(6) })
        chromeFrame.addView(tbCol, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(14), ui.dp(14), ui.dp(110), 0) })
        chromeFrame.addView(fsBar, FrameLayout.LayoutParams(-1, ui.dp(52), Gravity.BOTTOM).apply { setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(14)) })
        videoBox.addView(chromeFrame, FrameLayout.LayoutParams(-1, -1))
        applyChromeFn = {
            val on = fullMode && chromeShown
            chromeFrame.visibility = if (on) View.VISIBLE else View.GONE
            subLp.bottomMargin = if (on) ui.dp(84) else ui.dp(12); sub.requestLayout()
        }

        // ---- لمس الفيديو: لمسة = إظهار/إخفاء الشريط (أو تشغيل/إيقاف في الوضع الرأسي)، لمستين = ±10ث، سحب رأسي = صوت (يمين) / سطوع (شمال) ----
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var volF = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var briF = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fullMode) { if (chromeShown) { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() } else showChrome() } else togglePlay()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val right = e.x > videoBox.width / 2f
                player.seekTo((player.currentPosition + (if (right) 10000 else -10000)).coerceAtLeast(0))
                giShow(if (right) "10 ⏩" else "⏪ 10", (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!fullMode || e1 == null || Math.abs(dy) < Math.abs(dx)) return false
                val d = dy / videoBox.height.coerceAtLeast(1) * 1.3f
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
        videoBox.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }

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
        counters = ui.text("", 11f, th.muted).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        sentDlg = ui.sheet(this, "📝 الجمل", listOf<View>(listView), true)
        logDlg = ui.sheet(this, "📜 اللوجز", listOf<View>(logSv), true)
        mem = ui.memRow()

        val grid = ui.grid(listOf<View>(
            ui.gridBtn("📝", "الجمل") { sentDlg.show() },
            ui.gridBtn("📜", "اللوجز") { logDlg.show() },
            ui.gridBtn("🕳", "سد الفجوات") { engine.retryFailed(); Toast.makeText(this, "بحاول أسد الفجوات", Toast.LENGTH_SHORT).show() },
            ui.gridBtn("📤", "تصدير SRT") { doExport() },
            ui.gridBtn("📥", "استيراد SRT") { doImport() },
            ui.gridBtn("📂", "فتح فيديو") { doOpen() },
            ui.gridBtn("⧉", "نافذة عائمة") { enterPip() },
            ui.gridBtn("⚙️", "الإعدادات") { finish() }
        ))
        val extrasCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(20)) }
        extrasCol.addView(ctl.root); extrasCol.addView(counters); extrasCol.addView(mem.root); extrasCol.addView(grid)
        val scroll = ScrollView(this).apply { addView(extrasCol) }
        extras = scroll
        page.addView(videoBox, LinearLayout.LayoutParams(-1, ui.dp(220)))
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        applyFull(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        videoBox.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob -> if (r - l != orr - ol || bt - t != ob - ot) applyFit(sv, videoBox) }

        val factory = if (uri != null) DefaultMediaSourceFactory(this)
        else DefaultMediaSourceFactory(DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(factory).build()
        player.setVideoSurfaceView(sv)
        player.setPlaybackSpeed(speed)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) { if (v.width > 0 && v.height > 0) { vidW = v.width; vidH = v.height; videoBox.post { applyFit(sv, videoBox) } } }
            override fun onIsPlayingChanged(p: Boolean) { val t = if (p) "⏸" else "▶"; ctl.play.text = t; fsPlayB.text = t; centerPlay.visibility = if (p) View.GONE else View.VISIBLE }
        })
        player.setMediaItem(MediaItem.fromUri(uri ?: Uri.parse(url!!)))
        player.prepare(); player.playWhenReady = true

        // المحرك + استرجاع التقدم المحفوظ
        vid = videoId()
        val store = Store(File(filesDir, "progress"), Store.keyFor(vid))
        val pb = PromptBuilder { p -> assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        engine = Engine(conf, { makeSource() }, store, this, pb)
        val savedPos = engine.load()
        if (savedPos > 5.0) { player.seekTo((savedPos * 1000).toLong()); cur = (savedPos * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}") }
        refreshList()
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
                val act = PlayerLogic.activeIndices(starts, ends, cur, offsetMs)
                val idx = act.lastOrNull() ?: -1
                val key = act.joinToString(",")
                if (idx != curIdx || key != curKey) {
                    curIdx = idx; curKey = key
                    sub.show(if (ccOn && act.isNotEmpty()) PlayerLogic.combine(act.map { list[it] }) else null)
                    adapter.notifyDataSetChanged()
                    if (idx >= 0 && sentDlg.isShowing && !listView.isPressed) listView.smoothScrollToPositionFromTop(idx, ui.dp(30))
                }
                st.text = status
                if (fullMode) fsBadge.text = PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toString() + "%\n" + list.size + " جملة"
                if (now - lastMem > 4000) { lastMem = now; if (!fullMode) mem.update(this@PlayerActivity) }
                counters.text = "جمل ${list.size} · تغطية ${PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0)}% · فجوات ${engine.failedCount()} · كوتة ${Quota.used(conf.model)}/${Models.quotaOf(conf.model)}"
                if (logDlg.isShowing) logTv.text = synchronized(logBuf) { logBuf.toString() }
                h.postDelayed(this, 200)
            }
        })
        Thread { engine.run() }.apply { isDaemon = true }.start()
    }

    /** أفقي = ملء الشاشة (شريط الأزرار العلوي + كبسولة التقدم تظهر بلمسة)، رأسي = فيديو فوق والكبسولة وشبكة الأزرار تحت */
    private fun applyFull(f: Boolean) {
        fullMode = f
        extras.visibility = if (f) View.GONE else View.VISIBLE
        val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams
        val dm = resources.displayMetrics
        lp.height = if (f) -1 else minOf(dm.widthPixels, dm.heightPixels) * 9 / 16
        videoBoxRef.layoutParams = lp
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
        if (Build.VERSION.SDK_INT >= 26) try { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) } catch (_: Exception) { Toast.makeText(this, "PiP مش مدعوم على الجهاز ده", Toast.LENGTH_SHORT).show() }
    }
    fun doExport() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "subtitles.srt") }, 7) }
    fun doImport() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 9) }
    fun doOpen() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, 8) }
    override fun onConfigurationChanged(c: Configuration) { super.onConfigurationChanged(c); applyFull(c.orientation == Configuration.ORIENTATION_LANDSCAPE) }
    override fun onPictureInPictureModeChanged(inPip: Boolean, c: Configuration) {
        super.onPictureInPictureModeChanged(inPip, c)
        if (inPip) {
            h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn()
            extras.visibility = View.GONE; st.visibility = View.GONE; fsOnly.forEach { it.visibility = View.GONE }; assistMenuV.visibility = View.GONE; centerPlay.visibility = View.GONE
            val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams; lp.height = -1; videoBoxRef.layoutParams = lp
        } else applyFull(c.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun applyFit(sv: SurfaceView, box: View) {
        if (vidW == 0 || box.width == 0) return
        val (w, hh) = PlayerLogic.fitSize(box.width, box.height, vidW, vidH, fit)
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
            saveRecent(); startActivity(Intent(this, PlayerActivity::class.java).apply { data = u; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }); finish()
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
    override fun onPause() { super.onPause(); saveRecent(); Thread { engine.saveNow() }.start() }
    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        saveRecent()
        engine.stop()
        try { engine.saveNow() } catch (_: Exception) {}
        player.release(); KeepAliveService.stop(this); super.onDestroy()
    }
}
