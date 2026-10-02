package com.tttt.subtitler

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
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
        try { if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null) return true } catch (_: Exception) {}
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
            codec.configure(fmt, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = 2
            val deadline = System.currentTimeMillis() + 90_000
            while (!outDone) {
                if (System.currentTimeMillis() > deadline) throw IOException("فك الصوت أخد وقت طويل")
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)
                        val n = if (ib != null) ex.readSampleData(ib, 0) else -1
                        val t = ex.sampleTime
                        if (n < 0 || t < 0 || t >= endUs + 300_000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) throw Unsupported(DRM_MSG)
                            codec.queueInputBuffer(ii, 0, n, t, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    enc = if (of.containsKey("pcm-encoding")) of.getInteger("pcm-encoding") else 2
                } else if (oi >= 0) {
                    var reached = false
                    val ob = codec.getOutputBuffer(oi)
                    if (info.size > 0 && ob != null) {
                        ob.order(ByteOrder.LITTLE_ENDIAN); ob.position(info.offset); ob.limit(info.offset + info.size)
                        if (enc == 4) {
                            val fb = ob.asFloatBuffer(); val a = FloatArray(fb.remaining()); fb.get(a)
                            reached = sink.addFloats(a, ch, rate, info.presentationTimeUs, startUs, endUs)
                        } else if (enc == 2) {
                            val sb = ob.asShortBuffer(); val a = ShortArray(sb.remaining()); sb.get(a)
                            reached = sink.addShorts(a, ch, rate, info.presentationTimeUs, startUs, endUs)
                        } else throw Exception("نوع PCM غير مدعوم ($enc)")
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (reached || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }
}

/** ملف على الموبايل (Uri) أو رابط http مباشر. الـ extractor بيفضل مفتوح ونعمل seek لكل مقطع (من غير ما نحمّل الملف في الرام). */
class FileSource(
    private val ctx: Context, private val uri: Uri?, private val url: String?,
    private val hdr: Map<String, String>, private val pref: Int, private val log: (String) -> Unit
) : AudioSource {
    private var ex: MediaExtractor? = null
    private var fmt: MediaFormat? = null
    private var durUs = 0L
    @Volatile private var preRollUs = 0L
    override fun setPreRoll(sec: Double) { preRollUs = (sec * 1_000_000).toLong() }

    private fun open() {
        val e = MediaExtractor()
        try {
            if (uri != null) e.setDataSource(ctx, uri, null) else e.setDataSource(url!!, hdr)
            val t = Tracks.pick(e, pref, log)
            e.selectTrack(t)
            val f = e.getTrackFormat(t)
            durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            ex = e; fmt = f
        } catch (t: Throwable) { try { e.release() } catch (_: Exception) {}; throw t }
    }

    @Synchronized override fun durationSec(): Double {
        if (ex == null) try { open() } catch (_: Exception) { return 0.0 }
        return durUs / 1_000_000.0
    }

    @Synchronized override fun wav(startSec: Double, endSec: Double): WavChunk? {
        if (ex == null) open()
        val e = ex!!; val f = fmt!!
        val startUs = (startSec * 1_000_000).toLong(); val endUs = (endSec * 1_000_000).toLong()
        try {
            e.seekTo(maxOf(0L, startUs - preRollUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sink = PcmSink(endSec - startSec)
            Decoder.decode(e, f, startUs, endUs, sink)
            if (sink.isEmpty()) return null
            return sink.finish(sink.firstPtsUs / 1_000_000.0)
        } catch (t: Throwable) {
            if (t !is Unsupported) { try { e.release() } catch (_: Exception) {}; ex = null }
            throw t
        }
    }

    @Synchronized override fun close() { try { ex?.release() } catch (_: Exception) {}; ex = null }
}

/** روابط HLS: بنحمّل الـ segments اللي المقطع محتاجها بس، وبنفك AES-128 لو موجود. */
class HlsSource(
    private val ctx: Context, private val url: String, private val hdr: Map<String, String>,
    private val pref: Int, private val log: (String) -> Unit
) : AudioSource {
    private var media: Hls.Media? = null
    private var mapBytes: ByteArray? = null
    private val cache = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 6
    }
    private val keys = HashMap<String, ByteArray>()
    @Volatile private var preRoll = 0.0
    override fun setPreRoll(sec: Double) { preRoll = sec }

    private fun http(u: String): ByteArray {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 60000
            for ((k, v) in hdr) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code >= 300) throw IOException("HTTP $code من $u")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun load(): Hls.Media {
        media?.let { return it }
        var u = url
        var text = String(http(u), Charsets.UTF_8)
        if (Hls.isMaster(text)) {
            u = Hls.pick(Hls.parseMaster(text, u)) ?: throw Unsupported("قائمة HLS فاضية")
            text = String(http(u), Charsets.UTF_8)
        }
        val m = Hls.parseMedia(text, u)
        m.unsupported?.let { throw Unsupported(it) }
        if (m.live) throw Unsupported("البث المباشر (live) مش مدعوم")
        if (m.segs.isEmpty()) throw Unsupported("قائمة HLS مفيهاش مقاطع")
        media = m
        log("📡 HLS: ${m.segs.size} مقطع، ${(m.total / 60).toInt()} دقيقة")
        return m
    }

    override fun durationSec(): Double = try { load().total } catch (_: Exception) { 0.0 }

    private fun segBytes(s: Hls.Seg): ByteArray {
        synchronized(cache) { cache[s.seq]?.let { return it } }
        var b = http(s.url)
        val k = s.key
        if (k != null && k.method == "AES-128") {
            val ku = k.uri ?: throw Unsupported(DRM_MSG)
            val kb = synchronized(keys) { keys[ku] } ?: http(ku).also { synchronized(keys) { keys[ku] = it } }
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kb, "AES"), IvParameterSpec(Hls.ivBytes(k.iv, s.seq)))
            b = c.doFinal(b)
        }
        synchronized(cache) { cache[s.seq] = b }
        return b
    }

    @Synchronized override fun wav(startSec: Double, endSec: Double): WavChunk? {
        val m = load()
        val need = m.segs.filter { it.start < endSec && it.start + it.dur > startSec - preRoll }
        if (need.isEmpty()) return null
        val tmp = File(ctx.cacheDir, "hls_chunk.bin")
        if (m.mapUrl != null && mapBytes == null) mapBytes = http(m.mapUrl)
        tmp.outputStream().buffered().use { o ->
            mapBytes?.let { o.write(it) }
            for (s in need) o.write(segBytes(s))
        }
        val e = MediaExtractor()
        try {
            e.setDataSource(tmp.path)
            val t = Tracks.pick(e, pref, log)
            e.selectTrack(t)
            val f = e.getTrackFormat(t)
            val first = e.sampleTime.let { if (it < 0) 0L else it }
            val base = need.first().start
            val startUs = first + ((startSec - base) * 1_000_000).toLong()
            val endUs = first + ((endSec - base) * 1_000_000).toLong()
            e.seekTo(maxOf(startUs - (preRoll * 1_000_000).toLong(), first), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sink = PcmSink(endSec - startSec)
            Decoder.decode(e, f, startUs, endUs, sink)
            if (sink.isEmpty()) return null
            return sink.finish(base + (sink.firstPtsUs - first) / 1_000_000.0)
        } finally { try { e.release() } catch (_: Exception) {}; tmp.delete() }
    }

    override fun close() { synchronized(cache) { cache.clear() } }
}
