package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

/** مهمة ترجمة في الخلفية لفيديو واحد (من غير مشغّل). التقدم بيتحفظ في نفس ملف الـ progress بتاع المشغّل، فاللي اترجم بيتكمّل. */
class BgJob(val vid: String, val title: String, val uri: String?, val url: String?, val hdr: Map<String, String>) {
    @Volatile var state = "queued"      // queued | running | done | failed | stopped
    @Volatile var pct = 0
    @Volatile var covered = 0.0
    @Volatile var dur = 0.0
    @Volatile var remainSec = -1L
    @Volatile var err = ""
    @Volatile var srt = ""
    @Volatile var stopReq = false
    /** وقف مؤقت عشان فيديو تاني يبدأ الأول: الفيديو ده يرجع للطابور بعده بدل ما يتحسب «اتوقف» */
    @Volatile var requeue = false
    @Volatile var paused = false
    @Volatile var engine: Engine? = null
    val active: Boolean get() = state == "queued" || state == "running"
}

object BgJobs {
    val jobs = CopyOnWriteArrayList<BgJob>()
    @Volatile var onChange: (() -> Unit)? = null
    fun notifyChange() { try { onChange?.invoke() } catch (_: Exception) {}; persist() }

    // ===== الطابور بيتحفظ في filesDir/bg_queue.json (الشغّال والمستني بس) عشان يكمّل بعد قفل البرنامج/إعادة تشغيل الجهاز =====
    @Volatile private var app: Context? = null
    private var lastSig = ""
    private var restored = false
    private fun qFile(c: Context) = File(c.filesDir, "bg_queue.json")
    private fun sigOf(l: List<BgJob>) = l.joinToString("|") { it.vid + ":" + it.paused }
    @Synchronized fun persist() {
        val c = app ?: return
        val act = jobs.filter { it.active }
        val sig = sigOf(act)
        if (sig == lastSig) return
        lastSig = sig
        try {
            val arr = JSONArray()
            act.forEach { j ->
                arr.put(JSONObject().put("vid", j.vid).put("title", j.title).put("uri", j.uri ?: JSONObject.NULL).put("url", j.url ?: JSONObject.NULL)
                    .put("hdr", JSONObject(j.hdr as Map<*, *>)).put("paused", j.paused))
            }
            qFile(c).writeText(arr.toString())
        } catch (_: Exception) {}
    }
    /** بيرجّع الطابور المحفوظ (مرة واحدة في كل عملية). بيرجّع عدد المهام المستنية/الشغالة */
    @Synchronized fun restore(ctx: Context): Int {
        val c = ctx.applicationContext
        app = c
        if (!restored) {
            restored = true
            try {
                val f = qFile(c)
                if (f.exists()) {
                    val arr = JSONArray(f.readText())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val vid = o.getString("vid")
                        if (jobs.any { it.vid == vid && it.active }) continue
                        val hdr = HashMap<String, String>()
                        o.optJSONObject("hdr")?.let { h -> h.keys().forEach { k -> hdr[k] = h.optString(k) } }
                        val j = BgJob(vid, o.optString("title", vid), if (o.isNull("uri")) null else o.optString("uri"), if (o.isNull("url")) null else o.optString("url"), hdr)
                        j.paused = o.optBoolean("paused", false)
                        jobs.add(j)
                    }
                }
            } catch (_: Exception) {}
            lastSig = sigOf(jobs.filter { it.active })
            notifyChange()
        }
        return jobs.count { it.active }
    }

    fun find(vid: String): BgJob? = jobs.lastOrNull { it.vid == vid }
    fun isActive(vid: String) = find(vid)?.active == true
    fun anyActive() = jobs.any { it.active }
    fun queuedCount() = jobs.count { it.state == "queued" }

    /** نفس معرّف الفيديو اللي بيستخدمه المشغّل (عشان نفس ملف التقدم) */
    fun videoId(ctx: Context, uri: Uri?, url: String?): String {
        if (uri != null) {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return "f:" + c.getString(0) + ":" + c.getLong(1)
                }
            } catch (_: Exception) {}
            return "f:$uri"
        }
        return "u:" + (url ?: "").substringBefore('?')
    }

    /** بيضيف الفيديو لطابور الترجمة في الخلفية. بيرجّع false لو هو أصلًا في الطابور/شغّال */
    fun enqueue(ctx: Context, job: BgJob): Boolean {
        restore(ctx)
        if (isActive(job.vid)) return false
        jobs.removeAll { it.vid == job.vid && !it.active }
        while (jobs.count { !it.active } > 20) jobs.firstOrNull { !it.active }?.let { jobs.remove(it) }
        jobs.add(job)
        BgService.start(ctx.applicationContext)
        notifyChange()
        return true
    }

    fun stop(vid: String) {
        val j = find(vid) ?: return
        if (!j.active) return
        j.stopReq = true
        if (j.state == "queued") j.state = "stopped"
        notifyChange()
    }
    fun pause(vid: String) { find(vid)?.let { if (it.active) { it.paused = true; it.engine?.paused = true; notifyChange() } } }
    fun resume(ctx: Context, vid: String) { find(vid)?.let { if (it.active) { it.paused = false; it.engine?.paused = false; BgService.start(ctx.applicationContext); notifyChange() } } }
    /** شيل الفيديو من الطابور (لو شغّال بيتوقف والتقدم بيتحفظ) */
    fun remove(j: BgJob) { if (j.active) { j.stopReq = true; if (j.state == "queued") j.state = "stopped" }; jobs.remove(j); notifyChange() }
    // ترتيب الطابور: الأول في القايمة هو اللي بيترجم الأول (الشغّال حاليًا بيكمّل). الحركة بين المستنيين بس.
    @Synchronized fun moveUp(j: BgJob) {
        val i = jobs.indexOf(j); if (i < 0 || j.state != "queued") return
        val p = (i - 1 downTo 0).firstOrNull { jobs[it].state == "queued" } ?: return
        jobs.remove(j); jobs.add(p, j); notifyChange()
    }
    @Synchronized fun moveDown(j: BgJob) {
        val i = jobs.indexOf(j); if (i < 0 || j.state != "queued") return
        val n = (i + 1 until jobs.size).firstOrNull { jobs[it].state == "queued" } ?: return
        jobs.remove(j); jobs.add(n, j); notifyChange()
    }
    /** أول واحد مستني (بيترجم بعد الشغّال حاليًا مباشرة) */
    @Synchronized fun moveTop(j: BgJob) {
        val i = jobs.indexOf(j); if (i < 0 || j.state != "queued") return
        val f = jobs.indexOfFirst { it.state == "queued" && it !== j }
        if (f < 0 || f >= i) return
        jobs.remove(j); jobs.add(f, j); notifyChange()
    }
    /** ابدأ ده دلوقتي: الشغّال حاليًا بيتوقف (التقدم محفوظ) ويرجع في الطابور بعد ده */
    @Synchronized fun startNow(ctx: Context, j: BgJob) {
        val i = jobs.indexOf(j); if (i < 0 || j.state != "queued") return
        j.paused = false
        val r = jobs.firstOrNull { it.state == "running" && it !== j }
        if (r != null) { r.requeue = true; r.stopReq = true; jobs.remove(j); jobs.add(jobs.indexOf(r), j); notifyChange() } else moveTop(j)
        BgService.start(ctx.applicationContext)
    }
    fun position(j: BgJob): Int = jobs.filter { it.state == "queued" }.indexOf(j) + 1
    fun retry(ctx: Context, j: BgJob): Boolean { jobs.remove(j); return enqueue(ctx, BgJob(j.vid, j.title, j.uri, j.url, j.hdr)) }
    fun clearFinished() { jobs.removeAll { !it.active }; notifyChange() }
    fun stopAll() { jobs.filter { it.active }.forEach { it.stopReq = true; if (it.state == "queued") it.state = "stopped" }; notifyChange() }

    /** وقف ترجمة فيديو في الخلفية وانتظار حفظ التقدم (لما المستخدم يفتحه في المشغّل) */
    fun stopAndWait(vid: String, ms: Long) {
        val j = find(vid) ?: return
        if (!j.active) return
        stop(vid)
        val t0 = System.currentTimeMillis()
        while (j.state == "running" && System.currentTimeMillis() - t0 < ms) try { Thread.sleep(50) } catch (_: InterruptedException) { return }
    }

    fun fmtRemain(sec: Long): String = when {
        sec < 0 -> ""
        sec < 90 -> "أقل من دقيقة"
        sec < 3600 -> "${(sec + 30) / 60} د"
        else -> "${sec / 3600}س ${(sec % 3600) / 60}د"
    }
}

class BgService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    private val lock = Any()
    private var workerAlive = false
    private var lastStartId = 0
    private var wake: PowerManager.WakeLock? = null
    private var lastNotif = 0L

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "ترجمة في الخلفية", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "خلصت الترجمة", NotificationManager.IMPORTANCE_DEFAULT))
        try { postForeground(null) } catch (_: Exception) { stopSelf(startId); return START_NOT_STICKY }   // النظام رفض الـ foreground (مثلاً من بعد الإقلاع): بلاش انهيار
        // لو النظام رجّع الخدمة بعد ما قتل العملية (intent = null) أو بعد ريستارت: ارجع الطابور المحفوظ
        Cfg.init(applicationContext)
        BgJobs.restore(this)
        if (i?.action == ACTION_STOP) BgJobs.stopAll()
        synchronized(lock) {
            lastStartId = startId
            if (!workerAlive) { workerAlive = true; Thread { work() }.apply { isDaemon = true }.start() }
        }
        return START_STICKY
    }

    /** المستخدم مسح التطبيق من الأخيرة: الخدمة (Foreground) بتكمّل، ولو الجهاز قتل العملية بنجدول إعادة تشغيل احتياطي */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!BgJobs.anyActive()) return
        try {
            val pi = PendingIntent.getForegroundService(this, 2, Intent(this, BgService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 4000, pi)
        } catch (_: Exception) {}
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun build(job: BgJob?): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, BgService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "⏹ إيقاف", stop).build())
        if (job != null && job.paused) return b.setContentTitle("⏸ ترجمة الخلفية متوقفة مؤقتًا — ${job.pct}%").setContentText(job.title).setProgress(100, job.pct, false).build()
        if (job == null) return b.setContentTitle("🌙 ترجمة في الخلفية").setContentText("بجهّز…").setProgress(0, 0, true).build()
        val q = BgJobs.queuedCount()
        val rem = BgJobs.fmtRemain(job.remainSec)
        val sb = StringBuilder(job.title)
        if (rem.isNotEmpty()) sb.append(" · باقي حوالي ").append(rem)
        if (q > 0) sb.append(" · +").append(q).append(" في الانتظار")
        return b.setContentTitle("🌙 بترجم في الخلفية — ${job.pct}%").setContentText(sb.toString()).setStyle(Notification.BigTextStyle().bigText(sb.toString()))
            .setProgress(100, job.pct, job.dur <= 0).build()
    }

    private fun postForeground(job: BgJob?) {
        val n = build(job)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }
    private fun updateNotif(job: BgJob, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotif < 1500) return
        lastNotif = now
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(job)) } catch (_: Exception) {}
    }

    private fun work() {
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "subtitler:bg").apply { setReferenceCounted(false); acquire(6 * 3600 * 1000L) }
        } catch (_: Exception) {}
        var stopId = 0
        try {
            while (true) {
                val job = synchronized(lock) {
                    val j = BgJobs.jobs.firstOrNull { it.state == "queued" && !it.paused }
                    if (j == null) { workerAlive = false; stopId = lastStartId }
                    j
                } ?: break
                try { wake?.acquire(6 * 3600 * 1000L) } catch (_: Exception) {}
                runJob(job)
            }
        } finally {
            try { wake?.release() } catch (_: Exception) {}
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            if (stopId != 0) stopSelf(stopId) else stopSelf()
        }
    }

    private fun runJob(job: BgJob) {
        if (job.stopReq) { job.state = "stopped"; BgJobs.notifyChange(); return }
        job.state = "running"; BgJobs.notifyChange()
        val app = applicationContext
        Cfg.init(app)
        val conf = Cfg.snapshot()
        if (conf.keys.isEmpty() && conf.backup.isEmpty()) { job.state = "failed"; job.err = "مفيش مفاتيح Gemini"; BgJobs.notifyChange(); done(job); return }
        postForeground(job)
        val store = Store(File(filesDir, "progress"), Store.keyFor(job.vid))
        val pb = PromptBuilder { p -> assets.open(p).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        var savedPos = 0.0
        val host = object : Host {
            override fun log(s: String) { LogStore.add("🌙[خلفية] " + s) }
            override fun status(s: String) {}
            override fun changed() {}
            override fun position() = savedPos
            override fun playerDuration() = job.dur
        }
        val lg: (String) -> Unit = {}
        val uri = job.uri?.let { Uri.parse(it) }
        val engine = Engine(conf, {
            val u = job.url
            if (uri == null && u != null && u.contains(".m3u8", true)) HlsSource(app, u, job.hdr, conf.audioTrack, lg) else FileSource(app, uri, u, job.hdr, conf.audioTrack, lg)
        }, store, host, pb)
        engine.headless = true
        engine.convDialect = Cfg.str("conv_dialect", "")
        job.engine = engine
        var ok = false
        engine.onFinished = { r -> ok = r }
        savedPos = engine.load()
        val startCover = engine.coveredSec(); val t0 = System.currentTimeMillis(); var lastRec = 0L
        val runner = Thread { engine.run() }.apply { isDaemon = true; start() }
        while (runner.isAlive) {
            if (job.stopReq) { engine.stop(); break }
            engine.paused = job.paused
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            val d = engine.durationSec(); if (d > 0) job.dur = d
            job.covered = engine.coveredSec()
            job.pct = if (job.dur > 0) (job.covered * 100 / job.dur).toInt().coerceIn(0, 100) else 0
            val gained = job.covered - startCover; val el = (System.currentTimeMillis() - t0) / 1000.0
            job.remainSec = if (gained > 5 && el > 8 && job.dur > 0) ((job.dur - job.covered) / (gained / el)).toLong().coerceAtLeast(0) else -1
            val now = System.currentTimeMillis()
            if (now - lastRec > 6000) { lastRec = now; Recents.saveProgress(app, job.vid, job.title, job.url ?: "", job.uri ?: "", job.dur, engine.subs.size, job.covered) }
            updateNotif(job); BgJobs.notifyChange()
        }
        runner.join(6000)
        engine.awaitStopped(3000)
        job.covered = engine.coveredSec(); job.dur = engine.durationSec().takeIf { it > 0 } ?: job.dur
        Recents.saveProgress(app, job.vid, job.title, job.url ?: "", job.uri ?: "", job.dur, engine.subs.size, job.covered)
        when {
            job.stopReq && job.requeue -> { job.state = "queued"; job.stopReq = false; job.requeue = false; job.err = "" }
            job.stopReq -> job.state = "stopped"
            ok -> { job.state = "done"; job.pct = 100; try { SrtWriter.save(app, job.uri, engine.subs, Cfg.str("sub_offset_ms:" + job.vid, "0").toLongOrNull() ?: 0L)?.let { job.srt = it } } catch (_: Exception) {}; val nf = engine.failedCount(); job.err = if (nf > 0) "فيها $nf مقطع فاشل — افتح الفيديو وادوس سد الفجوات" else "" }
            else -> { job.state = "failed"; job.err = engine.fatal ?: "الترجمة وقفت قبل ما تخلص" }
        }
        job.engine = null
        BgJobs.notifyChange()
        done(job)
    }

    private fun done(job: BgJob) {
        if (job.state == "stopped" || job.state == "queued") return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ok = job.state == "done"
        val n = Notification.Builder(this, CH_DONE).setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (ok) "✅ خلصت الترجمة" else "⚠ الترجمة وقفت").setContentText(job.title + (if (job.err.isNotEmpty()) " — " + job.err else ""))
            .setContentIntent(openApp()).setAutoCancel(true).build()
        try { nm.notify(1000 + (job.vid.hashCode() and 0xFFFF), n) } catch (_: Exception) {}
    }

    companion object {
        private const val CH = "bg_translate"
        private const val CH_DONE = "bg_translate_done"
        private const val ID = 78
        const val ACTION_STOP = "com.tttt.subtitler.BG_STOP"
        fun start(c: Context) { try { c.startForegroundService(Intent(c, BgService::class.java)) } catch (_: Exception) {} }
    }
}
