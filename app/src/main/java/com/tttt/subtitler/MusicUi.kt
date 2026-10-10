package com.tttt.subtitler

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
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
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    var onGrant: () -> Unit = {}

    private val h = Handler(Looper.getMainLooper())
    private val title = ui.text("🎵 الموسيقى", 18f, th.primary, true)
    private val back = ui.button("‹ رجوع") { openFolder(null) }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }

    // المشغّل
    private var dragging = false
    private val onEngine: () -> Unit = { paint(); refreshNow(); if (folder != null) showTracks(); if (lyricsOn) lyricsForCurrent() }
    private val nowTitle = ui.text("مفيش حاجة شغّالة", 14f, th.text, true).apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
    private val nowSub = ui.text("", 11f, th.muted).apply { setSingleLine() }
    private val seek = SeekBar(act).apply { max = 1000 }
    private val timeL = ui.text("0:00", 11f, th.muted)
    private val timeR = ui.text("0:00", 11f, th.muted)
    private val playBtn = ui.button("▶") { toggle() }

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
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        val sv = ScrollView(act).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(lyrScroll, LinearLayout.LayoutParams(-1, 0, 1f)); lyrScroll.visibility = View.GONE
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
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

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
            c.setOnClickListener { play(tracks, i) }
            list.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
        }
    }

    // ===== التشغيل (المحرك في MusicEngine والخدمة بتكمّله في الخلفية) =====
    fun play(q: List<Track>, i: Int) { MusicEngine.play(act, q, i) }
    private fun step(d: Int) { MusicEngine.step(d) }
    private fun toggle() { MusicEngine.toggle() }
    private fun paint() { playBtn.text = if (MusicEngine.isPlaying) "⏸" else "▶" }
    private fun refreshNow() {
        val t = MusicEngine.current
        nowTitle.text = t?.title ?: "مفيش حاجة شغّالة"; nowSub.text = t?.artist ?: ""
        if (t == null) { seek.progress = 0; timeL.text = "0:00"; timeR.text = "0:00" }
    }
    private fun tick() {
        if (MusicEngine.ready && !dragging) {
            val d = MusicEngine.durMs; val p = MusicEngine.posMs
            if (d > 0) { seek.progress = (p.toLong() * 1000 / d).toInt(); timeL.text = VideoLib.fmtDur(p.toLong()); timeR.text = VideoLib.fmtDur(d.toLong()) }
            paint()
            if (lyricsOn) lyricsTick()
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
    private fun identify() {
        val t = MusicEngine.current
        if (t == null) { Notice.show(act, "شغّل الأغنية الأول وبعدين دوس 🔎", 2400L); return }
        if (SongId.token().isEmpty()) { askToken { identify() }; return }
        if (idBusy) { Notice.show(act, "لسه بدوّر…", 1500L); return }
        idBusy = true
        Notice.show(act, "🔎 بسمع مقطع من الأغنية…", 2500L)
        // لو الأغنية شغّالة ناخد من مكان التشغيل، وإلا من نص الأغنية
        val start = if (MusicEngine.posMs > 5000) MusicEngine.posMs / 1000.0 else maxOf(10.0, t.durMs / 2000.0)
        SongId.recognize(act, t, start) { m, msg ->
            act.runOnUiThread {
                idBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (m == null) {
                    val tokenIssue = msg.contains("رفضت") || msg.contains("توكن")
                    val g = GAlert(act).setTitle("🔎 ما اتعرفتش").setMessage(msg)
                    if (tokenIssue) g.setNeutralButton("تغيير التوكن") { _, _ -> askToken { } }
                    g.setPositiveButton("تمام", null).show()
                } else {
                    val line = listOf(m.title, m.artist).filter { it.isNotBlank() }.joinToString(" — ")
                    GAlert(act).setTitle("🔎 الأغنية دي غالبًا:")
                        .setMessage(line + (if (m.album.isNotBlank()) "\nالألبوم: " + m.album else "") + "\n\nتحفظ الاسم ده للأغنية في التطبيق؟")
                        .setPositiveButton("حفظ الاسم") { _, _ -> SongId.saveOverride(t.id, m); MusicScan.cache = null; reload() }
                        .setNegativeButton("لأ", null).show()
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
    private fun lyrMsg(t: String, retry: Boolean = false) {
        lyrBox.removeAllViews(); lyrViews.clear(); lyrIdx = -2
        lyrBox.addView(ui.text(t, 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(40), 0, ui.dp(12)) })
        if (retry) lyrBox.addView(ui.button("🔁 جرّب تاني") { MusicEngine.current?.let { LyricsEngine.clear(act, it) }; lyrFor = null; lyricsForCurrent() })
    }
    private fun lyricsForCurrent() {
        val t = MusicEngine.current
        if (t == null) { lyrMsg("شغّل أغنية الأول وبعدين دوس 🎤"); lyrFor = null; return }
        if (lyrFor == t.uri) return
        lyrFor = t.uri; lyr = null
        val c = LyricsEngine.cached(act, t)
        if (c != null) { showLyrics(c); return }
        if (lyrBusy) { lyrMsg("⏳ لسه بكتب كلام أغنية تانية… استنى شوية"); lyrFor = null; return }
        lyrBusy = true
        lyrMsg("🎧 بسمع الأغنية وبكتب الكلام… ممكن ياخد دقيقة أو اتنين")
        val uri = t.uri
        LyricsEngine.transcribe(act, t, { n, tot -> act.runOnUiThread { if (lyricsOn && lyrFor == uri) lyrMsg("🎧 بكتب الكلام… مقطع $n من $tot") } }) { r, msg ->
            act.runOnUiThread {
                lyrBusy = false
                if (act.isDestroyed || act.isFinishing) return@runOnUiThread
                if (lyrFor != uri) { if (lyricsOn) { lyrFor = null; lyricsForCurrent() }; return@runOnUiThread }
                if (r == null || r.lines.isEmpty()) lyrMsg(msg.ifBlank { "ما لقيتش كلام" }, true) else { showLyrics(r); if (msg.isNotBlank()) Notice.show(act, msg, 3000L) }
            }
        }
    }
    private fun showLyrics(r: LyricsResult) {
        lyr = r; lyrBox.removeAllViews(); lyrViews.clear(); lyrIdx = -2; lyrWordKey = ""
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
