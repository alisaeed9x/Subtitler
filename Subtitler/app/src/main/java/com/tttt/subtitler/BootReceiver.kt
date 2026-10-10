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
        // أندرويد 14/15 ممكن يمنع تشغيل خدمة dataSync من BOOT_COMPLETED — ماننهارش لو اترفض
        try { LogStore.init(c.applicationContext) } catch (_: Exception) {}
        try {
            val n = BgJobs.restore(c)
            LogStore.add("🔁 بعد الريستارت/التحديث: طابور الخلفية فيه $n مهمة")
            if (n > 0) BgService.start(c)
        } catch (e: Exception) { LogStore.err("BootReceiver", e); LogStore.add("⚠ الخدمة ماقدرتش تشتغل من الريستارت — هتكمّل لما تفتح البرنامج") }
    }
}
