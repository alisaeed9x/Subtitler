package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DRM_MSG = "الفيديو محمي (DRM) ومش هينفع يتترجم"

object Tracks {
    private fun canDecode(f: MediaFormat): Boolean {
        val mime = f.getString(MediaFormat.KEY_MIME) ?: return false
        try { if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null) return true } catch (e: Exception) { LogStore.err("AudioIO:24", e) }
        return try { MediaCodec.createDecoderByType(mime).release(); true } catch (_: Exception) { false }
    }

    /** يختار مسار الصوت: اللي المستخدم طلبه (رقمه من 1) لو بيتفك على الجهاز، وإلا أول مسار بيتفك. */
    fun pick(e: MediaExtractor, pref: Int, log: (String) -> Unit): Int {
        val audio = (0 until e.trackCount).filter { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        if (audio.isEmpty()) throw Unsupported("الفيديو ملوش مسار صوت")
        val order = (listOf(audio.getOrNull(pref - 1)) + audio).filterNotNull().distinct()
        for (t in order) {
            val f = e.getTrackFormat(t)
            if (canDecode(f)) {
                if (audio.size > 1) log("🎚 مسار الصوت: ${audio.indexOf(t) + 1} من ${audio.size} (${f.getString(MediaFormat.KEY_MIME)})")
                return t
            }
            log("⚠ مسار الصوت ${audio.indexOf(t) + 1} (${f.getString(MediaFormat.KEY_MIME)}) مفيش له decoder على الجهاز")
        }
        val names = audio.joinToString("، ") { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "?" }
        throw Unsupported("الجهاز مفيهوش decoder لصيغة الصوت ($names). جرّب فيديو بصوت AAC أو MP3 أو Opus")
    }
}

object Decoder {
    /** يفك الصوت من المجال [startUs, endUs] (بوقت الـ extractor) ويحطه في sink. لازم يكون عمل seekTo قبل كده. */
    fun decode(ex: MediaExtractor, fmt: MediaFormat, startUs: Long, endUs: Long, sink: PcmSink) {
        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw Unsupported("صيغة صوت غير معروفة")
        val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw Unsupported("مفيش decoder لصيغة $mime") }
        try {
            // الحد الافتراضي لبفر الإدخال 8192 بايت — عينات HLS/TS ممكن تعدّيه فـ readSampleData يرمي IAE من غير رسالة. نكبّره قبل configure
            try {
                val curMax = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                if (curMax < (1 shl 20)) fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)
            } catch (e: Exception) { LogStore.err("AudioIO:56", e) }
            try { codec.configure(fmt, null, null, 0) } catch (x: IllegalArgumentException) { throw HlsBadMedia("configure فشل لصيغة الصوت: $fmt") }
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = 2
            val deadline = System.currentTimeMillis() + 90_000
            var st = "بداية"; var dg = ""
            try {
            while (!outDone) {
                if (System.currentTimeMillis() > deadline) throw IOException("فك الصوت أخد وقت طويل")
                if (!inDone) {
                    st = "dequeueInputBuffer"
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        st = "getInputBuffer"
                        val ib = codec.getInputBuffer(ii)
                        val ss = try { ex.sampleSize } catch (_: Exception) { -2L }
                        st = "readSampleData"; dg = "ii=$ii cap=" + (ib?.capacity() ?: -1) + " lim=" + (ib?.limit() ?: -1) + " pos=" + (ib?.position() ?: -1) + " sampleSize=$ss mime=$mime"
                        if (ib != null && ss > ib.capacity()) throw HlsBadMedia("عينة الصوت ($ss بايت) أكبر من بفر الكودك (${ib.capacity()} بايت) | mime=$mime")
                        val n = if (ib != null) { ib.clear(); ex.readSampleData(ib, 0) } else -1
                        st = "sampleTime"; dg = "n=$n"
                        val t = ex.sampleTime
                        if (n < 0 || t < 0 || t >= endUs + 300_000) {
                            st = "queueInputBuffer(EOS)"; dg = "ii=$ii n=$n t=$t"
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw Unsupported(DRM_MSG)
                            st = "queueInputBuffer"; dg = "ii=$ii n=$n t=$t cap=" + (ib?.capacity() ?: -1)
                            codec.queueInputBuffer(ii, 0, n, t, 0)
                            st = "advance"; ex.advance()
                        }
                    }
                }
                st = "dequeueOutputBuffer"
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    st = "outputFormat"
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    enc = if (of.containsKey("pcm-encoding")) of.getInteger("pcm-encoding") else 2
                } else if (oi >= 0) {
                    var reached = false
                    st = "getOutputBuffer"
                    val ob = codec.getOutputBuffer(oi)
                    if (info.size > 0 && ob != null) {
                        dg = "oi=$oi off=${info.offset} size=${info.size} cap=${ob.capacity()} lim=${ob.limit()} pos=${ob.position()} enc=$enc ch=$ch rate=$rate"
                        st = "ضبط position/limit للـ output"
                        // clear الأول عشان الـ limit القديم ما يرميش IAE لما الـ offset أكبر منه
                        ob.clear(); ob.order(ByteOrder.LITTLE_ENDIAN)
                        ob.limit(minOf(ob.capacity(), info.offset + info.size)); ob.position(minOf(info.offset, ob.limit()))
                        if (enc == 4) {
                            st = "قراءة float"
                            val fb = ob.asFloatBuffer(); val a = FloatArray(fb.remaining()); fb.get(a)
                            st = "sink.addFloats"
                            reached = sink.addFloats(a, ch, rate, info.presentationTimeUs, startUs, endUs)
                        } else if (enc == 2) {
                            st = "قراءة short"
                            val sb = ob.asShortBuffer(); val a = ShortArray(sb.remaining()); sb.get(a)
                            st = "sink.addShorts"
                            reached = sink.addShorts(a, ch, rate, info.presentationTimeUs, startUs, endUs)
                        } else throw Exception("نوع PCM غير مدعوم ($enc)")
                    }
                    st = "releaseOutputBuffer"
                    codec.releaseOutputBuffer(oi, false)
                    if (reached || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            } catch (x: IllegalArgumentException) {
                throw HlsBadMedia("[$st] " + x.javaClass.simpleName + (x.message?.let { " $it" } ?: "") + " | $dg | " + (x.stackTrace.firstOrNull()?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""))
            } catch (x: IllegalStateException) {
                throw HlsBadMedia("[$st] " + x.javaClass.simpleName + (x.message?.let { " $it" } ?: "") + " | $dg")
            }
        } finally {
            try { codec.stop() } catch (e: Exception) { LogStore.err("AudioIO:133", e) }
            codec.release()
        }
    }
}

/** ملف على الموبايل (Uri) أو رابط http مباشر. الـ extractor بيفضل مفتوح ونعمل seek لكل مقطع (من غير ما نحمّل الملف في الرام). */
class FileSource(
    private val ctx: Context, private val uri: Uri?, private val url: String?,
    private val hdr: Map<String, String>, private val pref: Int, private val log: (String) -> Unit
) : AudioSource {
    // حوض Extractors: كل باتش بياخد واحد لوحده (قبل كده كان واحد بس بقفل @Synchronized فكل الباتشات كانت بتستنى بعض — وده كان بيخلّي الفيديوهات اللي من لينك تتترجم باتش ورا باتش)
    private val idle = java.util.ArrayDeque<MediaExtractor>()
    private val lock = Any()
    @Volatile private var fmt: MediaFormat? = null
    @Volatile private var track = -1
    @Volatile private var durUs = 0L
    @Volatile private var preRollUs = 0L
    @Volatile private var closed = false
    private val slots = java.util.concurrent.Semaphore(if (uri != null) 2 else 4)   // أقصى عدد فك متوازي (محلي أخف من الشبكة)
    override fun setPreRoll(sec: Double) { preRollUs = (sec * 1_000_000).toLong() }

    private fun openNew(): MediaExtractor {
        var e = MediaExtractor()
        try {
            if (uri != null) e.setDataSource(ctx, uri, null)
            else try { e.setDataSource(url!!, hdr) } catch (t: Throwable) {
                // الـ HTTP الداخلي بتاع MediaExtractor بيضيّع الهيدرز (كوكيز/Referer/UA) بعد التحويلات وبيفشل مع سيرفرات كتير — نقرأ بالـ HTTP بتاعنا
                try { e.release() } catch (e: Exception) { LogStore.err("AudioIO:156", e) }
                log("⚠ فتح الرابط المباشر فشل (" + (t.message ?: t.javaClass.simpleName).take(70) + ") — بجرّب القراءة بالـ HTTP بتاعي")
                e = MediaExtractor()
                e.setDataSource(HttpRangeSource(url!!, hdr))
            }
            val t = if (track >= 0) track else Tracks.pick(e, pref, log)
            e.selectTrack(t)
            val f = e.getTrackFormat(t)
            if (fmt == null) {
                durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                track = t; fmt = f
            }
            return e
        } catch (t: Throwable) { try { e.release() } catch (e: Exception) { LogStore.err("AudioIO:166", e) }; throw t }
    }

    private fun acquire(): MediaExtractor {
        synchronized(lock) { idle.pollFirst()?.let { return it } }
        return openNew()
    }
    private fun giveBack(e: MediaExtractor) {
        val keep = synchronized(lock) { if (closed) false else { idle.addFirst(e); true } }
        if (!keep) try { e.release() } catch (_: Exception) {}
    }

    /** من غير قفل لو المدة معروفة: الواجهة بتسأل عليها كل ثانية */
    override fun durationSec(): Double {
        val d = durUs; if (d > 0) return d / 1_000_000.0
        return durationSlow()
    }
    private fun durationSlow(): Double {
        if (fmt == null) {
            val e = try { acquire() } catch (_: Exception) { return 0.0 }
            giveBack(e)
        }
        return durUs / 1_000_000.0
    }

    override fun wav(startSec: Double, endSec: Double): WavChunk? {
        slots.acquireUninterruptibly()
        try {
            val e = acquire(); val f = fmt ?: e.getTrackFormat(track.coerceAtLeast(0))
            val startUs = (startSec * 1_000_000).toLong(); val endUs = (endSec * 1_000_000).toLong()
            try {
                e.seekTo(maxOf(0L, startUs - preRollUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val sink = PcmSink(endSec - startSec)
                Decoder.decode(e, f, startUs, endUs, sink)
                giveBack(e)
                if (sink.isEmpty()) return null
                return sink.finish(sink.firstPtsUs / 1_000_000.0)
            } catch (t: Throwable) {
                if (t is Unsupported) giveBack(e) else try { e.release() } catch (x: Exception) { LogStore.err("AudioIO:190", x) }
                throw t
            }
        } finally { slots.release() }
    }

    override fun close() {
        val all = synchronized(lock) { closed = true; val l = ArrayList(idle); idle.clear(); l }
        for (e in all) try { e.release() } catch (x: Exception) { LogStore.err("AudioIO:195", x) }
    }
}

/** قراءة عشوائية من رابط http بطلبات Range وبنفس الهيدرز (كوكيز/Referer/UA) — احتياطي لما MediaExtractor يفشل يفتح الرابط بنفسه */
class HttpRangeSource(private val url: String, private val hdr: Map<String, String>) : MediaDataSource() {
    @Volatile private var total = -2L
    @Volatile private var finalUrl = url

    private fun connect(pos: Long, len: Int): HttpURLConnection {
        var u = finalUrl
        for (hop in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 40000; c.instanceFollowRedirects = false
            for ((k, v) in hdr) c.setRequestProperty(k, v)
            c.setRequestProperty("Range", "bytes=$pos-${pos + len - 1}")
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc == null) throw IOException("HTTP $code من غير Location")
                u = Hls.resolve(u, loc); finalUrl = u; continue
            }
            if (code >= 400) { c.disconnect(); finalUrl = url; throw IOException("HTTP $code") }
            return c
        }
        throw IOException("تحويلات (redirect) كتير")
    }

    override fun getSize(): Long {
        if (total != -2L) return total
        val c = connect(0, 1)
        try {
            val cr = c.getHeaderField("Content-Range")
            total = if (cr != null && cr.contains('/')) (cr.substringAfter('/').trim().toLongOrNull() ?: -1L)
                    else if (c.responseCode == 200) c.contentLengthLong else -1L
        } finally { c.disconnect() }
        return total
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val t = if (total == -2L) getSize() else total
        if (t >= 0 && position >= t) return -1
        var attempt = 0
        while (true) {
            try {
                val c = connect(position, size)
                try {
                    c.inputStream.use { ins ->
                        if (c.responseCode == 200 && position > 0) { var left = position; while (left > 0) { val k = ins.skip(left); if (k <= 0) break; left -= k } }
                        var got = 0
                        while (got < size) { val n = ins.read(buffer, offset + got, size - got); if (n < 0) break; got += n }
                        return if (got == 0) -1 else got
                    }
                } finally { c.disconnect() }
            } catch (e: IOException) {
                if (++attempt > 2) throw e
                try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) { throw e }
            }
        }
    }

    override fun close() {}
}

/** مصدر الصوت المناسب للرابط: HLS (حتى لو الرابط من غير .m3u8 بنفحص أول بايتات) أو ملف/رابط مباشر */
object AudioSources {
    private val MEDIA_EXT = Regex("\\.(mp4|m4v|mov|mkv|webm|mp3|m4a|aac|ogg|opus|wav|flac|3gp|flv|ts)(?=[?#]|$)", RegexOption.IGNORE_CASE)
    fun looksHls(url: String, hdr: Map<String, String>): Boolean {
        if (url.contains(".m3u8", true)) return true
        if (!url.startsWith("http", true) || MEDIA_EXT.containsMatchIn(url)) return false
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000; c.readTimeout = 6000
                for ((k, v) in hdr) c.setRequestProperty(k, v)
                c.setRequestProperty("Range", "bytes=0-63")
                val ct = (c.contentType ?: "").lowercase()
                if (ct.contains("mpegurl")) return true
                if (ct.startsWith("video/") || ct.startsWith("audio/")) return false
                val buf = ByteArray(64); val n = c.inputStream.use { it.read(buf) }
                n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")
            } finally { c.disconnect() }
        } catch (_: Throwable) { false }
    }
    fun make(ctx: Context, uri: Uri?, url: String?, hdr: Map<String, String>, pref: Int, log: (String) -> Unit): AudioSource =
        if (uri == null && url != null && looksHls(url, hdr)) HlsSource(ctx, url, hdr, pref, log) else FileSource(ctx, uri, url, hdr, pref, log)
}

private class HlsBadMedia(m: String) : IOException(m)

/**
 * روابط HLS (m3u8) — ترجمة على دفعات من غير ما نحمّل الفيلم كله:
 *  1) المقاطع (segments) اللي الباتش محتاجها بس بتتحمّل (4 مع بعض) وبتتخزّن على الديسك مؤقتًا (مش في الرام).
 *  2) أول ما باتش يتجهّز، بنبدأ في الخلفية نحمّل مقاطع الـ N باتشات اللي بعده (الافتراضي 3، إعداد «hls_ahead») — فالباتش الجاي بيتفك من الديسك على طول.
 *  3) الصوت بيتفك من مقاطع كل باتش لوحده ويتبعت لجيميناي؛ ومفيش تحميل لأي حاجة بعد النافذة دي لحد ما الترجمة توصلها.
 * وبيتعامل مع: صوت منفصل (rendition) أو variant من غير صوت (بيجرّب اللي بعده)، AES-128، BYTERANGE، fMP4،
 * ترويسات الصور الوهمية قبل TS، تحويلات http↔https، وإعادة محاولة كل مقطع.
 */
class HlsSource(
    private val ctx: Context, private val url: String, private val hdr: Map<String, String>,
    private val pref: Int, private val log: (String) -> Unit
) : AudioSource {
    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val CACHE_MAX = 400L * 1024 * 1024
        val NET: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r).also { it.isDaemon = true } }
        val PRE: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r).also { it.isDaemon = true } }
        val BG: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
    }
    private val dir = File(ctx.cacheDir, "hls").apply { mkdirs() }
    private var media: Hls.Media? = null
    private var cands: List<String> = emptyList()
    private var candIdx = 0
    private var firstText: String? = null
    private var loadErr: Exception? = null
    private var loadErrAt = 0L
    private val keys = HashMap<String, ByteArray>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val mine = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val preGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var preRoll = 0.0
    @Volatile private var lastEvict = 0L
    private val aheadPatches: Int = (try { Cfg.int("hls_ahead", 3) } catch (_: Throwable) { 3 }).coerceIn(1, 8)
    override fun setPreRoll(sec: Double) { preRoll = sec }

    // ---------- شبكة ----------
    private fun httpOnce(u0: String, off: Long, len: Long): ByteArray {
        var u = u0
        for (hop in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20000; c.readTimeout = 60000; c.instanceFollowRedirects = false
                for ((k, v) in hdr) c.setRequestProperty(k, v)
                if (hdr.keys.none { it.equals("User-Agent", true) }) c.setRequestProperty("User-Agent", UA)
                if (off >= 0 && len > 0) c.setRequestProperty("Range", "bytes=$off-${off + len - 1}")
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("HTTP $code من غير Location")
                    u = Hls.resolve(u, loc); continue
                }
                if (code >= 300) throw IOException("HTTP $code من ${u.take(90)}")
                val b = c.inputStream.use { it.readBytes() }
                if (off >= 0 && len > 0 && code == 200 && b.size > len) { val st = minOf(off.toInt(), b.size); return b.copyOfRange(st, minOf(b.size, st + len.toInt())) }
                return b
            } finally { c.disconnect() }
        }
        throw IOException("تحويلات (redirect) كتير")
    }

    private fun http(u: String, off: Long = -1, len: Long = -1): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            try { return httpOnce(u, off, len) }
            catch (e: IOException) {
                last = e
                val m = e.message ?: ""
                if (m.startsWith("HTTP 4") && !m.startsWith("HTTP 429") && !m.startsWith("HTTP 408")) throw e   // 403/404: مفيش فايدة من التكرار
                try { Thread.sleep(700L * (attempt + 1)) } catch (_: InterruptedException) { throw e }
            }
        }
        throw last ?: IOException("فشل التحميل")
    }

    // ---------- القوائم ----------
    @Synchronized private fun load(): Hls.Media {
        media?.let { return it }
        loadErr?.let { if (System.currentTimeMillis() - loadErrAt < 4000) throw it }
        try {
            if (cands.isEmpty()) {
                val text = String(http(url), Charsets.UTF_8)
                if (!text.contains("#EXTM3U") && !text.contains("#EXTINF") && !text.contains("#EXT-X-STREAM-INF"))
                    throw Unsupported("الرابط مرجعش قائمة HLS صالحة (غالبًا اللينك انتهت صلاحيته — اصطاده تاني)")
                if (Hls.isMaster(text)) cands = Hls.candidates(Hls.parseMaster(text, url)).ifEmpty { throw Unsupported("قائمة HLS فاضية") }
                else { cands = listOf(url); firstText = text }
            }
            val u = cands[candIdx]
            val text = if (u == url && firstText != null) firstText!! else String(http(u), Charsets.UTF_8)
            val m = Hls.parseMedia(text, u)
            m.unsupported?.let { throw Unsupported(it) }
            if (m.live) throw Unsupported("البث المباشر (live) مش مدعوم")
            if (m.segs.isEmpty()) throw Unsupported("قائمة HLS مفيهاش مقاطع")
            media = m; loadErr = null
            log("📡 HLS: ${m.segs.size} مقطع، ${(m.total / 60).toInt()} دقيقة" + (if (cands.size > 1) " (قائمة ${candIdx + 1}/${cands.size})" else "") +
                " — بحمّل باتش باتش، ومقدّمًا $aheadPatches باتشات قدّام بس")
            return m
        } catch (e: Unsupported) { throw e
        } catch (e: Exception) { loadErr = e; loadErrAt = System.currentTimeMillis(); throw e }
    }

    /** القائمة الحالية من غير مسار صوت: جرّب اللي بعدها. بترجّع true لو في بديل (أو حد غيرنا بدّل خلاص) */
    @Synchronized private fun switchCandidate(from: Hls.Media): Boolean {
        if (media !== from) return true
        if (candIdx + 1 >= cands.size) return false
        candIdx++; media = null; loadErr = null
        log("🔁 القائمة دي من غير صوت قابل للفك — بجرّب القائمة ${candIdx + 1}/${cands.size}")
        return true
    }

    override fun durationSec(): Double = try { load().total } catch (_: Exception) { 0.0 }

    // ---------- مقاطع على الديسك ----------
    private fun sha1(s: String): String = java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun segName(s: Hls.Seg) = sha1(s.url + "|" + s.off + "|" + s.len)
    private fun cached(s: Hls.Seg): Boolean = File(dir, segName(s) + ".seg").let { it.exists() && it.length() > 0 }

    private fun decrypt(b: ByteArray, s: Hls.Seg, k: Hls.KeyInfo): ByteArray {
        val ku = k.uri ?: throw Unsupported(DRM_MSG)
        val kb = synchronized(keys) { keys[ku] } ?: http(ku).also { synchronized(keys) { keys[ku] = it } }
        val iv = IvParameterSpec(Hls.ivBytes(k.iv, s.seq)); val sk = SecretKeySpec(kb, "AES")
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, sk, iv) }.doFinal(b)
        } catch (e: javax.crypto.BadPaddingException) {   // بعض السيرفرات بتشفّر من غير padding
            Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, sk, iv) }.doFinal(b.copyOf(b.size - b.size % 16))
        }
    }

    private fun segFile(s: Hls.Seg, hasMap: Boolean): File {
        val name = segName(s)
        val f = File(dir, "$name.seg")
        if (f.exists() && f.length() > 0) { f.setLastModified(System.currentTimeMillis()); mine.add(name); return f }
        val lock = locks.getOrPut(name) { Any() }
        synchronized(lock) {
            if (f.exists() && f.length() > 0) return f
            var b = http(s.url, s.off, s.len)
            val k = s.key
            if (k != null && k.method == "AES-128") b = decrypt(b, s, k)
            b = Hls.clean(b, hasMap)
            if (b.isEmpty()) throw IOException("مقطع HLS فاضي")
            val part = File(dir, "$name.part")
            part.writeBytes(b)
            if (!part.renameTo(f)) { f.writeBytes(b); part.delete() }
            mine.add(name)
        }
        return f
    }

    private fun mapFile(m: Hls.Media): File? {
        val mu = m.mapUrl ?: return null
        val f = File(dir, sha1("map|" + mu + "|" + m.mapOff + "|" + m.mapLen) + ".map")
        if (f.exists() && f.length() > 0) return f
        synchronized(locks.getOrPut(f.name) { Any() }) {
            if (f.exists() && f.length() > 0) return f
            val part = File(dir, f.name + ".part"); part.writeBytes(http(mu, m.mapOff, m.mapLen))
            if (!part.renameTo(f)) { f.writeBytes(part.readBytes()); part.delete() }
            mine.add(f.name.removeSuffix(".map"))
        }
        return f
    }

    /** نزّل المقاطع دي (4 مع بعض) وارجع لما تخلص كلها؛ أول فشل بيتبعت */
    private fun fetch(list: List<Hls.Seg>, hasMap: Boolean) {
        val todo = list.filter { !cached(it) }
        if (todo.isEmpty()) return
        if (todo.size == 1) { segFile(todo[0], hasMap); return }
        val fs = todo.map { s -> NET.submit<File> { segFile(s, hasMap) } }
        var err: Throwable? = null
        for (f in fs) try { f.get() } catch (e: java.util.concurrent.ExecutionException) { if (err == null) err = e.cause ?: e }
        err?.let { throw (it as? Exception) ?: IOException(it.toString()) }
    }

    /** نافذة التحميل المقدّم: مقاطع الـ N باتشات اللي بعد المجال ده، في الخلفية. أي طلب جديد بيلغي القديم. */
    private fun prefetch(m: Hls.Media, fromSec: Double, patchLen: Double) {
        val gen = preGen.incrementAndGet()
        val to = fromSec + maxOf(30.0, patchLen) * aheadPatches
        val hasMap = m.mapUrl != null
        val segs = m.segs.filter { it.start >= fromSec - 0.5 && it.start < to && !cached(it) }
        if (segs.isEmpty()) return
        BG.execute {
            try {
                for (grp in segs.chunked(2)) {
                    if (preGen.get() != gen) return@execute
                    val fs = grp.map { s -> PRE.submit<File> { segFile(s, hasMap) } }
                    for (f in fs) try { f.get() } catch (e: Exception) { LogStore.err("AudioIO:467", e) }   // الفشل هنا مش مهم: الباتش نفسه هيعيد المحاولة وقت ما يحتاجه
                }
            } catch (e: Throwable) { LogStore.err("AudioIO:469", e) }
        }
    }

    private fun evict() {
        val now = System.currentTimeMillis()
        if (now - lastEvict < 20_000) return
        lastEvict = now
        try {
            val all = dir.listFiles() ?: return
            var total = all.sumOf { it.length() }
            if (total <= CACHE_MAX) return
            for (f in all.sortedBy { it.lastModified() }) {
                if (total <= CACHE_MAX * 7 / 10) break
                if (now - f.lastModified() < 90_000) continue
                total -= f.length(); f.delete()
            }
        } catch (e: Throwable) { LogStore.err("AudioIO:486", e) }
    }

    // ---------- فك الصوت ----------
    override fun wav(startSec: Double, endSec: Double): WavChunk? {
        var m = load()
        while (true) {
            try { return decodeRange(m, startSec, endSec) }
            catch (e: Unsupported) {
                if ((e.message ?: "").contains("ملوش مسار صوت") && switchCandidate(m)) { m = load(); continue }
                throw e
            } catch (e: HlsBadMedia) {
                log("⚠ " + (e.message ?: "").take(220))
                if (switchCandidate(m)) { m = load(); continue }
                throw e
            }
        }
    }

    private fun decodeRange(m: Hls.Media, startSec: Double, endSec: Double): WavChunk? {
        val need = m.segs.filter { it.start < endSec && it.start + it.dur > startSec - preRoll }
        if (need.isEmpty()) return null
        val hasMap = m.mapUrl != null
        evict()
        fetch(need, hasMap)
        prefetch(m, endSec, endSec - startSec)
        val tmp = File.createTempFile("hls_chunk_", ".bin", ctx.cacheDir)
        val e = MediaExtractor()
        var step = "بداية"
        try {
            step = "كتابة الملف المؤقت"
            tmp.outputStream().buffered(256 * 1024).use { o ->
                mapFile(m)?.let { mf -> mf.inputStream().use { it.copyTo(o) } }
                for (s in need) segFile(s, hasMap).inputStream().use { it.copyTo(o) }
            }
            step = "فتح المقاطع"
            try { e.setDataSource(tmp.path) } catch (t: IOException) { throw HlsBadMedia("مقدرتش أقرأ مقاطع HLS: " + (t.message ?: "").take(80)) }
            step = "اختيار مسار الصوت"
            val t = Tracks.pick(e, pref, log)
            e.selectTrack(t)
            val f = e.getTrackFormat(t)
            val first = e.sampleTime.let { if (it < 0) 0L else it }
            val base = need.first().start
            val startUs = first + ((startSec - base) * 1_000_000).toLong()
            val endUs = first + ((endSec - base) * 1_000_000).toLong()
            step = "seek"
            e.seekTo(maxOf(startUs - (preRoll * 1_000_000).toLong(), first), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            step = "فك الصوت"
            val sink = PcmSink(endSec - startSec)
            Decoder.decode(e, f, startUs, endUs, sink)
            if (sink.isEmpty()) return null
            return sink.finish(base + (sink.firstPtsUs - first) / 1_000_000.0)
        } catch (x: IllegalArgumentException) { throw HlsBadMedia("فك الصوت فشل [خطوة: $step]: " + x.javaClass.simpleName + (x.message?.let { " $it" } ?: ""))
        } catch (x: IllegalStateException) { throw HlsBadMedia("فك الصوت فشل [خطوة: $step]: " + x.javaClass.simpleName + (x.message?.let { " $it" } ?: ""))
        } finally { try { e.release() } catch (e: Exception) { LogStore.err("AudioIO:540", e) }; tmp.delete() }
    }

    override fun close() {
        preGen.incrementAndGet()
        for (n in mine) for (ext in listOf("seg", "map", "part")) File(dir, "$n.$ext").delete()
        mine.clear()
    }
}
