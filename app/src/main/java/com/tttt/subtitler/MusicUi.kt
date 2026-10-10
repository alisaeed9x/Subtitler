package com.tttt.subtitler

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

/** (v189) ملف صوت من الجهاز */
class Track(val id: Long, val uri: String, val title: String, val artist: String, val durMs: Long, val folderKey: String, val folderName: String)

/** فحص ملفات الصوت (MP3 / M4A / FLAC / OGG / WAV…) عن طريق MediaStore.
 *  (v194) كل أغنية بتتحوّل كمان لـ VideoItem عشان صفحة الموسيقى تستخدم نفس كلاس صفحة الفيديوهات (LibraryUi بوضع الصوت). */
object MusicScan {
    @Volatile var cache: List<Track>? = null
    @Volatile var items: List<VideoItem>? = null
    private val byId = java.util.concurrent.ConcurrentHashMap<Long, Track>()
    fun trackFor(v: VideoItem): Track? = byId[v.id]
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    fun invalidate() { cache = null; items = null }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>(); val vis = ArrayList<VideoItem>()
        val base: Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME); add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME); add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED); add(MediaStore.Audio.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        // من غير النغمات والتنبيهات
        val sel = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0"
        try {
            ctx.contentResolver.query(base, proj.toTypedArray(), sel, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArt = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iBuck = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iAdd = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val dur = c.getLong(iDur)
                    if (dur in 1..4999) continue   // أصوات قصيرة جدًا (إشعارات / كليكات)
                    val name = c.getString(iName) ?: continue
                    val title = (c.getString(iTitle) ?: "").ifBlank { name.substringBeforeLast('.') }
                    var artist = c.getString(iArt) ?: ""
                    if (artist == "<unknown>") artist = ""
                    val (key, fname, fpath) = VideoLib.folderInfo(c.getString(iData), if (iRel >= 0) c.getString(iRel) else null, c.getString(iBuck))
                    val id = c.getLong(iId)
                    val ov = SongId.override(id)
                    val ttl = ov?.first?.ifBlank { null } ?: title
                    val art = ov?.second?.ifBlank { null } ?: artist
                    val uri = ContentUris.withAppendedId(base, id).toString()
                    out.add(Track(id, uri, ttl, art, dur, key, fname))
                    vis.add(VideoItem(id, uri, name, key, fname, fpath, c.getLong(iSize), dur, c.getLong(iAdd), c.getLong(iMod), 0, 0, ttl, art))
                }
            }
        } catch (_: Exception) {}
        byId.clear(); out.forEach { byId[it.id] = it }
        cache = out; items = vis
        return out
    }
}

/** (v195) صفحة «🎵 الموسيقى» = نفس صفحة الفيديوهات بالظبط (LibraryUi بوضع الصوت) + شريط مشغّل زجاجي صغير تحت القايمة.
 *  دوسة على أغنية (أو على الشريط) بتفتح المشغّل الكامل: الدايرة الزجاجية (OrbView) بقوس تقدّم الأغنية، والكلمات الحيّة فوقها، وأزرار التحكم تحت.
 *  الصوت/التضخيم والتعرّف والبوستر والريفريش والكلمات الكاملة كلها في قايمة ⋮ فوق. */
class MusicUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    var onGrant: () -> Unit = {}
    private val h = Handler(Looper.getMainLooper())

    /** نفس كلاس صفحة الفيديوهات بوضع الصوت — Main بيوصّل له الإعدادات والعمليات والبوابة */
    val lib: LibraryUi = LibraryUi(act, ui, th, { emptyMap() }, true) { v -> playFrom(v) }

    private val BLUE = 0xFF5B9DFF.toInt()
    private val C_DIM = 0xFFB4B7BF.toInt()
    private val OFF = 0x99FFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()

    // ===== أغلفة الأغاني (الحالية + اللي على الجنبين) =====
    private val artCache = android.util.LruCache<String, Bitmap>(14)
    private val artLoading = HashSet<String>()
    private val artNone = HashSet<String>()
    private var notifyNoPoster = false
    private var curUri: String? = null
    private var tintBmp: Bitmap? = null
    private var tintDone = false

    private val onEngine: () -> Unit = { paint(); refreshNow(); lib.refreshRows(); paintMini() }

    // ===== الشريط الصغير (كبسولة زجاجية تحت القايمة، وتقدّم الأغنية كتعبئة فيها) =====
    private val nowTitle = ui.text("", 14f, Color.WHITE, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val nowSub = ui.text("", 11f, C_DIM).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val miniArt = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.box(0xFF2A2A36.toInt(), Color.TRANSPARENT, 12); clipToOutline = true }
    private val miniFill = View(act).apply { setBackgroundColor(0x40A070FF) }
    private val miniPlay = GlyphBtn(act, "play", 22)
    private val miniNext = GlyphBtn(act, "next", 20)
    private val miniBar = FrameLayout(act).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR; visibility = View.GONE
        background = ui.box(0xFF0E0E16.toInt(), 0x30FFFFFF, 30); clipToOutline = true
    }
    private var miniFillW = -1

    // ===== المشغّل الكامل =====
    private val full = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; isClickable = true }
    private val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF6A3A8A.toInt(), 0xFF2A1638.toInt(), 0xFF0A0610.toInt(), BLACK))
    private var bgColor = 0xFF6A3A8A.toInt()
    private var bgAnim: ValueAnimator? = null
    private val orb = OrbView(act).apply { accent = BLUE }
    private val fTitle = ui.text("مفيش حاجة شغّالة", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val fArtist = ui.text("", 12f, C_DIM).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val bShuffle = GlyphBtn(act, "shuffle", 24)
    private val bPrev = GlyphBtn(act, "prev", 28)
    private val bPlay = GlyphBtn(act, "play", 40)
    private val bNext = GlyphBtn(act, "next", 28)
    private val bRepeat = GlyphBtn(act, "repeat", 24)
    private var fullOn = false
    private val middle = FrameLayout(act)
    private val eq = EqView(act).apply { accent = BLUE }
    private val ORB_SCALE = 0.44f
    private val lyrBtn = ui.text("📝 إظهار الكلمات", 12.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.box(0x33FFFFFF, 0x55FFFFFF, 20)
        setOnClickListener { toggleLyrics() }
    }
    private var lyrFontBtn: TextView? = null
    private fun canEq() = act.checkSelfPermission("android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED
    private fun askEq() {
        try { act.requestPermissions(arrayOf("android.permission.RECORD_AUDIO"), 14) } catch (_: Throwable) {}
    }

    // ===== (v196) خط وحجم الكلمات =====
    private fun lsz(): Float = (Cfg.str("lyr_size", "20").trim().toIntOrNull() ?: 20).coerceIn(14, 36).toFloat()
    private fun fontOpt(): FontOpt = SubStyle.fonts.firstOrNull { it.id == Cfg.str("lyr_font", "Noto Sans Arabic") } ?: SubStyle.fonts[0]
    private val lyrTfCache = HashMap<String, Typeface>()
    private fun lyrTfFor(f: FontOpt): Typeface {
        lyrTfCache[f.id]?.let { return it }
        var tf: Typeface? = null
        f.file?.let { fn ->
            try { tf = Typeface.Builder(act.assets, "fonts/$fn").setFontVariationSettings("'wght' ${f.weight}").build() } catch (_: Throwable) {}
            if (tf == null) try { tf = Typeface.createFromAsset(act.assets, "fonts/$fn") } catch (_: Throwable) {}
        }
        val out = tf ?: Typeface.create(if (f.serif) "serif" else "sans-serif", Typeface.BOLD)
        lyrTfCache[f.id] = out
        return out
    }
    private fun lyrTf(): Typeface = lyrTfFor(fontOpt())
    private fun restyleLyrics() { val r = lyr ?: return; showLyrics(r) }
    private fun bumpSize(dlt: Int) {
        val n = (lsz().toInt() + dlt).coerceIn(14, 36)
        Cfg.put("lyr_size", n.toString()); restyleLyrics()
        Notice.show(act, "حجم الكلمات: $n", 900L)
    }
    private fun fontDialog() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        val rows = ArrayList<Pair<FontOpt, TextView>>()
        fun paintRows() { val cur = fontOpt().id; rows.forEach { (f, tv) -> tv.background = ui.box(if (f.id == cur) 0x445B9DFF else 0x14808080, if (f.id == cur) BLUE else th.border, 10) } }
        SubStyle.fonts.forEach { f ->
            val tv = ui.text(f.label + " — كلمات الأغنية", 18f, th.text).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10)); typeface = lyrTfFor(f)
                setOnClickListener { Cfg.put("lyr_font", f.id); lyrFontBtn?.text = "🔤 الخط: " + f.label; paintRows(); restyleLyrics() }
            }
            rows.add(f to tv); col.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        }
        paintRows()
        val sc = ScrollView(act).apply { addView(col) }
        GAlert(act).setTitle("🔤 خط الكلمات").setView(sc).setPositiveButton("تمام", null).show()
    }

    // ===== الكلمات: حيّة فوق الدايرة + صفحة كاملة (بوضعين: 📝 كاملة من جيميناي · 🎧 تفريغ من الصوت) =====
    private val lyrPrevTv = ui.text("", 13f, 0x80FFFFFF.toInt()).apply { setLineSpacing(0f, 1.1f); maxLines = 2; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val lyrCurTv = ui.text("", 25f, Color.WHITE).apply { setLineSpacing(0f, 1.08f); maxLines = 3; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val lyrNextTv = ui.text("", 13f, 0x80FFFFFF.toInt()).apply { setLineSpacing(0f, 1.1f); maxLines = 2; textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val liveStatus = ui.text("", 12f, 0xB3FFFFFF.toInt()).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_TEXT_START }
    private val liveBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; minimumHeight = ui.dp(120); setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(10)) }
    private val inner = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val lyrPanel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val lyrStatus = ui.text("", 12.5f, 0xFFD0D3DA.toInt()).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)) }
    private val lyrBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(60)) }
    private val lyrScroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(lyrBox) }
    private val lyrViews = ArrayList<TextView>()
    private val modeChips = ArrayList<TextView>()
    private var lyrOn = false
    private var lyr: LyricsResult? = null
    private var lyrKey: String? = null        // uri|mode للي معروض دلوقتي
    private var lyrIdx = -2
    private var lyrWordKey = ""
    private var liveKey = ""
    private var liveIdx = -3
    private var shownVer = -1

    private fun autoLyr() = Cfg.str("music_auto_lyr", "1") != "0"

    private fun buildLyricsPanel() {
        // اختيار الوضع
        val modes = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(4)) }
        fun chip(label: String, m: String) = ui.text(label, 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(9), ui.dp(8), ui.dp(9)); tag = m
            setOnClickListener { if (LyricsEngine.mode() != m) { LyricsEngine.setMode(m); paintModes(); loadLyrics(manual = true) } }
        }
        val a = chip("📝 كلمات كاملة (جيميناي)", LyricsEngine.M_FULL); val b = chip("🎧 تفريغ من الصوت", LyricsEngine.M_AUDIO)
        modeChips.add(a); modeChips.add(b)
        modes.addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }); modes.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        lyrPanel.addView(modes, LinearLayout.LayoutParams(-1, -2))
        // أزرار التصحيح
        val fix = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        fun fb(t: String, f: () -> Unit) = ui.text(t, 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(0x22FFFFFF, 0x33FFFFFF, 10); setOnClickListener { f() }
        }
        fix.addView(fb("🔁 إعادة") { redoLyrics(false) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        fix.addView(fb("✍️ الكلمات غلط") { redoLyrics(true) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        fix.addView(fb("❌ الأغنية غلط") { wrongSong() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        fix.addView(fb("🔄 ريفريش") { refreshAll() }, LinearLayout.LayoutParams(0, -2, 1f))
        lyrPanel.addView(fix, LinearLayout.LayoutParams(-1, -2))
        // الخط والحجم
        val fontRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(2)) }
        val fbtn = fb("🔤 الخط: " + fontOpt().label) { fontDialog() }; lyrFontBtn = fbtn
        fontRow.addView(fbtn, LinearLayout.LayoutParams(0, -2, 2f).apply { marginEnd = ui.dp(4) })
        fontRow.addView(fb("A−") { bumpSize(-2) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        fontRow.addView(fb("A+") { bumpSize(2) }, LinearLayout.LayoutParams(0, -2, 1f))
        lyrPanel.addView(fontRow, LinearLayout.LayoutParams(-1, -2))
        lyrPanel.addView(lyrStatus, LinearLayout.LayoutParams(-1, -2))
        lyrPanel.addView(lyrScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        lyrPanel.setBackgroundColor(0x40000000)
        paintModes()
    }
    private fun paintModes() {
        val m = LyricsEngine.mode()
        modeChips.forEach { c ->
            val on = c.tag == m
            c.background = ui.box(if (on) BLUE else 0x22FFFFFF, if (on) BLUE else 0x33FFFFFF, 10)
            c.setTextColor(Color.WHITE)
        }
    }
    private fun setStatus(t: String) {
        lyrStatus.text = t; lyrStatus.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
        liveStatus.text = t; liveStatus.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
    }
    /** مفيش كلمات حاليًا: امسح السطور الحيّة (الرسالة بتظهر في سطر الحالة) */
    private fun liveReset() {
        liveKey = ""; liveIdx = -3
        lyrPrevTv.text = ""; lyrCurTv.text = ""; lyrNextTv.text = ""
        lyrPrevTv.visibility = View.GONE; lyrCurTv.visibility = View.GONE; lyrNextTv.visibility = View.GONE
    }
    private fun bigMsg(t: String) {
        lyrBox.removeAllViews(); lyrViews.clear(); lyrIdx = -2; lyr = null
        lyrBox.addView(ui.text(t, 15f, C_DIM).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(40), ui.dp(8), ui.dp(12)) })
        liveReset()
    }
    /** فتح/قفل صفحة الكلمات الكاملة (بتتفتح بالضغط على الكلمات فوق الدايرة أو من ⋮) */
    private fun toggleLyrics() {
        if (MusicEngine.current == null) { Notice.show(act, "شغّل أغنية الأول وبعدين دوس على الكلمات", 2200L); return }
        if (lyrOn) { hideLyrics(); return }
        lyrOn = true; lyrIdx = -2; lyrWordKey = ""
        paintModes(); paintLyrBtn()
        // الدواير تصغر وتنزل لتحت (متثبّتة من تحت) والكلمات تظهر من وراها
        orb.pivotX = orb.width / 2f; orb.pivotY = orb.height.toFloat()
        val top = orb.top + orb.height * (1f - ORB_SCALE)
        (lyrPanel.layoutParams as FrameLayout.LayoutParams).bottomMargin = (middle.height - top).toInt().coerceAtLeast(0)
        lyrPanel.requestLayout()
        lyrPanel.animate().cancel(); lyrPanel.alpha = 0f; lyrPanel.visibility = View.VISIBLE
        lyrPanel.animate().alpha(1f).setDuration(280L).start()
        orb.animate().cancel(); orb.animate().scaleX(ORB_SCALE).scaleY(ORB_SCALE).setDuration(320L).start()
        loadLyrics(manual = true)
    }
    private fun paintLyrBtn() { lyrBtn.text = if (lyrOn) "🙈 إخفاء الكلمات" else "📝 إظهار الكلمات" }
    private fun hideLyrics() {
        if (!lyrOn) return
        lyrOn = false; paintLyrBtn()
        lyrPanel.animate().cancel(); lyrPanel.animate().alpha(0f).setDuration(220L).withEndAction { if (!lyrOn) lyrPanel.visibility = View.GONE }.start()
        orb.animate().cancel(); orb.animate().scaleX(1f).scaleY(1f).setDuration(300L).start()
    }
    /** لما المشغّل نفسه يتقفل: رجّع الدايرة والكلمات لوضعهم فورًا (الأنيميشن مابيشتغلش على view مخفي) */
    private fun snapLyrReset() {
        lyrOn = false; paintLyrBtn()
        lyrPanel.animate().cancel(); lyrPanel.visibility = View.GONE; lyrPanel.alpha = 1f
        orb.animate().cancel(); orb.scaleX = 1f; orb.scaleY = 1f
    }
    private fun nameHint(t: Track): Pair<String, String>? = nameOf(t).let { if (it.first.isBlank()) null else it }

    /** يعرض الكلمات المحفوظة للأغنية والوضع الحاليين، أو يبدأ الكتابة (تلقائيًا لو الكلمات التلقائية شغّالة) — ودايمًا فيه رسالة حالة ظاهرة */
    private fun loadLyrics(force: Boolean = false, manual: Boolean = false) {
        val t = MusicEngine.current ?: run { bigMsg("شغّل أغنية الأول"); setStatus(""); return }
        val m = LyricsEngine.mode()
        val ck = t.uri + "|" + m
        if (!force && lyrKey == ck && lyr != null) return
        lyrKey = ck; lyr = null; shownVer = LyricsEngine.version
        val c = LyricsEngine.cached(act, t, m)
        if (c != null && c.lines.isNotEmpty()) {
            showLyrics(c)
            if (!c.complete && !LyricsEngine.isRunning(t, m)) { setStatus("🎧 الكلمات ناقصة — بكمّلها…"); startLyrics(t, m, false, false) }
            else if (!c.complete) setStatus("🎧 بتتكتب… (${c.done} من ${c.total} مقطع)")
            else setStatus("")
            return
        }
        if (c != null && c.complete) { bigMsg("ما لقيتش كلام لأغنية دي بالوضع ده."); setStatus("جرّب الوضع التاني من ⋮ ← الكلمات كاملة"); return }
        if (!manual && !autoLyr() && !LyricsEngine.isRunning(t, m)) { bigMsg("دوس على المكان ده عشان تجيب الكلمات."); setStatus("🎤 اضغط هنا للكلمات (الكلمات التلقائية مقفولة)"); return }
        startLyrics(t, m, false, false)
    }
    private fun redoLyrics(wrong: Boolean) {
        val t = MusicEngine.current ?: run { Notice.show(act, "شغّل أغنية الأول", 1500L); return }
        val m = LyricsEngine.mode()
        if (LyricsEngine.isRunning(t, m)) { Notice.show(act, "لسه بكتب الكلام… استنى شوية", 1800L); return }
        LyricsEngine.clear(act, t, m); lyrKey = t.uri + "|" + m
        startLyrics(t, m, wrong, true)
    }
    private fun startLyrics(t: Track, m: String, retry: Boolean, fresh: Boolean) {
        val ck = t.uri + "|" + m
        lyrKey = ck
        if (LyricsEngine.isRunning(t, m)) { setStatus("🎧 بتتكتب في الخلفية… استنى"); if (lyr == null) bigMsg("⏳ بتتكتب… هتظهر هنا أول ما تجهز"); return }
        if (lyr == null) bigMsg(if (m == LyricsEngine.M_FULL) "📝 بطلب الكلمات الكاملة من جيميناي…\nعلى مرحلتين، ممكن تاخد دقيقة" else "🎧 بسمع الأغنية وبكتب الكلام…\nمقطع مقطع وبتظهر وهي بتتكتب")
        setStatus(if (m == LyricsEngine.M_FULL) "📝 بجهّز الطلب…" else "🎧 بجهّز الصوت…")
        LyricsEngine.start(act, t, m, nameHint(t), retry, fresh, { stage ->
            act.runOnUiThread { if (fullOn && lyrKey == ck) setStatus(stage) }
        }) { r, msg ->
            act.runOnUiThread {
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (!fullOn || lyrKey != ck) { if (msg.isNotBlank()) Notice.show(act, "🎤 " + t.title + ": " + msg, 3200L); return@runOnUiThread }
                val c = LyricsEngine.cached(act, t, m)
                if (c != null && c.lines.isNotEmpty()) { showLyrics(c); setStatus(msg) }
                else { bigMsg("⚠ " + msg.ifBlank { "ما لقيتش كلام" }); setStatus("جرّب ⋮ ← الكلمات كاملة ← 🔁 إعادة، أو الوضع التاني") }
                if (msg.isNotBlank()) Notice.show(act, msg, 3600L)
            }
        }
    }
    /** لو الكلمات اتحفظت منها حتة جديدة وانت فاتحها (وضع الصوت بيتكتب مقطع مقطع) */
    private fun lyricsPoll() {
        val t = MusicEngine.current ?: return
        val m = LyricsEngine.mode()
        if (lyrKey != t.uri + "|" + m || LyricsEngine.version == shownVer) return
        shownVer = LyricsEngine.version
        val c = LyricsEngine.cached(act, t, m) ?: return
        if (c.lines.isNotEmpty() && c.lines.size != (lyr?.lines?.size ?: -1)) { showLyrics(c); if (!c.complete) setStatus("🎧 اتكتب ${c.done} من ${c.total} مقطع…") }
    }
    private fun showLyrics(r: LyricsResult) {
        lyr = r; lyrBox.removeAllViews(); lyrViews.clear(); lyrIdx = -2; lyrWordKey = ""; liveKey = ""; liveIdx = -3
        if (r.title.isNotBlank() || r.artist.isNotBlank())
            lyrBox.addView(ui.text("🎵 " + listOf(r.title, r.artist).filter { it.isNotBlank() }.joinToString(" — "), 12f, C_DIM).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(14)) })
        r.lines.forEach { l ->
            val tv = ui.text(l.text, lsz(), C_DIM, true).apply {
                typeface = lyrTf(); gravity = Gravity.CENTER; setPadding(0, ui.dp(9), 0, ui.dp(9)); alpha = 0.6f
                setOnClickListener { MusicEngine.seekTo((l.start * 1000).toInt()) }
            }
            lyrViews.add(tv); lyrBox.addView(tv, LinearLayout.LayoutParams(-1, -2))
        }
        lyricsTick()
    }
    private fun lyricsTick() {
        val r = lyr ?: return
        val sec = MusicEngine.posMs / 1000.0
        val i = LyricsEngine.indexAt(r.lines, sec)
        if (!lyrOn) return
        if (i != lyrIdx) {
            lyrViews.getOrNull(lyrIdx)?.let { v -> v.text = r.lines[lyrIdx].text; v.setTextColor(C_DIM); v.alpha = 0.6f; v.textSize = lsz() }
            lyrIdx = i; lyrWordKey = ""
            lyrViews.getOrNull(i)?.let { v -> v.alpha = 1f; v.textSize = lsz() + 4f; v.post { lyrScroll.smoothScrollTo(0, (v.top + v.height / 2 - lyrScroll.height / 2).coerceAtLeast(0)) } }
        }
        // تلوين الكلمات تدريجيًا جوه السطر الشغّال (تقريبي: وقت السطر متقسّم على كلماته بالتساوي)
        if (i >= 0) {
            val l = r.lines[i]; val v = lyrViews[i]
            val words = l.text.split(" ").filter { it.isNotEmpty() }
            val frac = ((sec - l.start) / (l.end - l.start).coerceAtLeast(0.5)).coerceIn(0.0, 1.0)
            val k = Math.ceil(frac * words.size).toInt().coerceIn(0, words.size)
            val key = "$i:$k"
            if (key != lyrWordKey) {
                lyrWordKey = key
                var chars = 0; var seen = 0
                for (w in words) { if (seen >= k) break; val at = l.text.indexOf(w, chars); chars = at + w.length; seen++ }
                val sp = SpannableString(l.text)
                sp.setSpan(ForegroundColorSpan(BLUE), 0, chars.coerceIn(0, l.text.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(Color.WHITE), chars.coerceIn(0, l.text.length), l.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                v.text = sp
            }
        }
    }
    /** الكلمات الحيّة فوق الدايرة: السطر اللي قبله (باهت) · الشغّال (كبير، والكلمات اللي اتقالت أبيض) · اللي بعده (باهت) */
    private fun liveTick(r: LyricsResult, sec: Double, i: Int) {
        if (r.lines.isEmpty()) return
        val l = r.lines.getOrNull(i)
        val words = l?.text?.split(" ")?.filter { it.isNotEmpty() } ?: emptyList()
        val k = if (l != null && words.isNotEmpty())
            Math.ceil(((sec - l.start) / (l.end - l.start).coerceAtLeast(0.5)).coerceIn(0.0, 1.0) * words.size).toInt().coerceIn(0, words.size) else 0
        val key = "${r.lines.size}:$i:$k"
        if (key == liveKey) return
        liveKey = key
        val prev = r.lines.getOrNull(i - 1)?.text ?: ""
        val next = (if (i < 0) r.lines.firstOrNull() else r.lines.getOrNull(i + 1))?.text ?: ""
        lyrPrevTv.text = prev; lyrPrevTv.visibility = if (prev.isEmpty()) View.GONE else View.VISIBLE
        lyrNextTv.text = next; lyrNextTv.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        if (l == null) { lyrCurTv.text = "♪ ♪ ♪"; lyrCurTv.setTextColor(0x80FFFFFF.toInt()) }
        else {
            var chars = 0; var seen = 0
            for (w in words) { if (seen >= k) break; val at = l.text.indexOf(w, chars); chars = at + w.length; seen++ }
            val e = chars.coerceIn(0, l.text.length)
            val sp = SpannableString(l.text)
            sp.setSpan(ForegroundColorSpan(Color.WHITE), 0, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(0x8CFFFFFF.toInt()), e, l.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            lyrCurTv.text = sp
        }
        lyrCurTv.visibility = View.VISIBLE
        if (i != liveIdx) { liveIdx = i; lyrCurTv.alpha = 0f; lyrCurTv.animate().alpha(1f).setDuration(220L).start() }
    }

    // ===== بناء المشغّل الكامل =====
    private fun buildFull() {
        full.background = bg
        full.setPadding(0, ui.dp(6), 0, ui.dp(14))
        // شريط علوي: ⌄ · مقبض · ⋮
        val top = FrameLayout(act)
        top.addView(GlyphBtn(act, "chev", 24).apply { setOnClickListener { hideFull() } }, FrameLayout.LayoutParams(ui.dp(56), ui.dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(View(act).apply { background = ui.box(0x59FFFFFF, Color.TRANSPARENT, 3) }, FrameLayout.LayoutParams(ui.dp(40), ui.dp(5), Gravity.CENTER))
        val dots = GlyphBtn(act, "dots", 24).apply { setOnClickListener { v -> menu(v) } }
        top.addView(dots, FrameLayout.LayoutParams(ui.dp(56), ui.dp(48), Gravity.END or Gravity.CENTER_VERTICAL))

        liveReset(); liveStatus.visibility = View.GONE

        // الدايرة
        orb.onToggle = { toggle() }
        orb.onStep = { d -> userStep(d) }
        orb.onSeek = { f -> if (MusicEngine.durMs > 0) MusicEngine.seekTo((MusicEngine.durMs.toLong() * f).toInt()) }
        orb.seekLabel = { f -> VideoLib.fmtDur((MusicEngine.durMs.toLong() * f).toLong()) }

        // الأزرار
        val ctl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(14), 0, ui.dp(14), 0) }
        bShuffle.setOnClickListener { val on = MusicEngine.toggleShuffle(); paintMode(); Notice.show(act, if (on) "🔀 العشوائي شغّال" else "➡️ العشوائي اتقفل", 1300L) }
        bRepeat.setOnClickListener { cycleMode() }
        bPrev.setOnClickListener { if (MusicEngine.current != null) orb.commit(-1) }
        bNext.setOnClickListener { if (MusicEngine.current != null) orb.commit(1) }
        bPlay.setOnClickListener { toggle() }
        ctl.addView(bShuffle, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
        ctl.addView(bPrev, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
        ctl.addView(bPlay, LinearLayout.LayoutParams(0, ui.dp(72), 1.3f))
        ctl.addView(bNext, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
        ctl.addView(bRepeat, LinearLayout.LayoutParams(0, ui.dp(64), 1f))

        // الترتيب: [الكلمات] [الدايرة] [اسم الأغنية] — والوسط (inner) بيتغطى بصفحة الكلمات الكاملة لما تتفتح
        inner.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))
        // اسم الأغنية والفنان + زرار إظهار الكلمات جنبهم
        val titleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(fTitle, LinearLayout.LayoutParams(-1, -2)); titleCol.addView(fArtist, LinearLayout.LayoutParams(-1, -2))
        val titleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(18), 0, ui.dp(14), 0) }
        titleRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
        titleRow.addView(lyrBtn, LinearLayout.LayoutParams(-2, -2))
        inner.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2); bottomMargin = ui.dp(4) })
        buildLyricsPanel(); lyrPanel.visibility = View.GONE
        // الكلمات ورا الدواير: لوحة الكلمات تحت، والدايرة (inner) فوقها
        middle.addView(lyrPanel, FrameLayout.LayoutParams(-1, -1)); middle.addView(inner, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(top, LinearLayout.LayoutParams(-1, ui.dp(48)))
        col.addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(eq, LinearLayout.LayoutParams(-1, ui.dp(58)).apply { setMargins(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(2)) })
        col.addView(ctl, LinearLayout.LayoutParams(-1, ui.dp(76)))
        full.addView(col, FrameLayout.LayoutParams(-1, -1))
        setBg(bgColor)
    }
    private fun setBg(col: Int) {
        bg.setColors(intArrayOf(col, blend(col, BLACK, 0.55f), blend(col, BLACK, 0.88f), BLACK))
    }
    private fun animateBg(to: Int) {
        bgAnim?.cancel()
        val from = bgColor
        bgAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 450L
            addUpdateListener { bgColor = it.animatedValue as Int; setBg(bgColor) }
            start()
        }
    }
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
    /** لون الخلفية واللون المميّز للكبسولة من الغلاف الحالي */
    private fun tint(b: Bitmap?) {
        if (tintDone && b === tintBmp) return
        tintDone = true; tintBmp = b
        val col = ArtColor.dominant(b) ?: 0xFF7B4A9E.toInt()       // من غير غلاف: بنفسجي زي التصميم
        animateBg(col)
        miniFill.setBackgroundColor((col and 0x00FFFFFF) or 0x4A000000)
    }

    // ===== قايمة ⋮ =====
    private fun menu(anchor: View) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (lyrOn) "🙈 إخفاء الكلمات" else "📝 إظهار الكلمات") to { toggleLyrics() }
        if (!canEq()) items += "📊 تفعيل المؤثر الموسيقي (إذن)" to { askEq() }
        items += "🎧 تعرّف على أغنية شغّالة في تطبيق تاني" to { try { act.startActivity(android.content.Intent(act, SongRequestActivity::class.java)) } catch (_: Throwable) {} }
        items += "🔊 مستوى الصوت والتضخيم" to { volumeDialog() }
        items += "🖼 البوستر غلط" to { wrongPoster() }
        items += "🔎 تعرّف على الأغنية" to { identify() }
        items += "❌ الأغنية غلط" to { wrongSong() }
        items += "🔄 ريفريش (الاسم والكلمات والبوستر)" to { refreshAll() }
        items += (if (autoLyr()) "⏹ إيقاف الكلمات التلقائية" else "▶ تشغيل الكلمات التلقائية") to {
            Cfg.put("music_auto_lyr", if (autoLyr()) "0" else "1")
            Notice.show(act, if (autoLyr()) "🎤 الكلمات هتتجاب تلقائيًا لما تفتح المشغّل" else "🎤 الكلمات مش هتتجاب إلا لما تدوس عليها", 2400L)
        }
        val pm = android.widget.PopupMenu(act, anchor)
        items.forEachIndexed { i, it -> pm.menu.add(0, i, i, Icons.convert(it.first)) }
        pm.setOnMenuItemClickListener { m -> items[m.itemId].second(); true }
        pm.show()
    }
    private fun volumeDialog() {
        val bar = DualVolBar(act, th.primary, th.border).apply { max = 200; progress = MusicEngine.volPct; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(8), 0, ui.dp(8), 0) }
        val lbl = ui.text("", 14f, th.text, true).apply { gravity = Gravity.CENTER }
        fun paintVol() {
            val p = MusicEngine.volPct
            lbl.text = if (p <= 100) "$p%" else "$p%  " + MusicEngine.boostDbText()
            lbl.setTextColor(if (p > 100) 0xFFFF5252.toInt() else th.text)
        }
        paintVol()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { MusicEngine.setVol(p); paintVol() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(lbl, LinearLayout.LayoutParams(-1, -2)); col.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(44)))
        GAlert(act).setTitle("🔊 مستوى الصوت").setMessage("بعد 100% (الأحمر) بيبقى تضخيم للصوت.").setView(col).setPositiveButton("تمام", null).show()
    }

    private val repeatNames = arrayOf("➡️ تشغيل بالترتيب (من غير تكرار)", "🔁 تكرار الكل", "🔂 تكرار الأغنية دي")
    private fun cycleMode() {
        val m = MusicEngine.cycleMode(); paintMode()
        Notice.show(act, repeatNames[m], 1400L)
    }
    private fun paintMode() {
        bShuffle.set("shuffle", if (MusicEngine.shuffle) BLUE else OFF)
        val m = MusicEngine.mode
        bRepeat.set(if (m == 2) "repeat1" else "repeat", if (m == 0) OFF else BLUE)
    }
    fun showFull() {
        if (MusicEngine.current == null) return
        fullOn = true; full.visibility = View.VISIBLE
        if (!canEq() && Cfg.str("eq_asked", "") != "1") {
            Cfg.put("eq_asked", "1"); Notice.show(act, "📊 المؤثر الموسيقي محتاج إذن «تسجيل الصوت» (للتحليل بس، مفيش تسجيل)", 3200L); askEq()
        }
        paint(); refreshNow()
        loadLyrics()
    }
    private fun hideFull() { snapLyrReset(); eq.release(); fullOn = false; full.visibility = View.GONE }
    /** الاسم المعروض: الاسم اللي اتحفظ من التعرف لو موجود، وإلا بيانات الملف */
    private fun nameOf(t: Track): Pair<String, String> { val o = SongId.override(t.id); return (o?.first?.ifBlank { null } ?: t.title) to (o?.second?.ifBlank { null } ?: t.artist) }

    // ===== الأغلفة =====
    /** غلاف المدمج في الملف، وإلا من الإنترنت (ويكيبيديا ← iTunes ← Deezer ← صورة الفنان) */
    private fun fetchArt(t: Track): Bitmap? {
        var bmp: Bitmap? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(act, Uri.parse(t.uri))
            val b = if (Cfg.str("noemb_${t.id}", "").isEmpty()) r.embeddedPicture else null
            if (b != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(b, 0, b.size, o)
                var ss = 1; while (o.outWidth / ss > 900 || o.outHeight / ss > 900) ss *= 2
                bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
            }
        } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
        if (bmp == null) { val n = nameOf(t); bmp = Poster.fetchSync(act, n.first, n.second) }
        return bmp
    }
    private fun ensureArt(t: Track?) {
        if (t == null) return
        val k = t.uri
        if (artCache.get(k) != null || k in artNone || !artLoading.add(k)) return
        Thread {
            val b = try { fetchArt(t) } catch (_: Throwable) { null }
            if (b != null) try { FaceFocus.compute(b) } catch (_: Throwable) {}
            act.runOnUiThread {
                artLoading.remove(k)
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (b != null) artCache.put(k, b) else artNone.add(k)
                if (k == MusicEngine.current?.uri) {
                    if (b == null && notifyNoPoster) Notice.show(act, "🖼 مفيش بوستر تاني — هيفضل شكل الدايرة (🔄 ريفريش يرجّع البحث)", 3200L)
                    notifyNoPoster = false
                }
                refreshArts()
            }
        }.apply { isDaemon = true }.start()
    }
    private fun forgetArt(t: Track) { artCache.remove(t.uri); artNone.remove(t.uri); tintDone = false }
    private fun refreshArts() {
        val cur = MusicEngine.current
        val pv = MusicEngine.neighbor(-1); val nx = MusicEngine.neighbor(1)
        ensureArt(cur)
        if (fullOn) { ensureArt(pv); ensureArt(nx) }
        val cb = cur?.let { artCache.get(it.uri) }
        orb.setArts(pv?.let { artCache.get(it.uri) }, cb, nx?.let { artCache.get(it.uri) }, pv != null, nx != null)
        if (cb == null) miniArt.setImageDrawable(null) else miniArt.setImageBitmap(cb)
        tint(cb)
    }

    // ===== التشغيل =====
    /** دوسة على أغنية: طابور التشغيل = القايمة المعروضة بترتيبها (فولدر/بحث/كل الأغاني) */
    private fun playFrom(v: VideoItem) {
        val q = lib.shownVideos().ifEmpty { listOf(v) }
        val tracks = q.mapNotNull { MusicScan.trackFor(it) }
        val i = tracks.indexOfFirst { it.id == v.id }
        if (i < 0) { Notice.show(act, "الأغنية دي مش موجودة في الفحص — دوس 🔄", 2400L); return }
        MusicEngine.play(act, tracks, i); showFull()
    }
    private fun playFolder(f: FolderItem) {
        val tracks = f.videos.sortedWith { a, b -> VideoLib.natural(a.name, b.name) }.mapNotNull { MusicScan.trackFor(it) }
        if (tracks.isEmpty()) return
        MusicEngine.play(act, tracks, 0); showFull()
    }
    private fun step(d: Int) { MusicEngine.step(d) }
    /** تالي/سابق من الدايرة (سحب أو زرار): بيرجّع true لو الأغنية اتغيّرت فعلًا (السابق بعد 3 ثواني بيرجّع لأول الأغنية بس) */
    private fun userStep(d: Int): Boolean {
        val before = MusicEngine.current?.uri
        step(d)
        val a = MusicEngine.current?.uri
        return a != null && a != before
    }
    private fun toggle() { MusicEngine.toggle() }
    private fun paint() {
        val pl = MusicEngine.isPlaying
        val g = if (pl) "pause" else "play"
        bPlay.set(g); miniPlay.set(g); orb.playing = pl; eq.playing = pl
        paintMode()
    }
    private fun paintMini() { miniBar.visibility = if (MusicEngine.current == null) View.GONE else View.VISIBLE }
    private fun updateMiniFill(frac: Float) {
        val w = (miniBar.width * frac.coerceIn(0f, 1f)).toInt()
        if (Math.abs(w - miniFillW) < 2) return
        miniFillW = w
        val lp = miniFill.layoutParams as FrameLayout.LayoutParams
        lp.width = w; miniFill.layoutParams = lp
    }
    private fun refreshNow() {
        val t = MusicEngine.current
        val nm = t?.let { nameOf(it) }
        nowTitle.text = nm?.first ?: ""; nowSub.text = nm?.second ?: ""
        fTitle.text = nm?.first ?: "مفيش حاجة شغّالة"; fArtist.text = nm?.second ?: ""
        orb.initial = (nm?.first ?: "♪").trim().take(1).ifEmpty { "♪" }
        if (t?.uri != curUri) {
            curUri = t?.uri
            if (t != null) { artNone.remove(t.uri); tintDone = false }
            if (t == null) { orb.progress = 0f; orb.timeText = "0:00"; miniFillW = -1; updateMiniFill(0f); liveReset() }
        }
        refreshArts()
        // أغنية اتغيّرت والمشغّل مفتوح → حمّل كلماتها
        if (fullOn && t != null && lyrKey != t.uri + "|" + LyricsEngine.mode()) loadLyrics()
    }
    private fun tick() {
        if (MusicEngine.ready) {
            val d = MusicEngine.durMs; val p = MusicEngine.posMs
            if (d > 0) {
                val f = p.toFloat() / d
                orb.progress = f; orb.timeText = VideoLib.fmtDur(p.toLong())
                updateMiniFill(f)
            }
            paint()
            if (fullOn) { lyricsTick(); lyricsPoll(); eq.bind(MusicEngine.sessionId, canEq()) }
        }
        h.postDelayed({ tick() }, if (fullOn) 120L else 300L)
    }

    // ===== الفحص (نفس طريقة الفيديوهات: كاش فوري + فحص في الخلفية) =====
    private var started = false
    fun ensureLoaded() { if (!started) { started = true; load() } }
    fun load() {
        if (!MusicScan.hasPermission(act)) { lib.showNoPermission(); return }
        val c = MusicScan.items
        if (c != null) lib.showVideos(c) else if (!lib.hasData) lib.showScanning()
        Thread {
            val res = try { MusicScan.scan(act); MusicScan.items ?: emptyList() } catch (_: Exception) { emptyList<VideoItem>() }
            act.runOnUiThread { if (!act.isDestroyed && !act.isFinishing) lib.showVideos(res) }
        }.apply { isDaemon = true }.start()
    }
    /** ريفريش (الزرار فوق أو السحب): يمسح الكاش ويفحص من الأول */
    fun reload(full: Boolean = false) {
        MusicScan.invalidate(); started = true
        if (full) { Thumbs.clear(); lib.showScanning() }
        load()
    }
    // ===== (v192) التعرف على الأغنية =====
    private var idBusy = false
    private fun askToken(then: () -> Unit) {
        val et = android.widget.EditText(act).apply { hint = "توكن AudD"; setSingleLine(); setText(SongId.token()); setTextColor(th.text); setHintTextColor(th.muted) }
        GAlert(act).setTitle("🔎 توكن خدمة التعرف (AudD)")
            .setMessage("سجّل في audd.io وخد التوكن وحطه هنا (مرة واحدة). الخدمة ليها حد مجاني وبعده بتتحاسب، والمقطع بيتبعت لسيرفراتهم.")
            .setView(et)
            .setPositiveButton("حفظ") { _, _ -> if (et.text.toString().isNotBlank()) { SongId.saveToken(et.text.toString()); then() } }
            .setNegativeButton("إلغاء", null).show()
    }
    private fun identify(avoid: List<String> = emptyList()) {
        val t = MusicEngine.current
        if (t == null) { Notice.show(act, "شغّل الأغنية الأول وبعدين دوس 🔎", 2400L); return }
        if (idBusy) { Notice.show(act, "لسه بدوّر…", 1500L); return }
        idBusy = true
        Notice.show(act, if (SongId.token().isEmpty() || avoid.isNotEmpty()) "🔎 جيميناي بيسمع مقطع من الأغنية…" else "🔎 بسمع مقطع من الأغنية…", 2500L)
        // لو الأغنية شغّالة ناخد من مكان التشغيل، وإلا من نص الأغنية
        val start = if (MusicEngine.posMs > 5000 && avoid.isEmpty()) MusicEngine.posMs / 1000.0 else maxOf(10.0, t.durMs / 2000.0)
        SongId.auto(act, t, start, avoid) { m, msg ->
            act.runOnUiThread {
                idBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (m == null) {
                    val g = GAlert(act).setTitle("🔎 ما اتعرفتش").setMessage(msg)
                    g.setNeutralButton("توكن AudD") { _, _ -> askToken { } }
                    g.setPositiveButton("تمام", null).show()
                } else {
                    val line = listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" — ")
                    GAlert(act).setTitle("🔎 الأغنية دي غالبًا:")
                        .setMessage(line + (if (m.album.isNotBlank()) "\nالألبوم: " + m.album else "") + "\n\nتحفظ الاسم ده للأغنية؟ (هجيب الغلاف والكلمات بيه)")
                        .setPositiveButton("حفظ الاسم") { _, _ -> applyIdentified(t, m) }
                        .setNegativeButton("لأ، غلط") { _, _ -> identify(avoid + line) }.show()
                }
            }
        }
    }


    private val triedNames = HashMap<Long, ArrayList<String>>()
    /** الأغنية اتعرّفت غلط: نعرّفها تاني (جيميناي) من غير الأسماء اللي اتقال إنها غلط، ونحفظ الاسم الجديد ونجيب الغلاف والكلمات */
    private fun wrongSong() {
        val t = MusicEngine.current ?: run { Notice.show(act, "شغّل أغنية الأول", 1500L); return }
        if (idBusy) { Notice.show(act, "لسه بدوّر…", 1500L); return }
        val n = nameOf(t)
        val tried = triedNames.getOrPut(t.id) { ArrayList() }
        listOf(n.first + " — " + n.second, n.first).filter { it.isNotBlank() && it !in tried }.forEach { tried.add(it) }
        idBusy = true
        Notice.show(act, "🔎 بدوّر على الأغنية الصح…", 2500L)
        val start = maxOf(8.0, t.durMs / 1000.0 * (0.25 + 0.2 * (tried.size % 3)))
        SongId.auto(act, t, start, tried.toList()) { m, msg ->
            act.runOnUiThread {
                idBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (m == null) { GAlert(act).setTitle("🔎 ما قدرتش أعرفها").setMessage(msg).setPositiveButton("تمام", null).show(); return@runOnUiThread }
                applyIdentified(t, m)
                Notice.show(act, "✅ الأغنية: " + listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" — "), 3000L)
            }
        }
    }
    /** ريفريش: الاسم (لو مش محفوظ يدويًا) والغلاف والكلمات من الأول */
    private fun refreshAll() {
        val t = MusicEngine.current ?: return
        if (LyricsEngine.isRunning(t, LyricsEngine.mode()) || idBusy) { Notice.show(act, "لسه شغّال… استنى شوية", 1500L); return }
        val n = nameOf(t)
        Poster.resetSkip(n.first, n.second); Poster.forget(act, n.first, n.second); Cfg.put("noemb_${t.id}", "")
        forgetArt(t); refreshNow()
        LyricsEngine.clearAll(act, t); lyr = null; lyrKey = null
        if (SongId.override(t.id) == null) {
            idBusy = true
            Notice.show(act, "🔎 بحدّث الاسم والكلمات والبوستر…", 2500L)
            SongId.auto(act, t, maxOf(10.0, t.durMs / 2000.0), emptyList()) { m, _ ->
                act.runOnUiThread {
                    idBusy = false
                    if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                    if (m != null) applyIdentified(t, m) else if (fullOn) loadLyrics(force = true)
                }
            }
        } else if (fullOn) loadLyrics(force = true)
    }
    /** البوستر غلط: انسى الغلاف ده (حتى لو كان مدمج في الملف) وجرّب اللي بعده (ويكيبيديا/iTunes/Deezer/صورة الفنان) */
    private fun wrongPoster() {
        val t = MusicEngine.current ?: run { Notice.show(act, "شغّل أغنية الأول", 1500L); return }
        val n = nameOf(t)
        Poster.reject(act, n.first, n.second); Cfg.put("noemb_${t.id}", "1")
        notifyNoPoster = true; forgetArt(t)
        Notice.show(act, "🖼 بدوّر على بوستر تاني…", 1800L)
        refreshNow()
    }

    /** اسم جديد اتأكد: احفظه، صفّر الغلاف القديم وهات الجديد، وعيد الكلمات */
    private fun applyIdentified(t: Track, m: SongMatch) {
        val old = nameOf(t)
        SongId.saveOverride(t.id, m)
        Poster.forget(act, old.first, old.second); Poster.forget(act, m.title, m.artist)
        LyricsEngine.clearAll(act, t); lyrKey = null; lyr = null
        forgetArt(t); refreshNow()
        reload()
        if (fullOn) loadLyrics(force = true)
    }

    init {
        // الجسم: صفحة الفيديوهات-بتاعة-الصوت فوق + الكبسولة الصغيرة تحت
        body.addView(lib.root, LinearLayout.LayoutParams(-1, 0, 1f))
        miniBar.addView(miniFill, FrameLayout.LayoutParams(0, -1, Gravity.START))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(9), 0, ui.dp(6), 0) }
        row.addView(miniArt, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        col.addView(nowTitle, LinearLayout.LayoutParams(-1, -2)); col.addView(nowSub, LinearLayout.LayoutParams(-1, -2))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10); marginEnd = ui.dp(6) })
        row.addView(miniPlay, LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)))
        row.addView(miniNext, LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)))
        miniBar.addView(row, FrameLayout.LayoutParams(-1, -1))
        miniPlay.setOnClickListener { toggle() }
        miniNext.setOnClickListener { step(1) }
        miniBar.setOnClickListener { showFull() }
        body.addView(miniBar, LinearLayout.LayoutParams(-1, ui.dp(62)).apply { setMargins(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) })
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        MusicEngine.loadPrefs()
        buildFull()
        root.addView(full, FrameLayout.LayoutParams(-1, -1)); full.visibility = View.GONE

        lib.onPlayFolder = { f -> playFolder(f) }
        lib.onGrant = { onGrant() }
        lib.onPull = { reload() }
        lib.onRefresh = { reload(true) }
        MusicEngine.listeners.add(onEngine)
        refreshNow(); paint(); paintMini()
        tick()
    }

    /** معرّف جلسة الصوت الحالية (للإيكولايزر لاحقًا) — 0 لو مفيش مشغّل */
    val sessionId: Int get() = MusicEngine.sessionId

    /** زرار الرجوع: يقفل الكلمات الكاملة، بعدين المشغّل، وبعدين يرجع من الفولدر/البحث/المخفي (زي الفيديوهات) */
    fun onBack(): Boolean {
        if (fullOn) { if (lyrOn) hideLyrics() else hideFull(); return true }
        return lib.back()
    }
    /** بيفصل الشاشة عن المحرك — الموسيقى بتكمّل في الخلفية (الإيقاف من ✕ في الإشعار) */
    fun destroy() { h.removeCallbacksAndMessages(null); MusicEngine.listeners.remove(onEngine) }
    /** بيوقف مؤقتًا لو فيه حاجة شغّالة (مثلًا لما فيديو يتفتح) */
    fun pause() { MusicEngine.pause() }
}
