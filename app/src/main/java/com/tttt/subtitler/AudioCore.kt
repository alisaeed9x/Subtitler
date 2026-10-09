package com.tttt.subtitler

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** مقطع صوت جاهز للإرسال: WAV 16kHz mono PCM16. startSec = وقت أول عينة فعلاً في الفيديو. */
class WavChunk(val bytes: ByteArray, val startSec: Double, val durSec: Double, val silent: Boolean, val gain: Double = 1.0)

interface AudioSource {
    /** مدة الفيديو بالثواني لو معروفة، وإلا 0 */
    fun durationSec(): Double
    /** صوت من startSec لـ endSec. يرجع null لو مفيش صوت في المجال ده. */
    fun wav(startSec: Double, endSec: Double): WavChunk?
    fun close()
    /** فك الصوت من قبل نقطة البداية بالثواني دي (دقة توقيت أعلى). الافتراضي: مفيش. */
    fun setPreRoll(sec: Double) {}
}

object Wav {
    fun header(dataLen: Int, rate: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataLen); b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen)
        return b.array()
    }
}

/**
 * يستقبل إطارات PCM (أي عدد قنوات / معدل عينات) ويطلّع PCM16 mono 16kHz.
 * - دمج القنوات لمونو (5.1: أمامي يمين/يسار + السنتر)
 * - تقليل المعدل بمتوسط صندوقي (box filter) عشان نقلل الـ aliasing
 * - بيحسب نسبة الكلام (نفس فكرة فلتر الصمت VAD في الأصل: نوافذ 0.5ث و RMS > 0.012)
 */
class PcmSink(expectedSec: Double = 60.0, private val outRate: Int = 16000) {
    private var buf = ByteArray(maxOf(4096, ((expectedSec + 2.0) * outRate * 2).toInt()))
    private var len = 0
    var firstPtsUs = -1L
        private set
    private var inRate = 0
    private var step = 1.0
    private var accSum = 0.0
    private var accCnt = 0.0
    private var winN = 0
    private var winSq = 0.0
    private var winTotal = 0
    private var winActive = 0
    private val winSize = outRate / 2

    // (v166) فلتر low-pass (Butterworth درجة 4) قبل خفض المعدل: بيمنع الـ aliasing (الهسهسة/الخشونة) من الترددات فوق 8kHz
    private class Biq(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0; var z2 = 0.0
        fun run(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
        companion object {
            fun lowpass(fs: Double, fc: Double, q: Double): Biq {
                val w = 2 * Math.PI * fc / fs; val c = Math.cos(w); val al = Math.sin(w) / (2 * q); val a0 = 1 + al
                return Biq((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
            }
        }
    }
    private var lp1: Biq? = null
    private var lp2: Biq? = null
    // (v166) ستريو بفرق طور (L ≈ -R): المتوسط بيلغي الكلام. بنقيس الارتباط ولو سالب بناخد القناة الأعلى بس
    private var sLR = 0.0; private var sLL = 0.0; private var sRR = 0.0
    private var stereoMode = 0   // 0 = متوسط، 1 = يسار بس، 2 = يمين بس

    val samples: Int get() = len / 2
    fun isEmpty() = len == 0

    private fun configure(rate: Int) {
        inRate = rate; step = rate.toDouble() / outRate; accSum = 0.0; accCnt = 0.0
        if (rate > outRate) { lp1 = Biq.lowpass(rate.toDouble(), outRate * 0.45, 0.5412); lp2 = Biq.lowpass(rate.toDouble(), outRate * 0.45, 1.3066) }
        else { lp1 = null; lp2 = null }
    }

    private fun emit(v: Float) {
        val c = if (v > 1f) 1f else if (v < -1f) -1f else v
        val s = (c * 32767f).toInt()
        if (len + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = (s and 0xFF).toByte(); buf[len++] = ((s shr 8) and 0xFF).toByte()
        winSq += (c * c).toDouble(); winN++
        if (winN >= winSize) closeWindow()
    }

    private fun closeWindow() {
        if (winN == 0) return
        val rms = Math.sqrt(winSq / winN)
        winTotal++; if (rms > 0.012) winActive++
        winN = 0; winSq = 0.0
    }

    private fun mono(x0: Float) {
        val a = lp1; val b = lp2
        val x = if (a != null && b != null) b.run(a.run(x0.toDouble())).toFloat() else x0
        var w = 1.0
        while (w > 1e-9) {
            val need = step - accCnt
            if (w >= need - 1e-12) {
                accSum += x * need
                emit((accSum / step).toFloat())
                accSum = 0.0; accCnt = 0.0; w -= need
            } else { accSum += x * w; accCnt += w; w = 0.0 }
        }
    }

    private fun mix(get: (Int, Int) -> Float, f: Int, ch: Int): Float = when {
        ch == 1 -> get(f, 0)
        ch == 2 -> when (stereoMode) { 1 -> get(f, 0); 2 -> get(f, 1); else -> (get(f, 0) + get(f, 1)) * 0.5f }
        // 5.1: الحوار في السنتر؛ نركّز عليه ونخلّي الأمامي خفيف عشان الموسيقى/المؤثرات ماتغطيش على الكلام
        ch >= 6 -> 0.2f * (get(f, 0) + get(f, 1)) + 0.8f * get(f, 2)
        else -> { var s = 0f; for (c in 0 until ch) s += get(f, c); s / ch }
    }

    /**
     * @param get دالة (رقم الإطار، رقم القناة) -> قيمة بين -1 و 1
     * @return true لو وصلنا لنهاية المجال (endUs)
     */
    fun addFrames(get: (Int, Int) -> Float, frames: Int, channels: Int, rate: Int, ptsUs: Long, startUs: Long, endUs: Long): Boolean {
        if (rate <= 0 || channels <= 0) return false
        if (channels == 2 && stereoMode == 0) {
            for (f in 0 until frames) { val l = get(f, 0).toDouble(); val r = get(f, 1).toDouble(); sLR += l * r; sLL += l * l; sRR += r * r }
            if (sLL + sRR > 1.0 && sLR < -0.5 * Math.sqrt(sLL * sRR)) stereoMode = if (sLL >= sRR) 1 else 2
        }
        for (f in 0 until frames) {
            val t = ptsUs + (f * 1_000_000L) / rate
            if (t < startUs) continue
            if (t >= endUs) return true
            if (firstPtsUs < 0) { firstPtsUs = t; configure(rate) }
            else if (rate != inRate) configure(rate)
            mono(mix(get, f, channels))
        }
        return false
    }

    fun addShorts(a: ShortArray, channels: Int, rate: Int, ptsUs: Long, startUs: Long, endUs: Long): Boolean =
        addFrames({ f, c -> a[f * channels + c] / 32768f }, a.size / channels, channels, rate, ptsUs, startUs, endUs)

    fun addFloats(a: FloatArray, channels: Int, rate: Int, ptsUs: Long, startUs: Long, endUs: Long): Boolean =
        addFrames({ f, c -> a[f * channels + c] }, a.size / channels, channels, rate, ptsUs, startUs, endUs)

    fun finish(startSec: Double): WavChunk {
        closeWindow()
        val pcm = len
        val n = pcm / 2
        fun rd(k: Int): Int = ((buf[2 * k + 1].toInt() shl 8) or (buf[2 * k].toInt() and 0xFF)).toShort().toInt()
        fun wr(k: Int, v: Int) { val c = v.coerceIn(-32768, 32767); buf[2 * k] = (c and 0xFF).toByte(); buf[2 * k + 1] = ((c shr 8) and 0xFF).toByte() }
        // (v166) 1) high-pass 80Hz: يشيل الدمدمة/الـ DC/هزّة الميكروفون اللي بتوسّخ الكلام وبتضلّل قياس الصوت
        if (n > 64) {
            val w0 = 2 * Math.PI * 80.0 / outRate; val c = Math.cos(w0); val al = Math.sin(w0) / (2 * 0.7071); val a0 = 1 + al
            val b0 = (1 + c) / 2 / a0; val b1 = -(1 + c) / a0; val b2 = b0; val a1 = -2 * c / a0; val a2 = (1 - al) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            for (k in 0 until n) {
                val x = rd(k).toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x; y2 = y1; y1 = y
                wr(k, Math.round(y).toInt())
            }
        }
        // 2) تضخيم حسب مستوى الكلام الفعلي (مش أعلى قمة): قمة واحدة عالية (طقطقة/تصفيق) كانت بتمنع رفع باقي الكلام
        var appliedGain = 1.0
        var pk = 0
        for (k in 0 until n) { val a = Math.abs(rd(k)); if (a > pk) pk = a }
        val fr = outRate / 50   // إطار 20ms
        val nf = n / fr
        if (pk >= 300 && nf >= 10) {
            val lv = DoubleArray(nf)
            for (f in 0 until nf) { var sq = 0.0; for (k in f * fr until (f + 1) * fr) { val v = rd(k) / 32768.0; sq += v * v }; lv[f] = Math.sqrt(sq / fr) }
            val sorted = lv.sortedArray()
            val speech = sorted[(nf * 0.90).toInt().coerceIn(0, nf - 1)]    // مستوى الإطارات العالية = الكلام
            if (speech > 0.003) {
                val g = minOf(8.0, 0.12 / speech)                             // هدف: الكلام حوالي -18 dBFS
                if (g > 1.15) {
                    appliedGain = g
                    for (k in 0 until n) {
                        val v = rd(k) / 32768.0 * g
                        val a = Math.abs(v)
                        // 3) soft limiter: فوق 0.8 بنلين القمم بدل ما تتقصّ (تقطيع = تشويه بيوجع التعرّف)
                        val o = if (a <= 0.8) v else Math.signum(v) * (0.8 + 0.2 * Math.tanh((a - 0.8) / 0.2))
                        wr(k, Math.round(o * 32767.0).toInt())
                    }
                }
            }
        }
        val out = ByteArray(44 + pcm)
        System.arraycopy(Wav.header(pcm, outRate), 0, out, 0, 44)
        System.arraycopy(buf, 0, out, 44, pcm)
        val silent = winTotal > 0 && winActive.toDouble() / winTotal < 0.06
        return WavChunk(out, startSec, (pcm / 2).toDouble() / outRate, silent, appliedGain)
    }
}

/** تحليل قوائم HLS (m3u8): master / media مع AES-128 وكشف الـ DRM. */
object Hls {
    class Variant(val uri: String, val bw: Long, val audioGroup: String?)
    class AudioMedia(val group: String, val uri: String, val isDefault: Boolean)
    class Master(val variants: List<Variant>, val audio: List<AudioMedia>)
    class KeyInfo(val method: String, val uri: String?, val iv: String?)
    /** off/len = BYTERANGE (‎-1 = المقطع ملف كامل) */
    class Seg(val url: String, val dur: Double, val start: Double, val seq: Long, val key: KeyInfo?, val off: Long = -1, val len: Long = -1)
    class Media(val segs: List<Seg>, val mapUrl: String?, val total: Double, val live: Boolean, val unsupported: String?,
                val mapOff: Long = -1, val mapLen: Long = -1, val vod: Boolean = false)

    fun isMaster(t: String) = t.contains("#EXT-X-STREAM-INF")

    fun attrs(line: String): Map<String, String> {
        val body = line.substringAfter(':', "")
        val m = HashMap<String, String>()
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(body).forEach { m[it.groupValues[1]] = it.groupValues[2].trim().trim('"') }
        return m
    }

    fun resolve(base: String, ref: String): String = try { java.net.URI(base).resolve(ref.trim()).toString() } catch (_: Exception) { ref.trim() }

    fun parseMaster(t: String, base: String): Master {
        val lines = t.lines().map { it.trim() }
        val vs = ArrayList<Variant>(); val au = ArrayList<AudioMedia>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = attrs(l)
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) vs.add(Variant(resolve(base, lines[j]), a["BANDWIDTH"]?.toLongOrNull() ?: Long.MAX_VALUE, a["AUDIO"]))
                i = j
            } else if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")) {
                val a = attrs(l)
                val u = a["URI"]
                if (u != null) au.add(AudioMedia(a["GROUP-ID"] ?: "", resolve(base, u), a["DEFAULT"] == "YES"))
            }
            i++
        }
        return Master(vs, au)
    }

    /** أصغر variant، ولو ليه rendition صوت لوحده ناخده (تحميل أقل بكتير). */
    fun pick(m: Master): String? {
        val v = m.variants.minByOrNull { it.bw } ?: return m.audio.firstOrNull()?.uri
        val g = v.audioGroup
        if (g != null) {
            val cands = m.audio.filter { it.group == g }
            val a = cands.firstOrNull { it.isDefault } ?: cands.firstOrNull()
            if (a != null) return a.uri
        }
        return v.uri
    }

    /**
     * ترتيب القوائم اللي نجرّبها لاستخراج الصوت: الأفضل (pick) الأول، وبعدها renditions الصوت، وبعدها باقي الـ variants من الأصغر.
     * لو أول قائمة طلعت من غير مسار صوت (صوت منفصل / variant صورة بس) بننقل للي بعدها بدل ما نفشل.
     */
    fun candidates(m: Master): List<String> {
        val out = LinkedHashSet<String>()
        pick(m)?.let { out.add(it) }
        m.audio.sortedByDescending { it.isDefault }.forEach { out.add(it.uri) }
        m.variants.sortedBy { it.bw }.forEach { out.add(it.uri) }
        return out.toList()
    }

    private fun range(v: String?): Pair<Long, Long>? {
        if (v == null) return null
        val len = v.substringBefore('@').trim().toLongOrNull() ?: return null
        val off = v.substringAfter('@', "").trim().toLongOrNull() ?: -1L
        return len to off
    }

    fun parseMedia(t: String, base: String): Media {
        val lines = t.lines().map { it.trim() }
        val segs = ArrayList<Seg>()
        var seq = 0L; var firstSeq = 0L; var dur = -1.0; var total = 0.0
        var key: KeyInfo? = null
        var mapUrl: String? = null; var mapOff = -1L; var mapLen = -1L
        var ended = false; var vod = false
        var bad: String? = null
        var pendLen = -1L; var pendOff = -1L
        val lastEnd = HashMap<String, Long>()
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> { seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L; firstSeq = seq }
                l.startsWith("#EXT-X-PLAYLIST-TYPE:") -> if (l.contains("VOD", true)) vod = true
                l.startsWith("#EXT-X-KEY") -> {
                    val a = attrs(l)
                    val method = a["METHOD"] ?: "NONE"
                    val kf = a["KEYFORMAT"]
                    if (method == "NONE") key = null
                    else {
                        if (method != "AES-128" || (kf != null && kf != "identity")) bad = "الفيديو محمي (DRM / تشفير $method) ومش هينفع يتترجم"
                        key = KeyInfo(method, a["URI"]?.let { resolve(base, it) }, a["IV"])
                    }
                }
                l.startsWith("#EXT-X-MAP") -> {
                    val a = attrs(l)
                    a["URI"]?.let { mapUrl = resolve(base, it) }
                    range(a["BYTERANGE"])?.let { (len, off) -> mapLen = len; mapOff = if (off >= 0) off else 0L }
                }
                l.startsWith("#EXT-X-BYTERANGE:") -> range(l.substringAfter(':'))?.let { (len, off) -> pendLen = len; pendOff = off }
                l.startsWith("#EXTINF:") -> dur = l.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                l.startsWith("#EXT-X-ENDLIST") -> ended = true
                l.isNotEmpty() && !l.startsWith("#") && dur >= 0 -> {
                    val u = resolve(base, l)
                    var off = -1L; var len = -1L
                    if (pendLen > 0) {
                        len = pendLen
                        off = if (pendOff >= 0) pendOff else (lastEnd[u] ?: 0L)
                        lastEnd[u] = off + len
                    }
                    segs.add(Seg(u, dur, total, seq, key, off, len))
                    total += dur; seq++; dur = -1.0; pendLen = -1L; pendOff = -1L
                }
            }
        }
        // بعض المواقع بتنسى #EXT-X-ENDLIST في فيديو عادي: لو نوعها VOD أو بتبدأ من 0 وطويلة نعتبرها فيديو عادي مش بث
        val live = !ended && !vod && !(firstSeq == 0L && total >= 180.0)
        return Media(segs, mapUrl, total, live, bad, mapOff, mapLen, vod || ended)
    }

    /**
     * تنضيف مقطع بعد تحميله وفك تشفيره:
     * - شيل وسوم ID3 اللي في أول المقاطع الصوتية (AAC/MP3) عشان الدمج مايبوظش
     * - مواقع كتير بتحط «ترويسة صورة» (PNG/JPG/GIF) قبل بيانات TS عشان تهرّب من الحجب: بندوّر على أول بايت 0x47 متكرر كل 188 بايت ونقصّ اللي قبله
     */
    fun clean(b: ByteArray, hasMap: Boolean): ByteArray {
        var o = 0
        while (b.size - o > 10 && b[o] == 'I'.code.toByte() && b[o + 1] == 'D'.code.toByte() && b[o + 2] == '3'.code.toByte()) {
            val sz = ((b[o + 6].toInt() and 0x7F) shl 21) or ((b[o + 7].toInt() and 0x7F) shl 14) or ((b[o + 8].toInt() and 0x7F) shl 7) or (b[o + 9].toInt() and 0x7F)
            val foot = if ((b[o + 5].toInt() and 0x10) != 0) 10 else 0
            o += 10 + sz + foot
        }
        if (o >= b.size) return ByteArray(0)
        val isTs = b[o].toInt() == 0x47 && (b.size < o + 189 || b[o + 188].toInt() == 0x47)
        val isAdts = (b[o].toInt() and 0xFF) == 0xFF && b.size > o + 1 && (b[o + 1].toInt() and 0xF0) == 0xF0
        val isMp4 = b.size > o + 8 && String(b, o + 4, 4, Charsets.ISO_8859_1).let { it == "ftyp" || it == "styp" || it == "moof" || it == "moov" || it == "sidx" }
        if (!isTs && !isAdts && !isMp4 && !hasMap) {
            val lim = minOf(b.size - 377, o + 16384)
            var i = o + 1
            while (i < lim) {
                if (b[i].toInt() == 0x47 && b[i + 188].toInt() == 0x47 && b[i + 376].toInt() == 0x47) { o = i; break }
                i++
            }
        }
        return if (o == 0) b else b.copyOfRange(o, b.size)
    }

    fun ivBytes(iv: String?, seq: Long): ByteArray {
        val out = ByteArray(16)
        if (iv != null && iv.startsWith("0x", true)) {
            val hex = iv.substring(2).padStart(32, '0').takeLast(32)
            for (i in 0 until 16) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        } else {
            for (i in 0 until 8) out[15 - i] = ((seq shr (8 * i)) and 0xFF).toByte()
        }
        return out
    }
}
