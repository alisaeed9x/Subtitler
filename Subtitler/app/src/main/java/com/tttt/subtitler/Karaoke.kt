package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * (v202) فصل الصوت عن الموسيقى + خريطة «امتى المغني بيغني» لتوقيت الكلمات.
 *
 * الفصل بطريقة استخراج القناة الوسطى (مش ذكاء صناعي): الصوت البشري غالبًا في نص الاستريو، فبناخد
 *   v = فلتر (200–5000 هرتز) على (يمين + شمال)/2  ← «الصوت»
 *   الموسيقى = كل قناة − v                          ← «الموسيقى» (الباص والستريو بيفضلوا)
 * يعني صوت + موسيقى = الأغنية الأصلية بالظبط. النتيجة تقريبية: أدوات في النص بنفس النطاق (طبلة/جيتار) ممكن تروح مع الصوت،
 * وصدى المغني على الجنبين ممكن يفضل في الموسيقى. الأغاني المونو (قناة واحدة) مينفعش تتفصل.
 *
 * نفس القراءة بتطلّع خريطة لحظات الغناء (segments) اللي Lyrics.kt بيوزّع عليها سطور الكلمات بدل التوزيع على طول الأغنية.
 */
object Karaoke {
    class Seg(val s: Double, val e: Double)

    private const val HOP = 0.05          // ثانية لكل إطار تحليل
    private val running = ConcurrentHashMap<String, Boolean>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "stems").apply { mkdirs() }
    private fun base(t: Track) = "t${t.id}_${t.durMs}"
    fun file(ctx: Context, t: Track, kind: Int) = File(dir(ctx), base(t) + (if (kind == 1) "_v" else "_m") + ".wav")
    fun hasStem(ctx: Context, t: Track, kind: Int): Boolean = file(ctx, t, kind).let { it.exists() && it.length() > 4096 }
    fun isRunning(t: Track) = running.containsKey(base(t))
    private fun vadFile(ctx: Context, t: Track) = File(dir(ctx), base(t) + ".vad")

    /** لحظات الغناء المحفوظة للأغنية (أو null لو لسه ماتحلّلتش / مفيش غناء واضح) */
    fun cachedSegments(ctx: Context, t: Track): List<Seg>? = try {
        val f = vadFile(ctx, t)
        if (!f.exists()) null else {
            val l = f.readLines().mapNotNull { ln -> val p = ln.trim().split(' '); if (p.size == 2) Seg(p[0].toDouble(), p[1].toDouble()) else null }
            l
        }
    } catch (_: Throwable) { null }

    /** بيرجّع لحظات الغناء — لو مش محسوبة بيحلّل الصوت الأول (بيتنادى من خيط خلفية). فاضية = ماقدرتش/مفيش غناء واضح */
    fun segments(ctx: Context, t: Track): List<Seg> {
        cachedSegments(ctx, t)?.let { return it }
        analyze(ctx, t, false) { }
        return cachedSegments(ctx, t) ?: emptyList()
    }

    private fun prune(ctx: Context) {
        try {
            val l = dir(ctx).listFiles { f -> f.name.endsWith(".wav") }?.sortedByDescending { it.lastModified() } ?: return
            for (i in 12 until l.size) l[i].delete()
        } catch (_: Throwable) {}
    }

    private class Wav(val f: File, sr: Int) {
        val tmp = File(f.path + ".tmp")
        val os = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)
        var bytes = 0L
        init { os.write(ByteArray(44)) }
        fun write(b: ByteArray, n: Int) { os.write(b, 0, n); bytes += n }
        val rate = sr
        fun finish(ok: Boolean) {
            try { os.flush(); os.close() } catch (_: Throwable) {}
            if (!ok) { tmp.delete(); return }
            try {
                RandomAccessFile(tmp, "rw").use { r ->
                    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray())
                    h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2); h.putInt(rate); h.putInt(rate * 4); h.putShort(4); h.putShort(16)
                    h.put("data".toByteArray()); h.putInt(bytes.toInt())
                    r.seek(0); r.write(h.array())
                }
                f.delete(); tmp.renameTo(f)
            } catch (_: Throwable) { tmp.delete() }
        }
    }

    /**
     * بيفك تشفير الأغنية ويحلّلها (بيتنادى من خيط خلفية). stems=true: بيكتب ملفين WAV (صوت / موسيقى) كمان.
     * بيرجّع null لو تمام، أو رسالة الخطأ.
     */
    fun analyze(ctx: Context, t: Track, stems: Boolean, onProgress: (Int) -> Unit): String? {
        val key = base(t)
        if (running.putIfAbsent(key, true) != null) return "الأغنية دي بتتحلّل دلوقتي"
        val app = ctx.applicationContext
        var ex: MediaExtractor? = null; var codec: MediaCodec? = null
        var wv: Wav? = null; var wm: Wav? = null
        var ok = false; var mono = false
        try {
            ex = MediaExtractor(); ex.setDataSource(app, Uri.parse(t.uri), null)
            var ti = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) { val f = ex.getTrackFormat(i); if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break } }
            if (ti < 0 || fmt == null) return "ملقيتش مسار صوت في الملف"
            ex.selectTrack(ti)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = 2
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durMs * 1000L

            // حالة الفلاتر (واحد-قطب): باص 120هـ مش مستخدم هنا — الموسيقى = الأصل − الصوت
            var hp = 0f; var lp = 0f; var aHp = 0f; var aLp = 0f
            fun coef(fc: Float) = (1.0 - Math.exp(-2.0 * Math.PI * fc / sr)).toFloat()
            aHp = coef(200f); aLp = coef(5000f)
            val rv = ArrayList<Float>(); val rm = ArrayList<Float>()
            var accV = 0.0; var accM = 0.0; var cnt = 0
            val hopN = (HOP * sr).toInt().coerceAtLeast(1)
            var outBuf = ByteArray(1 shl 16); var mBuf = ByteArray(1 shl 16)

            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false; var started = false
            while (!outDone) {
                if (!inDone) {
                    val ib = codec.dequeueInputBuffer(10000)
                    if (ib >= 0) {
                        val buf = codec.getInputBuffer(ib)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10000)
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (!started) {
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        aHp = coef(200f); aLp = coef(5000f)
                    }
                    enc = if (of.containsKey("pcm-encoding")) of.getInteger("pcm-encoding") else 2
                } else if (ob >= 0) {
                    val b = codec.getOutputBuffer(ob)
                    if (b != null && info.size > 0) {
                        if (!started) {
                            started = true; mono = ch < 2
                            if (stems && !mono) { wv = Wav(file(app, t, 1), sr); wm = Wav(file(app, t, 2), sr) }
                        }
                        b.position(info.offset); b.limit(info.offset + info.size); b.order(ByteOrder.LITTLE_ENDIAN)
                        val bps = if (enc == 4) 4 else 2
                        val frames = info.size / (bps * ch)
                        if (outBuf.size < frames * 4) { outBuf = ByteArray(frames * 4); mBuf = ByteArray(frames * 4) }
                        var o = 0
                        for (i in 0 until frames) {
                            val l: Float; val r: Float
                            if (enc == 4) {
                                l = b.float; r = if (ch >= 2) b.float else l
                                for (k in 2 until ch) b.float
                            } else {
                                l = b.short / 32768f; r = if (ch >= 2) b.short / 32768f else l
                                for (k in 2 until ch) b.short
                            }
                            val mid = (l + r) * 0.5f
                            lp += aLp * (mid - lp)               // لو-باس 5000
                            hp += aHp * (lp - hp)                // لو-باس 200 على الناتج
                            val v = lp - hp                      // باند 200–5000
                            val ml = l - v; val mr = r - v
                            accV += (v * v).toDouble(); accM += ((ml * ml + mr * mr) * 0.5f).toDouble(); cnt++
                            if (cnt >= hopN) { rv.add(Math.sqrt(accV / cnt).toFloat()); rm.add(Math.sqrt(accM / cnt).toFloat()); accV = 0.0; accM = 0.0; cnt = 0 }
                            if (wv != null) {
                                val sv = (v.coerceIn(-1f, 1f) * 32767f).toInt(); val sl = (ml.coerceIn(-1f, 1f) * 32767f).toInt(); val sRr = (mr.coerceIn(-1f, 1f) * 32767f).toInt()
                                outBuf[o] = (sv and 255).toByte(); outBuf[o + 1] = ((sv shr 8) and 255).toByte(); o += 2
                                outBuf[o] = (sv and 255).toByte(); outBuf[o + 1] = ((sv shr 8) and 255).toByte(); o += 2
                                mBuf[o - 4] = (sl and 255).toByte(); mBuf[o - 3] = ((sl shr 8) and 255).toByte()
                                mBuf[o - 2] = (sRr and 255).toByte(); mBuf[o - 1] = ((sRr shr 8) and 255).toByte()
                            }
                        }
                        if (wv != null) {
                            wv.write(outBuf, o)
                            wm!!.write(mBuf, o)
                        }
                        if (durUs > 0) onProgress(((info.presentationTimeUs * 100L) / durUs).toInt().coerceIn(0, 99))
                    }
                    codec.releaseOutputBuffer(ob, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            // خريطة الغناء من منحنى الصوت
            val seg = buildSegments(rv)
            try { vadFile(app, t).writeText(seg.joinToString("\n") { "%.2f %.2f".format(java.util.Locale.US, it.s, it.e) }) } catch (_: Throwable) {}
            ok = true
            if (stems && mono) return "الأغنية دي مونو (قناة واحدة) — مينفعش تتفصل"
            if (stems && wv == null) return "ماقدرتش أقرا الصوت"
            return null
        } catch (e: Throwable) {
            LogStore.err("karaoke", e)
            return "فشل تحليل الصوت: " + (e.message ?: e.javaClass.simpleName).take(80)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex?.release() } catch (_: Throwable) {}
            wv?.finish(ok); wm?.finish(ok)
            if (ok && wv != null) prune(app)
            running.remove(key)
        }
    }

    /** منحنى طاقة الصوت → مقاطع غنا: عتبة نسبية، سد الفجوات القصيرة، وشيل الومضات القصيرة */
    internal fun buildSegments(rv: List<Float>): List<Seg> {
        if (rv.size < 40) return emptyList()
        val sorted = rv.sorted()
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        val thr = maxOf(p95 * 0.18f, 0.003f)
        // تنعيم 3 إطارات
        val sm = FloatArray(rv.size) { i -> val a = rv[maxOf(0, i - 1)]; val b = rv[i]; val c = rv[minOf(rv.size - 1, i + 1)]; (a + b + c) / 3f }
        val act = BooleanArray(sm.size) { sm[it] > thr }
        // سد الفجوات أقل من 0.6ث
        var i = 0
        while (i < act.size) {
            if (!act[i]) { var j = i; while (j < act.size && !act[j]) j++; if (i > 0 && j < act.size && (j - i) * HOP < 0.6) for (k in i until j) act[k] = true; i = j } else i++
        }
        // شيل الومضات أقل من 0.3ث
        i = 0
        while (i < act.size) {
            if (act[i]) { var j = i; while (j < act.size && act[j]) j++; if ((j - i) * HOP < 0.3) for (k in i until j) act[k] = false; i = j } else i++
        }
        val out = ArrayList<Seg>()
        i = 0
        while (i < act.size) {
            if (act[i]) { var j = i; while (j < act.size && act[j]) j++; out.add(Seg(i * HOP, j * HOP)); i = j } else i++
        }
        val total = out.sumOf { it.e - it.s }
        val dur = rv.size * HOP
        return if (out.isEmpty() || total < dur * 0.10) emptyList() else out
    }

    /**
     * بيوزّع سطور الكلمات على لحظات الغناء بس (بيتخطى المقدمة والفواصل الموسيقية): كل سطر ياخد وقت بنسبة حروفه،
     * وبعدين بنثبّت بداية السطر على أقرب بداية غنا حقيقية لو قريبة (±1.2ث).
     */
    fun place(lines: List<String>, segs: List<Seg>): List<LyricLine> {
        if (lines.isEmpty() || segs.isEmpty()) return emptyList()
        val total = segs.sumOf { it.e - it.s }
        val w = lines.map { it.count { c -> c.isLetterOrDigit() }.coerceAtLeast(6).toDouble() }
        val sw = w.sum()
        fun map(p: Double): Pair<Double, Int> {
            var left = p.coerceIn(0.0, total)
            for ((k, s) in segs.withIndex()) { val len = s.e - s.s; if (left <= len || k == segs.size - 1) return Pair(s.s + left.coerceAtMost(len), k); left -= len }
            return Pair(segs.last().e, segs.size - 1)
        }
        val onsets = segs.map { it.s }
        val starts = DoubleArray(lines.size); val ends = DoubleArray(lines.size); val segOf = IntArray(lines.size)
        var acc = 0.0
        for (i in lines.indices) {
            val a = acc / sw * total; acc += w[i]; val b = acc / sw * total
            val (s0, si) = map(a); val (e0, _) = map(b)
            starts[i] = s0; ends[i] = e0; segOf[i] = si
        }
        for (i in lines.indices) {
            val near = onsets.minByOrNull { Math.abs(it - starts[i]) }
            if (near != null && Math.abs(near - starts[i]) <= 1.2) {
                val prev = if (i > 0) starts[i - 1] + 0.3 else 0.0
                if (near > prev) starts[i] = near
            }
            if (i > 0 && starts[i] <= starts[i - 1]) starts[i] = starts[i - 1] + 0.3
        }
        val res = ArrayList<LyricLine>()
        for (i in lines.indices) {
            val nextS = if (i + 1 < lines.size) starts[i + 1] else Double.MAX_VALUE
            var e = ends[i].coerceAtLeast(starts[i] + 1.0)
            val sEnd = segs[segOf[i].coerceIn(0, segs.size - 1)].e
            if (e > sEnd && sEnd > starts[i] + 0.6) e = sEnd          // السطر مايتمدّش على فاصل موسيقي
            e = minOf(e, nextS, starts[i] + 10.0)
            res.add(LyricLine(starts[i], maxOf(e, starts[i] + 0.5), lines[i]))
        }
        return res
    }
}
