package com.tttt.subtitler

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * (v181) المحركات العصبية بره الـ APK. موديل بصمة الصوت (26MB) بيتنزّل أول مرة كمهمة في تبويب «المهام»
 * ويتحفظ في filesDir/neural، فيفضل موجود مع أي تحديث لنفس التطبيق (نفس الاسم والتوقيع).
 * لو النسخة الجديدة طلبت موديل مختلف (ID مختلف) الملف القديم بيتمسح لوحده والجديد بيتنزّل.
 * الإضافة بتكمّل من نفس المكان لو النت قطع (Range)، وبتتأكد من sha256 قبل ما تستخدم الملف.
 */
object NeuralEngine {
    /** غيّر الـ ID لما تغيّر الموديل: ده اللي بيخلّي القديم يتمسح والجديد يتنزّل */
    const val ID = "wespeaker-r34-lm-v1"
    const val FILE = "voice_embed-wespeaker-r34-lm-v1.onnx"
    const val URL_ = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx"
    const val SIZE = 26530550L
    const val SHA = "e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012"
    private const val TITLE = "🧠 المحرك العصبي لبصمة الصوت (26MB)"
    private const val OFF_KEY = "neural_off"

    fun dir(c: Context): File = File(c.filesDir, "neural").apply { mkdirs() }
    fun file(c: Context): File = File(dir(c), FILE)
    fun installed(c: Context): Boolean = try { val f = file(c); f.exists() && f.length() == SIZE } catch (_: Throwable) { false }

    /** بيمسح أي موديل قديم (نسخة قديمة من الموديل أو ملفات جزئية بتاعته) والملفات القديمة اللي كانت في v172–v180 */
    fun cleanOld(c: Context) {
        try {
            dir(c).listFiles()?.forEach { if (it.name != FILE && it.name != "$FILE.part") it.delete() }
            File(c.filesDir, "voice_embed.onnx").delete()
            c.getExternalFilesDir(null)?.let { File(it, "voice_embed.onnx") }?.let { if (it.exists()) it.delete() }
        } catch (_: Throwable) {}
    }

    private fun running(): TaskItem? = TaskCenter.items.firstOrNull { it.kind == "download" && it.title == TITLE && (it.state == 0 || it.state == 1 || it.state == 6) }
    private fun last(): TaskItem? = TaskCenter.items.firstOrNull { it.kind == "download" && it.title == TITLE }

    /** عند فتح التطبيق: لو الموديل مش موجود واليوزر ماحذفوش بإيده، ينزّل في الخلفية لوحده */
    fun auto(c: Context) {
        val app = c.applicationContext
        cleanOld(app)
        if (installed(app) || Cfg.bool(OFF_KEY, false)) return
        if (last() != null) return
        start(app)
    }

    /** زرار «تحميل» من الإعدادات: بيشيل علامة الحذف ويبدأ */
    fun manual(c: Context) { Cfg.put(OFF_KEY, "0"); start(c.applicationContext) }

    fun start(app: Context): TaskItem? {
        cleanOld(app)
        if (installed(app)) return null
        running()?.let { return it }
        return TaskCenter.add(app, "download", TITLE) { t -> run(app, t) }
    }

    fun delete(c: Context) {
        val app = c.applicationContext
        Cfg.put(OFF_KEY, "1")
        running()?.let { TaskCenter.cancel(it) }
        try { dir(app).listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
        VoiceNet.reset()
        LogStore.add("🧠 المحرك العصبي اتمسح")
    }

    fun status(c: Context): String {
        if (installed(c)) {
            val e = VoiceNet.lastErr
            return if (VoiceNet.isReady()) "✅ المحرك العصبي متحمّل وشغّال (26MB)" else if (e.isNotEmpty()) "⚠ متحمّل لكن مش شغّال: $e" else "✅ متحمّل (26MB) — هيشتغل مع أول فيديو"
        }
        val r = running()
        if (r != null) return "⬇ بيتحمّل… ${r.pct}% ${r.msg}"
        val l = last()
        if (l != null && l.state == 3) return "⚠ التحميل فشل: ${l.msg} — جرّب زرار التحميل"
        return if (Cfg.bool(OFF_KEY, false)) "⛔ المحرك العصبي محذوف — البصمة الخفيفة بس شغّالة" else "⬇ لسه ما اتحمّلش — البصمة الخفيفة شغّالة لحد ما يتحمّل"
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(1 shl 16)
        f.inputStream().use { i -> while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun run(app: Context, t: TaskItem) {
        cleanOld(app)
        val part = File(dir(app), "$FILE.part"); val out = file(app)
        var conn: HttpURLConnection? = null
        t.onCancel = { try { conn?.disconnect() } catch (_: Throwable) {} }
        t.onPause = { }
        var tries = 0
        while (true) {
            if (t.cancelled) throw TaskCancelled()
            try {
                var have = if (part.exists()) part.length() else 0L
                if (have >= SIZE) { part.delete(); have = 0L }
                t.msg = if (have > 0) "بيكمّل التحميل…" else "بيتصل…"; TaskCenter.changed()
                val cn = (URL(URL_).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Subtitler/1.81")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                conn = cn
                val code = cn.responseCode
                if (code != 200 && code != 206) throw IOException("رد السيرفر HTTP $code")
                var got = if (code == 206) have else 0L
                FileOutputStream(part, code == 206).use { os ->
                    cn.inputStream.use { ins ->
                        val buf = ByteArray(1 shl 16); var lastPct = -1
                        while (true) {
                            if (t.cancelled) throw TaskCancelled()
                            while (t.paused && !t.cancelled) Thread.sleep(300)
                            val n = ins.read(buf); if (n < 0) break
                            os.write(buf, 0, n); got += n
                            val p = (got * 100 / SIZE).toInt().coerceIn(0, 99)
                            if (p != lastPct) { lastPct = p; t.pct = p; t.msg = "${got / 1048576} / ${SIZE / 1048576} MB"; TaskCenter.changed() }
                        }
                    }
                }
                try { cn.disconnect() } catch (_: Throwable) {}
                if (part.length() < SIZE) throw IOException("الاتصال قطع قبل ما الملف يكمل")
                break
            } catch (e: TaskCancelled) { throw e
            } catch (e: Throwable) {
                if (t.cancelled) throw TaskCancelled()
                tries++
                LogStore.add("⚠ تحميل المحرك العصبي: ${e.javaClass.simpleName} ${e.message ?: ""} (محاولة $tries/6)")
                if (tries >= 6) throw IOException("${e.message ?: e.javaClass.simpleName} — اتأكد من النت وجرّب 🔁")
                t.msg = "النت قطع — محاولة ${tries + 1}/6"; TaskCenter.changed()
                Thread.sleep(3000L * tries)
            }
        }
        t.msg = "بيتأكد من سلامة الملف…"; TaskCenter.changed()
        val h = sha256(part)
        if (part.length() != SIZE || h != SHA) { part.delete(); throw IOException("الملف اتحمّل بس مش مطابق (sha256) — دوس 🔁 يحمّله من الأول") }
        if (out.exists()) out.delete()
        if (!part.renameTo(out)) { part.copyTo(out, true); part.delete() }
        VoiceNet.reset()
        LogStore.add("🧠 المحرك العصبي اتحمّل وتأكدنا من سلامته")
        t.msg = ""
        try { VoiceNet.available() } catch (_: Throwable) {}   // يجرّبه فورًا عشان خطأ التحميل (لو فيه) يظهر في اللوج والإعدادات
    }
}
