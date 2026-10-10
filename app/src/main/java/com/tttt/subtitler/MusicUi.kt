package com.tttt.subtitler

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
import android.widget.SeekBar
import android.widget.TextView

/** (v189) ملف صوت من الجهاز */
class Track(val id: Long, val uri: String, val title: String, val artist: String, val durMs: Long, val folderKey: String, val folderName: String)

/** فحص ملفات الصوت (MP3 / M4A / FLAC / OGG / WAV…) عن طريق MediaStore — بنفس فكرة فولدرات الفيديو */
object MusicScan {
    @Volatile var cache: List<Track>? = null
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val base: Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = ArrayList<String>().apply {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.DISPLAY_NAME); add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
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
                val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                while (c.moveToNext()) {
                    val dur = c.getLong(iDur)
                    if (dur in 1..4999) continue   // أصوات قصيرة جدًا (إشعارات / كليكات)
                    val name = c.getString(iName) ?: continue
                    val title = (c.getString(iTitle) ?: "").ifBlank { name.substringBeforeLast('.') }
                    var artist = c.getString(iArt) ?: ""
                    if (artist == "<unknown>") artist = ""
                    val (key, fname, _) = VideoLib.folderInfo(c.getString(iData), if (iRel >= 0) c.getString(iRel) else null, c.getString(iBuck))
                    val ov = SongId.override(c.getLong(iId))
                    out.add(Track(c.getLong(iId), ContentUris.withAppendedId(base, c.getLong(iId)).toString(), ov?.first?.ifBlank { null } ?: title, ov?.second?.ifBlank { null } ?: artist, dur, key, fname))
                }
            }
        } catch (_: Exception) {}
        cache = out
        return out
    }
}

/** (v189) صفحة «🎵 الموسيقى»: فولدرات الصوتيات ← الأغاني ← مشغّل أساسي تحت (تشغيل/إيقاف · السابق/التالي · شريط التقدم).
 *  الإيكولايزر وسلايدر تضخيم الصوت في الجزء التاني — [sessionId] جاهز عشانهم. */
class MusicUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    val root = FrameLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    var onGrant: () -> Unit = {}

    private val h = Handler(Looper.getMainLooper())
    private val title = ui.text("🎵 الموسيقى", 18f, th.primary, true)
    private val back = ui.button("‹ رجوع") { openFolder(null) }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }

    // المشغّل
    private var dragging = false
    private val onEngine: () -> Unit = { paint(); refreshNow(); if (folder != null) showTracks(); if (lyricsOn) lyricsForCurrent() else resumeBg() }
    private val nowTitle = ui.text("مفيش حاجة شغّالة", 14f, th.text, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val nowSub = ui.text("", 11f, th.muted).apply { setSingleLine() }
    private val seek = SeekBar(act).apply { max = 1000 }
    private val timeL = ui.text("0:00", 11f, th.muted)
    private val timeR = ui.text("0:00", 11f, th.muted)
    private val playBtn = ui.button("▶") { toggle() }

    // ===== (v193) مشغّل الأسطوانة (الشاشة الكاملة) =====
    private val full = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; isClickable = true }
    private val vinyl = VinylView(act, th.primary)
    private val fTitle = ui.text("مفيش حاجة شغّالة", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val fArtist = ui.text("", 13f, 0xFFB4B7BF.toInt()).apply { gravity = Gravity.CENTER; setSingleLine() }
    private val fSeek = SeekBar(act).apply { max = 1000; layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val fTimeL = ui.text("0:00", 11f, 0xFFB4B7BF.toInt())
    private val fTimeR = ui.text("0:00", 11f, 0xFFB4B7BF.toInt())
    private val fPlay = ui.text("▶", 28f, if (th.isLight) Color.WHITE else Color.BLACK).apply {
        gravity = Gravity.CENTER; includeFontPadding = false
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.primary, th.accent)).apply { shape = GradientDrawable.OVAL }
        elevation = ui.dp(6).toFloat(); setOnClickListener { toggle() }
    }
    private val fMode = ui.text("🔁", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; setOnClickListener { cycleMode() } }
    private val fModeLbl = ui.text("بدون تكرار", 10f, 0xFFB4B7BF.toInt()).apply { gravity = Gravity.CENTER }
    private val fVol = DualVolBar(act, th.primary, 0x33FFFFFF).apply { max = 200; progress = 100; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(8), 0, ui.dp(8), 0) }
    private val fVolLbl = ui.text("100%", 12f, Color.WHITE, true).apply { gravity = Gravity.CENTER; minWidth = ui.dp(86) }
    private var fullOn = false
    private var fDrag = false
    private var artFor: String? = null

    private fun buildFull() {
        val tint = th.primary
        full.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(tint, 0xFF0A0A10.toInt(), 0.62f), 0xFF0A0A10.toInt(), 0xFF000000.toInt()))
        full.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(14))
        // شريط علوي
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text("✕", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); setOnClickListener { hideFull() } }, LinearLayout.LayoutParams(-2, -2))
        top.addView(ui.text("💿 الآن بيشغّل", 14f, 0xFFD0D3DA.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(ui.text("🖼", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); setOnClickListener { wrongPoster() } }, LinearLayout.LayoutParams(-2, -2))
        top.addView(ui.text("🔎", 20f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); setOnClickListener { identify() } }, LinearLayout.LayoutParams(-2, -2))
        full.addView(top, LinearLayout.LayoutParams(-1, -2))
        // الاسطوانة
        vinyl.setOnClickListener { toggle() }
        full.addView(vinyl, LinearLayout.LayoutParams(-1, 0, 1f))
        full.addView(fTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        full.addView(fArtist, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
        // السلايدر
        val tl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        tl.addView(fTimeL); tl.addView(fSeek, LinearLayout.LayoutParams(0, -2, 1f)); tl.addView(fTimeR)
        full.addView(tl, LinearLayout.LayoutParams(-1, -2))
        try {
            val cs = android.content.res.ColorStateList.valueOf(th.primary)
            fSeek.progressTintList = cs; fSeek.thumbTintList = cs
            fSeek.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        } catch (_: Throwable) {}
        fSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u && MusicEngine.durMs > 0) fTimeL.text = VideoLib.fmtDur(MusicEngine.durMs.toLong() * p / 1000) }
            override fun onStartTrackingTouch(s: SeekBar?) { fDrag = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                fDrag = false
                if (MusicEngine.durMs > 0) MusicEngine.seekTo((MusicEngine.durMs.toLong() * (s?.progress ?: 0) / 1000).toInt())
            }
        })
        // الأزرار: وضع التكرار · السابق · تشغيل · التالي · الكلمات
        val ctl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val modeBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { cycleMode() } }
        modeBox.addView(fMode, LinearLayout.LayoutParams(-2, -2)); modeBox.addView(fModeLbl, LinearLayout.LayoutParams(-2, -2))
        fun side(t: String, f: () -> Unit) = ui.text(t, 26f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); setOnClickListener { f() } }
        ctl.addView(modeBox, LinearLayout.LayoutParams(0, -2, 1f))
        ctl.addView(side("⏮") { step(-1) }, LinearLayout.LayoutParams(0, -2, 1f))
        ctl.addView(fPlay, LinearLayout.LayoutParams(ui.dp(70), ui.dp(70)))
        ctl.addView(side("⏭") { step(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        ctl.addView(ui.text("🎤", 22f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); setOnClickListener { hideFull(); if (!lyricsOn) toggleLyrics() } }, LinearLayout.LayoutParams(0, -2, 1f))
        full.addView(ctl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        // الصوت + التضخيم (نفس فكرة مشغّل الفيديو: بعد 100% بيبقى أحمر = تضخيم)
        val vr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        vr.addView(ui.text("🔊", 18f, Color.WHITE).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) })
        vr.addView(fVol, LinearLayout.LayoutParams(0, ui.dp(36), 1f))
        vr.addView(fVolLbl)
        full.addView(vr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        fVol.progress = MusicEngine.volPct; paintVol()
        fVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) { MusicEngine.setVol(p); paintVol() } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    private fun paintVol() {
        val p = MusicEngine.volPct
        fVolLbl.text = if (p <= 100) "$p%" else "$p%  " + MusicEngine.boostDbText()
        fVolLbl.setTextColor(if (p > 100) 0xFFFF5252.toInt() else Color.WHITE)
    }
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
    private val modeNames = arrayOf("بدون تكرار", "تكرار الكل", "تكرار أغنية", "عشوائي")
    private fun cycleMode() {
        val m = MusicEngine.cycleMode(); paintMode()
        Notice.show(act, when (m) { 0 -> "➡️ تشغيل بالترتيب (من غير تكرار)"; 1 -> "🔁 تكرار الكل"; 2 -> "🔂 تكرار الأغنية دي"; else -> "🔀 عشوائي" }, 1400L)
    }
    private fun paintMode() {
        val m = MusicEngine.mode
        fMode.text = when (m) { 2 -> "🔂"; 3 -> "🔀"; else -> "🔁" }
        fMode.alpha = if (m == 0) 0.4f else 1f
        fMode.setTextColor(if (m == 0) Color.WHITE else th.primary)
        fModeLbl.text = modeNames[m]
    }
    fun showFull() {
        if (MusicEngine.current == null) return
        fullOn = true; full.visibility = View.VISIBLE
        fVol.progress = MusicEngine.volPct; paintVol(); paint(); refreshNow()
    }
    private fun hideFull() { fullOn = false; full.visibility = View.GONE }
    /** زرار الرجوع: يقفل مشغّل الأسطوانة، وبعدين يرجع من الفولدر للقايمة */
    fun onBack(): Boolean {
        if (fullOn) { hideFull(); return true }
        if (lyricsOn) { toggleLyrics(); return true }
        if (folder != null) { openFolder(null); return true }
        return false
    }
    /** الاسم المعروض: الاسم اللي اتحفظ من التعرف لو موجود، وإلا بيانات الملف */
    private fun nameOf(t: Track): Pair<String, String> { val o = SongId.override(t.id); return (o?.first?.ifBlank { null } ?: t.title) to (o?.second?.ifBlank { null } ?: t.artist) }
    private fun loadArt(t: Track?) {
        val uri = t?.uri
        if (uri == artFor) return
        artFor = uri; vinyl.art = null
        if (uri == null) return
        Thread {
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(act, Uri.parse(uri))
                val b = if (Cfg.str("noemb_${t?.id}", "").isEmpty()) r.embeddedPicture else null
                if (b != null) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(b, 0, b.size, o)
                    var ss = 1; while (o.outWidth / ss > 900 || o.outHeight / ss > 900) ss *= 2
                    bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = ss })
                }
            } catch (_: Throwable) {} finally { try { r.release() } catch (_: Throwable) {} }
            // مفيش غلاف جوه الملف → ندوّر عليه في قواعد البيانات (iTunes ثم Deezer) باسم الأغنية والفنان
            if (bmp == null && t != null) { val n = nameOf(t); bmp = Poster.fetchSync(act, n.first, n.second) }
            act.runOnUiThread {
                if (!act.isDestroyed && artFor == uri) {
                    vinyl.art = bmp
                    if (bmp == null && notifyNoPoster) Notice.show(act, "🖼 مفيش بوستر تاني — هيفضل شكل الاسطوانة (🔄 ريفريش يرجّع البحث)", 3200L)
                    notifyNoPoster = false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ===== (v191) الكلمات المتزامنة =====
    private var listScroll: ScrollView? = null   // لازم يتعرّف قبل init عشان قيمته ماتتصفّرش
    private var lyricsOn = false
    private var lyr: LyricsResult? = null
    private var lyrFor: String? = null          // uri الأغنية اللي الكلمات بتاعتها
    private var lyrBusy = false
    private var lyrIdx = -2
    private var lyrWordKey = ""
    private val lyrBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(160)) }
    private val lyrScroll = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(lyrBox) }
    private val lyrViews = ArrayList<TextView>()
    private val lyrBtn = ui.button("🎤") { toggleLyrics() }
    private val idBtn = ui.button("🔎") { identify() }

    private var all: List<Track> = emptyList()
    private var folder: String? = null   // null = قايمة الفولدرات · "*" = كل الأغاني · غير كده مفتاح فولدر

    /** معرّف جلسة الصوت الحالية (للإيكولايزر لاحقًا) — 0 لو مفيش مشغّل */
    val sessionId: Int get() = MusicEngine.sessionId

    init {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(6)) }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(back, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
        head.addView(ui.button("🔄") { reload() }, LinearLayout.LayoutParams(-2, -2))
        body.addView(head, LinearLayout.LayoutParams(-1, -2))
        val sv = ScrollView(act).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }
        body.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(lyrScroll, LinearLayout.LayoutParams(-1, 0, 1f)); lyrScroll.visibility = View.GONE
        listScroll = sv

        // شريط المشغّل (تحت)
        val bar = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.card); setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) }
        bar.addView(nowTitle); bar.addView(nowSub)
        val tl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        tl.addView(timeL); tl.addView(seek, LinearLayout.LayoutParams(0, -2, 1f)); tl.addView(timeR)
        bar.addView(tl, LinearLayout.LayoutParams(-1, -2))
        val ctl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER }
        ctl.addView(ui.button("⏮") { step(-1) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        ctl.addView(playBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        ctl.addView(ui.button("⏭") { step(1) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        ctl.addView(lyrBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(14), 0, ui.dp(6), 0) })
        ctl.addView(idBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        bar.addView(ctl, LinearLayout.LayoutParams(-1, -2))
        body.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        // الشريط الصغير: الضغط على اسم الأغنية أو 💿 بيفتح مشغّل الأسطوانة
        nowTitle.setOnClickListener { showFull() }; nowSub.setOnClickListener { showFull() }
        ctl.addView(ui.button("💿") { showFull() }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        MusicEngine.loadPrefs()
        buildFull()
        root.addView(full, FrameLayout.LayoutParams(-1, -1)); full.visibility = View.GONE

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                if (u && MusicEngine.durMs > 0) timeL.text = VideoLib.fmtDur(MusicEngine.durMs.toLong() * p / 1000)
            }
            override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                dragging = false
                if (MusicEngine.durMs > 0) MusicEngine.seekTo((MusicEngine.durMs.toLong() * (s?.progress ?: 0) / 1000).toInt())
            }
        })
        back.visibility = View.GONE
        MusicEngine.listeners.add(onEngine)
        refreshNow(); paint()
        tick()
        showFolders()
    }

    // ===== الفحص =====
    private var started = false
    /** بيتنادى لما الصفحة تتفتح أول مرة */
    fun ensureLoaded() { if (!started) { started = true; load() } }
    fun load() {
        if (!MusicScan.hasPermission(act)) { showNoPermission(); return }
        val c = MusicScan.cache
        if (c != null) { all = c; render() } else showMsg("⏳ بدوّر على ملفات الصوت…")
        Thread {
            val res = try { MusicScan.scan(act) } catch (_: Exception) { emptyList<Track>() }
            act.runOnUiThread { if (!act.isDestroyed && !act.isFinishing) { all = res; render() } }
        }.apply { isDaemon = true }.start()
    }
    private fun reload() { MusicScan.cache = null; started = true; load() }

    // ===== العرض =====
    private fun showMsg(t: String) {
        list.removeAllViews()
        list.addView(ui.text(t, 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(60), ui.dp(24), 0) })
    }
    private fun showNoPermission() {
        list.removeAllViews()
        list.addView(ui.text("محتاج إذن الوصول لملفات الصوت عشان أعرض الموسيقى اللي على الجهاز.", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(60), ui.dp(24), ui.dp(12)) })
        list.addView(ui.button("السماح بالوصول للصوتيات", true) { onGrant() })
    }
    private fun render() { if (folder == null) showFolders() else showTracks() }
    private fun openFolder(k: String?) { folder = k; render() }

    private fun showFolders() {
        list.removeAllViews(); back.visibility = View.GONE; title.text = "🎵 الموسيقى"
        if (all.isEmpty()) { list.addView(ui.text(if (MusicScan.hasPermission(act)) "مفيش ملفات صوت لسه.\nدوس 🔄 لو لسه ضايف ملفات." else "", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(60), ui.dp(24), 0) }); return }
        fun row(label: String, sub: String, k: String) {
            val c = ui.card()
            c.addView(ui.text(label, 15f, th.text, true)); c.addView(ui.text(sub, 11f, th.muted))
            c.setOnClickListener { openFolder(k) }
            list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        row("🎵 كل الأغاني", "${all.size} ملف", "*")
        all.groupBy { it.folderKey }.entries.sortedBy { it.value[0].folderName.lowercase() }.forEach { (k, v) ->
            row("📁 " + v[0].folderName, "${v.size} ملف", k)
        }
    }

    private fun showTracks() {
        val k = folder ?: return
        val tracks = (if (k == "*") all else all.filter { it.folderKey == k }).sortedBy { it.title.lowercase() }
        list.removeAllViews(); back.visibility = View.VISIBLE
        title.text = if (k == "*") "🎵 كل الأغاني" else "📁 " + (tracks.firstOrNull()?.folderName ?: "")
        if (tracks.isEmpty()) { showFolders(); folder = null; return }
        val cur = MusicEngine.current?.uri
        tracks.forEachIndexed { i, t ->
            val c = ui.card()
            val on = t.uri == cur
            c.addView(ui.text((if (on) "▶ " else "🎵 ") + t.title, 14f, if (on) th.primary else th.text, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
            c.addView(ui.text((if (t.artist.isNotBlank()) t.artist + "  ·  " else "") + VideoLib.fmtDur(t.durMs), 11f, th.muted).apply { setSingleLine() })
            c.setOnClickListener { play(tracks, i); showFull() }
            list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
        }
    }

    // ===== التشغيل (المحرك في MusicEngine والخدمة بتكمّله في الخلفية) =====
    fun play(q: List<Track>, i: Int) { MusicEngine.play(act, q, i) }
    private fun step(d: Int) { MusicEngine.step(d) }
    private fun toggle() { MusicEngine.toggle() }
    private fun paint() {
        val pl = MusicEngine.isPlaying
        playBtn.text = if (pl) "⏸" else "▶"
        fPlay.text = if (pl) "⏸" else "▶"; vinyl.playing = pl
        paintMode()
    }
    private fun refreshNow() {
        val t = MusicEngine.current
        nowTitle.text = t?.title ?: "مفيش حاجة شغّالة"; nowSub.text = t?.artist ?: ""
        if (t == null) { seek.progress = 0; timeL.text = "0:00"; timeR.text = "0:00" }
        fTitle.text = t?.let { nameOf(it).first } ?: "مفيش حاجة شغّالة"; fArtist.text = t?.let { nameOf(it).second } ?: ""
        vinyl.initial = (t?.let { nameOf(it).first } ?: "♪").trim().take(1).ifEmpty { "♪" }
        loadArt(t)
        if (t == null) { fSeek.progress = 0; fTimeL.text = "0:00"; fTimeR.text = "0:00"; vinyl.progress = 0f }
    }
    private fun tick() {
        if (MusicEngine.ready && !dragging) {
            val d = MusicEngine.durMs; val p = MusicEngine.posMs
            if (d > 0) { seek.progress = (p.toLong() * 1000 / d).toInt(); timeL.text = VideoLib.fmtDur(p.toLong()); timeR.text = VideoLib.fmtDur(d.toLong()) }
            if (fullOn && d > 0) {
                vinyl.progress = p.toFloat() / d
                if (!fDrag) { fSeek.progress = (p.toLong() * 1000 / d).toInt(); fTimeL.text = VideoLib.fmtDur(p.toLong()) }
                fTimeR.text = VideoLib.fmtDur(d.toLong())
            }
            paint()
            if (lyricsOn) { lyricsTick(); lyricsPoll() }
        }
        h.postDelayed({ tick() }, 300)
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

    // ===== الكلمات =====
    private fun toggleLyrics() {
        lyricsOn = !lyricsOn
        lyrBtn.alpha = if (lyricsOn) 1f else 0.7f
        listScroll?.visibility = if (lyricsOn) View.GONE else View.VISIBLE
        lyrScroll.visibility = if (lyricsOn) View.VISIBLE else View.GONE
        if (lyricsOn) { lyrFor = null; lyricsForCurrent() }
    }
    private var lyrRetry = false
    /** (v193) صف زرارين فوق الكلمات: الأغنية غلط (نعرّفها من الأول) · الكلمات غلط (الأغنية صح بس الكلمات مبوظة) */
    private fun fixRow(): LinearLayout {
        fun row(vararg b: Pair<String, () -> Unit>) = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            b.forEachIndexed { i, (txt, f) -> addView(ui.button(txt) { f() }.apply { textSize = 12.5f }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = ui.dp(6) }) }
        }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(row("❌ الأغنية غلط" to { wrongSong() }, "✍️ الكلمات غلط" to { wrongLyrics() }))
            addView(row("🔄 ريفريش" to { refreshAll() }, "🖼 البوستر غلط" to { wrongPoster() }))
        }
    }
    private val triedNames = HashMap<Long, ArrayList<String>>()
    /** الأغنية اتعرّفت غلط: نعرّفها تاني (جيميناي) من غير الأسماء اللي اتقال إنها غلط، ونحفظ الاسم الجديد ونجيب الغلاف والكلمات */
    private fun wrongSong() {
        val t = MusicEngine.current ?: return
        if (idBusy) { Notice.show(act, "لسه بدوّر…", 1500L); return }
        val n = nameOf(t)
        val tried = triedNames.getOrPut(t.id) { ArrayList() }
        listOf(n.first + " — " + n.second, n.first).filter { it.isNotBlank() && it !in tried }.forEach { tried.add(it) }
        lyr?.let { if (it.title.isNotBlank()) tried.add(it.title + " — " + it.artist) }
        idBusy = true
        Notice.show(act, "🔎 بدوّر على الأغنية الصح…", 2500L)
        // مقطع من مكان مختلف عن اللي فات
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
    /** الأغنية صح والكلمات غلط: امسح المخزّن واكتبها تاني بتركيز أعلى */
    private fun wrongLyrics() {
        val t = MusicEngine.current ?: return
        if (lyrBusy || LyricsEngine.isRunning(t)) { Notice.show(act, "لسه بكتب الكلام…", 1500L); return }
        LyricsEngine.clear(act, t); lyr = null; lyrFor = t.uri
        lyrMsg("✍️ بكتب الكلمات من الأول بتركيز أعلى…")
        startLyrics(t, true, true)
    }
    /** ريفريش: يبعت الطلب تاني لجيميناي من الأول — الاسم (لو مش محفوظ يدويًا) والكلمات والبوستر */
    private fun refreshAll() {
        val t = MusicEngine.current ?: return
        if (lyrBusy || LyricsEngine.isRunning(t) || idBusy) { Notice.show(act, "لسه شغّال… استنى شوية", 1500L); return }
        val n = nameOf(t)
        Poster.resetSkip(n.first, n.second); Poster.forget(act, n.first, n.second); Cfg.put("noemb_${t.id}", "")
        artFor = null; refreshNow()
        LyricsEngine.clear(act, t); lyr = null; lyrFor = t.uri
        lyrMsg("🔄 بعيد الطلب لجيميناي من الأول…")
        if (SongId.override(t.id) == null) {
            idBusy = true
            Notice.show(act, "🔎 بحدّث الاسم والكلمات والبوستر…", 2500L)
            SongId.auto(act, t, maxOf(10.0, t.durMs / 2000.0), emptyList()) { m, _ ->
                act.runOnUiThread {
                    idBusy = false
                    if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                    if (m != null) applyIdentified(t, m) else { lyrFor = t.uri; startLyrics(t, false, true) }
                }
            }
        } else startLyrics(t, false, true)
    }
    private var notifyNoPoster = false
    /** البوستر غلط: انسى الغلاف ده (حتى لو كان مدمج في الملف) وجرّب اللي بعده من قواعد البيانات */
    private fun wrongPoster() {
        val t = MusicEngine.current ?: run { Notice.show(act, "شغّل أغنية الأول", 1500L); return }
        val n = nameOf(t)
        Poster.reject(act, n.first, n.second); Cfg.put("noemb_${t.id}", "1")
        notifyNoPoster = true; artFor = null; vinyl.art = null
        Notice.show(act, "🖼 بدوّر على بوستر تاني…", 1800L)
        refreshNow()
    }
    /** اسم جديد اتأكد: احفظه، صفّر الغلاف القديم وهات الجديد، وعيد كتابة الكلمات */
    private fun applyIdentified(t: Track, m: SongMatch) {
        val old = nameOf(t)
        SongId.saveOverride(t.id, m)
        Poster.forget(act, old.first, old.second); Poster.forget(act, m.title, m.artist)
        LyricsEngine.clear(act, t); lyrFor = null; lyr = null
        artFor = null; refreshNow()
        MusicScan.cache = null; started = true
        Thread { try { val res = MusicScan.scan(act); act.runOnUiThread { if (!act.isDestroyed) { all = res; render() } } } catch (_: Throwable) {} }.apply { isDaemon = true }.start()
        if (lyricsOn) lyricsForCurrent()
    }
    private fun lyrMsg(t: String, retry: Boolean = false) {
        lyrBox.removeAllViews(); lyrViews.clear(); lyrIdx = -2
        lyrBox.addView(fixRow()); lyrBox.addView(lyrStatusTv)
        lyrBox.addView(ui.text(t, 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, ui.dp(12)) })
        if (retry) lyrBox.addView(ui.button("🔁 جرّب تاني") { MusicEngine.current?.let { LyricsEngine.clear(act, it) }; lyrFor = null; lyricsForCurrent() })
    }
    private val lyrStatusTv = ui.text("", 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(8)) }
    private var shownVer = -1
    private var bgChecked: String? = null
    private fun lyricsForCurrent() {
        val t = MusicEngine.current
        if (t == null) { lyrMsg("شغّل أغنية الأول وبعدين دوس 🎤"); lyrFor = null; return }
        if (lyrFor == t.uri) return
        lyrFor = t.uri; lyr = null; lyrStatusTv.text = ""
        val c = LyricsEngine.cached(act, t)
        if (c != null && c.lines.isNotEmpty()) {
            showLyrics(c)
            if (!c.complete) startLyrics(t, false)   // ناقصة → كمّلها من آخر مقطع
            return
        }
        if (c != null && c.complete) { lyrMsg("ما سمعتش كلام واضح في الأغنية دي (دوس 🔄 ريفريش لو عايز تجرّب تاني)"); return }
        lyrMsg("🎧 بسمع الأغنية وبكتب الكلام… ممكن ياخد دقيقة أو اتنين")
        startLyrics(t, false)
    }
    /** بيبدأ/بيكمّل كتابة الكلمات في الخلفية — بتتحفظ بعد كل مقطع فمفيش حاجة بتضيع لو خرجت */
    private fun startLyrics(t: Track, retry: Boolean, fresh: Boolean = false) {
        if (LyricsEngine.isRunning(t)) { lyrStatusTv.text = "🎧 الكلمات بتتكتب…"; return }
        if (lyrBusy) { lyrStatusTv.text = "⏳ لسه بكتب كلام أغنية تانية… هتتكمّل بعدها"; if (lyricsOn) lyrFor = null; return }
        lyrBusy = true
        lyrStatusTv.text = "🎧 بكتب الكلام…"
        val uri = t.uri
        val hint = nameOf(t).let { if (it.first.isBlank()) null else it }
        LyricsEngine.transcribe(act, t, hint, retry, fresh, { n, tot ->
            act.runOnUiThread { if (lyricsOn && MusicEngine.current?.uri == uri) lyrStatusTv.text = "🎧 بكتب الكلام… مقطع $n من $tot (بتتحفظ لوحدها)" }
        }) { r, msg ->
            act.runOnUiThread {
                lyrBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (!lyricsOn || MusicEngine.current?.uri != uri) return@runOnUiThread   // محفوظة — هتظهر لما ترجع للأغنية
                val c = LyricsEngine.cached(act, t)
                lyrStatusTv.text = if (c != null && !c.complete) "⚠ الكلمات ناقصة — هتتكمّل لما ترجع للأغنية" else ""
                if (c == null || c.lines.isEmpty()) lyrMsg(msg.ifBlank { "ما لقيتش كلام" }, true)
                else { showLyrics(c); if (msg.isNotBlank()) Notice.show(act, msg, 3000L) }
            }
        }
    }
    /** تحديث الشاشة لو الكلمات اتحفظت منها حتة جديدة وانت فاتحها */
    private fun lyricsPoll() {
        val t = MusicEngine.current ?: return
        if (lyrFor != t.uri || LyricsEngine.version == shownVer) return
        shownVer = LyricsEngine.version
        if (!LyricsEngine.isRunning(t)) return
        val c = LyricsEngine.cached(act, t) ?: return
        if (c.lines.size != (lyr?.lines?.size ?: -1)) showLyrics(c)
    }
    /** لو أغنية فيها كلمات ناقصة اتفتحت (من غير شاشة الكلمات) نكمّلها في الخلفية */
    private fun resumeBg() {
        val t = MusicEngine.current ?: return
        if (bgChecked == t.uri) return
        bgChecked = t.uri
        val c = LyricsEngine.cached(act, t) ?: return
        if (!c.complete && c.lines.isNotEmpty() && !lyrBusy && !LyricsEngine.anyRunning()) startLyrics(t, false)
    }
    private fun showLyrics(r: LyricsResult) {
        lyr = r; lyrBox.removeAllViews(); lyrViews.clear(); lyrIdx = -2; lyrWordKey = ""
        lyrBox.addView(fixRow()); lyrBox.addView(lyrStatusTv)
        if (r.title.isNotBlank() || r.artist.isNotBlank())
            lyrBox.addView(ui.text("🔎 الاسم المقترح (تخمين): " + listOf(r.title, r.artist).filter { it.isNotBlank() }.joinToString(" — "), 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(16)) })
        r.lines.forEach { l ->
            val tv = ui.text(l.text, 20f, th.muted, true).apply {
                gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(10)); alpha = 0.55f
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
        if (i != lyrIdx) {
            lyrViews.getOrNull(lyrIdx)?.let { v -> v.text = r.lines[lyrIdx].text; v.setTextColor(th.muted); v.alpha = 0.55f; v.textSize = 20f }
            lyrIdx = i; lyrWordKey = ""
            lyrViews.getOrNull(i)?.let { v -> v.alpha = 1f; v.textSize = 24f; v.post { lyrScroll.smoothScrollTo(0, (v.top + v.height / 2 - lyrScroll.height / 2).coerceAtLeast(0)) } }
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
                sp.setSpan(ForegroundColorSpan(th.primary), 0, chars.coerceIn(0, l.text.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(ForegroundColorSpan(th.text), chars.coerceIn(0, l.text.length), l.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                v.text = sp
            }
        }
    }
    /** بيفصل الشاشة عن المحرك — الموسيقى بتكمّل في الخلفية (الإيقاف من ✕ في الإشعار) */
    fun destroy() { h.removeCallbacksAndMessages(null); MusicEngine.listeners.remove(onEngine) }
    /** بيوقف مؤقتًا لو فيه حاجة شغّالة (مثلًا لما فيديو يتفتح) */
    fun pause() { MusicEngine.pause() }
}
