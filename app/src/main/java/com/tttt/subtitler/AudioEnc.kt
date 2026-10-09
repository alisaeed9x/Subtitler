package com.tttt.subtitler

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.Collections
import java.util.WeakHashMap

/**
 * (v166) ضغط الصوت قبل الإرسال: WAV 16kHz mono (≈32KB/ث) → AAC-LC ADTS (≈4KB/ث) = أصغر بحوالي 8 مرات.
 * بيتعمل بـ MediaCodec الموجود في أي أندرويد (من غير مكتبات). لو أي حاجة فشلت بنرجع للـ WAV زي ما كان (مفيش خسارة).
 * الـ WAV الأصلي فاضل في الذاكرة للمعالجات المحلية (قص الصمت / كشف الثغرات) — الضغط بس عند الرفع.
 */
object AudioEnc {
    class Packed(val bytes: ByteArray, val mime: String, val srcSize: Int)

    /** معدل البت: 32kbps لكلام mono 16kHz شفاف عمليًا؛ ارفعه لـ 48000 لو عايز أمان أكتر على حساب الحجم */
    const val BITRATE = 32000

    private val cache: MutableMap<ByteArray, Packed> = Collections.synchronizedMap(WeakHashMap<ByteArray, Packed>())
    @Volatile private var fails = 0

    fun pack(wav: ByteArray): Packed {
        cache[wav]?.let { return it }
        var p: Packed? = null
        if (fails < 3 && isWav(wav)) {
            try { p = aac(wav)?.takeIf { it.bytes.size < wav.size * 0.8 } }
            catch (_: Throwable) { fails++ }
        }
        val r = p ?: Packed(wav, "audio/wav", wav.size)
        cache[wav] = r
        return r
    }

    private fun isWav(b: ByteArray) = b.size > 46 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    private fun freqIdx(rate: Int): Int = when (rate) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5; 24000 -> 6
        22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> -1
    }

    private fun adts(out: java.io.ByteArrayOutputStream, size: Int, fi: Int) {
        val fl = size + 7
        val h = ByteArray(7)
        h[0] = 0xFF.toByte(); h[1] = 0xF1.toByte()
        h[2] = ((1 shl 6) or (fi shl 2) or 0).toByte()          // LC (aot 2 → 1) ، قناة واحدة
        h[3] = ((1 shl 6) or (fl shr 11)).toByte()
        h[4] = ((fl shr 3) and 0xFF).toByte()
        h[5] = (((fl and 7) shl 5) or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        out.write(h)
    }

    private fun aac(wav: ByteArray): Packed? {
        val rate = (wav[24].toInt() and 255) or ((wav[25].toInt() and 255) shl 8) or ((wav[26].toInt() and 255) shl 16) or ((wav[27].toInt() and 255) shl 24)
        val fi = freqIdx(rate); if (fi < 0) return null
        val total = wav.size - 44
        if (total < rate / 5) return null    // أقل من 0.1ث مش مستاهل
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val out = java.io.ByteArrayOutputStream(total / 6 + 1024)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0; var fed = 0L; var eosSent = false; var done = false
            val deadline = System.currentTimeMillis() + 30_000L
            while (!done) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("aac timeout")
                if (!eosSent) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        val n = minOf(ib.capacity() and 1.inv(), total - pos)
                        val pts = fed * 1_000_000L / rate
                        if (n <= 0) { codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true }
                        else { ib.put(wav, 44 + pos, n); codec.queueInputBuffer(ii, 0, n, pts, 0); pos += n; fed += n / 2 }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size); ob.get(chunk)
                        adts(out, info.size, fi); out.write(chunk)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    codec.releaseOutputBuffer(oi, false)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
        }
        val b = out.toByteArray()
        return if (b.size < 64) null else Packed(b, "audio/aac", wav.size)
    }
}
