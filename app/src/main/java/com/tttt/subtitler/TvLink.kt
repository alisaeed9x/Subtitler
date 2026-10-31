package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** (v518) رقم/اسم النسخة المثبّتة — الموبايل والتلفزيون بيقارنوا بيها للتحديث عبر الواي فاي */
object LinkVer {
    fun code(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Throwable) { 0L }

    fun name(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
}

/**
 * (v518) جانب التلفزيون من «ريموت الموبايل»:
 *  - سيرفر HTTP على المنفذ 18770 + رد على البحث التلقائي (UDP 18771) فالموبايل يلاقي التلفزيون لوحده.
 *  - اقتران: أول ريموت بيتقرن تلقائي من غير أي حاجة؛ أي ريموت بعده محتاج كود 5 أرقام بيظهر في شاشة «الريموتات».
 *  - أكتر من ريموت في نفس الوقت، كل واحد له اسمه وتوكن خاص. أي أمر بيتنفّذ بأسبقية الوصول.
 *  - نبضة (/ping) كل كام ثانية: لو وقفت أكتر من 12 ثانية الريموت يتعتبر منقطع وبيطلع إشعار.
 *  - أوامر: صوت/كتم/تشغيل-إيقاف · فتح لينك · نص للبحث · نقل مفاتيح Gemini · عرض تحديث من الموبايل.
 */
object TvLink {
    const val PORT = 18770
    const val UDP_PORT = 18771
    private const val ALIVE_MS = 12000L

    class Remote(val id: String, var name: String, var token: String) {
        @Volatile var lastSeen = 0L
        @Volatile var ip = ""
        @Volatile var kicked = false
        @Volatile var announced = false
        @Volatile var phoneVer = 0L
        fun alive(): Boolean = lastSeen > 0L && System.currentTimeMillis() - lastSeen < ALIVE_MS
    }

    class Offer(val ver: Long, val vname: String, val url: String, val from: String, val force: Boolean = false, val size: Long = 0L) { @Volatile var accepted = false }   // (v521) force = دفع إجباري من الموبايل

    private val h = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val remotes = ConcurrentHashMap<String, Remote>()
    @Volatile private var started = false
    private var http: MiniHttp? = null
    @Volatile private var pairCode = ""
    @Volatile private var pairCodeUntil = 0L
    @Volatile private var badTries = 0
    @Volatile private var lockUntil = 0L
    @Volatile private var lastCodeShown = 0L
    @Volatile var pendingOffer: Offer? = null
    @Volatile private var offerShowing = false
    @Volatile private var declinedVer = 0L
    private var top: WeakReference<Activity>? = null
    private val seenCmd = LinkedHashSet<String>()

    val running: Boolean get() = started

    /** (v519) أكتيفيتي تطبيقنا الظاهرة دلوقتي (null = تطبيق تاني قدّام أو الشاشة مقفولة) */
    fun topActivity(): Activity? = top?.get()
    fun appCtx(): Context? = if (started) app else null

    // ===== تشغيل =====
    @Synchronized fun start(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        try { Cfg.ensure(app) } catch (_: Throwable) {}
        loadRemotes()
        val srv = MiniHttp(PORT) { r -> route(r) }
        if (!srv.start()) return
        http = srv
        started = true
        try { CastCache.purgeStale(app) } catch (_: Throwable) {}   // (v564) بقايا فيديو موبايل لو التطبيق اتقفل فجأة
        trackActivities()
        startUdp()
        startWatcher()
    }

    private fun trackActivities() {
        try {
            (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { top = WeakReference(a); if (a !is LaunchRemoteActivity && a !is SendToTvActivity) offerIfAny(a); TvUpdate.resumePending(a) }
                override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        } catch (e: Throwable) { LogStore.err("TvLink:track", e) }
    }

    private fun startUdp() {
        Thread {
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(UDP_PORT))
                val buf = ByteArray(512)
                while (started) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val t = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (t.startsWith("SUBTV?")) {
                        val reply = hello().toString().toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (e: Exception) { LogStore.err("TvLink:udp", e) }
        }.also { it.isDaemon = true }.start()
    }

    /** بيراقب انقطاع الريموتات (نبضة وقفت) ويطلّع إشعار «انقطع» */
    private fun startWatcher() {
        Thread {
            while (started) {
                try {
                    for (rm in remotes.values) {
                        if (rm.announced && !rm.alive()) { rm.announced = false; notice("📱 الريموت «" + rm.name + "» انقطع") }
                    }
                } catch (_: Throwable) {}
                try { Thread.sleep(2500) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true }.start()
    }

    // ===== هوية التلفزيون =====
    fun tvId(): String {
        var id = Cfg.str("tvlink_id", "")
        if (id.isBlank()) { id = randHex(8); Cfg.put("tvlink_id", id) }
        return id
    }

    fun tvName(): String {
        val n = Cfg.str("tvlink_name", "")
        if (n.isNotBlank()) return n
        val m = try { Build.MODEL ?: "" } catch (_: Throwable) { "" }
        return if (m.isBlank()) "التلفزيون" else "تلفزيون $m"
    }

    private fun randHex(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return b.joinToString("") { String.format("%02x", it) }
    }

    private fun hello(): JSONObject = JSONObject()
        .put("app", "subtitler-tv").put("id", tvId()).put("name", tvName()).put("port", PORT)
        .put("ver", LinkVer.code(app)).put("vname", LinkVer.name(app)).put("needCode", true)

    // ===== قايمة الريموتات =====
    private fun loadRemotes() {
        try {
            val a = JSONArray(Cfg.str("tvlink_remotes", "[]"))
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id"); val tk = o.optString("token")
                if (id.isNotBlank() && tk.isNotBlank()) remotes[id] = Remote(id, o.optString("name", "موبايل"), tk)
            }
        } catch (_: Throwable) {}
    }

    private fun saveRemotes() {
        try {
            val a = JSONArray()
            for (r in remotes.values) a.put(JSONObject().put("id", r.id).put("name", r.name).put("token", r.token))
            Cfg.put("tvlink_remotes", a.toString())
        } catch (_: Throwable) {}
    }

    fun remoteList(): List<Remote> = remotes.values.sortedBy { it.name }

    fun kick(id: String) {
        val r = remotes[id] ?: return
        r.kicked = true; r.lastSeen = 0L
        if (r.announced) { r.announced = false; notice("📱 الريموت «" + r.name + "» اتفصل") }
    }

    fun forget(id: String) {
        val r = remotes.remove(id) ?: return
        saveRemotes()
        notice("🗑 اتمسح الريموت «" + r.name + "» من المقترنين")
    }

    /** كود إقران جديد (5 أرقام) صالح 10 دقايق — بيتعرض في شاشة «الريموتات» */
    fun newCode(): String {
        val c = (SecureRandom().nextInt(90000) + 10000).toString()
        pairCode = c; pairCodeUntil = System.currentTimeMillis() + 10 * 60 * 1000L
        return c
    }

    fun address(): String { val i = TypeServer.ip(); return if (i.isEmpty()) "" else "$i:$PORT" }

    // ===== إشعارات التلفزيون =====
    fun notice(msg: String, ms: Long = 2600L) {
        h.post {
            try { val a = top?.get(); if (a != null && !a.isFinishing && !a.isDestroyed) Notice.show(a, msg, ms) } catch (_: Throwable) {}
        }
    }

    // ===== التوجيه =====
    private fun err(code: Int, e: String) = HttpRes.json(JSONObject().put("ok", false).put("err", e).toString(), code)
    private fun ok(extra: JSONObject = JSONObject()) = HttpRes.json(extra.put("ok", true).toString())

    private fun route(r: HttpReq): HttpRes {
        if (r.path == "/hello") return HttpRes.json(hello().toString())
        if (r.path == "/pair") return pair(r)
        val rm = authOf(r) ?: return err(401, "auth")
        rm.ip = r.peer
        if (rm.kicked) { if (r.header("x-manual") == "1") rm.kicked = false else return err(409, "kicked") }
        touch(rm, r)
        return when (r.path) {
            "/ping" -> ok(JSONObject().put("ver", LinkVer.code(app)).put("vname", LinkVer.name(app)).put("name", tvName()).put("tv", tvId())
                .put("ctx", TvInput.context()).put("acc", TvAccess.connected).put("vol", sysVolPct()))
            "/cmd" -> cmd(r)
            "/key" -> keyCmd(r)
            "/ptr" -> ptrCmd(r)
            "/player" -> playerCmd(r)
            "/music" -> musicCmd(r)
            "/open" -> open(r)
            "/recent" -> recentList()           // (v678) آخر المشغّلة على التلفزيون (لقايمة الريموت)
            "/recent/play" -> recentPlay(r)     // (v678) تشغيل واحد منها على التلفزيون
            "/cosurf" -> coSurf(r)   // (v646) مزامنة المتصفح
            "/clear" -> clearHist(r)
            "/type" -> typeText(r)
            "/keys" -> keys(r, rm)
            "/ytacc" -> ytAcc(r, rm)
            "/offer" -> offer(r, rm)
            "/rpc" -> BrainRpc.exec(r)
            "/sync" -> BrainRpc.sync(r)
            "/frame" -> BrainRpc.frame(r)
            "/lib/folders" -> BrainRpc.libFolders(app, r)
            "/lib/videos" -> BrainRpc.libVideos(app, r)
            "/lib/thumb" -> BrainRpc.libThumb(app, r)
            "/file/lib" -> TvXfer.fileLib(app, r)     // (v596) تحميل فيديو من مكتبة التلفزيون على الموبايل (Range)
            "/file/cur" -> TvXfer.fileCur(app, r)     // (v596) السماعة الخاصة: ملف المشغّل المحلي
            "/pull" -> TvXfer.pull(app, r)            // (v596) الموبايل يبعت ملف: التلفزيون يسحبه
            "/priv" -> TvXfer.priv(app, r)            // (v596) كتم التلفزيون وقت السماعة الخاصة
            "/xfer/list" -> TvCatalogApi.list(app, r)   // (v607) مركز النقل: كتالوج التلفزيون (فيديوهات · صوتيات · صور · APK · تطبيقات · عروض)
            "/fs/list" -> TvFsApi.list(app, r)           // (v610) تصفح ملفات التلفزيون (الذاكرة + الفلاشات)
            "/fs/file" -> TvFsApi.file(app, r)           // (v610) تنزيل ملف من التلفزيون بمساره (Range)
            "/file/any" -> TvCatalogApi.file(app, r)     // (v607) تنزيل عنصر من الكتالوج (Range)
            "/cfg" -> BrainRpc.cfg(r)
            "/bye" -> { rm.announced = false; rm.lastSeen = 0L; notice("📱 الريموت «" + rm.name + "» فصل"); ok() }
            else -> err(404, "nf")
        }
    }

    private fun authOf(r: HttpReq): Remote? {
        val t = r.header("x-token")
        if (t.isEmpty()) return null
        return remotes.values.firstOrNull { it.token == t }
    }

    private fun touch(rm: Remote, r: HttpReq) {
        rm.lastSeen = System.currentTimeMillis()
        r.header("x-ver").toLongOrNull()?.let { rm.phoneVer = it }
        val nm = r.header("x-name")
        if (nm.isNotEmpty()) try { val d = URLDecoder.decode(nm, "UTF-8"); if (d.isNotBlank() && d != rm.name) { rm.name = d; saveRemotes() } } catch (_: Exception) {}
        if (!rm.announced) { rm.announced = true; notice("📱 الريموت «" + rm.name + "» اتصل") }
    }

    private fun pair(r: HttpReq): HttpRes {
        val now = System.currentTimeMillis()
        if (now < lockUntil) return err(403, "locked")
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val id = j.optString("id"); val nm = j.optString("name").ifBlank { "موبايل" }; val code = j.optString("code")
        if (id.isBlank()) return err(403, "bad")
        val codeOk = code.isNotEmpty() && code == pairCode && now < pairCodeUntil
        // (v585 / C2) الكود إجباري حتى لأول ريموت: اللي على نفس الشبكة لازم يشوف الكود على شاشة التلفزيون
        // (v598) اقتران من غير كود (الافتراضي): الموبايل بيختار التلفزيون ويتصل. للرجوع للكود الإجباري: Cfg «tvlink_need_code» = 1
        val strict = Cfg.str("tvlink_need_code", "0") == "1"
        if (!codeOk && !strict && code.isEmpty()) { /* مقبول */ }
        else if (!codeOk) {
            if (code.isNotEmpty()) { badTries++; if (badTries >= 8) { badTries = 0; lockUntil = now + 60000L } }
            else if (now - lastCodeShown > 8000L) {
                // محاولة من غير كود ← اعرض كود صالح على التلفزيون (من غير ما نغيّر كود لسه صالح عشان محاولة غريبة ما تبوّظش اقتران شغّال)
                lastCodeShown = now
                val c = if (pairCode.isNotEmpty() && now < pairCodeUntil) pairCode else newCode()
                notice("🔐 كود إقران الموبايل: $c", 12000L)
            }
            return err(403, "need_code")
        }
        badTries = 0
        val tok = randHex(24)
        val ex = remotes[id]
        if (ex != null) { ex.token = tok; ex.name = nm; ex.kicked = false } else remotes[id] = Remote(id, nm, tok)
        saveRemotes()
        if (codeOk) pairCode = ""   // الكود استُخدم
        notice("📱 اتقرن ريموت جديد: «$nm»")
        return ok(JSONObject().put("token", tok).put("tv", tvId()).put("name", tvName()))
    }

    // ===== الأوامر =====
    private fun cmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val c = j.optString("c"); val cid = j.optString("cid")
        if (cid.isNotEmpty()) synchronized(seenCmd) {
            if (!seenCmd.add(cid)) return ok(JSONObject().put("dup", true))   // نفس الأمر وصل مرتين
            if (seenCmd.size > 60) { val it = seenCmd.iterator(); it.next(); it.remove() }
        }
        exec(c)
        return ok()
    }

    private fun dup(cid: String): Boolean {
        if (cid.isEmpty()) return false
        synchronized(seenCmd) {
            if (!seenCmd.add(cid)) return true
            if (seenCmd.size > 60) { val it = seenCmd.iterator(); it.next(); it.remove() }
        }
        return false
    }

    /** (v519) زرار ريموت: up/down/left/right/ok/back/home/menu/recents/ff/rew/next/prev */
    private fun keyCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val ctx = JSONObject().put("ctx", TvInput.context()).put("acc", TvAccess.connected)
        if (dup(j.optString("cid"))) return ok(ctx.put("dup", true))
        val k = j.optString("k")
        if (k.isEmpty()) return err(403, "empty")
        TvInput.key(k)
        return ok(ctx)
    }

    /** (v520) مشغّل الفيديو: a = state أو أمر (toggle/seek/seekto/prev/next/speed/fit/cc/scale/shift/audio/quality/translate/more) و v = قيمة */
    private fun playerCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val a = j.optString("a")
        // (v531) المتصفح قدّامك (أو مفيش مشغّل برنامج) ← الأوامر والحالة على فيديو الصفحة؛ غير كده ← مشغّل البرنامج
        val useBr = BrowserActivity.live() != null && (top?.get() is BrowserActivity || !PlayerActivity.hasLive())
        if (a != "state" && a.isNotEmpty()) {
            if (dup(j.optString("cid"))) return ok(JSONObject().put("dup", true))
            val handled = if (useBr) BrowserActivity.remoteAct(a, j.optLong("v")) else PlayerActivity.remoteAct(a, j.optLong("v"))
            if (!handled) return ok(JSONObject().put("none", true))
            if (useBr) try { Thread.sleep(120L) } catch (_: Exception) {}   // الـJS يتنفّذ قبل ما نقرا الحالة
        }
        val st = (if (useBr) BrowserActivity.remoteState() else PlayerActivity.remoteState()) ?: return ok(JSONObject().put("none", true))
        return ok(JSONObject().put("st", st))
    }

    /** (v522) مشغّل الموسيقى: a = state أو أمر (toggle/next/prev/seek/seekto/shuffle/mode/lyrics) */
    private fun musicCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val a = j.optString("a")
        if (a != "state" && a != "lyrics" && a.isNotEmpty()) {
            if (dup(j.optString("cid"))) return ok(JSONObject().put("dup", true))
            h.post {
                try {
                    when (a) {
                        "toggle" -> MusicEngine.toggle()
                        "next" -> MusicEngine.step(1)
                        "prev" -> MusicEngine.step(-1)
                        "seekto" -> MusicEngine.seekTo(j.optLong("v").toInt().coerceAtLeast(0))
                        "seek" -> MusicEngine.seekTo((MusicEngine.posMs + j.optLong("v").toInt()).coerceIn(0, MusicEngine.durMs.coerceAtLeast(0)))
                        "shuffle" -> MusicEngine.toggleShuffle()
                        "mode" -> MusicEngine.cycleMode()
                    }
                } catch (e: Throwable) { LogStore.err("TvLink:music", e) }
            }
            try { Thread.sleep(80L) } catch (_: Exception) {}
        }
        val t = MusicEngine.current ?: return ok(JSONObject().put("none", true))
        val lr = try { LyricsEngine.cached(app, t, LyricsEngine.mode()) } catch (_: Throwable) { null }
        if (a == "lyrics") {
            val txt = lr?.lines?.joinToString("\n") { it.text } ?: ""
            return ok(JSONObject().put("lyrics", txt))
        }
        val pos = MusicEngine.posMs.toLong()
        val line = lr?.lines?.lastOrNull { it.start * 1000.0 <= pos }?.text ?: ""
        return ok(JSONObject().put("st", JSONObject()
            .put("title", t.title).put("artist", t.artist).put("pos", pos).put("dur", MusicEngine.durMs.toLong())
            .put("playing", MusicEngine.isPlaying).put("shuffle", MusicEngine.shuffle).put("mode", MusicEngine.mode)
            .put("line", line).put("haslyr", lr != null && lr.lines.isNotEmpty())))
    }

    /** (v519) تاتش باد: mv / tap / long / scroll (dx, dy نسبة من حجم التاتش باد) */
    /** (v646) مزامنة المتصفح من الموبايل: nav / scroll / tap / end — بتتنفّذ على الـUI thread */
    private fun coSurf(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        h.post { try { CoSurf.recv(j) } catch (e: Throwable) { LogStore.err("TvLink:cosurf", e) } }
        return ok()
    }

    private fun ptrCmd(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val op = j.optString("op")
        if (op.isEmpty()) return err(403, "empty")
        TvInput.ptr(op, j.optDouble("dx", 0.0).toFloat(), j.optDouble("dy", 0.0).toFloat())
        return ok(JSONObject().put("ctx", TvInput.context()).put("acc", TvAccess.connected))
    }

    /** (v522) افتح شاشة من لانشر التلفزيون (home / youtube / browser / sd / int) */
    fun showTv(w: String): Boolean {
        val a = app
        return try {
            a.startActivity(Intent(a, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tv_show", w))
            true
        } catch (e: Throwable) { LogStore.err("TvLink:show", e); false }
    }

    private val sleepRun = Runnable {
        try {
            showTv("home")
            if (!TvAccess.global(8)) notice("😴 مؤقت النوم خلص — اقفل التلفزيون من الريموت")   // 8 = GLOBAL_ACTION_LOCK_SCREEN
        } catch (e: Throwable) { LogStore.err("TvLink:sleep", e) }
    }

    /** (v522) مؤقت نوم: بعد N دقيقة يرجع للرئيسية ويقفل الشاشة (لو إمكانية الوصول شغّالة). 0 = إلغاء */
    fun setSleep(min: Int) {
        h.removeCallbacks(sleepRun)
        if (min > 0) { h.postDelayed(sleepRun, min * 60_000L); notice("😴 مؤقت النوم: $min دقيقة") } else notice("مؤقت النوم اتلغى")
    }

    /** (v662) صوت التلفزيون الحالي 0..100 (بيتبعت في /ping عشان سلايدر الريموت يبدأ من الصوت الحقيقي) */
    private fun sysVolPct(): Int = try {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / mx).coerceIn(0, 100)
    } catch (_: Throwable) { -1 }

    /** أوامر الصوت والتشغيل — بتتنفّذ على الـUI thread */
    fun exec(c: String) {
        h.post {
            try {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                when (c) {
                    "vol_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "vol_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                    "playpause" -> { if (!PlayerActivity.remoteToggle()) { if (MusicEngine.isPlaying) MusicEngine.pause() else MusicEngine.resume() } }
                    "open_yt" -> showTv("youtube")
                    "open_browser" -> showTv("browser")
                    "open_sd" -> showTv("sd")
                    "open_int" -> showTv("int")
                    "open_settings" -> showTv("settings")
                    "mic" -> showTv("mic")
                    "power" -> { if (!TvAccess.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) notice("⚠ زرار الباور محتاج تفعّل «إمكانية الوصول» لـ Subtitler") }
                    else -> if (c.startsWith("sleep:")) setSleep(c.substring(6).toIntOrNull() ?: 0)
                        else if (c.startsWith("vol_set:")) {   // (v662) سلايدر الصوت في الريموت: نسبة مطلقة 0..100
                            val p = (c.substring(8).toIntOrNull() ?: -1).coerceIn(-1, 100)
                            if (p >= 0) {
                                val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * mx / 100f).coerceIn(0, mx), 0)
                            }
                        }
                }
            } catch (e: Throwable) { LogStore.err("TvLink:exec", e) }
        }
    }

    // ===== فتح لينك على التلفزيون =====
    /** (v542) فتح رابط من BrainRpc ("load") — نفس مسار /open */
    fun openJson(j: JSONObject): Boolean =
        open(HttpReq("POST", "/open", emptyMap(), emptyMap(), j.toString().toByteArray(Charsets.UTF_8), "")).code == 200

    private fun open(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val u = j.optString("url").trim()
        val startMs = j.optLong("startms", 0L)   // (v543) كمّل من نفس المكان
        if (u.startsWith("tv://", true)) return openLocal(u.substring(5).trim(), startMs)   // (v543 م3) فيديو من مكتبة التلفزيون
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return err(403, "badurl")
        val title = j.optString("title")
        val visible = top?.get() != null
        val how = j.optString("how").ifBlank { Links.AUTO }   // (v532) video / video_tr / audio / web (الافتراضي auto)
        val lref = j.optString("ref"); val lua = j.optString("ua"); val lck = j.optString("cookie")   // (v533)
        h.post {
            try {
                val a = top?.get()
                // (v532) لو التركيز على خانة بحث (كيبورد ظاهر): نشيل التركيز ونقفل الكيبورد الأول، ونفتح من الأكتيفيتي الظاهرة نفسها بدل context التطبيق
                if (a != null) {
                    try {
                        (a.currentFocus as? android.widget.EditText)?.let { e ->
                            e.clearFocus()
                            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(e.windowToken, 0)
                        }
                    } catch (_: Throwable) {}
                }
                // (v570) على التلفزيون: يوتيوب/لينك مصطاد بيفتح المشغّل على طول (من غير صفحة يوتيوب الكبيرة)، والخروج بيرجّع للصفحة اللي كنت فيها
                if (DeviceUi.isTv(app) && TvDirect.tryOpen(a ?: app, u, how, title, lref, lua, lck, startMs)) return@post
                val ni = Intent(a ?: app, MainActivity::class.java)
                    .addFlags((if (a == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0) or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Links.EX_URL, u).putExtra(Links.EX_HOW, how).putExtra(Links.EX_TITLE, title)
                if (lref.isNotEmpty() || lua.isNotEmpty() || lck.isNotEmpty()) ni.putExtra("link_ref", lref).putExtra("link_ua", lua).putExtra("link_cookie", lck)
                if (startMs > 0L) ni.putExtra("link_startms", startMs)
                (a ?: app).startActivity(ni)
            } catch (e: Throwable) { LogStore.err("TvLink:open", e) }
        }
        return ok(JSONObject().put("visible", visible))
    }

    /** (v558) الموبايل مسح سجل المتصفح/يوتيوب عنده ← نمسح المقابل على التلفزيون: آخر المشغّلة (اللينكات بس مش ملفات الجهاز) + السجل العام + سجل المتصفح */
    private fun clearHist(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        fun isYt(x: Recent) = x.id.startsWith("yt:") || Links.ytId(x.url) != null
        when (j.optString("what")) {
            "browser" -> clearBrowserHist()
            "yt" -> {
                Recents.dropWhere(app) { isYt(it) }
                PubHistory.clear("v", "youtube")
                try { YtHistory.clear(app) } catch (e: Throwable) { LogStore.err("TvLink:clearYt", e) }
            }
            else -> return err(403, "what")
        }
        h.post { TvActivity.live?.get()?.onRemoteCleared() }
        return ok()
    }

    /** (v649) مسح سجل المتصفح + الفيديوهات اللي اتشغّلت منه (مشترك بين 🚨 الموبايل وزرار المسح) */
    fun clearBrowserHist() {
        try {
            Recents.dropWhere(app) { it.uri.isEmpty() && it.url.isNotEmpty() && !(it.id.startsWith("yt:") || Links.ytId(it.url) != null) }
            PubHistory.clear("v", "browser")
            try { BrowserStore.clearHistory(app) } catch (e: Throwable) { LogStore.err("TvLink:clearBr", e) }
            h.post { TvActivity.live?.get()?.onRemoteCleared() }
        } catch (e: Throwable) { LogStore.err("TvLink:clearBrH", e) }
    }

    /** (v678) آخر الفيديوهات المشغّلة على التلفزيون (نفس ترتيب رئيسية التلفزيون) — الموبايل بيعرضها جنب قايمته */
    private fun recentList(): HttpRes {
        val l = try { Recents.parse(java.io.File(app.filesDir, "recent.json").readText()) } catch (_: Exception) { emptyList() }
        val a = JSONArray()
        for (r in l.take(40)) a.put(JSONObject().put("id", r.id).put("title", r.title.ifBlank { Recents.titleOf(r.id) })
            .put("pos", r.posSec).put("dur", r.durSec).put("yt", Recents.ytIdOf(r) != null).put("local", r.uri.isNotEmpty()))
        return ok(JSONObject().put("items", a))
    }

    /** (v678) تشغيل عنصر من آخر المشغّلة (بالـid) — نفس سلوك الدوسة عليه في رئيسية التلفزيون */
    private fun recentPlay(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val id = j.optString("id")
        val found: Recent? = try { Recents.parse(java.io.File(app.filesDir, "recent.json").readText()).firstOrNull { x -> x.id == id } } catch (_: Exception) { null }
        val rc: Recent = found ?: return err(404, "nf")
        val yid = Recents.ytIdOf(rc)
        when {
            rc.uri.isNotEmpty() -> h.post {
                try {
                    val a = top?.get()
                    val ni = Intent(a ?: app, PlayerActivity::class.java)
                        .addFlags((if (a == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0) or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .setData(android.net.Uri.parse(rc.uri))
                        .putExtra("landscape", true).putExtra("fresh", false).putExtra("nosub", false).putExtra("autotr", false).putExtra("ask", false)
                    (a ?: app).startActivity(ni)
                } catch (e: Throwable) { LogStore.err("TvLink:recentPlay", e) }
            }
            yid != null -> openJson(JSONObject().put("url", YtNp.watchUrl(yid)).put("title", rc.title).put("how", Links.VIDEO))
            rc.url.isNotEmpty() -> openJson(JSONObject().put("url", rc.url).put("title", rc.title).put("how", Links.VIDEO))
            else -> return err(404, "nf")
        }
        return ok(JSONObject().put("title", rc.title))
    }

    /** (v543 م3) tv://<id> ← فيديو من مكتبة التلفزيون نفسه: يفتح المشغّل على ملفه (الترجمة بإيد الشخص، مش أوتوماتيك) */
    private fun openLocal(key: String, startMs: Long): HttpRes {
        val item = BrainRpc.findVideo(app, key) ?: return err(404, "nf")
        val visible = top?.get() != null
        h.post {
            try {
                val a = top?.get()
                val ni = Intent(a ?: app, PlayerActivity::class.java)
                    .addFlags((if (a == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0) or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .setData(android.net.Uri.parse(item.uri))
                    .putExtra("landscape", item.landscape).putExtra("fresh", false).putExtra("nosub", false).putExtra("autotr", false).putExtra("ask", false)
                if (startMs > 0L) ni.putExtra("startms", startMs)
                (a ?: app).startActivity(ni)
            } catch (e: Throwable) { LogStore.err("TvLink:openLocal", e) }
        }
        return ok(JSONObject().put("visible", visible).put("title", item.title))
    }

    private fun typeText(r: HttpReq): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val t = j.optString("text")
        if (t.isEmpty() && (j.optBoolean("search") || !j.has("text"))) return err(403, "empty")
        TypeServer.lastText = t; TypeServer.lastAt = System.currentTimeMillis()
        if (j.optBoolean("search")) {   // (v533) بحث من الريموت: اخرج من أي شاشة وابحث في شريط بحث اللانشر
            h.post {
                try { app.startActivity(Intent(app, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tv_show", "search").putExtra("tv_q", t)) }
                catch (e: Throwable) { LogStore.err("TvLink:search", e) }
            }
            return ok()
        }
        // (v596) كتابة فعلية: خانة في برنامجنا ← بنكتب فيها مباشرة، وغير كده بإمكانية الوصول (أي تطبيق)
        val typed = typeInApp(t) || TvAccess.typeText(t)
        if (typed) notice("⌨ " + t.take(40), 1600L) else notice("⌨ وصل نص من الموبايل — مفيش خانة كتابة مفتوحة", 2600L)
        return ok(JSONObject().put("typed", typed))
    }

    /** (v596) بيكتب في EditText المركّز عليها في الشاشة الظاهرة من البرنامج (على خيط الواجهة، مهلة 1.5 ثانية) */
    private fun typeInApp(t: String): Boolean {
        val a = top?.get() ?: return false
        val res = BooleanArray(1)
        val l = java.util.concurrent.CountDownLatch(1)
        h.post {
            try {
                val f = a.currentFocus
                if (f is android.widget.EditText) { f.setText(t); f.setSelection(f.text.length); res[0] = true }
            } catch (_: Throwable) {}
            l.countDown()
        }
        try { l.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return res[0]
    }

    // ===== نقل مفاتيح Gemini من الموبايل =====
    private fun keys(r: HttpReq, rm: Remote): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        var n = 0
        val place = j.optBoolean("place")   // (v529) النسخة الجديدة من الموبايل: أماكن المفاتيح بتتبدّل بالكامل حتى لو فاضية
        for (f in RemoteClient.KEY_FIELDS) {   // (v522) قايمة بيضاء: مفاتيح الترجمة + المزوّدين + الموديلات
            if (!j.has(f)) continue
            val v = j.optString(f)
            if (v.isNotBlank()) { Cfg.put(f, v); n++ }
            else if (place && f in RemoteClient.PLACE_FIELDS) {
                Cfg.put(f, when (f) { "nmain", "nback" -> "0"; "keymodes" -> "[]"; else -> "" }); n++
            }
        }
        if (n == 0) return err(403, "empty")
        try { ExtTranslate.builtin.forEach { ExtTranslate.clearBlock(it.id) } } catch (_: Throwable) {}   // (v522) فك أي إيقاف قديم بسبب مفتاح مرفوض
        try { KeyVault.save(app) } catch (_: Throwable) {}
        h.post { try { KeysSync.reload?.invoke() } catch (_: Throwable) {} }   // (v529) لو شاشة الإعدادات مفتوحة على التلفزيون: تتحدّث بالقيم الجديدة بدل ما تكتب القديم فوقها
        notice("🔑 وصلت مفاتيح Gemini من «" + rm.name + "»")
        return ok(JSONObject().put("n", n))
    }

    /** (v574) حساب يوتيوب من الموبايل أوتوماتيك أول ما يتصل (من غير QR): بنستقبل الجلسة (لو مختلفة عن اللي عندنا) وبنزامن الاشتراكات في الخلفية */
    private fun ytAcc(r: HttpReq, rm: Remote): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val ck = j.optString("c")
        if (ck.isBlank()) return err(403, "empty")
        try { YtAccount.init(app) } catch (_: Throwable) { }
        val fresh = !YtAccount.loggedIn() || !YtAccount.sameSession(ck)
        if (fresh) { if (!YtAccount.importCookie(ck)) return err(403, "bad") }
        if (fresh || YtAccount.syncDue(30L * 60_000L)) YtAccount.syncAsync(true)
        if (fresh) notice("▶ حساب يوتيوب وصل من «" + rm.name + "»")
        return ok(JSONObject().put("fresh", fresh))
    }

    // ===== تحديث من الموبايل =====
    private fun offer(r: HttpReq, rm: Remote): HttpRes {
        val j = try { JSONObject(r.bodyText()) } catch (_: Exception) { return err(403, "bad") }
        val ver = j.optLong("ver"); val url = j.optString("url"); val vname = j.optString("vname")
        if (!url.startsWith("http://", true)) return err(403, "badurl")
        val force = j.optBoolean("force")
        if (force) { if (ver < LinkVer.code(app)) return err(403, "older") }   // (v521) الإجباري بيعدّي نفس النسخة (إعادة تثبيت) بس أندرويد مبيسمحش بنزول لنسخة أقدم
        else if (ver <= LinkVer.code(app)) return err(403, "not_newer")
        if (!force && j.optBoolean("auto") && ver == declinedVer) return err(403, "declined")
        pendingOffer = Offer(ver, vname, url, rm.name, force, j.optLong("size", 0L))   // (v563) size = حجم الملف عند الموبايل
        val a = top?.get()
        h.post { val t = top?.get(); if (t != null && t !is LaunchRemoteActivity && t !is SendToTvActivity) offerIfAny(t) else offerOverlay() }   // (v525) أي شاشة (مشغّل/متصفح/يوتيوب…) أو تطبيق تاني قدّام
        return ok(JSONObject().put("shown", a != null || TvAccess.connected || canOverlay()))
    }

    private fun offerIfAny(a: Activity) {
        val o = pendingOffer ?: return
        if (offerShowing || a.isFinishing || a.isDestroyed) return
        if (if (o.force) o.ver < LinkVer.code(app) else o.ver <= LinkVer.code(app)) { pendingOffer = null; return }
        // (v573) من غير أي سؤال «هل تريد التحديث؟»: الملف موجود كامل ← شاشة التثبيت على طول، غير كده ← شاشة التنزيل على طول ثم التثبيت
        pendingOffer = null
        TvUpdate.download(a, o)   // بتبدأ بـreadyFile: لو الملف كامل بتفتح التثبيت من غير تنزيل
    }

    /** (v573) الإلغاء من شاشة التنزيل (غير الإجباري) ← العرض الأوتوماتيك لنفس النسخة ما يرجعش تاني */
    fun declineOffer(o: Offer) { declinedVer = o.ver }

    private fun canOverlay(): Boolean = try { android.os.Build.VERSION.SDK_INT >= 26 && android.provider.Settings.canDrawOverlays(app) } catch (_: Throwable) { false }

    /** (v525) تطبيق تاني شغّال قدّام (مفيش شاشة من برنامجنا): نافذة فوق كل حاجة بإمكانية الوصول أو صلاحية العرض فوق التطبيقات */
    private fun offerOverlay() {
        // (v573) من غير سؤال: علّم العرض «موافَق» وافتح شاشة التلفزيون؛ أول شاشة تفتح (offerIfAny) بتبدأ التنزيل/التثبيت على طول
        val o = pendingOffer ?: return
        if (o.accepted) return
        if (!(TvAccess.connected || canOverlay())) return
        o.accepted = true
        try {
            app.startActivity(Intent(app, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (e: Throwable) { LogStore.err("TvLink:overlayOpen", e) }
    }
}
