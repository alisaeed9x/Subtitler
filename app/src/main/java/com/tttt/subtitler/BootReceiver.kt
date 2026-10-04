package com.tttt.subtitler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** بعد إعادة تشغيل الجهاز (أو تحديث التطبيق): لو فيه طابور ترجمة محفوظ والتشغيل التلقائي مفعّل، شغّل الخدمة تكمّل الترجمة */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Cfg.init(c.applicationContext)
        if (!Cfg.bool("bg_autostart", true)) return
        if (BgJobs.restore(c) > 0) BgService.start(c)
    }
}
