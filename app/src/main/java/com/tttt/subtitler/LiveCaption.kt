package com.tttt.subtitler

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v162) ترجمة حية فوق أي تطبيق: بنلقط صوت الجهاز (AudioPlaybackCapture — أندرويد 10+) كل 8 ثواني،
 * نبعته لجيميناي، ونعرض الترجمة في شريط عايم تقدر تسحبه. الضغطة المطولة عليه بتقفل الخدمة.
 * حدود: تأخير ~10 ثواني (مقطع + طلب)، والتطبيقات اللي بتمنع التقاط الصوت (DRM) هتطلع صمت.
 */
class LiveRequestActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT < 29) { toast("الترجمة الحية محتاجة أندرويد 10 أو أحدث"); finish(); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("فعّل «العرض فوق التطبيقات» للبرنامج وبعدين دوس تاني")
            try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            finish(); return
        }
        if (Cfg.allMainKeys().isEmpty()) { toast("ضيف مفتاح Gemini الأول"); finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 71)
            return
        }
        askProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) askProjection()
        else { toast("لازم إذن الميكروفون عشان يلقط صوت الجهاز"); finish() }
    }

    private fun askProjection() {
        val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(m.createScreenCaptureIntent(), 72)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 72 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, LiveCaptionService::class.java).putExtra("rc", resultCode).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
    }

    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
}

class LiveCaptionService : Service() {
    companion object {
        @Volatile var running = false
        private const val CH = "live_caption"
        private const val NID = 4402
        private const val SR = 44100
        private const val CHUNK_SEC = 8
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var tv: TextView? = null
    private var mp: MediaProjection? = null
    private var rec: AudioRecord? = null
    private var th: Thread? = null
    @Volatile private var alive = false
    private val pool = Executors.newFixedThreadPool(2)
    private val inflight = AtomicInteger(0)
    private val queue = java.util.ArrayDeque<Pair<String, Long>>()
    private var showing = false
    @Volatile private var lastWarn = 0L

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
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)
            startRec(p)
            addOverlay()
            running = true
            WidgetHub.poke(this)
            say("🔴 الترجمة الحية شغّالة — الضغطة المطولة على الشريط بتقفلها")
        } catch (e: Throwable) {
            Toast.makeText(this, "مقدرتش أبدأ الترجمة الحية: " + (e.message ?: "").take(80), Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "الترجمة الحية", NotificationManager.IMPORTANCE_LOW))
        val stopPi = PendingIntent.getService(this, 1, Intent(this, LiveCaptionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this)
        val n = b.setContentTitle("🔴 الترجمة الحية شغّالة").setContentText("دوس «إيقاف» لقفلها")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NID, n)
    }

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
        val chunk = SR * CHUNK_SEC
        val buf = ShortArray(chunk)
        val tmp = ShortArray(4096)
        var n = 0
        var zeroRun = 0
        var ki = 0
        try {
            while (alive) {
                val got = r.read(tmp, 0, tmp.size)
                if (got <= 0) { Thread.sleep(40); continue }
                val take = minOf(got, chunk - n)
                System.arraycopy(tmp, 0, buf, n, take)
                n += take
                if (n < chunk) continue
                n = 0
                val rms = rms(buf)
                if (rms < 1.0) {
                    zeroRun++
                    if (zeroRun >= 3 && System.currentTimeMillis() - lastWarn > 60_000L) {
                        lastWarn = System.currentTimeMillis()
                        say("🔇 مفيش صوت ملتقط — ممكن التطبيق مانع التقاط الصوت (نتفليكس وتطبيقات DRM بتمنعه)")
                    }
                    continue
                }
                zeroRun = 0
                if (rms < 30.0) continue   // صمت تقريبًا: مانبعتش
                if (inflight.get() >= 3) continue
                val wav = toWav16k(buf)
                val myKi = ki++
                inflight.incrementAndGet()
                pool.execute { try { translate(wav, myKi) } catch (_: Throwable) {} finally { inflight.decrementAndGet() } }
            }
        } catch (_: InterruptedException) {}
        catch (_: Throwable) {}
    }

    private fun rms(b: ShortArray): Double {
        var s = 0.0
        for (v in b) s += v.toDouble() * v.toDouble()
        return Math.sqrt(s / b.size)
    }

    /** 44.1kHz mono → WAV 16kHz mono 16-bit (أصغر في الرفع) */
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
            val i0 = p.toInt()
            val f = p - i0
            val a = src[minOf(i0, n - 1)].toInt()
            val c = src[minOf(i0 + 1, n - 1)].toInt()
            val v = (a + (c - a) * f).toInt()
            out[44 + i * 2] = v.toByte(); out[45 + i * 2] = (v shr 8).toByte()
        }
        return out
    }

    private fun translate(wav: ByteArray, ki: Int) {
        val keys = Cfg.allMainKeys()
        if (keys.isEmpty()) return
        val model = Cfg.str("model", Models.DEFAULT).trim().ifEmpty { Models.DEFAULT }
        val lang = Cfg.str("lang", "فصحى"); val style = Cfg.str("style", "حرفي")
        val prompt = "ده مقطع صوت حوالي $CHUNK_SEC ثواني مسجّل من فيديو شغّال على الجهاز. اكتب ترجمة عربية بلهجة $lang (الأسلوب: $style) لأي كلام مسموع فيه، " +
            "كجمل قصيرة مناسبة لعرضها كترجمة فورية (حد أقصى حوالي 12 كلمة للسطر). تجاهل الموسيقى والمؤثرات والكلام غير المفهوم، وماتألفش حاجة مش مسموعة.\n" +
            "لو مفيش كلام: {\"lines\":[]}\nJSON فقط: {\"lines\":[{\"start\":0.0,\"end\":2.5,\"text\":\"...\"}]}"
        for (n in 0 until minOf(keys.size, 3)) {
            val key = keys[(ki + n) % keys.size]
            try {
                val res = Api.generate(model, key, prompt, wav, 1500, 0.1)
                val j = Parse.json(res.text) ?: continue
                val arr = j.optJSONArray("lines") ?: return
                for (q in 0 until arr.length()) {
                    val o = arr.optJSONObject(q) ?: continue
                    val t = o.optString("text").trim()
                    if (t.isEmpty()) continue
                    val dur = (o.optDouble("end", 2.0) - o.optDouble("start", 0.0)).let { if (it.isNaN()) 2.0 else it }
                    val ms = ((dur + 0.5).coerceIn(1.6, 5.0) * 1000).toLong()
                    main.post { enqueue(t, ms) }
                }
                return
            } catch (e: ApiErr) {
                if (e.code == 429) continue else return
            } catch (_: java.io.IOException) { continue }
        }
    }

    // ===== العرض =====

    private fun addOverlay() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val t = TextView(this)
        t.setTextColor(Color.WHITE); t.textSize = 18f; t.gravity = Gravity.CENTER; t.maxLines = 3
        t.layoutDirection = View.LAYOUT_DIRECTION_RTL
        t.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        val d = resources.displayMetrics.density
        t.setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        val bg = GradientDrawable(); bg.setColor(0xB3000000.toInt()); bg.cornerRadius = 14 * d
        t.background = bg
        t.text = "🔴 الترجمة الحية شغّالة…"
        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = (120 * d).toInt()
        var startY = 0; var startTouch = 0f
        t.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = lp.y; startTouch = ev.rawY }
                MotionEvent.ACTION_MOVE -> { lp.y = (startY + (startTouch - ev.rawY)).toInt().coerceAtLeast(0); try { w.updateViewLayout(t, lp) } catch (_: Exception) {} }
            }
            false
        }
        t.setOnLongClickListener { stopSelf(); true }
        w.addView(t, lp)
        tv = t
        main.postDelayed({ if (queue.isEmpty() && !showing) tv?.visibility = View.GONE }, 3000)
    }

    private fun say(m: String) { main.post { enqueue(m, 3500L) } }

    private fun enqueue(text: String, ms: Long) {
        while (queue.size >= 3) queue.pollFirst()
        queue.addLast(text to ms)
        if (!showing) next()
    }

    private fun next() {
        val item = queue.pollFirst()
        val t = tv ?: return
        if (item == null) { showing = false; t.visibility = View.GONE; return }
        showing = true
        t.text = item.first
        t.visibility = View.VISIBLE
        main.postDelayed({ next() }, item.second)
    }

    override fun onDestroy() {
        alive = false; running = false
        WidgetHub.poke(this)
        try { th?.interrupt() } catch (_: Exception) {}
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        try { tv?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { pool.shutdownNow() } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
