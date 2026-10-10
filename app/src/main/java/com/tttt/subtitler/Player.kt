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

/** مشغّل الفيديو والترجمة (اتنقل من Main.kt في v100 — من غير أي تغيير في الكود) */
class PlayerActivity : Activity(), Host {
    companion object { private val liveList = ArrayList<java.lang.ref.WeakReference<PlayerActivity>>() }
    override fun finish() { super.finish(); try { overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Main:725", e) } }
    @Volatile var cur = 0L
    @Volatile var durMs = 0L
    @Volatile var status = ""
    @Volatile var dirty = true
    private var lastBatch = 0L
    lateinit var player: ExoPlayer
    lateinit var sub: SubtitleView
    lateinit var visual: VisualMode
    lateinit var visOv: VisualOverlay
    var fx: Fx? = null
    lateinit var miniBar: DualProgress
    lateinit var st: TextView
    lateinit var floatBar: View
    lateinit var batchTv: TextView
    lateinit var logDrawer: LinearLayout
    lateinit var logHandle: TextView
    private lateinit var leftCol: LinearLayout
    /** الأزرار العايمة وإنت بتتفرج: شبه شفافة (75% شفافية)، ولما تدوس عليها بتبقى 100% وبترجع شفافة لما تتقفل */
    private val REST_A = 0.25f
    private val PROB_A = 0.45f
    private fun fadeTo(v: View, a: Float) { try { v.animate().alpha(a).setDuration(180).start() } catch (e: Exception) { LogStore.err("Main:744", e) } }
    private val floatFades = HashMap<View, Runnable>()
    private fun flashFloat(v: View, ms: Long = 3500L) {
        fadeTo(v, 1f); floatFades[v]?.let { h.removeCallbacks(it) }
        val r = Runnable { fadeTo(v, REST_A) }; floatFades[v] = r; h.postDelayed(r, ms)
    }
    /** (v95) زرار الروتيشن العايم (فوق 👁) + دالة بتحسب مكان الاتنين من ارتفاع الشريط السفلي عشان ما يدخلوش على شريحة الترجمة */
    lateinit var rotBtn: View
    var placeFloatFn: () -> Unit = {}
    var logOn = false   // اللوج مخفي افتراضيًا — اللسان ▸ بيفرده
    lateinit var logTv: TextView
    lateinit var logSv: ScrollView
    lateinit var engine: Engine
    lateinit var th: Theme
    lateinit var ui: Ui
    val logBuf = StringBuilder()
    val h = Handler(Looper.getMainLooper())
    var url: String? = null
    var aurl: String? = null
    private var qOpts: List<YtExtract.Opt> = emptyList()   // (v118) جودات يوتيوب
    private var qCur = -1
    private var hlsH = 0                                   // جودة HLS المثبّتة (0 = تلقائي)
    private var qualBRef: TextView? = null
    private val qualRowRefs = ArrayList<TextView>()          // (v120) زرار «🎞 الجودة» اللي جنب زرار الترجمة (في الشريط السفلي والواسع)
    private var qualPopFn: ((View) -> Unit)? = null
    // (v188) زرار «⏳ المهام» جنب زرار الترجمة: عداد أحمر + قايمة الأدوات بتنزل لفوق
    private var tasksPopFn: ((View) -> Unit)? = null
    private val taskBadges = ArrayList<TextView>()
    private fun taskCount(): Int = TaskCenter.active() + BgJobs.jobs.count { it.active }
    private val taskListener: () -> Unit = {
        val n = taskCount()
        for (bd in taskBadges) { bd.text = if (n > 99) "99+" else n.toString(); bd.visibility = if (n > 0) View.VISIBLE else View.GONE }
    }
    private fun setQualLabel(h: Int) {
        qualBRef?.text = if (h > 0) h.toString() else "HD"
        for (v in qualRowRefs) v.text = if (h > 0) "${h}p ▾" else if (vidW > 0 && vidH > 0) "${minOf(vidW, vidH)}p ▾" else "الجودة ▾"
        paintQual()
    }
    /** (v134) عدد الجودات المتاحة للفيديو: يوتيوب (قايمة الجودات) أو مسارات HLS/DASH بارتفاعات مختلفة */
    private fun qualCount(): Int {
        if (qOpts.size > 1) return qOpts.size
        return try {
            val hs = HashSet<Int>()
            for (g in player.currentTracks.groups) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
                for (ti in 0 until g.length) if (g.isTrackSupported(ti)) { val ht = g.getTrackFormat(ti).height; if (ht > 0) hs.add(ht) }
            hs.size
        } catch (_: Throwable) { 0 }
    }
    /** (v134) رقم الجودة: أخضر هادي لو الفيديو له أكتر من جودة تختار منها، أحمر لو جودة واحدة بس */
    private fun paintQual() {
        val multi = qualCount() > 1
        val col = if (multi) 0xFF6FCF8A.toInt() else 0xFFFF6B6B.toInt()
        qualBRef?.setTextColor(col)
        // (v134) أكتر من جودة → زرار الجودة بيظهر؛ جودة واحدة → علامة صغيرة (الجودة + الحجم) فوق زرار الترجمة بدل الزرار
        qualBRef?.visibility = if (uri == null && multi) View.VISIBLE else View.GONE
        for (v in qualRowRefs) { v.setTextColor(col); v.visibility = if (multi) View.VISIBLE else View.GONE }
        val ht = qOpts.getOrNull(qCur)?.h?.takeIf { it > 0 } ?: if (vidW > 0 && vidH > 0) minOf(vidW, vidH) else 0
        for (b in qBadges) {
            b.text = "🎞 " + (if (ht > 0) "${ht}p" else "—") + (if (sizeTxt.isNotEmpty()) "  ·  $sizeTxt" else "")
            b.visibility = if (!multi && uri == null && url != null) View.VISIBLE else View.GONE
        }
    }
    private val qBadges = ArrayList<TextView>()
    private var sizeTxt = ""
    private var sizeBusy = false
    private var dlRunning = false
    private var dlDlg: android.app.Dialog? = null
    private fun isHlsUrl(): Boolean = (url ?: "").contains(".m3u8", true)
    private fun dlName(): String = (intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid)).substringBeforeLast('.', "").ifBlank { intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid) }

    /** (v135) مصدر 🧰 أدوات الفيديو: الفيديو الشغّال دلوقتي (محلي = Uri، نت = لينك مباشر؛ aurl = صوت منفصل لو موجود) */
    private fun toolSrc(): ToolSrc? {
        val u = uri?.toString() ?: url ?: return null
        return ToolSrc(u, if (uri == null) aurl else null, dlName(), durMs, vidW, vidH, player.currentPosition, engine.subs, HashMap(hdr))
    }
    /** (v134) حجم الفيديو الحالي: HEAD للينك المباشر/اليوتيوب (صورة+صوت)، وتقدير bitrate×المدة لـ HLS */
    fun refreshSize(show: Boolean = false) {
        if (uri != null || url == null || sizeBusy) return
        sizeBusy = true
        val o = qOpts.getOrNull(qCur); val u = o?.url ?: url!!; val au = o?.audio ?: aurl; val hs = HashMap(hdr)
        val hlsNow = o == null && isHlsUrl()
        val brEst = try { player.videoFormat?.bitrate ?: -1 } catch (_: Throwable) { -1 }; val dur = durMs
        Thread {
            var n = -1L; var approx = false
            try {
                if (hlsNow) { if (brEst > 0 && dur > 0) { n = brEst / 8L * (dur / 1000L); approx = true } }
                else {
                    val a = Downloader.sizeOf(u, hs)
                    if (a > 0) { n = a; if (au != null) { val b = Downloader.sizeOf(au, hs); if (b > 0) n += b } }
                }
            } catch (_: Throwable) {}
            val txt = if (n > 0) (if (approx) "≈" else "") + Sniff.fmtSize(n) else ""
            runOnUiThread {
                sizeBusy = false; sizeTxt = txt; paintQual()
                if (show) Notice.show(this, if (txt.isNotEmpty()) "📦 حجم الفيديو: $txt" else "حجم الفيديو مش معروف من السيرفر", 2600L)
            }
        }.start()
    }
    private fun startDownload(job: Downloader.Job) {
        val app = applicationContext
        // (v137) التحميل بقى مهمة في تبويب «المهام»: إيقاف مؤقت / استكمال / إلغاء / إعادة، وممكن أكتر من تحميل ورا بعض
        TaskCenter.add(app, "download", (if (job.audioOnly) "🎧 " else "⬇ ") + job.name) { t ->
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { p -> job.ctl.paused = p }
            val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
        }
        try { Notice.show(this, "⬇ اتضاف للتحميل — تابعه من تبويب «المهام» (إيقاف · استكمال · إلغاء)", 3200L) } catch (_: Throwable) {}
    }
    /** (v134) زرار ⬇: قايمة الجودات (بحجم كل واحدة) — الاختيار بيبدأ التحميل */
    fun downloadPopup(anchor: View) {
        dlDlg?.let { if (it.isShowing) { it.dismiss(); dlDlg = null; return } }
        val u0 = url ?: return
        val hs = HashMap(hdr); val nm = dlName()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        }
        col.addView(ui.text("🎬 فيديو — اختار الجودة", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(4)) })
        fun addItem(label: String, job: Downloader.Job, sizeFn: (() -> Long)?) {
            val b = ui.button(label) { dlDlg?.dismiss(); startDownload(job) }
            col.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
            if (sizeFn != null) Thread {
                val n = try { sizeFn() } catch (_: Throwable) { -1L }
                if (n > 0) runOnUiThread { b.text = label + "  ·  " + Sniff.fmtSize(n) }
            }.start()
        }
        if (qOpts.isNotEmpty()) {
            for (o in qOpts) addItem(o.label, Downloader.Job(nm, o.url, o.audio, false, o.h, hs)) {
                val a = Downloader.sizeOf(o.url, hs); if (a > 0 && o.audio != null) a + maxOf(0L, Downloader.sizeOf(o.audio, hs)) else a
            }
        } else if (isHlsUrl()) {
            val seen = HashSet<Int>(); val tl = ArrayList<Pair<Int, Int>>()   // (الارتفاع، bitrate)
            for (g in player.currentTracks.groups) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
                for (ti in 0 until g.length) if (g.isTrackSupported(ti)) { val f = g.getTrackFormat(ti); if (f.height > 0 && seen.add(f.height)) tl.add(Pair(f.height, f.bitrate)) }
            if (tl.isEmpty()) addItem("أعلى جودة متاحة", Downloader.Job(nm, u0, null, true, 0, hs), null)
            else for ((hh, br) in tl.sortedByDescending { it.first })
                addItem("${hh}p", Downloader.Job(nm, u0, null, true, hh, hs), if (br > 0 && durMs > 0) ({ br / 8L * (durMs / 1000L) }) else null)
            col.addView(ui.text("HLS: بيتحمّل ملف .ts (مقاطع من غير تشفير بس)", 11f, th.muted).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(2)) })
        } else {
            val ht = if (vidW > 0 && vidH > 0) minOf(vidW, vidH) else 0
            addItem("الفيديو بالجودة الحالية" + (if (ht > 0) " (${ht}p)" else ""), Downloader.Job(nm, u0, aurl, false, ht, hs)) {
                val a = Downloader.sizeOf(u0, hs); if (a > 0 && aurl != null) a + maxOf(0L, Downloader.sizeOf(aurl!!, hs)) else a
            }
        }
        // (v134) صوت فقط: M4A (AAC) — لو الصوت لينك لوحده (يوتيوب) بيتحمّل هو بس، وإلا بيتحمّل الفيديو ويتستخرج منه الصوت (أقل جودة عشان الحجم)
        col.addView(ui.text("🎧 صوت فقط", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(4)) })
        val aOpt = qOpts.firstOrNull { it.audio != null }
        val loOpt = qOpts.filter { it.h > 0 }.minByOrNull { it.h } ?: qOpts.lastOrNull()
        val aJob: Downloader.Job = when {
            aOpt != null -> Downloader.Job(nm, aOpt.url, aOpt.audio, false, 1, hs, true)
            loOpt != null -> Downloader.Job(nm, loOpt.url, null, false, 1, hs, true)
            isHlsUrl() -> Downloader.Job(nm, u0, null, true, 1, hs, true)
            else -> Downloader.Job(nm, u0, aurl, false, 0, hs, true)
        }
        val aSrc = aJob.audio
        addItem("🎧 تحميل كصوت (M4A)", aJob, if (aSrc != null) ({ Downloader.sizeOf(aSrc, hs) }) else null)
        h.removeCallbacks(hideChrome); showChrome()
        dlDlg = GAlert(this).setTitle("⬇ تحميل").setView(col).setNegativeButton("إغلاق", null).show().also { d -> d.setOnDismissListener { if (dlDlg === d) dlDlg = null; showChrome() } }
    }
    var uri: Uri? = null
    private var httpDsf: DefaultHttpDataSource.Factory? = null
    val hdr = HashMap<String, String>()
    var touching = false
    lateinit var conf: Conf
    // قايمة الجمل (نسخة مرتبة + مصفوفات للبحث الثنائي)
    var list: List<Sub> = emptyList()
    var starts = LongArray(0); var ends = LongArray(0)
    private var hideTicks = 0
    private val seenKeys = HashSet<String>(); private val warnedKeys = HashSet<String>(); private var lastChkT = -1L
    private fun sentKey(q: Sub) = "${q.start}|${q.end}|${q.translated.hashCode()}"
    // الحوار والأصوات الخلفية بيتفصلوا: الحوار تحت، وصف الصوت (همهمة/موسيقى…) فوق الفيديو
    var spMap = IntArray(0); var spStarts = LongArray(0); var spEnds = LongArray(0)
    var sdMap = IntArray(0); var sdStarts = LongArray(0); var sdEnds = LongArray(0)
    lateinit var soundTv: TextView; var soundKey = ""
    var termFull = ""   // (v134) شرح المصطلح الظاهر فوق دلوقتي (بصيغة «المصطلح»: الشرح)
    private fun termName(t: String): String { val a = t.indexOf('«'); val b = t.indexOf('»'); return if (a >= 0 && b > a) t.substring(a + 1, b).trim() else t.trim().take(24) }
    /** (v134) دوس على ⓘ: الفيديو يقف ويظهر معنى المصطلح / المقصود منه في المشهد، وبيكمّل لما تقفل */
    fun showTermCard() {
        val full = termFull; if (full.isEmpty()) return
        val was = try { player.isPlaying } catch (_: Throwable) { false }
        try { player.pause() } catch (_: Throwable) {}
        val body = full.substringAfter('»', full).trimStart(':', '：', ' ', '\n').trim().ifBlank { full }
        fun resume() { if (was) { try { player.play() } catch (_: Throwable) {} } }
        GAlert(this).setTitle("ⓘ " + termName(full)).setMessage(body)
            .setPositiveButton("تمام") { _, _ -> resume() }
            .setOnCancelListener { resume() }
            .show()
    }
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
    lateinit var visBadge: TransBadge   // (v182) عدّاد جمل الوضع البصري — أقصى الشمال
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
    /** (v160) الأجزاء المحمّلة من الفيديو الأونلاين (بتتراكم أثناء المشاهدة وبتتدمج) */
    private val bufSegs = ArrayList<DoubleArray>()
    private fun addBuf(a: Double, b: Double) {
        if (b - a < 0.5) return
        var s = a; var e = b
        val it = bufSegs.iterator()
        while (it.hasNext()) { val x = it.next(); if (x[1] >= s - 1.0 && x[0] <= e + 1.0) { s = minOf(s, x[0]); e = maxOf(e, x[1]); it.remove() } }
        bufSegs.add(doubleArrayOf(s, e))
    }
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
    private var autoTr = false
    private var stripV: View? = null
    private val hideStrip = Runnable { dismissStrip() }
    fun dismissStrip() { h.removeCallbacks(hideStrip); stripV?.let { try { (it.parent as? ViewGroup)?.removeView(it) } catch (e: Exception) { LogStore.err("Main:823", e) } }; stripV = null }
    // (v91) شريط «كمّل / من الأول / بدون ترجمة» اتمسح بالكامل
    fun setLock(b: Boolean) {
        uiLocked = b
        if (b) { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn(); say("🔒 الشاشة مقفولة — المس الشاشة واضغط على القفل لفتحها") }
        else { lockOv.visibility = View.GONE; showChrome() }
    }
    // ===== إشعار التحكم (v87): الحالة بتتبعت للخدمة، والأوامر بترجع من الإشعار على remote() =====
    private var lastNotifAt = 0L
    private var notifTitleOv: String? = null
    fun notifTitle(): String {
        val t = notifTitleOv ?: intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid)
        return t.substringBeforeLast('.', t)
    }
    fun pushNotif() {
        try {
            val st = NotifState
            st.title = notifTitle(); st.posMs = player.currentPosition.coerceAtLeast(0); st.durMs = if (durMs > 0) durMs else 0L
            st.playing = player.isPlaying
            st.trState = if (!::engine.isInitialized || !engineStarted) 0 else if (engine.userPaused) 2 else 1
            st.trPct = if (durMs > 0 && ::engine.isInitialized) PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toInt() else 0
            KeepAliveService.refresh()
        } catch (e: Throwable) { LogStore.err("pushNotif", e) }
    }
    fun exitAll() {
        try { player.pause() } catch (e: Exception) { LogStore.err("exitAll", e) }
        saveRecent(); Thread { try { engine.saveNow() } catch (_: Exception) {} }.start()
        finishAndRemoveTask()
    }
    /** أوامر الإشعار: toggle / prev / next / seek / tr / exit */
    fun remote(cmd: String, arg: Long = 0L) {
        runOnUiThread {
            try {
                when (cmd) {
                    "toggle" -> togglePlay()
                    "prev" -> stepEpisode(-1)
                    "next" -> stepEpisode(1)
                    "seek" -> if (durMs > 0) player.seekTo(arg.coerceIn(0L, durMs))
                    "tr" -> if (engineStarted && !engine.userPaused) pauseTranslate() else { beginTranslate(); engine.translateFrom(player.currentPosition / 1000.0) }
                    "exit" -> exitAll()
                }
                lastNotifAt = System.currentTimeMillis(); pushNotif()
            } catch (e: Throwable) { LogStore.err("remote:$cmd", e) }
        }
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
    var tbCollapseFn: () -> Unit = {}
    var trPct = 0   // نسبة الترجمة (بتتحدّث كل 200ms) — للشريحة الصغيرة في الشريط السفلي
    private val logAuto = Runnable { if (logOn) toggleLog() }     // لوحة الباتشات بتتلم لوحدها
    private val probAuto = Runnable { if (probOn) toggleProb() }  // كبسولة الباتش الفاشل بتتلم لوحدها
    fun updateTr() { trUpdaters.forEach { try { it() } catch (e: Exception) { LogStore.err("Main:866", e) } } }
    fun beginTranslate() {
        try { val st = Throwable().stackTrace; LogStore.add("▶ طلب بدء الترجمة ← " + (st.getOrNull(1)?.let { it.methodName + ":" + it.lineNumber } ?: "?") + (if (autoTr) " (autotr)" else "")) } catch (_: Throwable) {}
        if (::engine.isInitialized && !engineReady) { pendingBegin = true; return }   // البيانات المحفوظة لسه بتتحمّل: هتبدأ لوحدها أول ما تخلص
        dismissStrip()
        if (noSub) { noSub = false; if (!ccOn) ccToggleFn() }   // بدأت ترجمة: رجّع إظهار الترجمة
        // (v134) الترجمة تبدأ من المكان اللي واقف فيه في الفيديو (مش من الأول): المؤشر بيتحط على باتش الوقت الحالي، واللي اتترجم قبل كده بيتخطّى
        val wasFresh = freshOnce
        val posNow = try { player.currentPosition } catch (_: Throwable) { cur }
        if (posNow > 0) cur = posNow
        if (!engineStarted) { engineStarted = true; engine.userPaused = false; startEngine() } else engine.userPaused = false
        if (!wasFresh && posNow > 2000L) { try { engine.translateFrom(posNow / 1000.0); log("▶ الترجمة من " + fmtMs(posNow)) } catch (_: Throwable) {} }
        updateTr()
    }
    private var trChipV: View? = null
    private var trChipDismissed = false
    private fun updateTrChip() { trChipV?.visibility = if (engineStarted || trChipDismissed || pipNow()) View.GONE else View.VISIBLE }
    fun pauseTranslate() { if (engineStarted) { engine.userPaused = true; updateTr() } }
    /** صف الأزرار: [▶ ترجمة] (أحمر) قبل البداية — وبعد الضغط يختفي ويظهر مكانه [⏸ إيقاف مؤقت] [▶ إلغاء الإيقاف] */
    private fun makeTrRow(compact: Boolean): ViewGroup {
        // (v144) الشريط السفلي: صف بيلفّ لوحده (FlowRow) والعلامة جواه — مفيش عمود رأسي ولا عرض ثابت يخلي الأزرار تتقص أو الشريحة تعلق
        val row: ViewGroup = if (compact) FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            else LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        fun b(t: String, fill: Int, f: () -> Unit) = IconTextView(this).apply {
            text = t; textSize = if (compact) 12f else 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(if (compact) 10 else 8), 0, ui.dp(if (compact) 10 else 8), 0)
            background = ui.box(fill, 0x33FFFFFF, if (compact) 8 else 10); setOnClickListener { f() }
        }
        val hh = ui.dp(if (compact) 40 else 44); val m = ui.dp(if (compact) 3 else 4)
        fun lp(w: Int) = LinearLayout.LayoutParams(w, hh).apply { setMargins(m, m, m, m) }
        val webVid = uri == null && url != null
        var dlRef: TextView? = null
        val dlB = b("⬇", 0xFF37474F.toInt()) { dlRef?.let { downloadPopup(it) } }
        dlRef = dlB
        val tr = b("▶ ترجمة", 0xFFE53935.toInt()) { beginTranslate() }
        var lgRef: TextView? = null
        val lgB = b(if (compact) "🌐 اللغة ▾" else "🌐 لغة الترجمة ▾", 0xFF37474F.toInt()) { lgRef?.let { langPopup(it) } }
        lgRef = lgB
        fun qBtn(): TextView {
            val cur = qOpts.getOrNull(qCur)?.h ?: 0
            val v = b(if (cur > 0) "${cur}p ▾" else "الجودة ▾", 0xFF37474F.toInt()) { }
            v.setOnClickListener { qualPopFn?.invoke(v) }
            qualRowRefs.add(v); return v
        }
        if (compact) {
            // الشريط السفلي: شريحة صغيرة بنسبة الترجمة (⏳ 19%) — دوس عليها تتفرد: إيقاف/استئناف + اللغة، وبتتلم لوحدها بعد 5 ثواني
            var open = false
            val closeR = Runnable { open = false; updateTr() }
            val chipB = b("▶ ترجمة", 0xFFE53935.toInt()) {
                if (!engineStarted) beginTranslate()
                else { open = !open; h.removeCallbacks(closeR); if (open) h.postDelayed(closeR, 5000); updateTr() }
            }
            val tg = b("⏸ إيقاف", 0xFF424B57.toInt()) { if (::engine.isInitialized && engine.userPaused) beginTranslate() else pauseTranslate(); h.removeCallbacks(closeR); h.postDelayed(closeR, 5000) }
            var rdRef: TextView? = null
            val rd = b("🔁 إعادة", 0xFF6A1B9A.toInt()) { h.removeCallbacks(closeR); h.postDelayed(closeR, 8000); rdRef?.let { redoDialog(it) } }
            rdRef = rd
            fun taskBtn(): View {
                val btn = b("⏳ المهام", 0xFF37474F.toInt()) { }
                val fl = FrameLayout(this)
                val badge = TextView(this).apply {
                    textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    includeFontPadding = false; minWidth = ui.dp(18); setPadding(ui.dp(4), 0, ui.dp(4), 0)
                    background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = ui.dp(9).toFloat(); setColor(0xFFE53935.toInt()); setStroke(ui.dp(1), Color.WHITE) }
                    visibility = View.GONE
                }
                fl.addView(btn, FrameLayout.LayoutParams(-2, -1))
                fl.addView(badge, FrameLayout.LayoutParams(-2, ui.dp(18), Gravity.TOP or Gravity.END))
                btn.setOnClickListener { tasksPopFn?.invoke(fl) }
                taskBadges.add(badge); if (!TaskCenter.extra.contains(taskListener)) TaskCenter.extra.add(taskListener); taskListener()
                return fl
            }
            row.addView(chipB, lp(-2)); row.addView(taskBtn(), lp(-2)); row.addView(tg, lp(-2)); row.addView(rd, lp(-2)); row.addView(lgB, lp(-2))
            if (webVid) { row.addView(qBtn(), lp(-2)); row.addView(dlB, lp(-2)) }
            trUpdaters.add {
                val paused = ::engine.isInitialized && engine.userPaused
                val started = engineStarted
                if (!started) open = false
                tg.visibility = if (started && open) View.VISIBLE else View.GONE
                rd.visibility = View.VISIBLE
                lgB.visibility = if (!started || open) View.VISIBLE else View.GONE
                if (!started) { chipB.text = "▶ ترجمة"; chipB.background = ui.box(0xFFE53935.toInt(), 0x33FFFFFF, 12) }
                else {
                    chipB.text = (if (paused) "⏸ " else if (trPct >= 100) "✅ " else "⏳ ") + trPct + "%" + (if (open) " ▴" else " ▾")
                    chipB.background = ui.box(if (paused) 0xFF8D6E00.toInt() else if (trPct >= 100) 0xFF2E7D32.toInt() else 0xFF1F5FBF.toInt(), 0x33FFFFFF, 12)
                }
                tg.text = if (paused) "▶ استئناف" else "⏸ إيقاف"
                tg.background = ui.box(if (paused) 0xFF2E7D32.toInt() else 0xFF424B57.toInt(), 0x33FFFFFF, 12)
            }
        } else {
            val ps = b("⏸ إيقاف مؤقت", 0xFF424B57.toInt()) { pauseTranslate() }
            val rs = b("▶ إلغاء الإيقاف", 0xFF2E7D32.toInt()) { beginTranslate() }
            var rd2Ref: TextView? = null
            val rd2 = b("🔁 إعادة", 0xFF6A1B9A.toInt()) { rd2Ref?.let { redoDialog(it) } }
            rd2Ref = rd2
            row.addView(tr, lp(0).apply { width = 0; weight = 2f }); row.addView(ps, lp(0).apply { width = 0; weight = 1f })
            row.addView(rs, lp(0).apply { width = 0; weight = 1f })
            row.addView(rd2, lp(0).apply { width = 0; weight = 1f })
            row.addView(lgB, lp(0).apply { width = 0; weight = 1.3f })
            if (webVid) { row.addView(qBtn(), lp(0).apply { width = 0; weight = 1.1f }); row.addView(dlB, lp(0).apply { width = 0; weight = 0.6f }) }
            trUpdaters.add {
                val paused = ::engine.isInitialized && engine.userPaused
                                tr.visibility = if (engineStarted) View.GONE else View.VISIBLE
                ps.visibility = if (engineStarted) View.VISIBLE else View.GONE
                rs.visibility = if (engineStarted) View.VISIBLE else View.GONE
                ps.alpha = if (paused) 0.4f else 1f; rs.alpha = if (paused) 1f else 0.4f
            }
        }
        updateTr()
        if (!webVid) return row
        // (v134) علامة صغيرة فوق زرار الترجمة: الجودة + الحجم (بتظهر لما الفيديو له جودة واحدة بس) — دوس عليها تعرف الحجم
        val badge = IconTextView(this).apply {
            textSize = 10.5f; setTextColor(0xFFFF6B6B.toInt()); gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(2)); background = ui.box(0xCC141418.toInt(), 0x33FFFFFF, 10)
            setOnClickListener { refreshSize(true) }
        }
        qBadges.add(badge)
        if (compact) {
            // (v144) في الشريط السفلي العلامة بقت آخر عنصر في نفس الصف (مش فوق الشريحة) — فضغطة الشريحة بتفرد إعادة/اللغة عادي
            row.addView(badge, LinearLayout.LayoutParams(-2, ui.dp(28)).apply { setMargins(m, m, m, m) })
            paintQual()
            return row
        }
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_HORIZONTAL }
        wrap.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, ui.dp(2), 0, 0) })
        wrap.addView(row, LinearLayout.LayoutParams(-1, -2))
        paintQual()
        return wrap
    }
    private val hideCard = Runnable { resumePending = false; applyCard() }
    private fun fmtMS(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

    private var batchPanel: View? = null
    lateinit var batchBtn: View
    fun closeBatchPanel() {
        batchPanel?.let { p -> try { (p.parent as? ViewGroup)?.removeView(p) } catch (e: Exception) { LogStore.err("Main:938", e) } }
        batchPanel = null
        if (::batchBtn.isInitialized) fadeTo(batchBtn, REST_A)
    }
    /** زرار 🔄 العايم: لوحة صغيرة فوق الفيديو — «من الأول خالص» + باتشات الفيديو (📍 يوديك للباتش) + «ابدأ من هنا» / «ده بس» */
    fun batchDialog() {
        if (batchPanel != null) { closeBatchPanel(); return }
        val bl = engine.batches()
        if (bl.isEmpty()) { Notice.show(this, ("مدة الفيديو لسه مش معروفة — استنى ثانية وجرّب تاني").toString(), 2300L); return }
        val curC = try { engine.chunkOfSec(player.currentPosition / 1000.0) } catch (_: Exception) { 0 }
        var sel = curC
        val rows = ArrayList<Pair<Int, LinearLayout>>()
        val selTv = ui.text("", 12f, th.primary, true)
        // الشغل التقيل (مسح + حفظ على القرص) بعيد عن الـ UI thread — ده اللي كان بيهنّج ويقفل البرنامج
        fun go(label: String, work: () -> Unit) {
            closeBatchPanel(); resumePending = false; applyCard()
            Notice.show(this, (label).toString(), 2300L)
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
        head.addView(IconTextView(this).apply { text = "✕"; textSize = 18f; setTextColor(Color.WHITE); setPadding(ui.dp(10), ui.dp(2), ui.dp(4), ui.dp(2)); setOnClickListener { closeBatchPanel() } })
        col.addView(head)
        col.addView(ui.button("🔁 ترجم من الأول خالص", true) { player.seekTo(0); go("🔄 بترجم من الأول…") { engine.redoAll() } })
        col.addView(ui.text("📍 دوس على الباتش يوديك له، وبعدين اختار ابدأ", 11f, th.muted))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        var curRow: View? = null
        for (b in bl) {
            val i = b.idx
            val row = LinearLayout(this).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7))
                addView(IconTextView(this@PlayerActivity).apply { text = "📍"; textSize = 16f; setPadding(0, 0, ui.dp(8), 0) })
                addView(ui.text("باتش ${i + 1} · ${fmtMS(b.start)}–${fmtMS(b.end)} ${b.mark}" + (if (b.count > 0) " · ${b.count} جملة" else "") + (if (i == curC) "  ◀ هنا" else ""), 12f, th.text, i == curC), LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { sel = i; try { player.seekTo((engine.chunkStartSec(i) * 1000).toLong()) } catch (e: Exception) { LogStore.err("Main:985", e) }; paint() }
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
        fadeTo(batchBtn, 1f)
        curRow?.let { r -> sv.post { sv.scrollTo(0, (r.top - ui.dp(40)).coerceAtLeast(0)) } }
    }

    /** نفس شغل «go» بتاع لوحة الباتشات: يمسح ويبدأ من جديد بعيد عن الـ UI thread */
    private fun redoGo(label: String, work: () -> Unit) {
        closeBatchPanel(); resumePending = false; applyCard()
        Notice.show(this, (label).toString(), 2300L)
        engine.paused = true
        Thread {
            try { work() } catch (e: Throwable) { log("⚠ " + (e.message ?: e.toString()).take(120)); engine.paused = false }
            dirty = true; curIdx = -2
            runOnUiThread { beginTranslate() }
        }.apply { isDaemon = true }.start()
    }
    private var redoPop: android.widget.PopupWindow? = null
    private var redoKeep = true
    /** زرار «🔁 إعادة»: قايمة منسدلة صغيرة تحت/فوق الزرار (زي قايمة اللغة): من الأول / من الباتش ده وبعده / الباتش ده بس / باتش معين — مع اختيار نحتفظ بالقديمة (🗂) ولا نمسحها */
    fun redoDialog(anchor: View) {
        redoPop?.let { if (it.isShowing) { it.dismiss(); redoPop = null; return } }
        if (!::engine.isInitialized) return
        val curC = try { engine.chunkOfSec(player.currentPosition / 1000.0) } catch (_: Exception) { 0 }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(0xF2141418.toInt(), 0x33FFFFFF, 12)
        }
        fun item(t: String, f: (TextView) -> Unit) = ui.fsBtn(t, f).apply { layoutParams = LinearLayout.LayoutParams(-1, ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) } }
        fun keepText() = if (redoKeep) "💾 احتفظ بالقديمة في 🗂: شغّال" else "🗑 امسح القديمة: شغّال"
        col.addView(item(keepText()) { v -> redoKeep = !redoKeep; v.text = keepText() })
        col.addView(item("🔁 من الأول خالص") { _ -> redoPop?.dismiss(); val k = redoKeep; player.seekTo(0); redoGo("🔄 بترجم من الأول…") { engine.redoAll(k) } })
        col.addView(item("▶ من الباتش ده وبعده (باتش ${curC + 1})") { _ -> redoPop?.dismiss(); val k = redoKeep; redoGo("🔄 بترجم من باتش ${curC + 1} وبعده…") { engine.redoFrom(curC, k) } })
        col.addView(item("☝ الباتش ده بس (باتش ${curC + 1})") { _ -> redoPop?.dismiss(); val k = redoKeep; redoGo("🔄 بترجم باتش ${curC + 1} لوحده…") { engine.redoOnly(curC, k) } })
        col.addView(item("📋 اختار باتش معين…") { _ -> redoPop?.dismiss(); batchDialog() })
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val popW = minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(280))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val up = loc[1] > screenH / 2
        val avail = (if (up) loc[1] - ui.dp(12) else screenH - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val popH = minOf(col.measuredHeight, avail)
        val pw = android.widget.PopupWindow(scroll, popW, popH, true)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (redoPop === pw) redoPop = null; showChrome() }
        if (up) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2))) else pw.showAsDropDown(anchor, 0, ui.dp(2))
        redoPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)
    }

    /** يوقف المشغّل خالص، يسلّم الترجمة لخدمة الخلفية (إشعار بالتقدم والوقت المتبقي) ويطلع بره التطبيق من غير ما يقفله */
    fun translateInBackground(goHome: Boolean = true, src: String = "المشغّل (زرار 🌙)") {
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty() && conf.backup.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        if (incognito) { say("🕶 الترجمة بالخلفية مش متاحة في التخفي"); return }
        if (handedOff) return
        handedOff = true
        closeSide(); resumePending = false
        say(if (src.contains("تلقائي")) "🌙 كمّلت الترجمة في الخلفية لأن ✔ «كمّل في الخلفية لما أخرج» مفعّل (من فوق في المشغّل) — تقفله من نفس الزرار" else "🌙 هكمّل الترجمة في الخلفية — التقدم في الإشعارات")
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:1017", e) }
        saveRecentForce()
        val vidNow = vid; val uriS = uri?.toString(); val urlS = aurl ?: url; val hdrC = HashMap(hdr)
        val app = applicationContext
        val eng = engine
        Thread {
            try { eng.stop(); eng.awaitStopped(4000); eng.saveNow() } catch (e: Exception) { LogStore.err("Main:1023", e) }
            BgJobs.enqueue(app, BgJob(vidNow, Recents.titleOf(vidNow), uriS, urlS, hdrC), src)
        }.start()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 12) } catch (e: Exception) { LogStore.err("Main:1027", e) }
        }
        if (goHome) try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { LogStore.err("Main:1029", e) }
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        if (pipNow()) finishAndRemoveTask() else finish()   // (v149) من نافذة الـ PiP: يشيل النافذة خالص
    }

    /** (v149) وضع «صوت بس» (من زرار الـ PiP): يشيل نافذة الـ PiP ويسيب الفيديو شغّال كصوت في الخلفية، ويوقف الترجمة */
    private var audioOnlyMode = false
    fun audioOnly() {
        audioOnlyMode = true
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        try { if (!incognito) saveRecentForce() } catch (e: Exception) { LogStore.err("Player:audioOnly1", e) }
        val eng = engine
        try { retireEngine() } catch (e: Throwable) { LogStore.err("Player:audioOnly2", e) }   // وقف الترجمة (لو شغّالة)
        Thread { try { eng.awaitStopped(3000); eng.saveNow() } catch (e: Throwable) { LogStore.err("Player:audioOnly3", e) } }.start()
        try { if (!player.isPlaying) player.play() } catch (e: Exception) { LogStore.err("Player:audioOnly4", e) }
        try { moveTaskToBack(true) } catch (e: Exception) { LogStore.err("Player:audioOnly5", e) }
    }

    /** (v149) إيقاف نهائي (من زرار الـ PiP): يحفظ التقدم، يوقف الفيديو والمحرك وكل الترجمة في الخلفية والإشعارات، ويطلّع التطبيق من الرام */
    fun fullStop() {
        try { player.pause() } catch (e: Exception) { LogStore.err("Player:fullStop1", e) }
        try { if (!handedOff) saveRecentForce() } catch (e: Exception) { LogStore.err("Player:fullStop2", e) }
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        handedOff = true   // عشان onDestroy ما يحفظش تاني ولا يشغّل ترجمة خلفية
        val eng = engine; val app = applicationContext
        Thread {
            try { eng.stop(); eng.awaitStopped(3000); eng.saveNow() } catch (e: Throwable) { LogStore.err("Player:fullStop3", e) }
            try { BgJobs.jobs.filter { it.active }.forEach { BgJobs.stopAndWait(it.vid, 3000L) } } catch (e: Throwable) { LogStore.err("Player:fullStop4", e) }
            try { app.stopService(Intent(app, BgService::class.java)); app.stopService(Intent(app, KeepAliveService::class.java)) } catch (_: Throwable) {}
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
        finishAndRemoveTask()
    }

    // ===== حفظ SRT تلقائي جنب الفيديو لما الترجمة تخلص (أو لما تعدّل التزامن بعدها) =====
    var offFsB: TextView? = null
    private var lastSrtN = -1
    private var lastSrtOff = Long.MIN_VALUE
    private var srtToastShown = false   // (v104) إشعار «اتحفظ SRT» مرة واحدة بس في الجلسة
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
        Thread { SrtWriter.save(app, u, subsNow, off)?.let { p -> runOnUiThread { log("💾 اتحفظ SRT: $p"); if (!srtToastShown) { srtToastShown = true; say("💾 اتحفظ SRT جنب الفيديو") } } } }.start()
    }

    // ===== (v167) السلايدر الموحّد للصوت: 0..100% صوت عادي، 100..200% تضخيم (أحمر) — LoudnessEnhancer على جلسة صوت المشغّل (الجزء الزيادة 0..100 = لحد +15dB) =====
    fun volTotal(): Int = 100 + (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100)
    fun setBoostPct(b: Int) { Cfg.put("vol_boost", b.coerceIn(0, 100).toString()); applyBoost() }
    fun boostDbText(b: Int) = "+" + "%.1f".format(b * 0.15) + " dB"
    private var loud: android.media.audiofx.LoudnessEnhancer? = null
    fun applyBoost() {
        val pct = (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100)
        try {
            if (!::player.isInitialized) return
            val sid = player.audioSessionId
            if (sid == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET || sid == 0) return
            if (loud == null) loud = android.media.audiofx.LoudnessEnhancer(sid)
            loud?.setTargetGain(pct * 15)
            loud?.enabled = pct > 0
        } catch (e: Throwable) { LogStore.err("boost", e) }
    }

    // ===== PiP =====
    private var inPip = false
    private var resumedNow = false
    private var pipOv: PipSubBar? = null
    private val pipExitCheck = Runnable { if (!resumedNow && !isFinishing && !audioOnlyMode) closeAfterPip() }
    fun pipNow() = inPip || (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
    private fun closeAfterPip() {
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:1057", e) }
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
    // (v125) حفظ مكان الوقوف كل 3 ثواني (كان بيتحفظ عند الخروج بس → بعد PiP / تحديث النسخة بيرجع لمكان قديم)
    private var lastPosSave = 0L
    private var lastRecentSave = 0L
    private var lastLogLen = -1
    private var lastBatchTxt = ""
    /** (v146) نص الباتشات (مفتاح · موديل · زمن الرد) والأسرع في كل دفعة أخضر */
    private fun batchStyled(): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        for ((t, fast) in engine.batchRows()) {
            if (sb.isNotEmpty()) sb.append('\n')
            val a = sb.length; sb.append(t)
            if (fast) sb.setSpan(android.text.style.ForegroundColorSpan(0xFF2ECC71.toInt()), a, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }
    private var miniLog: View? = null
    private var miniLogTv: TextView? = null
    fun closeMiniLog() {
        miniLog?.let { p -> try { (p.parent as? ViewGroup)?.removeView(p) } catch (_: Exception) {} }
        miniLog = null; miniLogTv = null
    }
    /** (v125) اللوج الصغير: بيوضّح أنهي باتش بيترجم دلوقتي (دوس على عداد الجمل) — بيتحدّث لوحده ويتسكرول */
    fun toggleMiniLog() {
        if (miniLog != null) { closeMiniLog(); return }
        val tv = IconTextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(8)); layoutDirection = View.LAYOUT_DIRECTION_RTL
            text = batchStyled().ifBlank { "مفيش باتشات لسه — دوس ▶ ترجمة" }
        }
        miniLogTv = tv
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; isClickable = true
            background = ui.box(0xEE101418.toInt(), 0x33FFFFFF, 12)
        }
        val head = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(2)) }
        head.addView(ui.text("📋 الباتشات — بيترجم دلوقتي", 12f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconTextView(this).apply { text = "✕"; textSize = 16f; setTextColor(Color.WHITE); setPadding(ui.dp(10), ui.dp(2), ui.dp(8), ui.dp(2)); setOnClickListener { closeMiniLog() } })
        col.addView(head)
        col.addView(MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.42f).toInt()).apply { addView(tv, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2))
        val w = minOf(ui.dp(270), resources.displayMetrics.widthPixels - ui.dp(28))
        videoBoxRef.addView(col, FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(66), ui.dp(14), 0) })
        miniLog = col
    }
    private var logMode = 0
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); LogStore.add("⚠ onTrimMemory level=$level · ${LogStore.heapLine()}") }
    override fun onLowMemory() { super.onLowMemory(); LogStore.add("⚠ onLowMemory · ${LogStore.heapLine()}") }
    override fun status(s: String) { status = s }
    override fun changed() { dirty = true; srtSoon() }
    override fun notice(s: String) { runOnUiThread { try { Notice.show(this, s, 2600L) } catch (e: Throwable) { LogStore.err("Main:1089", e) } } }
    override fun position() = cur / 1000.0
    override fun playerDuration() = durMs / 1000.0

    // ===== نسخ كل الجمل بتوقيتات الظهور والاختفاء (وتحديد اللي مش بتظهر في المشغّل) =====
    private fun fmtT(ms: Long): String { val t = ms.coerceAtLeast(0L); return String.format("%d:%02d:%02d.%03d", t / 3600000, t / 60000 % 60, t / 1000 % 60, t % 1000) }
    /** الجمل اللي هتتحجب ومش هتظهر في المشغّل: السبب لكل جملة (بنفس منطق العرض: الأحدث بس لو الجمل متداخلة) */
    private fun hiddenReasons(): Map<Int, String> {
        val out = HashMap<Int, String>()
        for (i in list.indices) {
            val q = list[i]
            if (q.translated.isBlank()) { out[i] = "النص المترجم فاضي"; continue }
            if (ends[i] <= starts[i]) { out[i] = "مدتها صفر أو سالبة (الاختفاء قبل أو مع الظهور)"; continue }
            val snd = q.isSound
            val map = if (snd) sdMap else spMap; val st = if (snd) sdStarts else spStarts; val en = if (snd) sdEnds else spEnds
            val maxN = if (snd) 2 else 1
            var seen = false; var t = starts[i]
            while (t <= ends[i]) {
                if (PlayerLogic.activeIndices(st, en, t, 0L, 400L, maxN).any { map[it] == i }) { seen = true; break }
                if (t == ends[i]) break
                t = minOf(t + 100L, ends[i])
            }
            if (!seen) out[i] = "متغطّية بجملة تانية متداخلة معاها زمنيًا (المشغّل بيعرض الأحدث بس)"
        }
        return out
    }
    fun sentencesReport(): String {
        val hidden = hiddenReasons()
        val sb = StringBuilder()
        sb.append("📝 الجمل — العدد: ${list.size}\n")
        sb.append("الأوقات بساعة المشغّل (ساعة:دقيقة:ثانية.ملّي)")
        if (offsetMs != 0L) sb.append(" — شاملة تزامن ${String.format("%+.1f", offsetMs / 1000.0)}ث")
        sb.append("\n")
        val short = list.indices.filter { ends[it] > starts[it] && ends[it] - starts[it] < 500L && it !in hidden }
        sb.append("⚠ جمل مش بتظهر في المشغّل: ${hidden.size}")
        if (hidden.isNotEmpty()) sb.append(" ← أرقامها: " + hidden.keys.sorted().joinToString("، ") { (it + 1).toString() })
        sb.append("\n⏱ جمل بتظهر أقل من نص ثانية: ${short.size}")
        if (short.isNotEmpty()) sb.append(" ← أرقامها: " + short.joinToString("، ") { (it + 1).toString() })
        sb.append("\n\n")
        for (i in list.indices) {
            val q = list[i]
            val a = starts[i] + offsetMs; val b = ends[i] + offsetMs
            sb.append("#${i + 1}  ظهور ${fmtT(a)}  ←  اختفاء ${fmtT(b)}  (مدة ${String.format("%.2f", (b - a) / 1000.0)}ث)")
            if (q.isSound) sb.append("  🔊 صوت/خلفية (بتظهر فوق الفيديو)")
            sb.append("\n")
            sb.append("ترجمة: ").append(q.translated.replace("\n", " / ")).append("\n")
            if (q.original.isNotBlank() && q.original != q.translated) sb.append("أصل: ").append(q.original.replace("\n", " / ")).append("\n")
            hidden[i]?.let { sb.append("⚠ مش بتظهر في المشغّل: ").append(it).append("\n") }
            sb.append("\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
    private var pendingSentTxt: String? = null
    fun copySentences() {
        if (list.isEmpty()) { Notice.show(this, ("مفيش جمل لسه").toString(), 2300L); return }
        val txt = try { sentencesReport() } catch (e: Throwable) { Notice.show(this, ("تعذّر تجهيز الجمل").toString(), 2300L); return }
        // الكليبورد بيتعدّى بالـ Binder (حد ~1MB): لو النص كبير جدًا بنحفظه كملف نصي بدل ما يفشل
        if (txt.length > 250_000) {
            pendingSentTxt = txt
            try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "sentences.txt") }, 31) } catch (_: Exception) { pendingSentTxt = null }
            Notice.show(this, ("الجمل كتير — اختار مكان حفظ ملف نصي").toString(), 3600L)
            return
        }
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("sentences", txt))
            Notice.show(this, ("📋 اتنسخت ${list.size} جملة بتوقيتاتها").toString(), 2300L)
        } catch (_: Throwable) {
            pendingSentTxt = txt
            try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "sentences.txt") }, 31) } catch (_: Exception) { pendingSentTxt = null }
        }
    }

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
        val sp = l.indices.filter { !l[it].isSound }; val sd = l.indices.filter { l[it].isSound && (Extras.deaf || !l[it].translated.trim().let { t -> t.startsWith("[") && t.endsWith("]") }) }   // (v105) أوصاف الأصوات القديمة [موسيقى] متظهرش
        spMap = sp.toIntArray(); spStarts = LongArray(sp.size) { starts[sp[it]] }; spEnds = LongArray(sp.size) { ends[sp[it]] }
        sdMap = sd.toIntArray(); sdStarts = LongArray(sd.size) { starts[sd[it]] }; sdEnds = LongArray(sd.size) { ends[sd[it]] }
        if (::sentDlg.isInitialized && sentDlg.isShowing) adapter.notifyDataSetChanged()   // القايمة مش ظاهرة = مفيش داعي نرسمها كل ثانية
    }

    /** (v121) أي فيديو جديد يفتح = أي مشغّل قديم لسه حي (PiP / خلفية / تحت في الستاك) يتوقف ويتقفل خالص: صوت + ترجمة */
    fun killForNewVideo() {
        try { visual.stop() } catch (_: Throwable) {}
        try { player.pause() } catch (_: Throwable) {}
        try { saveRecent() } catch (_: Throwable) {}
        try { pipOv?.hide(); pipOv = null } catch (_: Throwable) {}
        val eng0 = engine
        retireEngine()
        Thread { try { eng0.saveNow() } catch (_: Throwable) {} }.start()
        if (!isFinishing) finish()
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        synchronized(liveList) {
            liveList.removeAll { it.get() == null }
            liveList.toList().forEach { r -> r.get()?.let { o -> if (o !== this) try { o.runOnUiThread { o.killForNewVideo() } } catch (_: Throwable) {} } }
            liveList.removeAll { it.get() !== this }
            liveList.add(java.lang.ref.WeakReference(this))
        }
        UiWatchdog.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Cfg.init(this); CrashLog.install(this); conf = Cfg.snapshot()
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        applyBars(th)
        speed = Cfg.str("speed", "1").toFloatOrNull() ?: 1f; fit = Cfg.int("fit", 0).coerceIn(0, 2); fsFit = Cfg.int("fs_fit", 2).coerceIn(0, 2); offsetMs = 0L
        uri = intent.data; url = intent.getStringExtra("url")
        try {
            intent.getStringExtra("qlist")?.let { sj -> val qa = org.json.JSONArray(sj)
                qOpts = (0 until qa.length()).map { qi -> val o = qa.getJSONObject(qi); YtExtract.Opt(o.optString("l"), o.optString("u"), o.optString("a").ifEmpty { null }, o.optInt("h")) } }
        } catch (e: Exception) { LogStore.err("Player:qlist", e) }
        qCur = qOpts.indexOfFirst { it.url == url }
        aurl = intent.getStringExtra("aurl")?.takeIf { it.isNotEmpty() }   // (v118) صوت منفصل ليوتيوب (جودة عالية)
        noSub = intent.getBooleanExtra("nosub", false); askSaved = intent.getBooleanExtra("ask", false); autoTr = intent.getBooleanExtra("autotr", false)
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
        fx = Fx(this, videoBox, sub, { visOv.area() }, { player.currentPosition / 1000.0 }, { durMs / 1000.0 }, { try { player.isPlaying } catch (_: Exception) { false } },
            { makeRetriever() }, { s -> runOnUiThread { try { player.seekTo((s * 1000).toLong().coerceAtLeast(0L)) } catch (_: Exception) {} } },
            { m -> runOnUiThread { Notice.show(this, m, 2300L) } }, { t -> if (::visual.isInitialized) visual.boxesAt(visNow, t) else emptyList() })
        videoBox.addView(sub, subLp)
        sub.backdrop = sv
        st = IconTextView(this).apply { setTextColor(th.primary); textSize = 11f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)); setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        videoBox.addView(st, FrameLayout.LayoutParams(-2, -2, Gravity.TOP))
        soundTv = IconTextView(this).apply {
            setTextColor(0xFFE8EAED.toInt()); textSize = 13f; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
            gravity = Gravity.CENTER; maxLines = 2; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); background = ui.box(0x99000000.toInt(), 0x00000000, 14); visibility = View.GONE
        }
        // طبقة إيماءات شفافة فوق الفيديو والترجمة وتحت كل الأزرار (لازم تتضاف قبل درج اللوج وزرار 👁 وإلا بتبلع لمسهم)
        val gestureLayer = View(this)
        videoBox.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // (v134) فوق طبقة الإيماءات عشان علامة ⓘ تستقبل اللمس
        videoBox.addView(soundTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(28) })
        soundTv.setOnClickListener { showTermCard() }
        fx?.attachButton()
        // أزرار عايمة فوق الفيديو: 📋 اللوج (يخفي/يظهر حالة الترجمة) و 👁 بصري
        batchTv = IconTextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(8).toFloat() }
            layoutDirection = View.LAYOUT_DIRECTION_RTL; typeface = android.graphics.Typeface.MONOSPACE
            setOnClickListener { toggleLog() }
        }
        // درج اللوج: اللوج + لسان صغير في نص حافته. الضغط على اللسان بيدخّل اللوج أقصى الشمال ويفرده تاني
        logHandle = IconTextView(this).apply {
            text = "◂"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = REST_A
            background = ui.box(0xCC14171C.toInt(), 0x33FFFFFF, 8); setOnClickListener { toggleLog() }
            visibility = View.GONE   // (v90) اللوج بقى بيتفتح من زرار 📋 جنب 📝 في الشريط السفلي
        }
        logHandle.text = if (logOn) "◂" else "▸"
        logDrawer = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            addView(batchTv, LinearLayout.LayoutParams(-2, -2)); addView(logHandle, LinearLayout.LayoutParams(ui.dp(22), ui.dp(46)).apply { marginStart = ui.dp(2) }) }
        batchTv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!logOn) logDrawer.translationX = -(batchTv.width + ui.dp(2)).toFloat() }
        // عمود الشمال: كبسولة الباتش الفاشل (لو في) فوق، واللوج تحتها
        leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; clipChildren = false; clipToPadding = false }
        // (v100) اللوج العايم اتلغى: حالة الباتشات بقت جوه صفحة اللوجز (logCol)
        videoBox.addView(leftCol, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(0, ui.dp(66), 0, 0) })   // (v182) نازل تحت عدّاد الوضع البصري
        // 👁 بصري: دايرة عايمة فوق دايرة ✦
        // (v185) ضغطة واحدة = لقطة (بتتصوّر وتتترجم وتتحفظ مع الفيديو)؛ مفيش ضغطة مطوّلة، والتلقائي مابيتقفلش من الزرار ده (القايمة من عدّاد 👁 أقصى الشمال)
        floatBar = ui.fsCircle("👁") { flashFloat(floatBar); visualSnap() }.apply {
            textSize = 24f; alpha = REST_A
        }
        videoBox.addView(floatBar, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.END or Gravity.TOP).apply { setMargins(0, ui.dp(120), ui.dp(8), 0) })   // (v95) المكان بيتحسب في placeFloatFn
        // (v95) زرار الروتيشن: شاشة بسهمين دايريين زي MX Player — فوق 👁 على طول؛ بيقلب بين الرأسي والأفقي
        rotBtn = ui.fsCircle(Icons.ROT) { flashFloat(rotBtn); toggleFs() }.apply { textSize = 24f; alpha = REST_A }
        videoBox.addView(rotBtn, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END or Gravity.TOP).apply { setMargins(0, ui.dp(60), ui.dp(11), 0) })
        rotBtn.visibility = View.GONE   // (v97) زرار الروتيشن العايم اتلغى — الروتيشن في الشريط السفلي بس
        // 🔄 ترجم باتش معين: دايرة عايمة فوق 👁
        batchBtn = ui.fsCircle("🔄") { batchDialog() }.apply { textSize = 20f; alpha = REST_A; translationY = -ui.dp(60).toFloat() }
        // (v90) الزرار العايم 🔄 اتشال من الشاشة — «إعادة ترجمة» لسه في 🧰 وفي ☰
        // (v90) كبسولة «▶ ترجمة» الحمرا على الشمال اتشالت — زرار ▶ ترجمة الوحيد في الشريط السفلي
        // كبسولة جانبية للباتشات الفاشلة (زي اللوج): مخفية خالص لحد ما باتش يفشل، وبعدها بيظهر لسان ⚠ على الحافة الشمال — دوس عليه يفرد الكبسولة ودوس تاني يلمّها
        probBar = IconTextView(this).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); background = ui.box(0xE6B71C1C.toInt(), 0x33FFFFFF, 12)
            setOnClickListener { askRetryProblem() }
        }
        probHandle = IconTextView(this).apply {
            text = "⚠"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xE6B71C1C.toInt(), 0x33FFFFFF, 8); alpha = PROB_A; setOnClickListener { toggleProb() }
        }
        probDrawer = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(probBar, LinearLayout.LayoutParams(-2, -2)); addView(probHandle, LinearLayout.LayoutParams(ui.dp(26), ui.dp(40)).apply { marginStart = ui.dp(2) }) }
        probBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!probOn) probDrawer.translationX = -(probBar.width + ui.dp(2)).toFloat() }
        leftCol.addView(probDrawer, 0, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(4) })

        // زرار التشغيل الأوسط (بيظهر وقت الإيقاف) + شارة النسبة (شاشة كاملة) + فلاش السيك/الصوت/السطوع
        centerPlay = FadePop(this).apply {
            text = "▶"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); visibility = View.GONE
            background = glassCircleBg(resources.displayMetrics.density)
            setOnClickListener { togglePlay() }
        }
        videoBox.addView(centerPlay, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER))
        fsBadge = TransBadge(this, th).apply { visibility = View.GONE; alpha = 0.5f; setOnClickListener { alpha = 1f; toggleMiniLog(); h.postDelayed({ alpha = 0.5f }, 4000) }; setOnLongClickListener { alpha = 1f; closeMiniLog(); versionsPopup(this); h.postDelayed({ alpha = 0.5f }, 4000); true } }   // (v125) دوسة = اللوج الصغير (أنهي باتش بيترجم) · ضغطة طويلة = قايمة الترجمات المحفوظة
        videoBox.addView(fsBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(14), ui.dp(14), 0) })
        // (v182) عدّاد جمل الوضع البصري: أقصى الشمال (عدّاد الصوت أقصى اليمين). دوسة = قايمة الوضع البصري
        visBadge = TransBadge(this, th).apply { visibility = View.GONE; alpha = 0.5f; setOnClickListener { alpha = 1f; visualDialog(); h.postDelayed({ alpha = 0.5f }, 4000) } }
        videoBox.addView(visBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(14), ui.dp(14), 0, 0) })
        val gi = IconTextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(0x99000000.toInt(), Color.TRANSPARENT, 24); visibility = View.GONE
        }
        videoBox.addView(gi, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val volInd = VertInd(this, "🔊", 2f); val briInd = VertInd(this, "☀️")
        videoBox.addView(volInd, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(24), 0) })
        videoBox.addView(briInd, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { setMargins(ui.dp(24), 0, 0, 0) })
        val indHide = Runnable { volInd.visibility = View.GONE; briInd.visibility = View.GONE }
        fun indShow(v: VertInd, f: Float) { v.set(f); h.removeCallbacks(indHide); h.postDelayed(indHide, 900) }
        // فقاعة الأدوات (Assistive Touch): ✦ تفتح عمود دوائر
        fun roundBtn(t: String, f: () -> Unit) = IconTextView(this).apply {
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
        menu.addView(roundBtn("🕳") { engine.retryFailed(); Notice.show(this, ("بحاول أسد الفجوات").toString(), 2300L) })
        menu.addView(roundBtn("📥") { doImport() })
        menu.addView(roundBtn("📂") { doOpen() })
        videoBox.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, ui.dp(68), 0) })
        fsOnly = listOf<View>(fsBadge, visBadge); assistMenuV = menu
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
        /** زرار دايري صغير للصف الأدوات اللي تحت جنب أزرار الشاشة */
        fun mini(t: String, f: (TextView) -> Unit): TextView {
            val ref = arrayOfNulls<TextView>(1)
            val v = ui.fsCircle(t) { ref[0]?.let { f(it) }; showChrome() }
            ref[0] = v; v.textSize = 12f; return v
        }
        // زرار تبديل عرض الترجمة: ترجمة ← أصلي ← ترجمة+أصلي تحت ← أصلي فوق+ترجمة تحت
        val dualCycle = listOf(0, 3, 1, 4); val dualShort = mapOf(0 to "فردي", 3 to "أصلي", 1 to "مزدوج", 4 to "مزدوج (أصلي فوق)", 2 to "إنجليزي")
        fun dualLabel() = "💬 وضع الترجمة: " + (dualShort[curStyle().dual] ?: "فردي")
        fun dualBg() = ui.box(if (curStyle().dual == 0) 0xE0141418.toInt() else 0xFF1F5FBF.toInt(), 0x1FFFFFFF, 12)   // الأزرق = وضع غير الفردي
        fun dualCircleBg() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (curStyle().dual == 0) 0xE00F0F12.toInt() else 0xFF1F5FBF.toInt()); setStroke(ui.dp(1), 0x1FFFFFFF) }
        fun cycleDual(v: TextView) {
            val n = dualCycle[(dualCycle.indexOf(curStyle().dual) + 1) % dualCycle.size]
            Cfg.put("sub_dual", n.toString()); restyle(); v.text = "💬"; v.background = dualCircleBg()
            giShow(dualLabel(), Gravity.CENTER)
        }
        var fsSpeedB: TextView? = null
        var ccFsB: TextView? = null
        fun cycleSpeed() {
            speed = PlayerLogic.nextSpeed(speed); player.setPlaybackSpeed(speed)
            Cfg.p.edit().putString("speed", speed.toString()).apply()
            fsSpeedB?.text = "⏩ " + PlayerLogic.speedLabel(speed)
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
        fun dismissPop() { try { popup?.dismiss() } catch (e: Exception) { LogStore.err("Main:1353", e) }; popup = null; popupOwner = null }
        dismissPopFn = { dismissPop() }
        fun after(keep: Boolean) { if (!keep) dismissPop(); showChrome(); if (popup != null) h.removeCallbacks(hideChrome) }
        fun pk(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(true) }.apply { minimumWidth = ui.dp(150) }
        fun pd(t: String, f: (TextView) -> Unit): TextView = ui.fsBtn(t) { v -> f(v); after(false) }.apply { minimumWidth = ui.dp(150) }
        fun hRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        fun gCol() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(0xF2141418.toInt(), 0x33FFFFFF, 12) }
        val gText = gCol(); val gAi = gCol(); val gTool = gCol()
        // الأزرار العلوية في صف واحد بس (في الرأسي الصف بيتسحب أفقيًا لو الأزرار أكتر من العرض بدل ما تنزل لسطر تاني)
        // الترتيب من اليمين: CC ← احتواء ← القائمة ← الجمل ← النص ← لهجة ← أدوات (كل زرار بيتضاف على يسار اللي قبله)
        val tbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun tbAdd(v: View) { tbRow.addView(v, 0) }
        val tb = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(tbRow, FrameLayout.LayoutParams(-2, -2))
        }
        var tbScrolled = false
        tb.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!tbScrolled && tbRow.width > 0) { tbScrolled = true; tb.post { tb.scrollTo(tbRow.width, 0) } } }
        var textAtBottom = false
        fun togglePop(v: View, col: LinearLayout, above: Boolean) {
            val had = popupOwner === v; dismissPop()
            if (!had) {
                (col.parent as? android.view.ViewGroup)?.removeView(col)
                // (v125) القوايم الطويلة بتتسكرول وبتتحصر في المساحة المتاحة بدل ما تطلع بره الشاشة
                val dmP = resources.displayMetrics
                col.measure(View.MeasureSpec.makeMeasureSpec((dmP.widthPixels * 0.9f).toInt(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val goUp = above || (col === gText && textAtBottom)   // زرار في الشريط السفلي: القايمة تفتح فوقه
                val avail = (if (goUp) loc[1] - ui.dp(8) else dmP.heightPixels - loc[1] - v.height - ui.dp(8)).coerceAtLeast(ui.dp(120))
                val popH = minOf(col.measuredHeight, avail)
                val svP = android.widget.ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; addView(col) }
                val pw = android.widget.PopupWindow(svP, maxOf(col.measuredWidth, ui.dp(150)), popH, false)
                pw.isOutsideTouchable = true; pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
                if (goUp) pw.showAsDropDown(v, 0, -(v.height + popH + ui.dp(2)))
                else pw.showAsDropDown(v, 0, ui.dp(2))
                popup = pw; popupOwner = v
            }
            showChrome(); if (popup != null) h.removeCallbacks(hideChrome)
        }
        fun grp(label: String, col: LinearLayout, above: Boolean = false): TextView = ui.fsBtn("$label ▾") { v -> togglePop(v, col, above) }
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
        val spB = pk("⏩ " + PlayerLogic.speedLabel(speed)) { cycleSpeed() }
        fsSpeedB = spB; gText.addView(spB)
        run {
            val cs = curStyle()
            val bgTv = IconTextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
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
        val ccB = mini("CC") { toggleCc() }.apply { textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        ccFsB = ccB
        fun loopBg() = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (Cfg.str("loop", "0") == "1") 0xFF1F5FBF.toInt() else 0xE00F0F12.toInt()); setStroke(ui.dp(1), 0x1FFFFFFF) }
        val loopB = mini("🔂") { v -> Cfg.put("loop", if (Cfg.str("loop", "0") == "1") "0" else "1"); v.background = loopBg(); giShow(if (Cfg.str("loop", "0") == "1") "🔂 تكرار الفيديو: شغّال" else "🔂 تكرار الفيديو: مقفول", Gravity.CENTER) }.apply { background = loopBg() }
        val dualB = mini("💬") { v -> cycleDual(v) }.apply { background = dualCircleBg() }   // وضع الترجمة (فردي / أصلي / مزدوج) — أيقونة بس من غير كلام
        ccToggleFn = { toggleCc() }
        if (noSub) { ccOn = false; ccB.alpha = 0.4f; ctl.cc.alpha = 0.4f }
        val fitB = fb("⬛ " + PlayerLogic.fitNames[curFit()]) { v ->
            if (isLandNow()) { fsFit = (fsFit + 1) % 3; Cfg.p.edit().putString("fs_fit", fsFit.toString()).apply() }
            else { fit = (fit + 1) % 3; Cfg.p.edit().putString("fit", fit.toString()).apply() }
            v.text = "⬛ " + PlayerLogic.fitNames[curFit()]; applyFit(sv, videoBox)
        }
        fitFsB = fitB
        val menuB = IconGlyphButton(this, "menu").apply { background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12); setOnClickListener { togglePop(this, gTool, false) } }
        val bgOnB = IconGlyphButton(this, if (Cfg.bool("bg_on_exit", false)) "check_box" else "box").apply {
            background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12)
            setOnClickListener {
                val on = !Cfg.bool("bg_on_exit", false)
                Cfg.put("bg_on_exit", if (on) "1" else "0"); iconName = if (on) "check_box" else "box"
                giShowFn(if (on) "هكمّل الترجمة في الخلفية لما تخرج: شغّال" else "كمّل في الخلفية لما تخرج: مقفول")
                showChrome()
            }
            setOnLongClickListener { giShow("كمّل الترجمة في الخلفية لما أخرج", Gravity.CENTER); true }
        }
        // (v164) زرار مباشر لفلتر المشاهد الحساسة (زي ✔ الخلفية): ضغطة = يلف مقفول ← زرار «تخطي» ← تخطي تلقائي. ضغطة طويلة = إعدادات الأنواع
        val sceneB = IconGlyphButton(this, "shield")
        fun paintScene() {
            val m = Extras.sceneMode
            sceneB.background = ui.box(when (m) { 1 -> 0xFF1F5FBF.toInt(); 2 -> 0xFF2E7D32.toInt(); else -> 0xE0141418.toInt() }, 0x1FFFFFFF, 12)
            sceneB.alpha = if (m == 0) 0.65f else 1f
        }
        sceneB.setOnClickListener {
            val n = (Extras.sceneMode + 1) % 3
            Cfg.put("fx_scene", n.toString()); Extras.load(); fx?.refresh(); paintScene()
            giShowFn(if (n > 0 && Cfg.keys("viskeys").isEmpty()) "ضيف مفتاح «الوضع البصري» عشان فلتر المشاهد يشتغل" else when (n) {
                1 -> "⏭ فلتر المشاهد: زرار «تخطي» هيظهر وقت المشهد"
                2 -> "⏭ فلتر المشاهد: تخطي تلقائي"
                else -> "⏭ فلتر المشاهد: مقفول"
            })
            showChrome()
        }
        sceneB.setOnLongClickListener { ExtrasUi.show(this, fx) { fx?.refresh(); paintScene() }; true }
        paintScene()
        // (v118) زرار الجودة: يوتيوب بقايمة جودات (بحجمها التقريبي) · روابط HLS/DASH بمساراتها — للفرجة بداتا أقل
        val gQ = gCol()
        fun fillQ(): Boolean {
            gQ.removeAllViews()
            if (qOpts.size > 1) {
                for ((qi, o) in qOpts.withIndex()) gQ.addView(pd((if (qi == qCur) "✓ " else "") + o.label) { switchYt(qi) })
            } else {
                val tg = ArrayList<Triple<Int, androidx.media3.common.Tracks.Group, Int>>()
                for (g in player.currentTracks.groups) if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO)
                    for (ti in 0 until g.length) if (g.isTrackSupported(ti)) tg.add(Triple(g.getTrackFormat(ti).height, g, ti))
                // (v120) أعلى bitrate لكل ارتفاع، والحجم الكلي تقريبي = bitrate × مدة الفيديو (HLS/DASH مفيهوش حجم جاهز)
                val hs = tg.filter { it.first > 0 }.sortedWith(compareByDescending<Triple<Int, androidx.media3.common.Tracks.Group, Int>> { it.first }.thenByDescending { it.second.getTrackFormat(it.third).bitrate }).distinctBy { it.first }
                fun szOf(q: Triple<Int, androidx.media3.common.Tracks.Group, Int>): String {
                    val br = q.second.getTrackFormat(q.third).bitrate.toLong()
                    return if (br > 0 && durMs > 0) " · ≈" + Sniff.fmtSize(br / 8 * (durMs / 1000)) else ""
                }
                if (hs.size > 1) {
                    gQ.addView(pd((if (hlsH == 0) "✓ " else "") + "تلقائي") { hlsAuto() })
                    for (q in hs) gQ.addView(pd((if (hlsH == q.first) "✓ " else "") + q.first + "p" + szOf(q)) { hlsPick(q.second, q.third, q.first) })
                }
            }
            return gQ.childCount > 0
        }
        val qualB = mini("HD") { v -> if (fillQ()) togglePop(v, gQ, true) else Notice.show(this, "مفيش جودات تانية للفيديو ده", 2300L) }
        qualB.visibility = if (uri == null) View.VISIBLE else View.GONE
        qualBRef = qualB
        qualPopFn = { v -> if (fillQ()) togglePop(v, gQ, true) else Notice.show(this, "مفيش جودات تانية للفيديو ده", 2300L) }
        setQualLabel(qOpts.getOrNull(qCur)?.h ?: 0)
        val sentB = mini("📝") { sentDlg.show() }   // الجمل
        val charsB = mini("🧑") { showChars() }   // (v151) الشخصيات وجنسها — نافذة فوق الفيديو من غير ما تضغط شاشته
        val logB = mini("📜") { toggleLog() }   // (v90) اللوج — مكان 🔄 جنب الجمل
        // التوقيت: زرار واحد ⏱ (في الشريط السفلي) بيفتح قايمة صغيرة: تقديم −0.1 / القيمة (ضغطة = رجوع للصفر) / تأخير +0.1 — زي MX Player
        // لوحة ⏱: تزامن الترجمة (تقديم −0.1 / القيمة / تأخير +0.1) — حجم الخط بالقرص (pinch)
        val gSub = gCol()
        gSub.addView(pk("⏱ تقديم −0.1") { setOff(-100) })
        val offB = pk(String.format("%+.1fs", offsetMs / 1000.0)) { setOff(-offsetMs) }
        offFsB = offB; gSub.addView(offB)
        gSub.addView(pk("⏱ تأخير +0.1") { setOff(100) })
        // (v103) قايمة ☰ = قايمة منسدلة صغيرة فيها بس الأزرار اللي مش موجودة في المشغّل نفسه
        gTool.addView(pd("📤 تصدير SRT") { doExport() })
        gTool.addView(pd("📥 استيراد SRT") { doImport() })
        gTool.addView(pd("📂 فتح فيديو") { doOpen() })
        gTool.addView(pd("🕳 سد الفجوات") { engine.retryFailed(); Notice.show(this, ("بحاول أسد الفجوات").toString(), 2300L) })
        // (v87) ترجمات الفيديو: قايمة صغيرة منسدلة بالنسخ المحفوظة (دوسة على نسخة ترجّعها)
        val gVer = gCol()
        fun fillVer() {
            gVer.removeAllViews()
            gVer.addView(pk("‹ رجوع") { dismissPop(); togglePop(menuB, gTool, false) })
            gVer.addView(pd("💾 احفظ النسخة الحالية") { engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); Notice.show(this, "اتحفظت نسخة", 1800L) })
            val vs = engine.versions.toList()
            if (vs.isEmpty()) gVer.addView(ui.text("مفيش نسخ محفوظة لسه", 12f, Color.WHITE).apply { setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)) })
            vs.forEachIndexed { i, v -> gVer.addView(pd("${v.name} — ${v.subs.size} جملة") { restoreVersion(i) }) }
        }
        gTool.addView(pk("🗂 ترجمات الفيديو ›") { fillVer(); dismissPop(); togglePop(menuB, gVer, false) })
        gTool.addView(pd("🔄 إعادة ترجمة") { batchDialog() })
        gTool.addView(pd("👁 الوضع البصري (قايمة / إعادة)") { visualDialog() })
        gTool.addView(pd("🌙 ترجمة بالخلفية") { translateInBackground() })
        gTool.addView(pd("📜 ذكّرني") { recapDialog() })
        gTool.addView(pd("🌍 ترجمة جوجل (من غير API)") { googleTranslateNow() })
        gTool.addView(pd("🎛 ميزات المشاهدة") { ExtrasUi.show(this, fx) { fx?.refresh() } })
        // (v87) الإعدادات: قايمة صغيرة منسدلة بالأقسام — دوسة على قسم تفتحه دايركت (من غير شاشة القايمة الكبيرة)
        val gSet = gCol()
        gSet.addView(pk("‹ رجوع") { dismissPop(); togglePop(menuB, gTool, false) })
        for ((tid, tl) in listOf("fonts" to "🔤 الخطوط", "anim" to "✨ الأنيميشن", "look" to "🎬 العرض والألوان", "general" to "🌐 اللهجة والأسلوب", "chars" to "🧑 الشخصيات",
            "engine" to "⚙ الترجمة والمحرك", "keys" to "🔑 المفاتيح", "bg" to "🌙 الترجمة في الخلفية", "sec" to "🔒 الأمان", "theme" to "🎨 المظهر"))
            gSet.addView(pd(tl) { openSettings(tid) })
        gSet.addView(pd("📥 تحميل النص الأصلي (SRT)") { exportOriginalSrt() })
        // (v167) زرار «تضخيم الصوت» اتشال — التضخيم بقى جزء أحمر في سلايدر الصوت نفسه (بعد 100%)
        gTool.addView(pk("⚙️ الإعدادات ›") { dismissPop(); togglePop(menuB, gSet, false) })
        // (v135) 🧰 أدوات الفيديو (زرار في الشريط العلوي): قص · صوت · GIF · ضغط · ترجمة ثابتة — كل واحدة بتتحط في توبيب «المهام» وبتتحفظ لوحدها لما تخلص
        val gTools = gCol()
        val toolsUi = ToolsUi(this, ui, th)
        fun withSrc(f: (ToolSrc) -> Unit) { val ts = toolSrc(); if (ts == null) Notice.show(this, "مفيش فيديو شغّال", 2300L) else f(ts) }
        gTools.addView(ui.text("🧰 أدوات الفيديو", 12f, th.primary, true).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(4)) })
        gTools.addView(pd("✂ قص جزء من الفيديو") { withSrc { toolsUi.trim(it) } })
        gTools.addView(pd("🎧 تحويل لصوت") { withSrc { toolsUi.audio(it) } })
        gTools.addView(pd("🎞 عمل GIF") { withSrc { toolsUi.gif(it) } })
        gTools.addView(pd("📦 ضغط / تغيير الدقة") { withSrc { toolsUi.compress(it) } })
        gTools.addView(pd("🎬 ترجمة ثابتة (هارد ساب)") { withSrc { toolsUi.hardsub(it) } })
        gTools.addView(ui.text("النتيجة بتظهر في «📋 صفحة المهام» (زرار ⏳ جنب الترجمة)", 10f, 0xFF9AA0A6.toInt()).apply { setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(2)) })
        // (v188) صفحة المهام (التقدم + الناتج) في نافذة فوق المشغّل — الأدوات جواها بتشتغل على الفيديو الشغّال
        fun openTasksDialog() {
            val tu = TasksUi(this, ui, th) { k ->
                withSrc { ts ->
                    when (k) { "trim" -> toolsUi.trim(ts); "audio" -> toolsUi.audio(ts); "gif" -> toolsUi.gif(ts); "compress" -> toolsUi.compress(ts); "hardsub" -> toolsUi.hardsub(ts); else -> {} }
                }
            }
            val dlg = android.app.Dialog(this)
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
            box.addView(ui.button("✕ إغلاق") { dlg.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(8), ui.dp(12), 0) })
            box.addView(tu.root, LinearLayout.LayoutParams(-1, 0, 1f))
            dlg.setContentView(box)
            val l: () -> Unit = { tu.refresh() }
            TaskCenter.extra.add(l)
            dlg.setOnDismissListener { TaskCenter.extra.remove(l) }
            dlg.show()
            dlg.window?.setLayout(-1, -1)
        }
        val tasksPageB = pd("📋 صفحة المهام") { openTasksDialog() }
        gTools.addView(tasksPageB, 1)
        tasksPopFn = { v -> tasksPageB.text = "📋 صفحة المهام (" + taskCount() + ")"; togglePop(v, gTools, true) }
        val toolsB = IconGlyphButton(this, "toolbox").apply { background = ui.box(0xE0141418.toInt(), 0x1FFFFFFF, 12); setOnClickListener { togglePop(this, gTools, false) } }
        // (v151) خيارات متعددة الاختيار + زرار واحد «طبّق المحدد»: بيتنفّذوا ورا بعض (تنقيح ← لهجة+ ← عائلي/صريح ← ضمائر ← دمج مكرر)
        val aiBase = linkedMapOf("refine" to "✍ تنقيح بالسياق", "dial" to "🔥 لهجة +", "family" to "🧹 عائلي", "explicit" to "🔞 صريح", "pron" to "🔧 ضمائر", "dedup" to "🧠 دمج مكرر")
        val aiBtns = HashMap<String, TextView>()
        val aiApplyB = pd("▶ طبّق المحدد (0)") { applyAiOptions() }
        fun paintAi() {
            for ((id, b) in aiBtns) { val on = id in aiSel; b.text = (if (on) "✓ " else "") + aiBase[id]; b.background = ui.box(if (on) 0xFF1F5FBF.toInt() else 0xE0141418.toInt(), 0x1FFFFFFF, 12) }
            aiApplyB.text = "▶ طبّق المحدد (${aiSel.size})"; aiApplyB.alpha = if (aiSel.isEmpty()) 0.45f else 1f
        }
        for ((id, base) in aiBase) {
            val b = pk(base) { _ ->
                if (!aiSel.remove(id)) { aiSel.add(id); if (id == "family") aiSel.remove("explicit"); if (id == "explicit") aiSel.remove("family") }   // عائلي وصريح عكس بعض
                paintAi()
            }
            aiBtns[id] = b; gAi.addView(b)
        }
        gAi.addView(aiApplyB); paintAi()
        // ===== الصف العلوي: الأساسي ظاهر دايمًا (☰ 📝 CC وضع-الترجمة Aa) والباقي بيتفرد بسهم ❮ وبيتلم بعد 5 ثواني =====
        fun tip(v: TextView, name: String): TextView { v.setOnLongClickListener { giShow(name, Gravity.CENTER); true }; return v }   // ضغطة طويلة = اسم الزرار
        // (v96) الزرار ❮ اتشال: كل أزرار الصف العلوي ظاهرة على طول (☰ القائمة + 🔤 النص)
        val tbExtra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        fun exAdd(v: View) { tbExtra.addView(v, 0) }
        tbCollapseFn = { }
        menuB.setOnLongClickListener { giShow("القائمة", Gravity.CENTER); true }
        toolsB.setOnLongClickListener { giShow("أدوات الفيديو", Gravity.CENTER); true }
        tbRow.addView(menuB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }); tbAdd(tbExtra)

        val textB = tip(grp("🔤", gText), "النص")
        exAdd(textB)

        fsPlayB = IconTextView(this).apply {
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
        // صف تحت (فوق شريط الوقت، فوق القفل): A− A+ | ⏱ توقيت | ترجمة (إيقاف/استئناف + اللغة). بيلفّ لسطر تاني لو الشاشة ضيقة
        // الصف ده بيبدأ من اليمين: شريحة الترجمة بس (حجم الترجمة والتوقيت اتنقلوا لزرار Aa فوق)
        val auxRow = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        auxRow.addView(makeTrRow(true))
        fsBar.addView(auxRow, LinearLayout.LayoutParams(-1, -2))
        fsBar.addView(timeRow, LinearLayout.LayoutParams(-1, ui.dp(30)))
        // صف الأدوات الصغيرة (فوق أزرار الشاشة) على اليمين: Aa (حجم/توقيت) · 📋 اللوج · 📝 الجمل · CC · 💬 وضع الترجمة
        val toolRow = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val toolR = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        fun barPill(b: TextView): TextView = b.apply { layoutParams = LinearLayout.LayoutParams(-2, ui.dp(30)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }; minimumWidth = ui.dp(30); setPadding(ui.dp(8), 0, ui.dp(8), 0); textSize = 12f }
        val aiB = barPill(tip(grp("🗣", gAi, true), "لهجة (عائلي/صريح)"))
        val syncB = barPill(tip(grp("⏱", gSub, true), "تزامن الترجمة (تقديم / تأخير)"))   // (v91) مكان Aa — حجم الخط بقى بالقرص (pinch) على الشاشة
        toolR.addView(syncB)
        val toolBtns = listOf<TextView>(syncB, tip(logB, "اللوج"), tip(sentB, "الجمل"), tip(ccB, "إظهار/إخفاء الترجمة"), tip(loopB, "تكرار الفيديو"), tip(dualB, "وضع الترجمة"), tip(charsB, "الشخصيات"))
        toolBtns.drop(1).forEach { b ->
            toolR.addView(b, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        toolRow.addView(toolR, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        fsBar.addView(toolRow, LinearLayout.LayoutParams(-1, ui.dp(36)))
        // (v90) شمال: 🔓 ✨ 🧰 جنب بعض · النص: ⏮ ⏯ ⏭ كبار وموزّعين على عرض الشريط · يمين: 🔄(لاندسكيب/بورتريت) ⧉ ⛶ (⛶ على الحافة)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
        // (v96) ثلاث أعمدة: شمال (وزن 1) | نص (⏮ ▶ ⏭ متسنطرين فعلًا على عرض الشاشة) | يمين (وزن 1) — فالنص دايمًا في نص الشريط بالظبط
        val leftB = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        val lockB = ui.fsCircle("🔓") { setLock(true) }
        val lockLp = { LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(4) } }
        leftB.addView(lockB, lockLp()); leftB.addView(aiB)
        btnRow.addView(leftB, LinearLayout.LayoutParams(0, -2, 1f))
        val pillBg = { ui.box(0xE00F0F12.toInt(), 0x29FFFFFF, 24) }
        val prevB = ui.fsCircle("⏮") { stepOrSeek(-1); showChrome() }.apply { textSize = 20f; background = pillBg() }
        val nextB = ui.fsCircle("⏭") { stepOrSeek(1); showChrome() }.apply { textSize = 20f; background = pillBg() }
        fsPlayB.textSize = 30f
        fsPlayB.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE00F0F12.toInt()); setStroke(ui.dp(1), 0x29FFFFFF) }
        val mid = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER; setPadding(ui.dp(6), 0, ui.dp(6), 0) }
        mid.addView(prevB, LinearLayout.LayoutParams(ui.dp(60), ui.dp(44)).apply { marginEnd = ui.dp(8) })
        mid.addView(fsPlayB, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        mid.addView(nextB, LinearLayout.LayoutParams(ui.dp(60), ui.dp(44)).apply { marginStart = ui.dp(8) })
        btnRow.addView(mid, LinearLayout.LayoutParams(-2, -2))
        val rightB = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fsBarFs = ui.fsCircle("⛶") { cycleFitNow(); showChrome() }
        val rotBarB = ui.fsCircle(Icons.ROT) { toggleFs(); showChrome() }.apply { textSize = 19f }   // لاندسكيب/بورتريت (أيقونة الروتيشن زي MX)
        val pipBarB = ui.fsCircle("⧉") { enterPip() }
        rightB.addView(rotBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) })
        rightB.addView(pipBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        rightB.addView(fsBarFs, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(8) })
        btnRow.addView(rightB, LinearLayout.LayoutParams(0, -2, 1f))
        fsBar.addView(btnRow, LinearLayout.LayoutParams(-1, ui.dp(72)))
        // (v91) في العرض الواسع (لاندسكيب): ⏱ 📋 📝 CC 💬 بتتنقل للصف السفلي جنب 🔄؛ في الضيق (بورتريت) بتفضل في صفها
        var toolsWide = false
        fun placeTools(wide: Boolean) {
            if (toolsWide == wide) return; toolsWide = wide
            // (v101) كل الأزرار قليلة الاستعمال (☰ 🔤 ⏱ 💬 CC 📋 📝 🔁) في الشريط العلوي جنب عداد الجمل، مفرودة بطوله — والشريط السفلي فيه 🔓 ✨ + ⏮ ▶ ⏭ + أزرار الشاشة بس.
            // 🧰 اتشال: كل اللي فيه موجود في ☰ القائمة / عداد الجمل ▾ / شريحة الترجمة 🔁
            toolBtns.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            (menuB.parent as? ViewGroup)?.removeView(menuB); (textB.parent as? ViewGroup)?.removeView(textB)
            leftB.removeAllViews(); rightB.removeAllViews(); tbRow.removeAllViews()
            // toolBtns = [⏱ توقيت, 📋 لوج, 📝 جمل, CC, 🔁 تكرار, 💬 وضع الترجمة, 🧑 شخصيات]
            fun lp(i: Int) = LinearLayout.LayoutParams(if (i == 0) -2 else ui.dp(38), ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
            leftB.addView(lockB, lockLp()); leftB.addView(aiB)
            rightB.addView(rotBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(8) })
            rightB.addView(pipBarB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            rightB.addView(fsBarFs, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginStart = ui.dp(8) })
            // من الشمال لليمين (LTR): 🔁 📋 📝 🔤 ⏱ 💬 CC ☰  ← يعني ☰ على اليمين جنب عداد الجمل
            tbRow.addView(toolBtns[4], lp(4)); tbRow.addView(toolBtns[1], lp(1)); tbRow.addView(toolBtns[2], lp(2)); tbRow.addView(toolBtns[6], lp(6))
            tbRow.addView(textB, LinearLayout.LayoutParams(-2, ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            tbRow.addView(toolBtns[0], lp(0)); tbRow.addView(toolBtns[5], lp(5)); tbRow.addView(toolBtns[3], lp(3))
            tbRow.addView(bgOnB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            tbRow.addView(sceneB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            (toolsB.parent as? ViewGroup)?.removeView(toolsB)
            tbRow.addView(toolsB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            tbRow.addView(menuB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })
            textB.minimumWidth = ui.dp(38); textB.setPadding(ui.dp(8), 0, ui.dp(8), 0)
            textAtBottom = false
            tb.visibility = View.VISIBLE
            toolRow.visibility = View.GONE
        }
        // (v101) الزراير اللي على جنبين ⏮ ⏭ كتير: لو مكانها ضيّق بنصغّرها (حجم + مسافات + خط) بدل ما آخر زرار يتاكل
        placeTools(true)
        val sideOrig = HashMap<View, FloatArray>()
        var fitW = -1
        fun scaleSide(g: LinearLayout, avail: Int) {
            val kids = (0 until g.childCount).map { g.getChildAt(it) }
            if (kids.isEmpty()) return
            var need = 0f
            for (c in kids) {
                val lp = c.layoutParams as LinearLayout.LayoutParams
                if (!sideOrig.containsKey(c)) {
                    val ts = (c as? TextView)?.textSize ?: 0f
                    val nat = if (lp.width > 0) lp.width.toFloat() else { c.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); c.measuredWidth.toFloat() }
                    sideOrig[c] = floatArrayOf(lp.width.toFloat(), lp.height.toFloat(), lp.leftMargin.toFloat(), lp.rightMargin.toFloat(), ts, c.paddingLeft.toFloat(), c.paddingRight.toFloat(), nat, c.minimumWidth.toFloat())
                }
                val o = sideOrig[c]!!; need += o[7] + o[2] + o[3]
            }
            val f = if (need <= 0f) 1f else (avail / need).coerceIn(0.58f, 1f)
            for (c in kids) {
                val o = sideOrig[c] ?: continue; val lp = c.layoutParams as LinearLayout.LayoutParams
                lp.width = if (o[0] > 0) (o[0] * f).toInt() else o[0].toInt()
                lp.height = if (o[1] > 0) (o[1] * f).toInt() else o[1].toInt()
                lp.setMargins((o[2] * f).toInt(), lp.topMargin, (o[3] * f).toInt(), lp.bottomMargin)
                (c as? TextView)?.let { tv -> if (o[4] > 0) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o[4] * (0.5f + 0.5f * f)) }
                c.minimumWidth = (o[8] * f).toInt()
                c.setPadding((o[5] * f).toInt(), c.paddingTop, (o[6] * f).toInt(), c.paddingBottom)
                c.layoutParams = lp
            }
        }
        fun fitSides(w: Int) {
            if (w == fitW) return; fitW = w
            val mf = if (w >= ui.dp(720)) 1f else if (w >= ui.dp(480)) 0.86f else 0.76f
            (prevB.layoutParams as LinearLayout.LayoutParams).apply { width = (ui.dp(60) * mf).toInt(); height = (ui.dp(44) * mf).toInt(); marginEnd = (ui.dp(8) * mf).toInt() }.also { prevB.layoutParams = it }
            (nextB.layoutParams as LinearLayout.LayoutParams).apply { width = (ui.dp(60) * mf).toInt(); height = (ui.dp(44) * mf).toInt(); marginStart = (ui.dp(8) * mf).toInt() }.also { nextB.layoutParams = it }
            (fsPlayB.layoutParams as LinearLayout.LayoutParams).apply { width = (ui.dp(56) * mf).toInt(); height = (ui.dp(56) * mf).toInt() }.also { fsPlayB.layoutParams = it }
            val midW = (ui.dp(60) * 2 + ui.dp(8) * 2 + ui.dp(56)) * mf + ui.dp(12)
            val avail = ((w - ui.dp(28) - midW) / 2f - ui.dp(2)).toInt().coerceAtLeast(ui.dp(60))
            scaleSide(leftB, avail); scaleSide(rightB, avail)
        }
        fsBar.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> val w = r - l; if (w > 0) fsBar.post { placeTools(true); fitSides(w) } }
        fsBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot && chromeShown) applyChromeFn() }   // ارتفاع الشريط اتغيّر: ارفع الترجمة فوقه
        val chromeFrame = FrameLayout(this).apply { visibility = View.GONE; tag = "chromeFrame" }
        // في الرأسي الصفوف بتلفّ لسطر جديد (FlowRow) — من غير HorizontalScrollView لأنه بيدّي عرض لا نهائي فالصف عمره ما بيلفّ
        chromeFrame.addView(tb, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply { setMargins(ui.dp(8), ui.dp(10), ui.dp(100), 0) })   // (v90) يمين، جنب عداد الجمل
        chromeFrame.addView(fsBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(0, 0, 0, ui.dp(6)) })
        // زرار القفل الصغير: بيظهر لما تلمس الشاشة وهي مقفولة
        lockOv = ui.fsCircle("🔒") { setLock(false) }.apply { visibility = View.GONE }
        videoBox.addView(lockOv, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(22), 0, 0, ui.dp(22)) })
        videoBox.addView(chromeFrame, FrameLayout.LayoutParams(-1, -1))
        // (v104) شرايط التقدم الـ3 بحجم رفيع جدًا دايمًا تحت الفيديو؛ لمسة على الشاشة بتظهر الشريط الكبير ولمسة تانية ترجّعها صغيرة
        miniBar = DualProgress(this, th).apply { thin = true; isClickable = false; isFocusable = false; alpha = 0.33f }
        videoBox.addView(miniBar, FrameLayout.LayoutParams(-1, 3, Gravity.BOTTOM))
        videoBox.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if (b - t != ob - ot) { val hh = ((b - t) * 0.025f).toInt().coerceAtLeast(3); val lp = miniBar.layoutParams; if (lp.height != hh) { lp.height = hh; miniBar.layoutParams = lp } }
        }
        // زرار ⛶ (.fullscreen-btn): أسفل يسار الفيديو 10dp في الرأسي، 18dp في الشاشة الكاملة
        fsBtnV = ui.fsCircle("⛶") { toggleFs(); showChrome() }
        fsBtnLp = FrameLayout.LayoutParams(ui.dp(34), ui.dp(34), Gravity.BOTTOM or Gravity.LEFT)
        videoBox.addView(fsBtnV, fsBtnLp)
        // (v95) مكان 👁 والروتيشن: في النص بين الشريط العلوي والشريط السفلي (مش فوق شريحة الترجمة أبدًا)
        placeFloatFn = fn@{
            val bh = videoBox.height; if (bh <= 0) return@fn
            val chromeOn = fullMode && chromeShown && !pipNow()
            val eyeH = ui.dp(44); val rotH = ui.dp(48); val gap = ui.dp(10)
            val zTop = ui.dp(84)   // (v97) تحت عداد الجمل خالص
            val zBot = bh - (if (chromeOn) fsBar.height + ui.dp(16) else ui.dp(60))
            val room = zBot - zTop
            val both = rotH + gap + eyeH
            val showRot = false   // (v97) مفيش روتيشن عايم
            val stackH = if (showRot) both else eyeH
            val top = (if (chromeOn) zTop + (room - stackH) / 2 else zTop).coerceAtMost(zBot - stackH).coerceAtLeast(ui.dp(2))
            val rotLp = rotBtn.layoutParams as FrameLayout.LayoutParams
            val eyeLp = floatBar.layoutParams as FrameLayout.LayoutParams
            val eyeTop = if (showRot) top + rotH + gap else top
            if (rotLp.topMargin != top) { rotLp.topMargin = top; rotBtn.layoutParams = rotLp }
            if (eyeLp.topMargin != eyeTop) { eyeLp.topMargin = eyeTop; floatBar.layoutParams = eyeLp }
            val rv = if (showRot) View.VISIBLE else View.GONE
            if (rotBtn.visibility != rv) rotBtn.visibility = rv
        }
        videoBox.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) placeFloatFn() }
        fsBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) placeFloatFn() }
        applyChromeFn = {
            val on = fullMode && chromeShown && !pipNow()
            val wasOn = chromeFrame.visibility == View.VISIBLE
            chromeFrame.visibility = if (on) View.VISIBLE else View.GONE; if (!on) { dismissPop(); tbCollapseFn() }
            if (on && !wasOn) { chromeFrame.alpha = 0f; chromeFrame.translationY = ui.dp(14).toFloat(); chromeFrame.animate().alpha(1f).translationY(0f).setDuration(220).start() }
            else if (!on) { chromeFrame.animate().cancel(); chromeFrame.alpha = 1f; chromeFrame.translationY = 0f }
            centerPlay.visibility = if (on || pipNow() || uiLocked) View.GONE else if (buffering) View.VISIBLE else if (player.isPlaying) View.GONE else View.VISIBLE   // الشريط ظاهر = فيه زرار تشغيل تحت، فمنشيلش الأوسط فوق الترجمة
            val cv = (!fullMode || chromeShown) && !pipNow()
            floatBar.visibility = if (!pipNow()) View.VISIBLE else View.GONE
            batchBtn.visibility = if (cv) View.VISIBLE else View.GONE
            miniBar.visibility = if (!on && !pipNow()) View.VISIBLE else View.GONE
            updateTrChip()
            fsBtnV.visibility = if (!fullMode) View.VISIBLE else View.GONE   // في الشاشة الكاملة ⛶ جوه الشريط السفلي
            subLp.bottomMargin = if (on) maxOf(ui.dp(128), fsBar.height + ui.dp(12)) else ui.dp(12); sub.requestLayout()
            placeFloatFn()
        }

        // ---- لمس الفيديو: لمسة = إظهار/إخفاء الشريط (أو تشغيل/إيقاف في الوضع الرأسي)، لمستين = ±10ث، سحب رأسي = صوت (يمين) / سطوع (شمال) ----
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var volF = (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0).coerceIn(0, 100).let { b -> if (b > 0) 1f + b / 100f else am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }   // (v167) 0..1 صوت النظام، 1..2 تضخيم
        var briF = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
        var scrollLogged = false
        var hScrub = false; var scrubBase = 0L; var scrubTarget = 0L; var fast2x = false
        // ---- تحريك الفيديو لحظيًا مع الصباع (سحب أفقي على الفيديو أو سحب شريط التقدم) ----
        var lastScrub = 0L
        fun fastSeek(on: Boolean) { try { player.setSeekParameters(if (on) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT) } catch (e: Exception) { LogStore.err("Main:1623", e) } }
        fun scrubTo(posMs: Long) { val now = System.currentTimeMillis(); if (now - lastScrub >= 90) { lastScrub = now; player.seekTo(posMs) } }
        fun deltaTxt(ms: Long) = (if (ms >= 0) "+" else "−") + PlayerLogic.clock(Math.abs(ms))
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scrollLogged = false; hScrub = false; scrubBase = player.currentPosition; dismissPop(); dismissStrip(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (hScrub || fast2x) return
                fast2x = true; try { player.setPlaybackSpeed(2f) } catch (e: Exception) { LogStore.err("Main:1630", e) }
                log("👆 ضغطة مطولة: 2×")
                giShow("2× ⏩", Gravity.CENTER)
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // اللمس على الشاشة ما بيشغّلش ولا بيوقّف الفيديو — التشغيل/الإيقاف من زرار التشغيل بس
                if (fullMode) {
                    if (chromeShown) { log("👆 ضغطة: إخفاء الشريط"); h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
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
                    volF = (volF + d).coerceIn(0f, 2f)
                    val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(minOf(volF, 1f) * mx), 0)
                    val bNow = Math.round((volF - 1f).coerceAtLeast(0f) * 100)
                    if (bNow != (Cfg.str("vol_boost", "0").toIntOrNull() ?: 0)) setBoostPct(bNow)
                    if (bNow > 0) player.volume = 1f
                    ctl.vol.progress = Math.round(volF * 100)
                    indShow(volInd, volF)
                } else {
                    briF = (briF + d).coerceIn(0.02f, 1f)
                    val wl = window.attributes; wl.screenBrightness = briF; window.attributes = wl
                    indShow(briInd, briF)
                }
                return true
            }
        })
        // ===== قرص (pinch out/in) على الشاشة = تكبير/تصغير الترجمة (60%–200%) =====
        var pinching = false; var pinchLock = false; var pinchBase = 100; var pinchAcc = 1f
        val sgd = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                if (pipNow() || uiLocked) return false
                pinching = true; pinchLock = true; pinchBase = Cfg.int("sub_scale", 100); pinchAcc = 1f
                val t = android.os.SystemClock.uptimeMillis()
                val c = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0); gd.onTouchEvent(c); c.recycle()   // ألغي أي إيماءة شغالة (ضغطة مطولة 2× / سحب)
                h.removeCallbacks(hideChrome); log("🤏 قرص: حجم الترجمة")
                return true
            }
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                pinchAcc *= d.scaleFactor
                val n = Math.round(pinchBase * pinchAcc).coerceIn(60, 200)
                if (n != Cfg.int("sub_scale", 100)) { Cfg.put("sub_scale", n.toString()); restyle() }
                giShow("📏 $n%", Gravity.CENTER)
                return true
            }
            override fun onScaleEnd(d: android.view.ScaleGestureDetector) { pinching = false }
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
                if (fast2x) { fast2x = false; try { player.setPlaybackSpeed(speed) } catch (e: Exception) { LogStore.err("Main:1711", e) }; gi.visibility = View.GONE }
                if (hScrub) { hScrub = false; fastSeek(false); player.seekTo(scrubTarget); if (ev.actionMasked == MotionEvent.ACTION_UP && !syncing) showChrome() }
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { pinchLock = false; pinching = false }
            sgd.onTouchEvent(ev)
            if (pinchLock || ev.pointerCount > 1) {   // القرص شغال (أو لسه صباع واحد باقي بعده): ما نبعتش اللمس لباقي الإيماءات
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) { pinching = false; pinchLock = false }
                return@setOnTouchListener true
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
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                if (!u) return
                if (p <= 100) { player.volume = p / 100f; if ((Cfg.str("vol_boost", "0").toIntOrNull() ?: 0) != 0) setBoostPct(0) }
                else { player.volume = 1f; setBoostPct(p - 100) }
                giShow(if (p <= 100) "🔊 $p%" else "🔊 $p%  تضخيم ${boostDbText(p - 100)}", Gravity.CENTER)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        ctl.vol.max = 200; ctl.vol.progress = volTotal()
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
                col.addView(ui.text(if (q.translated.isEmpty()) q.original else q.withSpeaker(q.translated), 14f, th.text))
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
        val copyChip = IconTextView(this).apply {
            text = "📋 نسخ كل الجمل بالتوقيتات"; textSize = 12f; setTextColor(th.text); gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
            setOnClickListener { copySentences() }
        }
        val sentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = LinearLayout.LayoutParams(-1, -1) }
        sentCol.addView(copyChip, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(6) })
        sentCol.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        sentDlg = ui.sheet(this, "📝 الجمل", listOf<View>(sentCol), true, frac = 0.5f)
        sentDlg.setOnShowListener { adapter.notifyDataSetChanged() }
        val logCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = LinearLayout.LayoutParams(-1, -1) }
        fun logChip(t: String, f: () -> Unit) = IconTextView(this).apply {
            text = t; textSize = 12f; setTextColor(th.text); gravity = Gravity.CENTER; minHeight = ui.dp(44); setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); background = ui.box(th.surface, th.border, 8)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6); topMargin = ui.dp(4) }; setOnClickListener { f() }
        }
        fun logShow(m: Int) {
            logMode = m; lastLogLen = -1
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
            Notice.show(this, ("📋 اللوج اتنسخ").toString(), 2300L)
        }
        val logRow = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(4)) }
        logRow.addView(logChip("📋 نسخ") { logCopy() }); logRow.addView(logChip("الحالي") { logShow(0) })
        logRow.addView(logChip("🕘 الجلسة اللي فاتت") { logShow(1) }); logRow.addView(logChip("💥 آخر كراش") { logShow(2) })
        logCol.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(logRow) }, LinearLayout.LayoutParams(-1, -2))
        (batchTv.parent as? android.view.ViewGroup)?.removeView(batchTv)
        batchTv.setOnClickListener(null); batchTv.isClickable = false; batchTv.setTextIsSelectable(true)
        logCol.addView(MaxHeightScroll(this, (resources.displayMetrics.heightPixels * 0.14f).toInt()).apply { addView(batchTv, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(6)) })
        logSv.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); logCol.addView(logSv)
        logDlg = ui.sheet(this, "📜 اللوجز", listOf<View>(logCol), true, frac = 0.45f)
        logDlg.setOnShowListener { logShow(0) }
        mem = ui.memRow()

        // (v103) القايمة الجانبية اتشالت: ☰ بقت قايمة منسدلة (gTool)
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
        if (Build.VERSION.SDK_INT >= 33) { try { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7) } catch (e: Exception) { LogStore.err("Main:1892", e) } }
        PlayerRemote.act = java.lang.ref.WeakReference(this)
        KeepAliveService.start(this)

        h.post(object : Runnable {
            override fun run() {
                cur = player.currentPosition
                val d = player.duration
                if (d > 0) durMs = d
                run {
                    val nowP = System.currentTimeMillis()
                    if (nowP - lastPosSave >= 3000 && engineReady && !incognito && durMs > 0) {
                        lastPosSave = nowP
                        try {
                            if (cur > 1000 && cur < durMs - 4000) Cfg.p.edit().putString("lastpos", vid + "\t" + cur).apply()
                            else if (cur >= durMs - 4000 && (Cfg.p.getString("lastpos", "") ?: "").startsWith(vid + "\t")) Cfg.p.edit().remove("lastpos").apply()
                        } catch (_: Throwable) {}
                    }
                    if (nowP - lastRecentSave >= 20000 && player.isPlaying) { lastRecentSave = nowP; Thread { try { saveRecent() } catch (_: Throwable) {} }.apply { isDaemon = true }.start() }
                }
                val frac = if (durMs > 0) (cur.toFloat() / durMs).coerceIn(0f, 1f) else 0f
                val cov = if (durMs > 0) (engine.coveredSec() * 1000.0 / durMs).toFloat().coerceIn(0f, 1f) else 0f
                val pr = if (fullMode) fsProgV else ctl.prog
                if (!pr.dragging) pr.played = frac
                pr.translated = cov
                if (durMs > 0) {
                    val sr = engine.subs; val pn = engine.pendingCount()
                    if (sr !== segSubsRef || pn != segPendN) { segSubsRef = sr; segPendN = pn; segCov = engine.coverageSegs(); segPend = engine.pendingSegs() }
                }
                val onlineSrc = url != null || uri?.scheme?.startsWith("http") == true
                if (onlineSrc && durMs > 0) { try { addBuf(cur / 1000.0, player.bufferedPosition / 1000.0) } catch (_: Throwable) {} }
                val refSegs = engine.refiningSegs
                pr.durSec = durMs / 1000.0; pr.cov = segCov; pr.pend = segPend; pr.refine = refSegs; pr.buf = if (onlineSrc) ArrayList(bufSegs) else emptyList()
                pr.invalidate()
                if (miniBar.visibility == View.VISIBLE) { miniBar.played = frac; miniBar.durSec = durMs / 1000.0; miniBar.cov = segCov; miniBar.pend = segPend; miniBar.refine = refSegs; miniBar.buf = pr.buf; miniBar.invalidate() }
                val tEl = PlayerLogic.clock(cur); val tDu = PlayerLogic.clock(durMs)
                if (fullMode) { fsEl.text = tEl; fsDu.text = tDu } else { ctl.tEl.text = tEl; ctl.tDur.text = tDu }
                val now = System.currentTimeMillis()
                if (now - lastNotifAt >= 1000) { lastNotifAt = now; pushNotif() }
                if (dirty && now - lastRefresh > 1000) { dirty = false; lastRefresh = now; refreshList(); curIdx = -2 }
                visNow = (cur - offsetMs) / 1000.0
                visOv.showBoxes(visual.boxesAt(visNow, cur / 1000.0))
                try { fx?.tick(cur / 1000.0) } catch (_: Throwable) {}
                val act = PlayerLogic.activeIndices(spStarts, spEnds, cur, offsetMs, 400L, 1).map { spMap[it] }   // (v158) كل متحدث بيظهر لوحده (الأحدث بس)
                val sact = PlayerLogic.activeIndices(sdStarts, sdEnds, cur, offsetMs, 400L, 2).map { sdMap[it] }
                // مراقبة العرض: لو جملة عدّى وقتها وأنا شغّال عادي ومظهرتش في act → اتسجلت في اللوج بتوقيتها ونصها
                run {
                    val tNow = cur - offsetMs
                    for (ix in act) seenKeys.add(sentKey(list[ix]))
                    val pv = lastChkT
                    if (player.isPlaying && pv >= 0 && tNow - pv in 1L..1500L) {
                        for (q in list.indices) {
                            if (list[q].isSound || ends[q] <= pv || ends[q] > tNow || ends[q] - starts[q] < 600L) continue
                            val k = sentKey(list[q])
                            if (k !in seenKeys && warnedKeys.add(k)) LogStore.add("⚠ جملة #${q + 1} عدّى وقتها ومظهرتش في المشغّل (${fmtMs(starts[q] + offsetMs)} → ${fmtMs(ends[q] + offsetMs)}): ${list[q].translated.take(60)} | ظاهر وقتها: ${act.map { it + 1 }}")
                        }
                    }
                    lastChkT = tNow
                    if (seenKeys.size > 6000) seenKeys.clear()
                    if (warnedKeys.size > 2000) warnedKeys.clear()
                }
                val idx = act.lastOrNull() ?: -1
                val gs = PlayerLogic.orderSpeakers(act.map { list[it] })
                // جملة طويلة واحدة: بتتقسم لأجزاء بتظهر بالتتابع على مدة الجملة (التوقيت الأصلي ثابت)
                var parts: List<String> = emptyList(); var part = 0
                val ssn = sub.style
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
                // حارس العرض: جملة المفروض ظاهرة (curIdx >= 0) بس الـview مخفي أو ارتفاعه صفر → نرجّعه ونسجّل في اللوج
                if (ccOn && curIdx >= 0 && !sub.suppressed && !pipNow() && (sub.visibility != View.VISIBLE || sub.height <= 0)) {
                    if (++hideTicks >= 2) { hideTicks = 0; LogStore.add("⚠ الترجمة #${curIdx + 1} المفروض ظاهرة بس الـview مخفي/ارتفاعه صفر — اتصلّحت"); sub.visibility = View.VISIBLE; sub.requestLayout(); curIdx = -2 }
                } else hideTicks = 0
                // (v134) شرح المصطلحات: علامة ⓘ + اسم المصطلح بس بخط صغير (الشرح بيظهر لما تدوس عليها)؛ أوصاف الأصوات بتفضل زي ما هي
                val termIx = if (!ccOn) -1 else sact.firstOrNull { list[it].translated.trimStart().startsWith("«") } ?: -1
                val sTxt = if (!ccOn || sact.isEmpty()) "" else sact.joinToString("   ") { ix -> val t = list[ix].translated; if (t.trimStart().startsWith("«")) "ⓘ " + termName(t) else t }
                if (sTxt != soundKey) {
                    soundKey = sTxt; termFull = if (termIx >= 0) list[termIx].translated else ""
                    soundTv.textSize = if (termIx >= 0) 10.5f else 13f
                    soundTv.setTextColor(if (Extras.deaf && termIx < 0) 0xFFB8C0CC.toInt() else 0xFFE8EAED.toInt())
                    soundTv.text = sTxt; soundTv.visibility = if (sTxt.isEmpty()) View.GONE else View.VISIBLE
                }
                st.text = status
                if (now - lastBatch > 700) {
                    lastBatch = now
                    val bs = batchStyled(); val bl = bs.toString()
                    if (bl != lastBatchTxt) { lastBatchTxt = bl; batchTv.text = bs; miniLogTv?.text = if (bl.isBlank()) "مفيش باتشات لسه" else bs }
                    updateProblems()
                }
                if (now - lastBeat > 20000) { lastBeat = now; LogStore.add("💓 ${LogStore.heapLine()} · ${if (player.isPlaying) "بيشتغل" else "واقف"} @${fmtMs(cur)} · مترجم ${(engine.coveredSec() / 60).toInt()}د · ${engine.subs.size} جملة") }
                run { val pc = PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0); if (pc != trPct) { trPct = pc; updateTr() } }
                if (fullMode) fsBadge.set(PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0).toString() + "%", list.size.toString() + " جملة ▾")
                if (fullMode && ::visual.isInitialized) visBadge.set("👁 " + (if (visual.running) "شغّال" else "واقف"), visual.count.toString() + " جملة")
                if (now - lastMem > 4000) { lastMem = now; if (!fullMode) mem.update(this@PlayerActivity) }
                counters.text = "جمل ${list.size} · تغطية ${PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0)}% · فجوات ${engine.failedCount()} · كوتة ${Quota.used(conf.model)}/${Models.quotaOf(conf.model)}"
                if (logDlg.isShowing && logMode == 0) {
                    val nl = synchronized(logBuf) { logBuf.length }
                    if (nl != lastLogLen) {
                        lastLogLen = nl
                        val atBottom = !logSv.canScrollVertically(1)
                        logTv.text = synchronized(logBuf) { logBuf.toString() }
                        if (atBottom) logSv.post { logSv.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                h.postDelayed(this, 200)
            }
        })
        engineStarted = false; updateTr()
        // «🌐 ترجمة وفرجة» من المتصفح: الترجمة تبدأ لوحدها من غير ما تدوس ▶ ترجمة
        if (autoTr && !noSub && !askSaved) videoBoxRef.post { if (!engineStarted && !isFinishing && !isDestroyed) beginTranslate() }
    }

    /** (v118) تبديل جودة يوتيوب في نفس المكان من غير ما التشغيل يتقطع كتير. الجودة بتتحفظ للفيديوهات الجاية (داتا أقل) */
    private fun switchYt(i: Int) {
        val o = qOpts.getOrNull(i) ?: return
        if (i == qCur) return
        try {
            val pos = player.currentPosition; val play = player.playWhenReady
            val f = httpDsf ?: DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true)
            val au = o.audio
            // (v120) أي موقع: من غير صوت منفصل بنسيب ExoPlayer يختار النوع (MP4/HLS…) من اللينك؛ يوتيوب بصوت منفصل بيتدمج
            val src = if (au != null) { val pf = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(f)
                androidx.media3.exoplayer.source.MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(o.url))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(au)))) }
            else DefaultMediaSourceFactory(f).createMediaSource(MediaItem.fromUri(Uri.parse(o.url)))
            player.setMediaSource(src, pos); player.prepare(); player.playWhenReady = play
            qCur = i; sizeTxt = ""; setQualLabel(o.h); refreshSize()
            if (intent.getStringExtra("ytid") != null) Cfg.put("yt_maxh", o.h.toString())
            Notice.show(this, "🎞 الجودة: " + o.label, 2000L)
        } catch (e: Exception) { LogStore.err("Player:switchYt", e); Notice.show(this, "ما قدرتش أغيّر الجودة", 2300L) }
    }
    private fun hlsAuto() {
        hlsH = 0
        try { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO).build() } catch (e: Exception) { LogStore.err("Player:hlsAuto", e) }
        setQualLabel(0); Notice.show(this, "🎞 الجودة: تلقائي", 1800L)
    }
    private fun hlsPick(g: androidx.media3.common.Tracks.Group, i: Int, h: Int) {
        hlsH = h
        try { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, i)).build() } catch (e: Exception) { LogStore.err("Player:hlsPick", e) }
        setQualLabel(h); Notice.show(this, "🎞 الجودة: " + h + "p", 1800L)
    }

    private fun buildPlayer() {
        val sv = svRef; val videoBox = videoBoxRef
        val factory = if (uri != null) DefaultMediaSourceFactory(this)
        else { val f = DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true); httpDsf = f; DefaultMediaSourceFactory(f) }
        // MKV وغيره: لو الديكودر الأول فشل (HEVC / 10-bit) جرّب اللي بعده بدل شاشة سودا
        val rf = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, rf).setMediaSourceFactory(factory).build()
        // (v149) المشغّل بيمسك الـ audio focus: أي تطبيق تاني شغّال صوت بيتوقف، ولو حد تاني خد الصوت المشغّل بيقف بدل ما الصوتين يتخلطوا
        try { player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true); player.setHandleAudioBecomingNoisy(true) } catch (e: Throwable) { LogStore.err("Player:focus", e) }
        WebMute.pauseAll()
        player.setVideoSurfaceView(sv)
        applyBoost()
        player.setPlaybackSpeed(speed)
        var firstFrame = false
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { try { loud?.release() } catch (_: Throwable) {}; loud = null; applyBoost() }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    vidW = v.width; vidH = v.height; videoBox.post { applyFit(sv, videoBox) }
                    if (hlsH == 0 && qOpts.getOrNull(qCur) == null) setQualLabel(0)
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
                val t = if (p) "⏸" else "▶"; if (!buffering) { ctl.play.text = t; fsPlayB.text = t; if (::centerPlay.isInitialized) centerPlay.text = t }; centerPlay.visibility = if (pipNow() || (fullMode && chromeShown)) View.GONE else if (buffering) View.VISIBLE else if (p) View.GONE else View.VISIBLE
                // تشخيص الشاشة السودا: صوت شغّال ومفيش ولا فريم فيديو اتعرض بعد ٥ ثواني
                if (p && !firstFrame) h.postDelayed({ if (!firstFrame && !isFinishing) log("⚠️ مفيش فريم فيديو اتعرض بعد ٥ ثواني — غالبًا كودك/بروفايل الفيديو مش مدعوم على الجهاز (مثلًا HEVC 10-bit)") }, 5000)
            }
            override fun onPlaybackStateChanged(st: Int) {
                setBuffering(st == Player.STATE_BUFFERING)
                if (st == Player.STATE_READY) h.postDelayed({ checkIdentityDuration() }, 1500)
                // (v97) الفيديو خلص: لو اللوب شغّال يعيد من الأول، وإلا يروح للحلقة اللي بعدها (لو في)
                if (st == Player.STATE_ENDED && !isFinishing) {
                    if (Cfg.str("loop", "0") == "1") { player.seekTo(0); player.play() }
                    else if (hasNextEpisode()) h.post { stepEpisode(1) }
                }
            }
            override fun onRenderedFirstFrame() { firstFrame = true; webFirstFrame = true }
            override fun onTracksChanged(t: Tracks) {
                paintQual()
                if (sizeTxt.isEmpty()) refreshSize()
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
                offerLinkRefresh()
            }
        })
        val au = aurl
        if (uri == null && au != null) {
            val pf = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpDsf ?: DefaultHttpDataSource.Factory().setDefaultRequestProperties(hdr).setAllowCrossProtocolRedirects(true))
            player.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(pf.createMediaSource(MediaItem.fromUri(Uri.parse(url!!))), pf.createMediaSource(MediaItem.fromUri(Uri.parse(au)))))
        } else player.setMediaItem(MediaItem.fromUri(uri ?: Uri.parse(url!!)))
        player.prepare(); player.playWhenReady = true
    }

    // ===== (v139) عزل كل فيديو عن اللي قبله =====
    /** مصدر الصوت متربط بالفيديو اللي المحرك اتعمل له (مش بيقرا حقول المشغّل الحيّة): المحرك القديم ما يقدرش يفتح فيديو جديد بالغلط */
    private class SrcSpec(val uri: Uri?, @Volatile var url: String?, val hdr: HashMap<String, String>)
    private var spec: SrcSpec? = null
    /** وسيط بين المحرك والمشغّل: لما المحرك يتقفل (خروج / تغيير فيديو) كل ندائاته للمشغّل بتتجاهل */
    private class GuardedHost(val inner: Host) : Host {
        @Volatile var dead = false
        @Volatile var lastPos = 0.0
        @Volatile var lastDur = 0.0
        override fun log(s: String) { if (!dead) inner.log(s) }
        override fun status(s: String) { if (!dead) inner.status(s) }
        override fun changed() { if (!dead) inner.changed() }
        override fun notice(s: String) { if (!dead) inner.notice(s) }
        override fun position(): Double = if (dead) lastPos else inner.position().also { lastPos = it }
        override fun playerDuration(): Double = if (dead) lastDur else inner.playerDuration().also { lastDur = it }
        fun kill() { if (!dead) { try { lastPos = inner.position(); lastDur = inner.playerDuration() } catch (_: Throwable) {}; dead = true } }
    }
    private var guard: GuardedHost? = null
    /** أي مكان بيقفل المحرك الحالي لازم يعدّي من هنا: يقطع ندائاته للمشغّل الأول، وبعدين يقفله ويختمه */
    private fun retireEngine() {
        try { guard?.kill() } catch (_: Throwable) {}
        try { if (::engine.isInitialized) engine.stop() } catch (e: Throwable) { LogStore.err("Player:retire", e) }
    }

    private var freshOnce = false
    /** يشغّل المحرك. لو الدخول كان «ابدأ من الأول وجديد»: المسح والحفظ على خيط الخلفية قبل ما الحلقة تبدأ (مش على الـ UI) */
    private fun startEngine() {
        val fresh = freshOnce; freshOnce = false
        if (fresh) { try { player.seekTo(0) } catch (e: Exception) { LogStore.err("Main:2041", e) }; cur = 0L }
        val eng = engine   // (v139) المحرك اللي اتطلب له التشغيل بالذات — مش اللي يبقى في الحقل وقت ما الخيط يشتغل
        Thread {
            if (fresh) try { eng.redoAll() } catch (e: Throwable) { log("⚠ " + (e.message ?: e.toString()).take(120)) }
            eng.run()
        }.apply { isDaemon = true }.start()
    }

    /** (v117) الدخول الفوري: تحميل الترجمة المحفوظة بيتم في خيط خلفي — الشاشة بتفتح على طول واللودنج بيلف لحد ما تتحمّل البيانات */
    @Volatile private var engineReady = false
    private var pendingBegin = false
    private fun initEngine() {
        vid = videoId()
        if (uri == null && url != null && !incognito) webRegister()
        offsetMs = Cfg.str("sub_offset_ms:$vid", "0").toLongOrNull() ?: 0L
        runCatching { offFsB?.text = String.format("%+.1fs", offsetMs / 1000.0) }
        lastSrtN = -1
        val wasBg = BgJobs.isActive(vid)
        val store = Store(File(filesDir, "progress"), Store.keyFor(vid))
        val pb = PromptBuilder { p -> assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        engineReady = false; pendingBegin = false
        // (v139) أي محرك قديم لسه شغال يتقفل الأول ونقطع ندائاته، وبعدين الجديد بيتربط بمصدر صوت فيديوه هو بس
        retireEngine()
        val sp = SrcSpec(uri, aurl ?: url, HashMap(hdr)); spec = sp
        val gh = GuardedHost(this); guard = gh
        val eng = Engine(conf, { makeSource(sp) }, store, gh, pb)
        eng.persistBlocked = true
        engine = eng
        engine.convDialect = Cfg.str("conv_dialect", "")
        engine.toneStyle = Cfg.str("tone_style", ""); engine.toneStrength = Cfg.str("tone_strength", "متوسطة")
        engine.titleHint = run {
            val t = (intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: Recents.titleOf(vid)).substringBeforeLast('.', Recents.titleOf(vid))
            val folder = try { uri?.toString()?.let { SrtWriter.pathOf(applicationContext, it)?.parentFile?.name } } catch (_: Exception) { null } ?: ""
            // (v134) تنضيف الاسم قبل البحث على النت: شيل [الجروب] و(السنة) والجودة وصيغ الريبّ (1080p / x265 / WEB-DL …) عشان جوجل يلاقي العمل أسهل
            fun clean(x: String): String {
                val c = x.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
                    .replace(Regex("(?i)\\b(2160p|1080p|720p|480p|360p|x264|x265|h\\.?264|h\\.?265|hevc|web-?dl|web-?rip|blu-?ray|brrip|hdrip|hdtv|aac|ac3|mkv|mp4)\\b"), " ")
                    .replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
                return if (c.length >= 3) c else x
            }
            listOf(clean(t), clean(folder)).filter { it.isNotBlank() }.distinct().joinToString(" | ")
        }
        Live.engine = engine
        visual = VisualMode(conf, { cur / 1000.0 }, { makeRetriever() }, { m ->   // (v182) cur = نسخة volatile من مكان التشغيل — ExoPlayer بيرمي استثناء لو اتنادى من خيط تاني
            visLog(m)   // (v182) كل رسائل الوضع البصري بتتسجّل في اللوج
            val important = (m.startsWith("ضيف مفتاح") || m.contains("مش مدعوم") || m.startsWith("المفاتيح")) && !(visAutoRun && !m.startsWith("المفاتيح"))
            val now = System.currentTimeMillis()
            if (important || ((m.startsWith("👁") || m.startsWith("⏸") || m.startsWith("😴") || m.startsWith("⚠")) && now - visSayAt > 2500)) {
                if (!important) visSayAt = now
                runOnUiThread { Notice.show(this, m, 2300L) }
            }
        }, { }, { grabSurface() }, clipSrc = { makeExtractor() }, cacheDir = cacheDir)
        // (v185) النتايج بتتحفظ مع الفيديو وبترجع لما تفتحه تاني (المتخفي: من غير حفظ)
        if (!incognito) try { visual.bind(File(File(filesDir, "visual"), Store.keyFor(vid) + ".json")) } catch (_: Throwable) {}
        fx?.start(vid)
        // (v181) الوضع البصري تلقائي: بيشتغل لوحده مع فتح الفيديو لو فيه مفتاح للوضع البصري (ضغطة 👁 بتقفله/تشغّله، ومن الإعدادات تقدر تقفل التلقائي)
        if (VisualMode.auto() && conf.visKeys.any { it.length > 10 } && !incognito) {
            visAutoRun = true
            h.postDelayed({ if (!isFinishing && !isDestroyed && !visual.running) { visLog("👁 تشغيل تلقائي مع فتح الفيديو"); visual.mode = "scene"; visual.start() } }, 1200)
        }
        status = "⏳ بحمّل بيانات الفيديو…"
        val vidNow = vid
        Thread {
            // (كانت بتتعمل على الـ UI وبتجمّد الشاشة): إيقاف ترجمة الخلفية + قراءة وتحليل ملف الترجمة المحفوظة
            if (wasBg) try { BgJobs.stopAndWait(vidNow, 2500) } catch (e: Throwable) { LogStore.err("Player:stopBg", e) }
            val savedPos0 = try { eng.load() } catch (e: Throwable) { LogStore.err("Player:load", e); 0.0 }
            // (v125) آخر مكان وقوف فعلي (بيتحفظ كل 3 ث، حتى وإنت في PiP) أحدث من اللي في ملف الترجمة
            val lastPosSec = try { val s = Cfg.p.getString("lastpos", "") ?: ""; val i = s.indexOf('\t'); if (i > 0 && s.substring(0, i) == vidNow) (s.substring(i + 1).toLongOrNull() ?: 0L) / 1000.0 else 0.0 } catch (_: Throwable) { 0.0 }
            val savedPos = if (lastPosSec > 5.0) lastPosSec else savedPos0
            val rp = if (savedPos <= 5.0 && uri == null) {
                try { Recents.parse(File(filesDir, "recent.json").readText()).firstOrNull { it.id == vidNow }?.posSec ?: 0.0 } catch (_: Exception) { 0.0 }
            } else 0.0
            eng.persistBlocked = false
            runOnUiThread {
                if (isFinishing || isDestroyed || engine !== eng) return@runOnUiThread
                engineReady = true
                if (savedPos > 5.0 && !freshOnce) { player.seekTo((savedPos * 1000).toLong()); cur = (savedPos * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}") }
                else if (uri == null && !freshOnce && rp > 5.0) {
                    // رابط من الإنترنت: لو مفيش مكان وقوف في الترجمة، كمّل من آخر مكان اتفرجت عليه (من السجل)
                    player.seekTo((rp * 1000).toLong()); cur = (rp * 1000).toLong(); log("⏩ كملت من ${fmtMs(cur)}")
                }
                refreshList()
                LogStore.add("🎬 فتح فيديو $vidNow · ${LogStore.heapLine()}")
                maybePrompt(wasBg)
                if (pendingBegin) { pendingBegin = false; beginTranslate() }
            }
        }.apply { isDaemon = true }.start()
    }

    // ===== فشل لينك التحميل: تحديثه في الخلفية من صفحة الفيديو المحفوظة =====
    private var refreshAsked = false
    private var refreshTries = 0
    private fun offerLinkRefresh() {
        if (uri != null || url == null || refreshAsked || isFinishing || isDestroyed) return
        refreshAsked = true
        val appCtx = applicationContext; val id = vid
        Thread {
            val w = WebVideos.find(appCtx, id)
            val page = (w?.page?.takeIf { it.isNotEmpty() } ?: hdr["Referer"] ?: "")
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!page.startsWith("http")) { Notice.show(this, "مفيش لينك صفحة محفوظ للفيديو — احفظه من ⋮ ← تحديث لينك الفيديو", 4000L); return@runOnUiThread }
                if (++refreshTries > 2) { refreshAsked = false; return@runOnUiThread }   // (v142) محاولتين بس في الجلسة عشان مايدخلش في لف
                startLinkRefresh(page)   // (v142) بيجيب اللينك لوحده من غير ما يسألك
            }
        }.apply { isDaemon = true }.start()
    }
    private fun startLinkRefresh(page: String) {
        val old = url ?: return
        Notice.show(this, "⏳ بجيب لينك الفيديو من الصفحة…", 4000L)
        LinkRefresh.run(this, page, old, hdr["User-Agent"] ?: "") { r ->
            if (isFinishing || isDestroyed) return@run
            refreshAsked = false
            if (r != null && r.same) {
                applyNewLink(r)
                Notice.show(this, "✓ اتحدّث لينك الفيديو", 2500L)
                return@run
            }
            // (v142) مالقيناش نفس الفيديو بالظبط: بنعرضلك اللينكات اللي اتلقت في قايمة تختار منها هنا، من غير ما تروح تدوّر بإيدك
            val opts = r?.all?.distinct().orEmpty()
            if (r != null && opts.isNotEmpty()) {
                val labels = opts.mapIndexed { i, u -> val hh = Sniff.heightOf(u); (if (hh > 0) "${hh}p" else "لينك ${i + 1}") + " · " + Sniff.nameOf(u).take(40) }.toTypedArray()
                GAlert(this).setTitle("اختار لينك الفيديو (" + opts.size + ")")
                    .setItems(labels) { _, w -> applyNewLink(LinkRefresh.Res(opts[w], true, r.cookie, r.ua)); Notice.show(this, "✓ اتحدّث لينك الفيديو", 2500L) }
                    .setNegativeButton("إلغاء", null).show()
            } else {
                GAlert(this).setTitle("⚠ مالقيتش لينك الفيديو").setMessage("الصفحة ماطلّعتش أي لينك فيديو (غالبًا محمية أو محتاجة ضغطة على زرار التحميل).\nتفتح الصفحة تصطاد بنفسك؟")
                    .setPositiveButton("نعم") { _, _ -> startActivity(Intent(this, BrowserActivity::class.java).putExtra("start", page)) }
                    .setNegativeButton("لا", null).show()
            }
        }
    }
    private fun applyNewLink(r: LinkRefresh.Res) {
        url = r.url
        if (r.cookie.isNotEmpty()) hdr["Cookie"] = r.cookie
        spec?.let { sp -> if (aurl == null) sp.url = r.url; if (r.cookie.isNotEmpty()) sp.hdr["Cookie"] = r.cookie }
        httpDsf?.setDefaultRequestProperties(hdr)
        val appCtx = applicationContext; val id = vid; val nu = r.url; val ck = r.cookie
        val refB = intent.getStringExtra("ref") ?: ""; val titleB = intent.getStringExtra("title") ?: ""
        Thread { WebVideos.update(appCtx, id) { it.copy(url = nu, cookie = if (ck.isNotEmpty()) ck else it.cookie, ts = System.currentTimeMillis()) }; if (!incognito) WebIdent.bindLink(id, nu, refB, titleB) }.apply { isDaemon = true }.start()
        try { val pos = player.currentPosition; player.setMediaItem(MediaItem.fromUri(Uri.parse(r.url)), pos); player.prepare(); player.playWhenReady = true } catch (e: Exception) { LogStore.err("Main:2115", e) }
    }

    // ===== الفيديوهات المصطادة: تسجيل في السجل + لقطة مصغّرة (الاسم من صفحة الموقع) =====
    @Volatile private var webFirstFrame = false
    private var webSnapTries = 0
    private fun webRegister() {
        val u = url ?: return
        val appCtx = applicationContext; val id = vid
        val ref = hdr["Referer"] ?: ""; val ua = hdr["User-Agent"] ?: ""; val ck = hdr["Cookie"] ?: ""
        val siteTitle = intent.getStringExtra("title")?.takeIf { it.isNotBlank() }
        val t0 = siteTitle ?: Sniff.nameOf(u)
        val kind = if (u.contains("videoplayback")) "YT" else Sniff.kindOf(u)
        val page = if (WebIdent.specific(ref)) ref else ""   // (v142) صفحة الفيديو بتتحفظ لوحدها (من الصفحة اللي اتصاد منها) عشان تحديث اللينك يشتغل من غير ما تدخلها بإيدك
        Thread { WebVideos.register(appCtx, id, u, ref, ua, ck, t0, kind, siteTitle != null, page) }.apply { isDaemon = true }.start()
        h.postDelayed({ webSnap() }, 9000)
    }
    private fun webSnap() {
        if (isFinishing || isDestroyed || !::svRef.isInitialized) return
        val ready = webFirstFrame && svRef.width > 0 && svRef.height > 0
        if (!ready) { if (++webSnapTries < 4) h.postDelayed({ webSnap() }, 8000); return }
        val appCtx = applicationContext; val id = vid
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res ->
                if (res != android.view.PixelCopy.SUCCESS) return@request
                Thread { try { WebVideos.saveThumb(appCtx, id, bmp) } catch (e: Throwable) { LogStore.err("Main:2140", e) } }.apply { isDaemon = true }.start()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { LogStore.err("Main:2142", e) }
    }

    /** زرار 📋 في المشغّل = نفس صفحة اللوجز بتاعة القايمة (اللوج العايم اتلغى) */
    fun toggleLog() { if (logDlg.isShowing) logDlg.dismiss() else logDlg.show() }

    /**
     * (v151) 🧑 الشخصيات وجنسها: نافذة سفلية فوق الفيديو (الفيديو مابيتضغطش ولا بيتوقف).
     * لو مفيش شخصيات متحللة لسه (فيديو اتترجم قبل كده، أو التحليل التلقائي ماجاش) بتحلّلها من الترجمة الحالية أول ما تفتح.
     */
    private var charsDlg: android.app.Dialog? = null
    private fun showChars() {
        if (charsDlg?.isShowing == true) { charsDlg?.dismiss(); return }
        if (!::engine.isInitialized) { Notice.show(this, "المحرك لسه بيحمّل — جرّب كمان لحظة", 2300L); return }
        val eng = engine
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val d = ui.sheet(this, "🧑 الشخصيات", listOf<View>(col), false, onClose = { charsDlg = null })
        charsDlg = d
        var busy = false
        var analyzeRef: () -> Unit = {}
        fun render(list: List<Chr>, msg: String) {
            col.removeAllViews()
            if (msg.isNotEmpty()) col.addView(ui.text(msg, 12f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
            for (c in list) {
                val f = c.gender == "female"
                val row = LinearLayout(this).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9)); background = ui.box(th.surface, th.border, 10)
                }
                row.addView(ui.text(c.name, 15f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(ui.text(if (f) "👩 أنثى" else "👨 ذكر", 13f, if (f) 0xFFE0559A.toInt() else 0xFF4F8FE8.toInt(), true))
                col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(5) })
                if (c.role.isNotBlank()) col.addView(ui.text(c.role, 11f, th.muted).apply { setPadding(ui.dp(12), ui.dp(2), ui.dp(12), 0) })
            }
            col.addView(ui.button(if (busy) "⏳ بحلّل…" else "🔄 حلّل الشخصيات من الترجمة دلوقتي") { if (!busy) analyzeRef() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        }
        fun analyze() {
            busy = true
            render(eng.charactersNow(), "⏳ بحلّل الشخصيات من الترجمة… (ممكن ياخد شوية — تقدر تقفل النافذة)")
            eng.analyzeCharsNow(
                { m -> runOnUiThread { if (d.isShowing) Notice.show(this, m, 2600L) } },
                { list -> runOnUiThread { busy = false; if (d.isShowing) render(list, if (list.isEmpty()) "ملقيتش شخصيات واضحة في الجمل الحالية — كمّل الترجمة وجرّب تاني" else "") } }
            )
        }
        analyzeRef = { analyze() }
        val now = eng.charactersNow()
        d.show()
        if (now.isEmpty()) analyze() else render(now, "")
    }

    /** (v125) ⏮ ⏭: لو الفيديو من فولدر في المكتبة → الحلقة اللي قبلها/بعدها، وإلا (فيديو إنترنت/ملف لوحده) → الجملة اللي قبلها/بعدها (قبل كده كانت بتطلّع رسالة بس) */
    fun stepOrSeek(d: Int) {
        val curU = uri?.toString(); val all = VideoScan.cache
        val inLib = curU != null && all != null && all.any { it.uri == curU }
        if (inLib && (if (d > 0) hasNextEpisode() else true)) {
            val me = all!!.first { it.uri == curU }
            val sib = VideoLib.sortVideos(all.filter { it.folderKey == me.folderKey }, VideoLib.SORT_NAME)
            if (sib.getOrNull(sib.indexOfFirst { it.uri == curU } + d) != null) { stepEpisode(d); return }
        }
        val t = player.currentPosition - offsetMs
        if (starts.isNotEmpty()) {
            val i = if (d > 0) starts.indexOfFirst { it > t + 200 } else starts.indexOfLast { it < t - 1500 }
            if (i >= 0) { player.seekTo(starts[i] + offsetMs); return }
        }
        player.seekTo((player.currentPosition + d * 10000L).coerceIn(0L, if (durMs > 0) durMs else Long.MAX_VALUE))
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
        try { visual.stop() } catch (e: Exception) { LogStore.err("Main:2164", e) }
        retireEngine()
        try { engine.saveNow() } catch (e: Exception) { LogStore.err("Main:2166", e) }
        try { loud?.release(); loud = null } catch (_: Throwable) {}
        try { player.release() } catch (e: Exception) { LogStore.err("Main:2167", e) }
        uri = newUri; url = null; aurl = null; hdr.clear()
        cur = 0L; durMs = 0L; vidW = 0; vidH = 0; curIdx = -2; curKey = ""; dirty = true
        synchronized(logBuf) { logBuf.setLength(0) }
        status = ""; conf = Cfg.snapshot()
        try { sub.show(null) } catch (e: Exception) { LogStore.err("Main:2172", e) }
        visOv.showBoxes(emptyList())
        resumePending = false; applyCard()
        engineStarted = false; autoRotDone = false
        buildPlayer(); initEngine()
        notifTitleOv = Recents.titleOf(vid)
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
        if (::logDrawer.isInitialized) { (leftCol.layoutParams as FrameLayout.LayoutParams).topMargin = ui.dp(26); logDrawer.visibility = View.VISIBLE; leftCol.requestLayout() }
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = if (f) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else (if (Build.VERSION.SDK_INT >= 23 && th.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0)
        placeCard()
        if (f) showChrome() else { h.removeCallbacks(hideChrome); chromeShown = false; applyChromeFn() }
    }
    fun showChrome() { if (pipNow() || uiLocked) return; chromeShown = true; applyChromeFn(); h.removeCallbacks(hideChrome); h.postDelayed(hideChrome, 3500) }
    fun togglePlay() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
    /** (v97) في حلقة بعد الحالية في نفس الفولدر؟ (من غير رسالة) */
    private fun hasNextEpisode(): Boolean {
        val curU = uri?.toString() ?: return false
        val all = VideoScan.cache ?: return false
        val me = all.firstOrNull { it.uri == curU } ?: return false
        val sib = VideoLib.sortVideos(all.filter { it.folderKey == me.folderKey }, VideoLib.SORT_NAME)
        return sib.getOrNull(sib.indexOfFirst { it.uri == curU } + 1) != null
    }
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
            Notice.show(this, ("فعّل «العرض فوق التطبيقات» لمترجم الفيديو عشان الترجمة تظهر فوق، وارجع دوس ⧉ تاني").toString(), 3600L)
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return } catch (e: Exception) { LogStore.err("Main:2229", e) }
        }
        if (Build.VERSION.SDK_INT >= 26) try {
            val pb = PictureInPictureParams.Builder()
            if (vidW > 0 && vidH > 0) pb.setAspectRatio(Rational((vidW.toFloat() / vidH).coerceIn(0.45f, 2.3f).times(1000).toInt(), 1000))
            if (Build.VERSION.SDK_INT >= 31) try { pb.setSeamlessResizeEnabled(true) } catch (e: Throwable) { LogStore.err("Main:2234", e) }
            enterPictureInPictureMode(pb.build())
        } catch (_: Exception) { Notice.show(this, ("PiP مش مدعوم على الجهاز ده").toString(), 2300L) }
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
            extras.visibility = View.GONE; st.visibility = View.GONE; floatBar.visibility = View.GONE; rotBtn.visibility = View.GONE; batchBtn.visibility = View.GONE; trChipV?.visibility = View.GONE; closeBatchPanel(); closeMiniLog(); probDrawer.visibility = View.GONE; logDrawer.visibility = View.GONE; logHandle.visibility = View.GONE
            fsOnly.forEach { it.visibility = View.GONE }; assistMenuV.visibility = View.GONE; centerPlay.visibility = View.GONE
            visOv.showBoxes(emptyList())
            val lp = videoBoxRef.layoutParams as LinearLayout.LayoutParams; lp.height = -1; lp.setMargins(0, 0, 0, 0); videoBoxRef.layoutParams = lp; fsBtnV.visibility = View.GONE
            videoBoxRef.findViewWithTag<View>("chromeFrame")?.visibility = View.GONE
            sub.suppressed = true; applyCard()
            applyFit(svRef, videoBoxRef)
            // الترجمة في شريط صغير فوق الشاشة (تحت الستاتس بار) بدل جوه الفيديو
            if (canOverlay()) { pipOv = PipSubBar(this, { pipRect() }, listOf<Pair<String, () -> Unit>>("🌙 ترجم في الخلفية" to { translateInBackground(false, "شريط الـ PiP (زرار 🌙)") }, "🔊 صوت بس" to { audioOnly() }, "⏹ إيقاف نهائي" to { fullStop() })).also { it.show() } }
            curIdx = -2
        } catch (e: Exception) { LogStore.err("Main:2266", e) } }
        else {
            pipOv?.hide(); pipOv = null
            sub.suppressed = false; curIdx = -2; applyCard()
            applyFull(true)
            // لو الخروج من PiP كان بالـ ✕ (الأكتيفيتي مش ظاهرة) نقفل الفيديو ونخرج؛ لو كان توسيع، onResume بيلغي الفحص
            h.removeCallbacks(pipExitCheck); h.postDelayed(pipExitCheck, 500)
        }
    }

    private var lastFitTag = ""
    private fun applyFit(sv: SurfaceView, box: View) {
        if ((vidW == 0 || vidH == 0) && ::player.isInitialized) { val vs = player.videoSize; if (vs.width > 0 && vs.height > 0) { vidW = vs.width; vidH = vs.height } }
        if (vidW == 0 || box.width == 0) return
        val mode = if (pipNow()) 0 else curFit()
        val (w, hh) = PlayerLogic.fitSize(box.width, box.height, vidW, vidH, mode)
        val lp = FrameLayout.LayoutParams(w, hh, Gravity.CENTER)
        sv.layoutParams = lp; sv.requestLayout(); box.requestLayout()
        val tag = "$mode:${box.width}x${box.height}:${vidW}x$vidH"
        if (tag != lastFitTag) { lastFitTag = tag; LogStore.add("⬛ احتواء/تمديد: وضع=${PlayerLogic.fitNames[mode]} الحاوية=${box.width}x${box.height} الفيديو=${vidW}x$vidH ← السطح=${w}x$hh") }
    }

    private fun videoId(): String {
        val u = uri
        if (u != null) {
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1)
                }
            } catch (e: Exception) { LogStore.err("Main:2289", e) }
            return "f:$u"
        }
        intent.getStringExtra("ytid")?.takeIf { it.length == 11 }?.let { return "yt:$it" }   // (v117) يوتيوب: التقدم والترجمة على معرّف الفيديو مش على لينك الستريم المتغيّر
        val ref0 = intent.getStringExtra("ref") ?: ""; val title0 = intent.getStringExtra("title") ?: ""
        val legacy = webId(url ?: "", ref0, title0)
        if (incognito) return legacy
        // (v142) نفس الفيديو بجودة تانية أو اتصاد تاني بلينك جديد = نفس الهوية القديمة (الترجمة والتقدم بيفضلوا)
        val hit = WebIdent.resolve(legacy, url ?: "", ref0, title0)
        idVia = if (hit.id != legacy) hit.via else null
        return hit.id
    }
    private var idVia: String? = null
    private var durChecked = false
    /** حماية من دمج غلط: لو الهوية جت من تطابق صفحة/عنوان والمدة مختلفة كتير عن اللي اتسجّل، اسأل المستخدم */
    private fun checkIdentityDuration() {
        val via = idVia ?: return
        if (durChecked || durMs <= 0) return
        durChecked = true
        val id = vid; val d = durMs / 1000.0
        Thread {
            val old = try { Recents.parse(File(filesDir, "recent.json").readText()).firstOrNull { it.id == id }?.durSec ?: 0.0 } catch (_: Exception) { 0.0 }
            if (old > 30 && Math.abs(old - d) > 5.0) runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Notice.ask(this, "⚠ مدة الفيديو (" + PlayerLogic.clock((d * 1000).toLong()) + ") مختلفة عن الفيديو المحفوظ بنفس الاسم (" + PlayerLogic.clock((old * 1000).toLong()) + ") — ممكن يكون فيديو تاني؟", "افصله", "هو نفسه", 12000L,
                    { WebIdent.blockKey(via); startActivity(Intent(this, PlayerActivity::class.java).apply { putExtras(intent) }); finish() }, { })
            }
        }.apply { isDaemon = true }.start()
    }
    /** (v138) هوية فيديو النت: الاسم في اللينك لوحده ما بيكفيش — لينكات زي /videoplayback أو master.m3u8 أو video.mp4 بتتكرر لفيديوهات مختلفة،
     *  فكانت ترجمة فيديو بتتحمّل على فيديو تاني. اللينك العادي (اسم ملف مميّز) بيفضل بنفس الهوية القديمة عشان التقدم المحفوظ ما يضيعش. */
    private fun webId(u: String, ref: String, title: String): String {
        val noFrag = u.substringBefore('#')
        val base = noFrag.substringBefore('?')
        val q = noFrag.substringAfter('?', "")
        fun qp(k: String): String? = q.split('&').firstNotNullOfOrNull { kv -> if (kv.substringBefore('=').equals(k, true)) kv.substringAfter('=', "").takeIf { it.isNotBlank() } else null }
        val file = base.substringAfterLast('/').lowercase()
        val generic = u.contains("videoplayback") || file.isBlank() ||
            Regex("(master|index|playlist|chunklist|manifest|stream|video|play|media|file|main|source|hls|dash)(\\.(m3u8|mpd|mp4|webm|ts|php|m4v))?").matches(file)
        val stable = listOf("id", "docid", "vid", "video", "video_id", "videoid", "v", "fid", "file", "name", "mid").firstNotNullOfOrNull { k -> qp(k)?.let { "$k=$it" } }
        if (!generic) return "u:$base"
        if (stable != null) return "u:" + (if (u.contains("videoplayback")) "gv" else base) + "?" + stable
        val page = ref.substringBefore('#').substringBefore('?').trim()
        val extra = listOf(title.trim(), if (page.length > 12 && page.substringAfter("://").contains('/')) page else "").filter { it.isNotBlank() }.joinToString("|")
        return if (extra.isNotBlank()) "u:$base|$extra" else "u:$base?" + Integer.toHexString(q.hashCode())
    }

    private fun makeSource(sp: SrcSpec): AudioSource {
        val lg: (String) -> Unit = { if (spec === sp) log(it) }
        val src = AudioSources.make(applicationContext, sp.uri, sp.url, sp.hdr, conf.audioTrack, lg)   // يوتيوب بصوت منفصل: sp.url فيه رابط الصوت
        lg("🎙 مصدر الصوت: " + (if (sp.uri != null) "ملف محلي" else if (src is HlsSource) "رابط HLS" else "رابط مباشر"))
        return src
    }

    fun fmtMs(ms: Long) = String.format("%02d:%02d:%02d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60)

    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        super.onActivityResult(r, c, d)
        if (r == 8 && c == RESULT_OK) d?.data?.let { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { LogStore.err("Main:2308", e) }
            swapVideo(u)
        }
        if (r == 9 && c == RESULT_OK) d?.data?.let { u ->
            val parsed = try { PlayerLogic.parseSrt(contentResolver.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "") } catch (_: Exception) { emptyList() }
            if (parsed.isEmpty()) Notice.show(this, ("الملف مش SRT صالح").toString(), 2300L)
            else if (engine.subs.isNotEmpty()) Notice.show(this, ("فيه ترجمة موجودة بالفعل — الاستيراد بيشتغل على فيديو من غير ترجمة بس").toString(), 3600L)
            else {
                engine.importSubs(parsed.map { Sub(it.first, it.second, it.third, it.third, "male", "unknown", "none", emptyList(), emptyList(), false, false) })
                dirty = true; Thread { engine.saveNow() }.start()
                Notice.show(this, ("اتستورد ${parsed.size} جملة").toString(), 2300L)
            }
        }
        if (r == 32) {
            val t = pendingOrigSrt; pendingOrigSrt = null
            if (c == RESULT_OK && t != null) d?.data?.let { u ->
                try { contentResolver.openOutputStream(u)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }; Notice.show(this, ("تم حفظ SRT بالنص الأصلي").toString(), 2300L) }
                catch (_: Exception) { Notice.show(this, ("تعذّر حفظ الملف").toString(), 2300L) }
            }
        }
        if (r == 31) {
            val t = pendingSentTxt; pendingSentTxt = null
            if (c == RESULT_OK && t != null) d?.data?.let { u ->
                try { contentResolver.openOutputStream(u)?.use { it.write(t.toByteArray(Charsets.UTF_8)) }; Notice.show(this, ("تم حفظ الجمل").toString(), 2300L) }
                catch (_: Exception) { Notice.show(this, ("تعذّر حفظ الملف").toString(), 2300L) }
            }
        }
        if (r == 7 && c == RESULT_OK) d?.data?.let { u ->
            contentResolver.openOutputStream(u)?.use { it.write(PlayerLogic.toSrt(engine.subs, offsetMs).toByteArray()) }
            Notice.show(this, ("تم حفظ SRT").toString(), 2300L)
        }
    }

    private fun saveRecent() { if (!handedOff) saveRecentForce() }
    private fun saveRecentForce() {
        if (incognito) return
        try { if (durMs > 0 && cur > 1000 && cur < durMs - 4000 && vid.isNotEmpty()) Cfg.p.edit().putString("lastpos", vid + "\t" + cur).apply() } catch (_: Throwable) {}
        if (!::engine.isInitialized || !engineReady) return
        try {
            if (!incognito) {
            val f = File(filesDir, "recent.json")
            val old = Recents.parse(try { f.readText() } catch (_: Exception) { "" })
            val r = Recent(vid, (if (uri == null) intent.getStringExtra("title")?.takeIf { it.isNotBlank() } else null) ?: Recents.titleOf(vid), url ?: "", uri?.toString() ?: "", cur / 1000.0, durMs / 1000.0, engine.subs.size, engine.coveredSec(), System.currentTimeMillis())
            f.writeText(Recents.toJson(Recents.upsert(old, r, 300)))
            }
        } catch (e: Exception) { LogStore.err("Main:2341", e) }
    }
    /** أفقي: يرجع للرأسي من غير ما يقفل. رأسي: يحفظ التقدم ويوقف الفيديو ويقفل. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (sideOpen) { closeSide(); return }
        // (v110) علامة ✔ فوق: لو الترجمة لسه ما خلصتش، كمّلها في الخلفية أول ما أخرج
        if (!incognito && !handedOff && Cfg.bool("bg_on_exit", false) && durMs > 0 && PlayerLogic.percent(engine.coveredSec(), durMs / 1000.0) < 99 &&
            (Cfg.allMainKeys().isNotEmpty() || conf.keys.isNotEmpty() || conf.backup.isNotEmpty())) { translateInBackground(false, "خروج تلقائي (إعداد «كمّل في الخلفية لما أخرج»)"); return }
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:2347", e) }
        saveRecent(); Thread { engine.saveNow() }.start()
        super.onBackPressed()
    }
    // ===== أدوات الـ HTML اللي كانت ناقصة في النسخة النيتف =====
    // ===== لودينج ويندوز: بدل زرار ⏸/▶ اللي في النص أثناء التحميل (فيديو أونلاين / تنقّل / سيك) =====
    @Volatile private var buffering = false
    private val spinners = HashMap<View, WinSpinnerDrawable>()
    fun setBuffering(b: Boolean) {
        if (b == buffering && (b || spinners.isEmpty())) return
        buffering = b
        val targets = listOfNotNull<TextView>(if (::fsPlayB.isInitialized) fsPlayB else null, if (::ctl.isInitialized) ctl.play else null, if (::centerPlay.isInitialized) centerPlay else null)
        for (v in targets) {
            if (b) {
                val sp = spinners.getOrPut(v) { WinSpinnerDrawable() }
                v.text = ""; v.foreground = sp; sp.start()
            } else {
                spinners.remove(v)?.stop(); v.foreground = null
                v.text = if (player.isPlaying) "⏸" else "▶"
            }
        }
        applyChromeFn()
    }
    private fun say(m: String) = runOnUiThread { Notice.show(this, (m).toString(), 2300L) }
    private fun touchSubs() = runOnUiThread { curIdx = -2; dirty = true }

    fun runTool(label: String, instruction: String, all: Boolean) {
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        say("بدأ: $label…")
        engine.rewriteAll(label, instruction, if (all) 0.0 else player.currentPosition / 1000.0, { _ -> }) { n ->
            say(if (n > 0) "✅ $label: اتغيّرت $n جملة (تقدر ترجع من 🗂 ترجمات الفيديو)" else "$label: مفيش جمل اتغيّرت"); touchSubs()
        }
    }
    var giShowFn: (String) -> Unit = {}

    private var pendingOrigSrt: String? = null
    /** (v153) تحميل SRT بالنص الأصلي المستخرج (من الإعدادات) — بيفتح اختيار مكان الحفظ */
    fun exportOriginalSrt() {
        val txt = PlayerLogic.toSrtOriginal(engine.subs, offsetMs)
        if (txt.isBlank()) { say("مفيش نص أصلي لسه"); return }
        val base = try { Recents.titleOf(vid).substringBeforeLast('.', Recents.titleOf(vid)) } catch (_: Throwable) { "video" }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]+"), " ").trim().ifEmpty { "video" }
        pendingOrigSrt = txt
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "$safe.orig.srt") }, 32) }
        catch (_: Exception) { pendingOrigSrt = null; say("تعذّر فتح اختيار مكان الحفظ") }
    }

    /** (v153) 🌍 ترجمة كل الجمل بجوجل (من غير API) وعرضها على الفيديو مكان الترجمة الحالية */
    fun googleTranslateNow() {
        if (engine.subs.none { !it.isSound && it.original.isNotBlank() }) { say("مفيش نص أصلي لسه — لازم الفيديو يتحلل الأول"); return }
        say("🌍 بترجم بجوجل… (من غير API)")
        engine.googleTranslateAll({ m -> say(m) }) { n -> say(if (n > 0) "✅ جوجل: اتترجمت $n جملة (تقدر ترجع من 🗂 ترجمات الفيديو)" else "جوجل: مفيش جمل اتغيّرت"); touchSubs() }
    }

    private val aiSel = linkedSetOf<String>()
    private val DIAL_INSTR get() = "زوّد شدة اللهجة الشعبية في كل جملة درجة واحدة: ألفاظ وتعبيرات الشارع والعامية المحلية (${conf.lang}) بدل الفصحى والكلام الرسمي، من غير ما تغيّر المعنى أو الجنس."
    private val FAMILY_INSTR = "نضّف الجملة من الألفاظ الخارجة والإيحاءات الجنسية وخليها عائلية ومناسبة لكل الأعمار مع الحفاظ على المعنى العام."
    private val EXPLICIT_INSTR = "رجّع الترجمة لمطابقة صراحة النص الأصلي بالظبط (الألفاظ والإيحاءات زي ما هي في الأصل من غير تلطيف ولا حذف)."

    /** (v151) بينفّذ كل الخيارات المحددة في قايمة ✨ بالترتيب بدوسة واحدة، وكل خطوة بتستنى اللي قبلها */
    fun applyAiOptions() {
        if (aiSel.isEmpty()) { say("اختار خيار واحد على الأقل من القايمة"); return }
        if (Cfg.allMainKeys().isEmpty() && conf.keys.isEmpty()) { say("ضيف مفتاح API الأول"); return }
        val order = listOf("refine", "dial", "family", "explicit", "pron", "dedup").filter { it in aiSel }
        val results = ArrayList<String>()
        var stopped = false
        fun rep(m: String) { if (m.startsWith("في عملية شغالة")) { stopped = true; say(m) } else say(m) }
        fun step(k: Int) {
            if (stopped) return
            if (k >= order.size) { say("✅ خلصت: " + results.joinToString(" · ") + " (تقدر ترجع من 🗂 ترجمات الفيديو)"); touchSubs(); return }
            fun fin(label: String, n: Int) { results.add("$label $n"); touchSubs(); step(k + 1) }
            when (order[k]) {
                "refine" -> { say("✍ (${k + 1}/${order.size}) تنقيح بالسياق…"); engine.refineWholeNow({ m -> rep(m) }) { n -> fin("تنقيح", n) } }
                "dial" -> { say("🔥 (${k + 1}/${order.size}) لهجة +…"); engine.rewriteAll("زيادة شدة اللهجة", DIAL_INSTR, 0.0, { m -> rep(m) }) { n -> fin("لهجة", n) } }
                "family" -> { say("🧹 (${k + 1}/${order.size}) عائلي…"); engine.rewriteAll("عائلي", FAMILY_INSTR, 0.0, { m -> rep(m) }) { n -> fin("عائلي", n) } }
                "explicit" -> { say("🔞 (${k + 1}/${order.size}) صريح…"); engine.rewriteAll("صريح", EXPLICIT_INSTR, 0.0, { m -> rep(m) }) { n -> fin("صريح", n) } }
                "pron" -> { say("🔧 (${k + 1}/${order.size}) ضمائر…"); engine.correctPronounsNow({ m -> rep(m) }) { n -> fin("ضمائر", n) } }
                else -> { val n = engine.removeDuplicates(); fin("دمج مكرر", n) }
            }
        }
        step(0)
    }

    fun pronounsNow() {
        say("🔧 بصحّح الضمائر…")
        engine.correctPronounsNow({ _ -> }) { n -> say(if (n > 0) "✅ اتصحّحت $n جملة" else "الضمائر سليمة (أو مفيش جدول شخصيات)"); touchSubs() }
    }

    fun familyDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.button("🧹 عائلي — نضّف الألفاظ الخارجة والإيحاءات") { d.dismiss(); runTool("عائلي", "نضّف الجملة من الألفاظ الخارجة والإيحاءات الجنسية وخليها عائلية ومناسبة لكل الأعمار مع الحفاظ على المعنى العام.", true) })
        box.addView(ui.button("🔞 صريح — طابق صراحة النص الأصلي بالظبط") { d.dismiss(); runTool("صريح", "رجّع الترجمة لمطابقة صراحة النص الأصلي بالظبط (الألفاظ والإيحاءات زي ما هي في الأصل من غير تلطيف ولا حذف).", true) })
        box.addView(ui.button("↩ رجّع آخر نسخة قبل التعديل") { d.dismiss(); versionsDialog() })
        d.setContentView(android.widget.ScrollView(this).apply { addView(LinearLayout(this@PlayerActivity).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18); addView(ui.text("😐 عادي / عائلي / صريح", 17f, th.primary, true)); addView(box) }) })
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
        // (v134) الشكل بقى صفوف أفقية: اللهجات جنب بعض · تحتها الأسلوب والشدة · تحتها أزرار التطبيق (كانت عمود طويل)
        fun flow() = FlowRow(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun cLp() = ViewGroup.MarginLayoutParams(-2, ui.dp(34)).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) }
        fun lbl(t: String) = ui.text(t, 11f, 0xFF9AA0A6.toInt()).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), 0, ui.dp(4), 0) }
        fun lblLp() = ViewGroup.MarginLayoutParams(-2, ui.dp(34))
        val rowL = flow()
        for (l in langs) rowL.addView(ui.fsBtn((if (l == curL) "✓ " else "") + (if (l == "فصحى") "فصحى (حرفية)" else l)) { _ ->
            langPop?.dismiss()
            val v = if (l == "فصحى") "" else l
            Cfg.p.edit().putString("conv_dialect", v).apply()
            engine.setConvDialect(v, true)
            if (v.isNotEmpty() && v == conf.lang) { say("لهجة الترجمة في الإعدادات أصلًا $v — مفيش تحويل مطلوب"); touchSubs(); return@fsBtn }
            say(if (l == "فصحى") "الترجمة بالفصحى الحرفية" else "هحوّل للهجة $l من الباتش اللي إنت فيه وللقدّام، وبعدين اللي قبله — والباتشات الجديدة هتيجي باللهجة دي (النسخة القديمة اتحفظت في 🗂)")
            touchSubs()
        }, cLp())
        col.addView(rowL, LinearLayout.LayoutParams(-1, -2))
        // أسلوب الترجمة وشدته: بيتحفظوا وبيدخلوا في برومبت كل باتش جديد وكل تحويل لهجة، ومعاهم زرار تطبيق على اللي اتترجم فعلًا
        // (v134) الأسلوب والشدة بقوا سلايدرين بدل أزرار — بتحرّك واللي بتقف عليه بيتحفظ
        val toneStyles = listOf("حرفي", "شعبي", "جرئ", "+18"); val toneDegrees = listOf("خفيفة", "متوسطة", "شديدة")
        var stI = toneStyles.indexOf(Cfg.str("tone_style", "").ifBlank { "حرفي" }).coerceAtLeast(0)
        var dgI = toneDegrees.indexOf(Cfg.str("tone_strength", "متوسطة")).let { if (it < 0) 1 else it }
        fun curStyle() = toneStyles[stI]
        fun curDeg() = toneDegrees[dgI]
        val styleLbl = lbl(""); val degLbl = lbl("")
        fun mkSb(max: Int, p: Int) = android.widget.SeekBar(this).apply {
            this.max = max; progress = p; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6))
            val tint = android.content.res.ColorStateList.valueOf(th.primary)
            progressTintList = tint; thumbTintList = tint
        }
        val styleSb = mkSb(toneStyles.size - 1, stI); val degSb = mkSb(toneDegrees.size - 1, dgI)
        fun paintTone() {
            styleLbl.text = "الأسلوب:  " + curStyle() + "   (حرفي ◂ شعبي ◂ جرئ ◂ +18)"
            degLbl.text = "الشدة:  " + curDeg() + if (stI == 0) "   (بتشتغل مع شعبي / جرئ / +18 بس)" else ""
            degSb.alpha = if (stI == 0) 0.4f else 1f
        }
        styleSb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                stI = p; val v = if (p == 0) "" else toneStyles[p]; Cfg.put("tone_style", v); engine.toneStyle = v; paintTone()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                say(if (stI == 0) "الأسلوب حرفي — الباتشات الجاية من غير تعديل أسلوب" else "الأسلوب ${curStyle()} (${curDeg()}) — الباتشات الجاية هتتترجم بيه. لو عايزه على اللي اتترجم دوس «طبّق»")
            }
        })
        degSb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                dgI = p; Cfg.put("tone_strength", toneDegrees[p]); engine.toneStrength = toneDegrees[p]; paintTone()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                say(if (stI == 0) "الشدة بتشتغل مع شعبي / جرئ / +18 بس" else "الشدة ${curDeg()} — الباتشات الجاية هتتترجم بيها")
            }
        })
        col.addView(styleLbl, LinearLayout.LayoutParams(-1, -2)); col.addView(styleSb, LinearLayout.LayoutParams(-1, -2))
        col.addView(degLbl, LinearLayout.LayoutParams(-1, -2)); col.addView(degSb, LinearLayout.LayoutParams(-1, -2))
        paintTone()
        fun toneInstr(): String {
            val st = curStyle()
            val what = when (st) { "شعبي" -> "كلام الشارع الشعبي والتعبيرات اليومية بدل الرسمي"; "جرئ" -> "أجرأ وأكتر حرية في الألفاظ من غير تلطيف زيادة"; "+18" -> "صريح بلا تلطيف: الألفاظ والإيحاءات تطابق صراحة الأصل بالظبط"; else -> "" }
            return if (what.isEmpty()) "رجّع كل جملة لترجمة حرفية أمينة قريبة من الأصل من غير مبالغة في العامية أو الجرأة."
            else "أعد صياغة كل جملة بأسلوب «$st»: $what. الشدة: ${curDeg()}. حافظ على المعنى والجنس."
        }
        val rowA = flow()
        rowA.addView(ui.fsBtn("🔁 طبّق من هنا للآخر") { _ -> langPop?.dismiss(); runTool("أسلوب " + curStyle() + " (" + curDeg() + ")", toneInstr(), false) }, cLp())
        rowA.addView(ui.fsBtn("🔁 طبّق على الفيديو كله") { _ -> langPop?.dismiss(); runTool("أسلوب " + curStyle() + " (" + curDeg() + ")", toneInstr(), true) }, cLp())
        col.addView(rowA, LinearLayout.LayoutParams(-1, -2))
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        val popW = minOf(resources.displayMetrics.widthPixels - ui.dp(24), ui.dp(430))
        col.measure(View.MeasureSpec.makeMeasureSpec(popW, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val screenH = resources.displayMetrics.heightPixels
        val up = loc[1] > screenH / 2   // الزرار في النص التحتاني (الشريط السفلي) → القايمة تفتح فوقه
        val avail = (if (up) loc[1] - ui.dp(12) else screenH - loc[1] - anchor.height - ui.dp(12)).coerceAtLeast(ui.dp(120))
        val popH = minOf(col.measuredHeight, avail)
        val pw = android.widget.PopupWindow(scroll, popW, popH, true)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (langPop === pw) langPop = null; showChrome() }
        if (up) pw.showAsDropDown(anchor, 0, -(anchor.height + popH + ui.dp(2))) else pw.showAsDropDown(anchor, 0, ui.dp(2))
        langPop = pw
        h.removeCallbacks(hideChrome); showChrome(); h.removeCallbacks(hideChrome)   // الشريط يفضل ظاهر وإنت بتختار
    }

    /** كبسولة جانبية: باتش ما اترجمش / رجع ناقص + إعادة على المفاتيح الاحتياطية. مخفية لحد ما يحصل فشل */
    lateinit var probBar: TextView
    lateinit var probHandle: TextView
    lateinit var probDrawer: LinearLayout
    private var probOn = false
    private var probIdx = -1
    fun toggleProb() {
        probOn = !probOn
        probDrawer.animate().translationX(if (probOn) 0f else -(probBar.width + ui.dp(2)).toFloat()).setDuration(220).start()
        probHandle.text = if (probOn) "◂" else "⚠"
        fadeTo(probHandle, if (probOn) 1f else PROB_A)
        h.removeCallbacks(probAuto); if (probOn) h.postDelayed(probAuto, 8000)
    }
    fun updateProblems() {
        val p = try { engine.problems() } catch (_: Exception) { emptyList() }
        if (p.isEmpty() || pipNow()) {
            probIdx = -1
            if (probDrawer.visibility != View.GONE) { probDrawer.visibility = View.GONE; probOn = false; probHandle.text = "⚠" }
            return
        }
        val b = p[0]; probIdx = b.idx
        val what = if (b.mark == "❌") "ما اترجمش" else "رجع ناقص"
        probBar.text = "باتش ${b.idx + 1} $what" + (if (p.size > 1) " (+${p.size - 1})" else "") + " · 🔁 إعادة؟"
        if (probDrawer.visibility != View.VISIBLE) {
            probDrawer.visibility = View.VISIBLE; probOn = false; probHandle.text = "⚠"
            probDrawer.post { probDrawer.translationX = -(probBar.width + ui.dp(2)).toFloat() }
        }
    }
    /** زرار الباتش الفاشل: من غير سؤال — بيعيد على طول وإشعار صغير (والإعادة التلقائية شغالة لوحدها كمان) */
    fun askRetryProblem() {
        val i = probIdx; if (i < 0) return
        val n = try { engine.retryProblems() } catch (_: Exception) { 0 }
        if (probOn) toggleProb()
        if (n > 0) Notice.show(this, if (n == 1) "🔁 بعيد ترجمة باتش ${i + 1}" else "🔁 بعيد ترجمة $n باتش", 2200L)
    }


    /** يرجّع نسخة محفوظة ويظبط لهجة الترجمة الجاية على لهجتها */
    fun restoreVersion(i: Int) {
        val v = engine.versions.getOrNull(i) ?: return
        val n = engine.applyVersion(i)
        val d = if (v.dialect == "فصحى") "" else v.dialect
        Cfg.p.edit().putString("conv_dialect", d).apply()
        engine.adoptDialect(d)
        say("اتطبّقت نسخة «${v.name}» (اتغيّرت $n جملة)"); touchSubs()
    }
    private var verPop: android.widget.PopupWindow? = null
    /** قايمة صغيرة جنب عداد الجمل: الترجمات المحفوظة ولهجة كل واحدة — دوس على واحدة تبدّل ليها */
    fun versionsPopup(anchor: View) {
        verPop?.let { if (it.isShowing) { it.dismiss(); verPop = null; return } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(0xF2141418.toInt(), 0x33FFFFFF, 12)
        }
        val curD = Cfg.str("conv_dialect", "").ifBlank { "فصحى" }
        col.addView(ui.text("🗂 الترجمات — الحالية: $curD", 11f, th.muted).apply { setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(4)) })
        val vs = engine.versions.toList()
        if (vs.isEmpty()) col.addView(ui.text("مفيش نسخ لسه — أول ما تغيّر اللهجة بتتحفظ النسخة القديمة هنا", 12f, th.muted).apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2)) })
        vs.forEachIndexed { i, v ->
            col.addView(ui.fsBtn("${v.dialect.ifBlank { "—" }} · ${v.subs.size} جملة" + (if (v.name.startsWith("نسخة") || v.name.startsWith("قبل") || v.name.startsWith("بعد")) " · ${v.name}" else "")) { _ ->
                verPop?.dismiss(); restoreVersion(i)
            }.apply { minimumWidth = ui.dp(190) })
        }
        col.addView(ui.fsBtn("💾 احفظ الحالية") { _ -> verPop?.dismiss(); engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); say("اتحفظت النسخة الحالية") }.apply { minimumWidth = ui.dp(190) })
        col.addView(ui.fsBtn("🗂 كل النسخ…") { _ -> verPop?.dismiss(); versionsDialog() }.apply { minimumWidth = ui.dp(190) })
        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        col.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(220), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val pw = android.widget.PopupWindow(scroll, ui.dp(220), minOf(col.measuredHeight, maxH), true)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0)); pw.isOutsideTouchable = true
        pw.setOnDismissListener { if (verPop === pw) verPop = null; showChrome() }
        verPop = pw
        pw.showAsDropDown(anchor, -(ui.dp(220) - anchor.width), ui.dp(2))
    }

    fun versionsDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("🗂 ترجمات الفيديو (نسخ محفوظة في الجلسة)", 16f, th.primary, true))
        box.addView(ui.text("أي إعادة صياغة بتحفظ نسخة قبلها تلقائيًا. اضغط على نسخة عشان ترجّع نص الترجمة بتاعها.", 12f, th.muted))
        box.addView(ui.button("💾 احفظ النسخة الحالية") { engine.saveVersion("نسخة " + fmtMs(player.currentPosition).substring(3)); d.dismiss(); versionsDialog() })
        val vs = engine.versions.toList()
        if (vs.isEmpty()) box.addView(ui.text("مفيش نسخ محفوظة لسه", 13f, th.muted))
        vs.forEachIndexed { i, v ->
            box.addView(ui.button("${v.name} — ${v.dialect.ifBlank { "—" }} — ${v.subs.size} جملة") { restoreVersion(i); d.dismiss() })
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
                val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
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

    /** (v185) قاريء مقاطع لنفس مصدر الفيديو (لقص مقطع 10 ثواني للوضع البصري) — m3u8 مش مدعوم (بيرجّع null) */
    fun makeExtractor(): android.media.MediaExtractor? {
        val e = android.media.MediaExtractor()
        return try {
            val u = uri; val l = url
            if (u != null) e.setDataSource(this, u, null) else if (l != null && !l.contains(".m3u8", true)) e.setDataSource(l, HashMap(hdr)) else { e.release(); return null }
            e
        } catch (_: Exception) { try { e.release() } catch (_: Exception) {}; null }
    }

    fun visualSnap() {
        if (!::svRef.isInitialized || svRef.width <= 0 || svRef.height <= 0) { Notice.show(this, ("مفيش فيديو شغّال").toString(), 2300L); return }
        if (visBusy) { Notice.show(this, ("👁 لسه بترجم اللقطة اللي فاتت…").toString(), 2300L); return }
        visBusy = true
        val tok = ++visTok
        h.postDelayed({ if (visBusy && visTok == tok) { visBusy = false;  } }, 60_000)
        val wasPlaying = try { player.playWhenReady } catch (_: Exception) { false }
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:2522", e) }
        val curUs = player.currentPosition * 1000
        val t0 = (player.currentPosition - offsetMs) / 1000.0
                val done = { runOnUiThread { visBusy = false; if (wasPlaying && !isFinishing && !isDestroyed) try { player.play() } catch (e: Exception) { LogStore.err("Main:2525", e) } } }
        fun fallback() {
            Thread {
                val r = makeRetriever()
                val b = try { r?.getFrameAtTime(curUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Exception) { null }
                try { r?.release() } catch (e: Exception) { LogStore.err("Main:2530", e) }
                if (b != null) visual.snap(b, { t0 }, done) else { done() }
            }.start()
        }
        val bmp = android.graphics.Bitmap.createBitmap(svRef.width, svRef.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res -> if (res == android.view.PixelCopy.SUCCESS) visual.snap(bmp, { t0 }, done) else fallback() }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { fallback() }
    }
    private var visBusy = false
    /** (v182) تسجيل في اللوج من غير ما يلمس سطر الحالة بتاع الترجمة */
    private fun visLog(m: String) {
        try {
            synchronized(logBuf) { logBuf.append(fmtMs(cur)).append("  ").append(m).append('\n'); if (logBuf.length > 30000) logBuf.delete(0, 10000) }
            LogStore.add(fmtMs(cur) + "  " + m)
        } catch (_: Throwable) {}
    }
    /** (v182) لقطة فريم من سطح الفيديو (PixelCopy) بحجم أقصاه 640 — بتتنادى من خيط الوضع البصري، فيديوهات m3u8 */
    fun grabSurface(): android.graphics.Bitmap? {
        if (!::svRef.isInitialized) return null
        val sw = svRef.width; val sh = svRef.height
        if (sw <= 0 || sh <= 0 || !svRef.holder.surface.isValid) return null
        val sc = minOf(1f, 960f / maxOf(sw, sh))
        val bmp = android.graphics.Bitmap.createBitmap((sw * sc).toInt().coerceAtLeast(2), (sh * sc).toInt().coerceAtLeast(2), android.graphics.Bitmap.Config.ARGB_8888)
        val latch = java.util.concurrent.CountDownLatch(1); val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            android.view.PixelCopy.request(svRef, bmp, { res -> ok.set(res == android.view.PixelCopy.SUCCESS); latch.countDown() }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) { bmp.recycle(); return null }
        val done = try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        if (!done || !ok.get()) { bmp.recycle(); return null }
        return bmp
    }
    private var visSayAt = 0L
    private var visAutoRun = false
    /** (v181) ضغطة واحدة على 👁 = تشغيل/إيقاف الوضع البصري المتواصل (كان لازم ضغطة مطوّلة ← تشغيل). اللقطة الواحدة من القايمة (ضغطة مطوّلة). */
    fun visualToggle() {
        if (visual.running) {
            visual.stop(); visOv.showBoxes(emptyList()); visAutoRun = false; visLog("👁 الوضع البصري اتقفل (ضغطة)")
            Notice.show(this, "👁 الوضع البصري اتقفل", 2000L)
        } else {
            visAutoRun = false; visual.mode = "scene"; visual.start(); visLog("👁 الوضع البصري اتشغّل (ضغطة)")
            Notice.show(this, "👁 الوضع البصري شغّال — بيحلل نوافذ الفيديو قدّام التشغيل، أول نتيجة بتاخد كام ثانية", 3200L)
        }
    }
    private var visTok = 0

    fun visualDialog() {
        val d = GDialog(this); d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 18) }
        box.addView(ui.text("👁 الوضع البصري", 17f, th.primary, true))
        box.addView(ui.text("☁ Gemini: بيبعت مقاطع فيديو (10 ثواني) قدّام مكان التشغيل، وبيحدد مكان كل نص ووقت ظهوره واختفائه وحركته، والترجمة بتتعرض في مكانها وبتتحرك معاه. الترجمة الصوتية بتطلع فوق النص البصري لحد ما يختفي.\nبيشتغل تلقائي مع فتح الفيديو، والنتايج بتتحفظ مع الفيديو وترجع لما تفتحه تاني. ضغطة على 👁 = لقطة واحدة وتتحفظ.\nm3u8: بيلقط فريمات من الشاشة وقت التشغيل (النتيجة بتتأخر حوالي 10ث).", 12f, th.muted))
        visual.mode = "scene"
        val st = ui.text(if (visual.running) "الحالة: شغّال — " + visual.status else "الحالة: واقف", 13f, th.text)
        box.addView(st)
        box.addView(ui.button(if (visual.running) "⏹ إيقاف" else "▶ تشغيل", true) {
            if (visual.running) { visual.stop(); visLog("👁 الوضع البصري اتقفل (القايمة)") } else {
                visLog("👁 الوضع البصري اتشغّل (القايمة)"); visual.start()
            }
            d.dismiss()
        })
        // (v185) إعادة الوضع البصري: من الأول (يمسح كل النتايج المحفوظة) أو من مكان التشغيل وقدّام بس
        box.addView(ui.button("🔁 إعادة الوضع البصري من الأول") {
            visual.stop(); visual.clear(); visOv.showBoxes(emptyList()); visual.mode = "scene"; visual.start()
            visLog("👁 إعادة الوضع البصري من الأول (القايمة)"); Notice.show(this, "👁 بيعيد تحليل الفيديو من الأول", 2300L); d.dismiss()
        })
        box.addView(ui.button("↪ إعادة من مكان التشغيل وقدّام") {
            val t = cur / 1000.0
            visual.stop(); visual.clearFrom(t); visOv.showBoxes(emptyList()); visual.mode = "scene"; visual.start()
            visLog("👁 إعادة الوضع البصري من " + fmtMs(cur) + " (القايمة)"); Notice.show(this, "👁 بيعيد التحليل من مكان التشغيل", 2300L); d.dismiss()
        })
        box.addView(ui.button("📸 لقطة واحدة دلوقتي") { d.dismiss(); visualSnap() })
        box.addView(ui.button("🗑 مسح النتائج") { visual.clear(); visOv.showBoxes(emptyList()); d.dismiss() })
        d.setContentView(android.widget.ScrollView(this).apply { addView(box) })
        d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT) }
        ui.fullPage(d)
        d.show()
    }

    /** الإعدادات من المشغّل: بتفتح شاشة الإعدادات فوق الفيديو من غير ما تقفله — الرجوع (Back) بيرجّعك للفيديو */
    private var settingsOpened = false
    /** (v124) تشغيل من تبويب متخفي: مفيش «فيديوهات الإنترنت المحفوظة» ولا «الأخيرة» ولا لقطة مصغّرة ولا ترجمة خلفية */
    private val incognito: Boolean by lazy { intent?.getBooleanExtra("incognito", false) == true }
    fun openSettings(tab: String? = null) {
        settingsOpened = true
        resumeAfterSettings = try { player.isPlaying } catch (_: Exception) { false }
        try { player.pause() } catch (e: Exception) { LogStore.err("Main:2566", e) }
        saveRecent(); Thread { engine.saveNow() }.start()
        // (v100) نفس اتجاه المشغّل بالظبط (من غير روتيشن) ومن غير حركة انتقال — كان بيجبر العرض فيعمل نتشة ويرجع
        startActivity(Intent(this, MainActivity::class.java).putExtra("from_player", true).putExtra("land", isLandNow()).apply { if (tab != null) putExtra("tab", tab) })
        try { overridePendingTransition(0, 0) } catch (e: Exception) { LogStore.err("Main:2570", e) }
    }
    override fun onResume() {
        super.onResume(); internalNav = false; resumedNow = true; h.removeCallbacks(pipExitCheck)
        WebMute.pauseAll()   // (v149) أي صوت WebView (متصفح/يوتيوب) يتقفل أول ما المشغّل يظهر
        if (audioOnlyMode) {   // رجعنا من وضع «صوت بس»: المحرك كان موقوف، نجهّزه من جديد (الترجمة بتبدأ لما تدوس ترجم)
            audioOnlyMode = false; engineStarted = false
            try { initEngine(); say("الترجمة كانت موقوفة في وضع الصوت — دوس ترجم لو عايز تكمّل") } catch (e: Throwable) { LogStore.err("Player:audioResume", e) }
        }
        if (Cfg.str("theme", "mx") != th.id) { recreate(); return }
        if (settingsOpened) {
            settingsOpened = false
            // (v122) رجعنا من الإعدادات: اللهجة / الأسلوب / الموديل / الشخصيات / المسرد لازم توصل للمحرك الشغّال فعلًا مش بس تتحفظ
            try {
                val nc = Cfg.snapshot(); conf = nc
                if (::engine.isInitialized) {
                    engine.applyConf(nc)
                    engine.toneStyle = Cfg.str("tone_style", ""); engine.toneStrength = Cfg.str("tone_strength", "متوسطة")
                }
            } catch (e: Throwable) { LogStore.err("Player:applyConf", e) }
        }
        restyleFn()
        if (resumeAfterSettings) { resumeAfterSettings = false; try { player.play() } catch (e: Exception) { LogStore.err("Main:2576", e) } }
    }
    override fun onPause() { super.onPause(); LogStore.add("⏸ onPause · ${LogStore.heapLine()}"); resumedNow = false; if (!handedOff) { saveRecent(); Thread { engine.saveNow() }.start() } }
    override fun onStop() {
        super.onStop(); saveRecent()
        // ✕ على نافذة PiP: النظام بيوقف الأكتيفيتي وهي لسه في وضع PiP — نقفل الفيديو ونخرج (إلا لو الشاشة اتقفلت)
        val interactive = try { (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive } catch (_: Exception) { true }
        if (pipNow() && interactive && !isFinishing && !audioOnlyMode) closeAfterPip()
    }
    override fun onDestroy() {
        LogStore.add("🔚 onDestroy (isFinishing=$isFinishing)")
        try { TaskCenter.extra.remove(taskListener) } catch (_: Throwable) {}
        h.removeCallbacksAndMessages(null)
        pipOv?.hide(); pipOv = null
        saveRecent()
        try { visual.stop() } catch (e: Exception) { LogStore.err("Main:2590", e) }
        try { fx?.stop() } catch (_: Throwable) {}
        if (Live.engine === engine) Live.engine = null
        retireEngine()
        if (!handedOff) try { engine.saveNow() } catch (e: Exception) { LogStore.err("Main:2593", e) }
        if (incognito && isFinishing) try { File(File(filesDir, "progress"), Store.keyFor(vid) + ".json").delete() } catch (_: Throwable) {}   // (v124) المتخفي: الترجمة المحفوظة بتتمسح أول ما تقفل
        player.release()
        synchronized(liveList) { liveList.removeAll { it.get() == null || it.get() === this } }
        val cur0 = PlayerRemote.act?.get()
        if (cur0 === this) PlayerRemote.act = null
        if (cur0 == null || cur0 === this) KeepAliveService.stop(this)
        super.onDestroy()
    }
}
