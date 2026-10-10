package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** (v190) محرك الموسيقى: مشغّل واحد للعملية كلها (مش مربوط بالشاشة) — بيكمّل شغل لما تخرج من التطبيق لأن MusicService بتخلّي العملية شغّالة */
object MusicEngine {
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var mp: MediaPlayer? = null
    var queue: List<Track> = emptyList(); private set
    var idx = -1; private set
    @Volatile var ready = false; private set
    private var wantPlay = false
    private var focusReq: AudioFocusRequest? = null
    private var resumeOnFocus = false
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    val current: Track? get() = queue.getOrNull(idx)
    val sessionId: Int get() = try { mp?.audioSessionId ?: 0 } catch (_: Throwable) { 0 }
    val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (_: Throwable) { false }
    val posMs: Int get() = try { if (ready) mp?.currentPosition ?: 0 else 0 } catch (_: Throwable) { 0 }
    val durMs: Int get() = try { if (ready) mp?.duration ?: 0 else (current?.durMs ?: 0L).toInt() } catch (_: Throwable) { 0 }

    private fun changed() { main.post { for (l in listeners) { try { l() } catch (_: Throwable) {} }; MusicService.refresh() } }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { c ->
        when (c) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnFocus = isPlaying; pause() }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) { resumeOnFocus = false; resume() }
        }
    }
    private fun gainFocus() {
        val a = app ?: return
        try {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener).build()
            focusReq = r; am.requestAudioFocus(r)
        } catch (_: Throwable) {}
    }
    private fun dropFocus() {
        val a = app ?: return
        try { focusReq?.let { (a.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) } } catch (_: Throwable) {}
        focusReq = null
    }

    fun play(ctx: Context, q: List<Track>, i: Int) {
        if (i !in q.indices) return
        app = ctx.applicationContext
        queue = q; idx = i; ready = false; wantPlay = true
        release()
        MusicService.start(ctx.applicationContext)
        gainFocus()
        val t = q[i]
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setWakeMode(ctx.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(ctx.applicationContext, Uri.parse(t.uri))
                setOnPreparedListener { ready = true; if (wantPlay) try { it.start() } catch (_: Throwable) {}; changed() }
                setOnCompletionListener { step(1) }
                setOnErrorListener { _, _, _ -> ready = false; main.post { step(1) }; true }
                prepareAsync()
            }
        } catch (e: Exception) { LogStore.err("music-play", e) }
        changed()
    }
    fun step(d: Int) {
        if (queue.isEmpty()) return
        val a = app ?: return
        val n = idx + d
        if (n in queue.indices) play(a, queue, n) else if (d > 0) play(a, queue, 0)   // آخر أغنية → يلف على الأولى
    }
    fun toggle() { if (isPlaying) pause() else resume() }
    fun resume() {
        val m = mp
        if (m == null) { app?.let { if (queue.isNotEmpty()) play(it, queue, idx.coerceAtLeast(0)) }; return }
        wantPlay = true; gainFocus()
        if (ready) try { m.start() } catch (_: Throwable) {}
        changed()
    }
    fun pause() { wantPlay = false; try { if (mp?.isPlaying == true) mp?.pause() } catch (_: Throwable) {}; changed() }
    fun seekTo(ms: Int) { if (ready) try { mp?.seekTo(ms) } catch (_: Throwable) {}; changed() }
    private fun release() { try { mp?.release() } catch (_: Throwable) {}; mp = null; ready = false }
    /** إيقاف نهائي (زرار ✕ في الإشعار) */
    fun stopAll() {
        wantPlay = false; release(); dropFocus(); queue = emptyList(); idx = -1
        app?.let { MusicService.stop(it) }
        changed()
    }
}

/** (v190) خدمة foreground لتشغيل الموسيقى في الخلفية: إشعار تحكم (⏮ ⏯ ⏭ ✕) + MediaSession (أزرار السماعة والشاشة المقفولة) */
class MusicService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var recv: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        try {
            val s = MediaSession(this, "subtitler-music")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { MusicEngine.resume() }
                override fun onPause() { MusicEngine.pause() }
                override fun onSkipToNext() { MusicEngine.step(1) }
                override fun onSkipToPrevious() { MusicEngine.step(-1) }
                override fun onSeekTo(pos: Long) { MusicEngine.seekTo(pos.toInt()) }
                override fun onStop() { MusicEngine.stopAll() }
            })
            s.isActive = true
            session = s
        } catch (e: Throwable) { LogStore.err("music-session", e) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { MusicEngine.pause(); return }
                when (i?.getStringExtra("cmd")) {
                    "prev" -> MusicEngine.step(-1); "next" -> MusicEngine.step(1); "toggle" -> MusicEngine.toggle()
                    "close" -> MusicEngine.stopAll()
                    "noisy" -> MusicEngine.pause()
                }
            }
        }
        recv = r
        try {
            val f = IntentFilter(ACT); f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        } catch (e: Throwable) { LogStore.err("music-recv", e) }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    private fun pi(cmd: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(this, 100 + code, Intent(ACT).setPackage(packageName).putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(res: Int, label: String, cmd: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, res), label, pi(cmd, code)).build()

    private fun clock(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "تشغيل الموسيقى", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val t = MusicEngine.current
        val playing = MusicEngine.isPlaying
        val dur = MusicEngine.durMs; val pos = MusicEngine.posMs
        val title = t?.title ?: "الموسيقى"
        val sub = (if (!t?.artist.isNullOrBlank()) t!!.artist + " · " else "") + (if (dur > 0) "${clock(pos)} / ${clock(dur)}" else "")
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "music"), PendingIntent.FLAG_IMMUTABLE)
        try {
            session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "").putLong(MediaMetadata.METADATA_KEY_DURATION, dur.toLong().coerceAtLeast(0)).build())
            session?.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos.toLong(), if (playing) 1f else 0f).build())
        } catch (_: Throwable) {}
        val b = Notification.Builder(this, CH)
            .setContentTitle(title).setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open).setOngoing(playing).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "السابق", "prev", 1))
            .addAction(act(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "إيقاف" else "تشغيل", "toggle", 2))
            .addAction(act(android.R.drawable.ic_media_next, "التالي", "next", 3))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "إغلاق", "close", 4))
        val ms = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        try { session?.sessionToken?.let { ms.setMediaSession(it) } } catch (_: Throwable) {}
        b.setStyle(ms)
        return b.build()
    }

    private fun refreshNow() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) } catch (e: Throwable) { LogStore.err("music-notif", e) }
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        try { recv?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        try { session?.isActive = false; session?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val CH = "music_ctl"
        private const val ID = 78
        const val ACT = "com.tttt.subtitler.MUSIC_CMD"
        @Volatile private var inst: MusicService? = null
        fun start(c: Context) { try { c.startForegroundService(Intent(c, MusicService::class.java)) } catch (_: Exception) {} }
        fun stop(c: Context) { try { c.stopService(Intent(c, MusicService::class.java)) } catch (_: Exception) {} }
        fun refresh() { val s = inst ?: return; s.main.post { s.refreshNow() } }
    }
}
