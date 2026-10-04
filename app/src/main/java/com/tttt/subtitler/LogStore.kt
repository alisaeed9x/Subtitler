package com.tttt.subtitler

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * لوج دايم على القرص: كل سطر بيتكتب فورًا (مش بيتحفظ في الذاكرة بس)، فلو البرنامج قفل فجأة
 * آخر السطور بتفضل موجودة. اللوج الحالي = session_log.txt، ولما جلسة جديدة تبدأ القديم بيتنقل لـ prev_log.txt.
 */
object LogStore {
    private val lock = Any()
    private var dir: File? = null
    private var out: FileOutputStream? = null
    private var size = 0L
    private const val MAX = 400_000L
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(c: Context) {
        synchronized(lock) {
            if (dir != null) return
            val d = c.applicationContext.filesDir; dir = d
            val cf = File(d, "session_log.txt"); val pf = File(d, "prev_log.txt")
            try { if (cf.exists() && cf.length() > 0) cf.copyTo(pf, true) } catch (_: Exception) {}
            try { out = FileOutputStream(cf, false); size = 0 } catch (_: Exception) {}
            add("🚀 جلسة جديدة · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        }
    }

    fun add(s: String) {
        synchronized(lock) {
            val o = out ?: return
            try {
                val b = (fmt.format(Date()) + "  " + s + "\n").toByteArray(Charsets.UTF_8)
                o.write(b); o.flush(); size += b.size
                if (size > MAX) trim()
            } catch (_: Exception) {}
        }
    }

    private fun trim() {
        val d = dir ?: return
        val cf = File(d, "session_log.txt")
        try {
            out?.close()
            val all = cf.readBytes()
            var from = (all.size - (MAX / 2).toInt()).coerceAtLeast(0)
            while (from < all.size && all[from] != '\n'.code.toByte()) from++
            val keep = all.copyOfRange((from + 1).coerceAtMost(all.size), all.size)
            out = FileOutputStream(cf, false); out!!.write(keep); out!!.flush(); size = keep.size.toLong()
        } catch (_: Exception) { try { out = FileOutputStream(cf, true) } catch (_: Exception) {} }
    }

    private fun tail(f: File, max: Int): String = try { if (f.exists()) f.readText(Charsets.UTF_8).let { if (it.length > max) it.takeLast(max) else it } else "" } catch (_: Exception) { "" }

    fun currentText(max: Int = 150_000): String {
        val d = dir ?: return ""
        return synchronized(lock) { tail(File(d, "session_log.txt"), max) }
    }
    fun prevText(max: Int = 80_000): String {
        val d = dir ?: return ""
        return tail(File(d, "prev_log.txt"), max)
    }
    fun lastLines(text: String, n: Int): String = text.trimEnd().lines().takeLast(n).joinToString("\n")
    fun crashText(): String = dir?.let { d -> tail(File(d, "last_crash.txt"), 20_000).ifBlank { tail(File(d, "last_crash_seen.txt"), 20_000) } } ?: ""

    fun heapLine(): String {
        val r = Runtime.getRuntime(); val mb = 1048576L
        return "heap ${(r.totalMemory() - r.freeMemory()) / mb}/${r.maxMemory() / mb}MB"
    }

    fun header(c: Context): String {
        val v = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" }
        return "Subtitler $v · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    }

    class ExitInfo(val ts: Long, val abnormal: Boolean, val text: String)

    /** سبب خروج آخر عملية (أندرويد 11+): كراش · ANR · نفاد ذاكرة · قتل من النظام … */
    fun lastExit(c: Context): ExitInfo? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(c.packageName, 0, 5).firstOrNull { it.pid != android.os.Process.myPid() } ?: return null
            val r = e.reason
            val name = when (r) {
                android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (استثناء جافا)"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (كراش في كود نيتف/ديكودر)"
                android.app.ApplicationExitInfo.REASON_ANR -> "ANR (البرنامج هنّج ومارداش)"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (النظام قفله عشان الرام)"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (النظام قتله)"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE (استهلاك زيادة)"
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                else -> "reason=$r"
            }
            val bad = r == android.app.ApplicationExitInfo.REASON_CRASH || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                r == android.app.ApplicationExitInfo.REASON_ANR || r == android.app.ApplicationExitInfo.REASON_LOW_MEMORY ||
                r == android.app.ApplicationExitInfo.REASON_SIGNALED || r == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                r == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE || r == android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e.timestamp))
            ExitInfo(e.timestamp, bad, "سبب آخر خروج: $name\nالوقت: $t\n" + (e.description?.let { "التفاصيل: $it\n" } ?: "") + "الذاكرة وقتها: RSS ${e.rss / 1024}MB · PSS ${e.pss / 1024}MB")
        } catch (_: Throwable) { null }
    }
}
