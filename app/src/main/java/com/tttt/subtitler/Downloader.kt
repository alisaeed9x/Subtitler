package com.tttt.subtitler

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * (v134) تحميل الفيديوهات اللي جاية من النت:
 *  - لينك مباشر (mp4 …) → تنزيل عادي
 *  - يوتيوب/مواقع بصورة + صوت منفصلين → تنزيل الاتنين ودمجهم في mp4 (MediaMuxer)
 *  - HLS (m3u8) غير مشفّر بمقاطع TS → ضم المقاطع في ملف .ts
 * كله بيشتغل على خيط خلفي (استدعيه من Thread).
 */
object Downloader {
    class Job(val name: String, val url: String, val audio: String?, val hls: Boolean, val targetH: Int, val headers: Map<String, String>, val audioOnly: Boolean = false)

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private fun open(url: String, headers: Map<String, String>, method: String = "GET", range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true; c.requestMethod = method
        c.setRequestProperty("User-Agent", headers["User-Agent"] ?: UA)
        for ((k, v) in headers) if (!k.equals("User-Agent", true)) c.setRequestProperty(k, v)
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    /** حجم الملف بالبايت (HEAD، وبعدها Range 0-0) أو -1 لو مش معروف */
    fun sizeOf(url: String, headers: Map<String, String>): Long {
        try {
            val c = open(url, headers, "HEAD")
            val n = c.contentLengthLong; c.disconnect()
            if (n > 0) return n
        } catch (_: Throwable) {}
        try {
            val c = open(url, headers, "GET", "bytes=0-0")
            val cr = c.getHeaderField("Content-Range"); c.disconnect()
            val t = cr?.substringAfter('/', "")?.trim()?.toLongOrNull()
            if (t != null && t > 0) return t
        } catch (_: Throwable) {}
        return -1L
    }

    private fun readText(url: String, headers: Map<String, String>): String {
        val c = open(url, headers); try { return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun copyTo(url: String, headers: Map<String, String>, out: File, onPct: (Int) -> Unit) {
        val c = open(url, headers)
        try {
            val total = c.contentLengthLong
            c.inputStream.use { ins ->
                out.outputStream().buffered().use { os ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); done += n
                        if (total > 0) { val p = (done * 100 / total).toInt(); if (p != last) { last = p; onPct(p) } }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    /** HLS غير مشفّر (مقاطع TS): بيختار أقرب جودة للمطلوب وبيضم المقاطع. بيرجّع رسالة خطأ أو null */
    private fun hls(url: String, headers: Map<String, String>, targetH: Int, out: File, onPct: (Int) -> Unit): String? {
        var base = url; var pl = readText(url, headers)
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val lines = pl.lines(); var best: String? = null; var bestScore = Long.MAX_VALUE
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val h = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j >= lines.size) continue
                val score = (if (targetH > 0) Math.abs(h - targetH).toLong() * 1_000_000_000L else 0L) - bw
                if (score < bestScore) { bestScore = score; best = lines[j].trim() }
            }
            val v = best ?: return "ما لقيتش مسار فيديو في اللينك ده"
            base = resolve(url, v); pl = readText(base, headers)
        }
        if (pl.contains("#EXT-X-KEY") && !pl.contains("METHOD=NONE")) return "الفيديو ده مشفّر — التحميل مش مدعوم"
        if (pl.contains("#EXT-X-MAP")) return "الفيديو ده بصيغة fMP4 HLS — التحميل مش مدعوم"
        val segs = pl.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { resolve(base, it) }
        if (segs.isEmpty()) return "مفيش مقاطع تتحمّل في اللينك ده"
        out.outputStream().buffered().use { os ->
            var last = -1
            for ((k, s) in segs.withIndex()) {
                val c = open(s, headers)
                try { c.inputStream.use { it.copyTo(os) } } finally { c.disconnect() }
                val p = (k + 1) * 100 / segs.size; if (p != last) { last = p; onPct(p) }
            }
        }
        return null
    }

    /** دمج ملف صورة وملف صوت (mp4 / m4a) في mp4 واحد */
    private fun mux(v: File, a: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val exs = ArrayList<MediaExtractor>()
        try {
            val map = ArrayList<Pair<MediaExtractor, Int>>()
            for (f in listOf(v, a)) {
                val ex = MediaExtractor(); ex.setDataSource(f.absolutePath); exs.add(ex)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i); val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    val want = if (f === v) mime.startsWith("video/") else mime.startsWith("audio/")
                    if (!want) continue
                    ex.selectTrack(i); map.add(Pair(ex, muxer.addTrack(fmt))); break
                }
            }
            muxer.start()
            val buf = ByteBuffer.allocate(4 * 1024 * 1024); val info = MediaCodec.BufferInfo()
            for ((ex, ti) in map) {
                while (true) {
                    buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break
                    val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, n, ex.sampleTime, flags)
                    muxer.writeSampleData(ti, buf, info); ex.advance()
                }
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Throwable) {}
            for (e in exs) try { e.release() } catch (_: Throwable) {}
        }
    }

    /** استخراج مسار الصوت من أي ملف (mp4 / m4a / ts / webm) لملف صوت لوحده */
    private fun extractAudio(src: File, out: File, outFmt: Int) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            var ti = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) throw java.io.IOException("مفيش مسار صوت في الفيديو ده")
            ex.selectTrack(ti)
            val muxer = MediaMuxer(out.absolutePath, outFmt)
            try {
                val mt = muxer.addTrack(fmt); muxer.start()
                val buf = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo()
                while (true) {
                    buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break
                    info.set(0, n, ex.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(mt, buf, info); ex.advance()
                }
                muxer.stop()
            } finally { try { muxer.release() } catch (_: Throwable) {} }
        } finally { try { ex.release() } catch (_: Throwable) {} }
    }

    /** صوت فقط: M4A (AAC) — ولو الصيغة opus/vorbis بيطلع .webm صوت */
    private fun finishAudio(ctx: Context, raw: File, base: String, tmpDir: File): Pair<Boolean, String> {
        val o1 = File(tmpDir, "o.m4a")
        try { extractAudio(raw, o1, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); raw.delete(); return Pair(true, publish(ctx, o1, "$base.m4a", "audio/mp4")) }
        catch (_: Throwable) { try { o1.delete() } catch (_: Throwable) {} }
        val o2 = File(tmpDir, "o.webm")
        try { extractAudio(raw, o2, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM); raw.delete(); return Pair(true, publish(ctx, o2, "$base.webm", "audio/webm")) }
        catch (_: Throwable) { try { o2.delete() } catch (_: Throwable) {} }
        try { raw.delete() } catch (_: Throwable) {}
        return Pair(false, "ما قدرتش أستخرج الصوت من الصيغة دي")
    }

    /** ينقل الملف لمجلد التنزيلات (MediaStore على أندرويد 10+) وبيرجّع المسار/الوصف */
    private fun publish(ctx: Context, f: File, display: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, display); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Subtitler")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw java.io.IOException("ما قدرتش أكتب في التنزيلات")
            ctx.contentResolver.openOutputStream(uri)?.use { os -> f.inputStream().use { it.copyTo(os) } }
            f.delete()
            return "Download/Subtitler/$display"
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val dst = File(dir, display); f.copyTo(dst, true); f.delete()
        return dst.absolutePath
    }

    private fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(80).ifBlank { "video" }

    /** بيرجّع (نجح؟, رسالة). استدعيه من خيط خلفي. onPct: 0..100 */
    fun run(ctx: Context, job: Job, onPct: (String, Int) -> Unit): Pair<Boolean, String> {
        val tmpDir = File(ctx.cacheDir, "dl"); tmpDir.mkdirs()
        val base = safeName(job.name)
        try {
            if (job.audioOnly) {
                val raw = File(tmpDir, "raw.bin")
                if (job.hls) {
                    val err = hls(job.url, job.headers, job.targetH, raw) { onPct("⬇ تحميل", it) }
                    if (err != null) { raw.delete(); return Pair(false, err) }
                } else copyTo(job.audio ?: job.url, job.headers, raw) { onPct("⬇ تحميل الصوت", it) }
                onPct("🎧 استخراج الصوت", 99)
                return finishAudio(ctx, raw, base, tmpDir)
            }
            if (job.hls) {
                val t = File(tmpDir, "h.ts")
                val err = hls(job.url, job.headers, job.targetH, t) { onPct("⬇ تحميل", it) }
                if (err != null) { t.delete(); return Pair(false, err) }
                return Pair(true, publish(ctx, t, "$base.ts", "video/mp2t"))
            }
            val v = File(tmpDir, "v.bin")
            if (job.audio == null) {
                copyTo(job.url, job.headers, v) { onPct("⬇ تحميل", it) }
                val ext = job.url.substringBefore('?').substringAfterLast('.', "mp4").lowercase().let { if (it.length in 2..4) it else "mp4" }
                val mime = if (ext == "webm") "video/webm" else if (ext == "mkv") "video/x-matroska" else "video/mp4"
                return Pair(true, publish(ctx, v, "$base.$ext", mime))
            }
            val a = File(tmpDir, "a.bin")
            copyTo(job.url, job.headers, v) { onPct("⬇ الصورة", it / 2) }
            copyTo(job.audio, job.headers, a) { onPct("⬇ الصوت", 50 + it / 2) }
            onPct("🔧 دمج الصوت والصورة", 99)
            val out = File(tmpDir, "m.mp4")
            return try {
                mux(v, a, out); v.delete(); a.delete()
                Pair(true, publish(ctx, out, "$base.mp4", "video/mp4"))
            } catch (e: Throwable) {
                // الدمج فشل (صيغة مش مدعومة): نحفظ الملفين منفصلين بدل ما نضيّع التحميل
                try { out.delete() } catch (_: Throwable) {}
                val p1 = publish(ctx, v, "$base (صورة).mp4", "video/mp4"); val p2 = publish(ctx, a, "$base (صوت).m4a", "audio/mp4")
                Pair(true, "الدمج مدعمش الصيغة دي — اتحفظ ملفين: $p1 + $p2")
            }
        } catch (e: Throwable) {
            LogStore.err("Downloader", e)
            return Pair(false, "التحميل فشل: " + (e.message ?: e.javaClass.simpleName).take(80))
        }
    }
}
