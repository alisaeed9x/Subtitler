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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.ref.WeakReference

/** حالة المشغّل اللي بتظهر في الإشعار (المشغّل بيحدّثها، والخدمة بتعرضها) */
object NotifState {
    @Volatile var title = ""
    @Volatile var posMs = 0L
    @Volatile var durMs = 0L
    @Volatile var playing = false
    @Volatile var trPct = 0
    /** 0 = الترجمة ماابتدتش · 1 = شغالة · 2 = موقوفة */
    @Volatile var trState = 0
}

/** جسر بين الإشعار والمشغّل: الأوامر (السابق/التالي/تشغيل/سيك/ترجمة/خروج) بتروح للمشغّل الحي */
object PlayerRemote {
    @Volatile var act: WeakReference<PlayerActivity>? = null
    fun send(cmd: String, arg: Long = 0L) { act?.get()?.remote(cmd, arg) }
}

/** خدمة foreground: بتخلّي العملية شغالة والترجمة تكمّل لما الشاشة تتقفل، وبتعرض إشعار تحكم (اسم الفيديو · الوقت · شريط تقدم · أزرار) */
class KeepAliveService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var recv: BroadcastReceiver? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        try {
            val s = MediaSession(this, "subtitler")
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() { PlayerRemote.send("toggle") }
                override fun onPause() { PlayerRemote.send("toggle") }
                override fun onSkipToNext() { PlayerRemote.send("next") }
                override fun onSkipToPrevious() { PlayerRemote.send("prev") }
                override fun onSeekTo(pos: Long) { PlayerRemote.send("seek", pos) }
            })
            s.isActive = true
            session = s
        } catch (e: Throwable) { LogStore.err("notif-session", e) }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { PlayerRemote.send(i?.getStringExtra("cmd") ?: return) }
        }
        recv = r
        try {
            val f = IntentFilter(ACT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
        } catch (e: Throwable) { LogStore.err("notif-recv", e) }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    private fun pi(cmd: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(this, code, Intent(ACT).setPackage(packageName).putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(res: Int, label: String, cmd: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, res), label, pi(cmd, code)).build()

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun build(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "تحكم المشغّل والترجمة", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val st = NotifState
        val title = st.title.ifBlank { "مترجم الفيديو" }
        val trTxt = when (st.trState) { 1 -> "الترجمة ${st.trPct}%"; 2 -> "الترجمة موقوفة ${st.trPct}%"; else -> "الترجمة ماابتدتش" }
        val timeTxt = if (st.durMs > 0) "${clock(st.posMs)} / ${clock(st.durMs)}" else clock(st.posMs)
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE)
        try {
            session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).putLong(MediaMetadata.METADATA_KEY_DURATION, st.durMs.coerceAtLeast(0)).build())
            session?.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, st.posMs, if (st.playing) 1f else 0f).build())
        } catch (_: Throwable) {}
        val b = Notification.Builder(this, CH)
            .setContentTitle(title)
            .setContentText("$timeTxt · $trTxt")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "السابق", "prev", 1))
            .addAction(act(if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (st.playing) "إيقاف" else "تشغيل", "toggle", 2))
            .addAction(act(android.R.drawable.ic_media_next, "التالي", "next", 3))
            .addAction(act(android.R.drawable.ic_popup_sync, if (st.trState == 1) "وقّف الترجمة" else "ترجم من هنا", "tr", 4))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, "خروج", "exit", 5))
        val ms = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        try { session?.sessionToken?.let { ms.setMediaSession(it) } } catch (_: Throwable) {}
        b.setStyle(ms)
        if (Build.VERSION.SDK_INT < 33 && st.durMs > 0) b.setProgress(st.durMs.toInt().coerceAtLeast(1), st.posMs.toInt().coerceIn(0, st.durMs.toInt()), false)
        return b.build()
    }

    private fun refreshNow() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build()) } catch (e: Throwable) { LogStore.err("notif-upd", e) }
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        try { recv?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        try { session?.isActive = false; session?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val CH = "keep_alive_ctl"
        private const val ID = 77
        const val ACT = "com.tttt.subtitler.NOTIF_CMD"
        @Volatile private var inst: KeepAliveService? = null
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
        fun stop(c: Context) {
            try { c.stopService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
        /** المشغّل بيناديها (كل ثانية تقريبًا) بعد ما يحدّث NotifState */
        fun refresh() { val s = inst ?: return; s.main.post { s.refreshNow() } }
    }
}
