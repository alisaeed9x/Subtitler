package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * (v197) «🎧 تعرّف على الأغنية» — ويدجت خارجية (زي ويدجت الترجمة الحية): بتسمع أي صوت شغّال على الجهاز من أي تطبيق،
 * أول ما تعرف اسم الأغنية بتطلّع كارت عايم فيه لينكات تشغّلها (YouTube · Spotify · Deezer · Apple Music · أنغامي · SoundCloud · جوجل).
 * مصدران للاسم:
 *  1) (الأدق والأسرع) اسم الأغنية اللي التطبيق التاني نفسه بيعرضه (MediaSession) — محتاج «الوصول للإشعارات» للتطبيق (مرة واحدة).
 *  2) لو مفيش: بنلقط صوت الجهاز (AudioPlaybackCapture) ونبعت مقطع ~15ث لـ AudD (لو فيه توكن) أو جيميناي.
 * حدود: تطبيقات DRM/اللي مانعة الالتقاط بتطلع صمت (الاسم من المصدر الأول بيفضل شغّال معاها).
 */
class SongRequestActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT < 29) { toast("التعرف على الأغاني محتاج أندرويد 10 أو أحدث"); finish(); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("فعّل «العرض فوق التطبيقات» للبرنامج وبعدين دوس تاني")
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            finish(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 81)
            return
        }
        askProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 81 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) askProjection()
        else { toast("لازم إذن الميكروفون عشان يلقط صوت الجهاز"); finish() }
    }

    private fun askProjection() {
        val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(m.createScreenCaptureIntent(), 82)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 82 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, SongCatchService::class.java).putExtra("rc", resultCode).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
    }

    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
}

/** مكوّن فاضي: أندرويد بيشترط وجوده (ومنحه «الوصول للإشعارات») عشان نقرا جلسات الميديا للتطبيقات التانية */
class SongNotifListener : NotificationListenerService()

class SongCatchService : Service() {
    companion object {
        @Volatile var running = false
        private const val CH = "song_catch"
        private const val NID = 4403
        private const val SR = 44100
        private const val RING = SR * 15
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var card: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var head: TextView? = null
    private var sub: TextView? = null
    private var body: com.tttt.subtitler.FlowRow? = null
    private var minBtn: TextView? = null
    private var mp: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var th: Thread? = null
    @Volatile private var alive = false

    private val ring = ShortArray(RING)
    private var wpos = 0
    private var filled = 0
    @Volatile private var lastMetaAt = 0L
    @Volatile private var idBusy = false
    @Volatile private var nextAudioIdAt = 0L
    private var fails = 0
    private var lastKey = ""
    private var expanded = false
    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startFg()
        val rc = intent.getIntExtra("rc", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = m.getMediaProjection(rc, data) ?: throw IllegalStateException("no projection")
            mp = p
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { main.post { stopSelf() } } }, main)
            startRec(p)
            addCard()
            running = true
            WidgetHub.poke(this)
            main.postDelayed({ pollSessions() }, 800)
        } catch (e: Throwable) {
            Toast.makeText(this, "مقدرتش أبدأ التعرف على الأغاني: " + (e.message ?: "").take(80), Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "التعرف على الأغاني", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 2, Intent(this, SongCatchService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        val n = b.setContentTitle("🎧 بسمع الأغاني الشغّالة").setContentText("دوس «إيقاف» لقفله")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

    // ===== مصدر 1: اسم الأغنية من جلسة الميديا للتطبيق التاني =====
    private fun listenerOn(): Boolean = try {
        (android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").contains(packageName)
    } catch (_: Throwable) { false }

    private fun pollSessions() {
        if (!running && !alive) return
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(this, SongNotifListener::class.java))
            for (c in list) {
                if (c.packageName == packageName) continue
                if (c.playbackState?.state != PlaybackState.STATE_PLAYING) continue
                val md = c.metadata ?: continue
                val t = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
                val a = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "").trim()
                if (t.isEmpty()) continue
                lastMetaAt = System.currentTimeMillis()
                val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Throwable) { c.packageName }
                onSong(t, a, "📱 من $app")
                break
            }
        } catch (_: SecurityException) {
        } catch (_: Throwable) {}
        main.postDelayed({ pollSessions() }, 2500)
    }

    // ===== مصدر 2: صوت الجهاز =====
    private fun startRec(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, SR * 2)).setAudioPlaybackCaptureConfig(cfg).build()
        rec = r
        r.startRecording()
        alive = true
        th = Thread { captureLoop(r) }.also { it.isDaemon = true; it.start() }
    }

    private fun captureLoop(r: AudioRecord) {
        val tmp = ShortArray(2048)
        var lastCheck = 0L
        try {
            while (alive) {
                val got = r.read(tmp, 0, tmp.size)
                if (got <= 0) { Thread.sleep(30); continue }
                synchronized(ring) {
                    var i = 0
                    while (i < got) {
                        val n = minOf(got - i, RING - wpos)
                        System.arraycopy(tmp, i, ring, wpos, n)
                        wpos = (wpos + n) % RING; i += n
                    }
                    filled = minOf(RING, filled + got)
                }
                val now = System.currentTimeMillis()
                if (now - lastCheck > 4000) { lastCheck = now; maybeIdentify(now) }
            }
        } catch (_: InterruptedException) {} catch (_: Throwable) {}
    }

    private fun audible(a: ShortArray): Boolean {
        val w = SR / 2; var good = 0; var n = 0; var i = 0
        while (i + w <= a.size) {
            var s = 0.0
            for (k in i until i + w step 8) { val v = a[k].toDouble(); s += v * v }
            if (Math.sqrt(s / (w / 8)) > 300) good++
            n++; i += w
        }
        return n > 0 && good * 10 >= n * 6
    }

    private fun maybeIdentify(now: Long) {
        if (idBusy || now < nextAudioIdAt || filled < RING) return
        if (now - lastMetaAt < 25_000L) return          // الاسم جاي من التطبيق نفسه — مش محتاجين نبعت صوت
        val snap = synchronized(ring) {
            val a = ShortArray(RING)
            System.arraycopy(ring, wpos, a, 0, RING - wpos); System.arraycopy(ring, 0, a, RING - wpos, wpos)
            a
        }
        if (!audible(snap)) return
        idBusy = true
        main.post { setHead("🎧 بسمع الأغنية…", "بحاول أعرفها من الصوت") }
        val wav = toWav16k(snap)
        SongId.autoClip(wav, emptyList()) { m, msg ->
            main.post {
                idBusy = false
                if (!running) return@post
                if (m != null) { fails = 0; nextAudioIdAt = System.currentTimeMillis() + 60_000L; onSong(m.title, m.artist, "🎧 اتعرفت من الصوت") }
                else { fails++; nextAudioIdAt = System.currentTimeMillis() + (if (fails >= 3) 120_000L else 25_000L); setHead("🤷 مش قادر أعرف الأغنية", msg.take(70)) }
            }
        }
    }

    private fun toWav16k(src: ShortArray): ByteArray {
        val n = src.size
        val outN = (n.toLong() * 16000L / SR).toInt()
        val out = ByteArray(44 + outN * 2)
        fun w32(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        fun w16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(out, 0); w32(4, 36 + outN * 2)
        "WAVE".toByteArray().copyInto(out, 8); "fmt ".toByteArray().copyInto(out, 12)
        w32(16, 16); w16(20, 1); w16(22, 1); w32(24, 16000); w32(28, 32000); w16(32, 2); w16(34, 16)
        "data".toByteArray().copyInto(out, 36); w32(40, outN * 2)
        val ratio = SR / 16000.0
        for (i in 0 until outN) {
            val p = i * ratio
            val i0 = p.toInt(); val f = p - i0
            val a = src[minOf(i0, n - 1)].toInt(); val c = src[minOf(i0 + 1, n - 1)].toInt()
            val v = (a + (c - a) * f).toInt().coerceIn(-32768, 32767)
            out[44 + i * 2] = v.toByte(); out[45 + i * 2] = (v shr 8).toByte()
        }
        return out
    }

    // ===== أغنية اتعرفت =====
    private fun onSong(title: String, artist: String, source: String) {
        val key = (title + "|" + artist).lowercase().trim()
        if (key == lastKey) return
        lastKey = key
        setHead("🎵 " + title + (if (artist.isNotBlank()) " — $artist" else ""), "$source · بدوّر على لينكات…")
        body?.removeAllViews()
        SongLinks.quick(title, artist).forEach { body?.addView(chip(it)) }
        expand(true)
        SongLinks.exact(title, artist, { l -> main.post { if (key == lastKey && running) addExact(l, title, artist) } }, {
            main.post { if (key == lastKey && running) sub?.text = "$source · اضغط لينك يشغّلها · الضغطة المطولة تنسخه" }
        })
    }

    private var ytLink: String? = null
    private fun addExact(l: SongLink, title: String, artist: String) {
        val b = body ?: return
        var pos = 0
        // اللينكات المباشرة في الأول (بترتيب وصولها)
        for (i in 0 until b.childCount) { if ((b.getChildAt(i).tag as? SongLink)?.exact == true) pos = i + 1 }
        b.addView(chip(l), pos)
        if (l.kind == "yt") {
            ytLink = l.url
            b.addView(chip(SongLink("📥 افتح في برنامجي", l.url, true, "app")), pos + 1)
        }
    }

    private fun chip(l: SongLink): TextView = TextView(this).apply {
        tag = l
        text = l.label; setTextColor(Color.WHITE); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(if (l.exact) 0x665B9DFF else 0x33FFFFFF); cornerRadius = dp(18).toFloat() }
        layoutParams = ViewGroupMargin.lp(dp(3))
        setOnClickListener { openLink(l) }
        setOnLongClickListener {
            try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", l.url)); Toast.makeText(this@SongCatchService, "اتنسخ اللينك", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            true
        }
    }

    private fun openLink(l: SongLink) {
        try {
            val i = if (l.kind == "app")
                Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, l.url)
            else Intent(Intent.ACTION_VIEW, Uri.parse(l.url))
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) { Toast.makeText(this, "مفيش تطبيق يفتح اللينك ده", Toast.LENGTH_SHORT).show() }
    }

    // ===== الكارت العايم =====
    private object ViewGroupMargin {
        fun lp(m: Int) = android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(m, m, m, m) }
    }

    private fun setHead(t: String, s: String) { head?.text = t; sub?.text = s }

    private fun expand(on: Boolean) {
        expanded = on
        body?.visibility = if (on) View.VISIBLE else View.GONE
        minBtn?.text = if (on) "⌄" else "⌃"
    }

    private fun addCard() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(0xEB101018.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x40FFFFFF) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val h = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14.5f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; text = "🎧 بسمع الأغاني…" }
        val mb = TextView(this).apply { setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0); text = "⌃"; setOnClickListener { expand(!expanded) } }
        val sb = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(4), 0); text = "⏹"; setOnClickListener { stopSelf() } }
        row.addView(h, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(mb, LinearLayout.LayoutParams(-2, -2)); row.addView(sb, LinearLayout.LayoutParams(-2, -2))
        val s = TextView(this).apply { setTextColor(0xFFB4B7BF.toInt()); textSize = 11.5f; maxLines = 2; text = "شغّل أغنية في أي تطبيق وهعرفها" }
        val b = com.tttt.subtitler.FlowRow(this).apply { visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        c.addView(row, LinearLayout.LayoutParams(-1, -2)); c.addView(s, LinearLayout.LayoutParams(-1, -2)); c.addView(b, LinearLayout.LayoutParams(-1, -2))
        head = h; sub = s; body = b; minBtn = mb; card = c
        if (!listenerOn()) {
            s.text = "شغّل أغنية في أي تطبيق وهعرفها من الصوت. لأدق وأسرع اسم فعّل «الوصول للإشعارات» ↓"
            b.addView(chip(SongLink("🔔 فعّل قراءة اسم الأغنية", "", false, "notif")).apply {
                setOnClickListener { try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {} }
            })
            expand(true)
            main.postDelayed({ if (lastKey.isEmpty() && running) expand(false) }, 9000)
        }
        val p = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(70)
        lp = p
        // سحب الكارت من الهيدر
        var startY = 0; var startTouch = 0f
        row.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = p.y; startTouch = ev.rawY }
                MotionEvent.ACTION_MOVE -> { p.y = (startY + (ev.rawY - startTouch)).toInt().coerceAtLeast(0); try { w.updateViewLayout(c, p) } catch (_: Exception) {} }
            }
            false
        }
        h.setOnClickListener { expand(!expanded) }
        w.addView(c, p)
    }

    override fun onDestroy() {
        alive = false; running = false
        WidgetHub.poke(this)
        try { th?.interrupt() } catch (_: Exception) {}
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        try { card?.let { wm?.removeView(it) } } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
