package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File

/** بيسجّل سبب أي كراش (مع آخر سطور اللوج) في ملف، ويعرضه في أول فتح بعدها عشان نعرف نصلّحه */
object CrashLog {
    private var installed = false
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")
    private fun seen(c: Context) = File(c.filesDir, "last_crash_seen.txt")
    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        LogStore.init(app)
        val f = file(app)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val st = android.util.Log.getStackTraceString(e).take(6000)
                LogStore.add("💥 CRASH في thread ${t.name} · ${LogStore.heapLine()}\n$st")
                f.writeText("thread: ${t.name}\n$st\n\n— آخر سطور اللوج —\n" + LogStore.lastLines(LogStore.currentText(20000), 40))
            } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
    fun showIfAny(a: Activity) {
        var crash = ""
        val f = file(a)
        if (f.exists()) {
            crash = try { f.readText() } catch (_: Exception) { "" }
            try { f.copyTo(seen(a), true) } catch (_: Exception) {}
            f.delete()
        }
        val ex = LogStore.lastExit(a)
        val seenTs = Cfg.str("exit_seen_ts", "0").toLongOrNull() ?: 0L
        val showEx = ex != null && ex.abnormal && ex.ts > seenTs
        if (ex != null) Cfg.put("exit_seen_ts", ex.ts.toString())
        if (crash.isBlank() && !showEx) return
        val prevTail = LogStore.lastLines(LogStore.prevText(40000), 30)
        val shown = (if (showEx) ex!!.text + "\n\n" else "") + (if (crash.isNotBlank()) crash.take(1500) + "\n\n" else "") +
            (if (crash.isBlank() && prevTail.isNotBlank()) "— آخر سطور اللوج قبل ما يقفل —\n$prevTail" else "")
        val full = LogStore.header(a) + "\n\n" + (if (showEx) ex!!.text + "\n\n" else "") + crash + "\n\n— اللوج الكامل للجلسة اللي فاتت —\n" + LogStore.prevText(60000)
        try {
            GAlert(a).setTitle("⚠ التطبيق قفل المرة اللي فاتت").setMessage(shown.take(3500))
                .setPositiveButton("📋 نسخ") { _, _ ->
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", full))
                    Notice.show(a, ("اتنسخ — ابعته لي").toString(), 2300L)
                }.setNegativeButton("إغلاق", null).show()
        } catch (_: Exception) {}
    }
}
