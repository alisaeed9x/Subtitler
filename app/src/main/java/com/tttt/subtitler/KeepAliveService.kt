package com.tttt.subtitler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** خدمة foreground بسيطة: بتخلّي العملية شغالة والترجمة تكمّل لما الشاشة تتقفل أو التطبيق يروح الخلفية.
 *  المحرك نفسه لسه جوه PlayerActivity — الخدمة بس بتمنع النظام يقتل العملية. */
class KeepAliveService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "الترجمة شغالة", NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("مترجم الفيديو")
            .setContentText("الترجمة شغالة في الخلفية")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CH = "keep_alive"
        private const val ID = 77
        fun start(c: Context) {
            try { c.startForegroundService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
        fun stop(c: Context) {
            try { c.stopService(Intent(c, KeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }
}
