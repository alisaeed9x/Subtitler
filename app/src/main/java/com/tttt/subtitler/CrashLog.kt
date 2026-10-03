package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.io.File

/** بيسجّل سبب أي كراش في ملف، ويعرضه في أول فتح بعدها عشان نعرف نصلّحه */
object CrashLog {
    private var installed = false
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")
    fun install(c: Context) {
        if (installed) return
        installed = true
        val f = file(c.applicationContext)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText("thread: ${t.name}\n" + android.util.Log.getStackTraceString(e).take(6000)) } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }
    }
    fun showIfAny(a: Activity) {
        val f = file(a)
        if (!f.exists()) return
        val txt = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (txt.isBlank()) return
        try {
            AlertDialog.Builder(a).setTitle("⚠ التطبيق قفل المرة اللي فاتت").setMessage(txt.take(2500))
                .setPositiveButton("نسخ") { _, _ ->
                    (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("crash", txt))
                    Toast.makeText(a, "اتنسخ — ابعته لي", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("إغلاق", null).show()
        } catch (_: Exception) {}
    }
}
