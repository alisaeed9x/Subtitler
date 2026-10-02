import com.tttt.subtitler.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

var fails = 0
fun check(n: String, ok: Boolean, x: String = "") { println((if (ok) "PASS " else "FAIL ") + n + (if (x.isNotEmpty()) "  [$x]" else "")); if (!ok) fails++ }

fun sineShorts(rate: Int, ch: Int, sec: Double, freq: Double, amp: Double, chMask: Int = -1): ShortArray {
    val n = (rate * sec).toInt(); val a = ShortArray(n * ch)
    for (i in 0 until n) { val v = (Math.sin(2 * Math.PI * freq * i / rate) * amp * 32767).toInt().toShort(); for (c in 0 until ch) if (chMask == -1 || chMask == c) a[i * ch + c] = v }
    return a
}
fun feed(sink: PcmSink, a: ShortArray, ch: Int, rate: Int, startUs: Long, endUs: Long, bufFrames: Int = 1024) {
    var f = 0; val total = a.size / ch
    while (f < total) {
        val e = minOf(total, f + bufFrames)
        val part = a.copyOfRange(f * ch, e * ch)
        if (sink.addShorts(part, ch, rate, f * 1_000_000L / rate, startUs, endUs)) return
        f = e
    }
}
fun pcmOf(w: WavChunk): ShortArray { val b = ByteBuffer.wrap(w.bytes, 44, w.bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN); val s = ShortArray((w.bytes.size - 44) / 2); b.asShortBuffer().get(s); return s }
fun crossings(s: ShortArray): Int { var c = 0; for (i in 1 until s.size) if ((s[i - 1] < 0) != (s[i] < 0)) c++; return c }

fun main() {
    for ((rate, ch) in listOf(48000 to 2, 44100 to 1, 22050 to 2, 8000 to 1, 16000 to 1)) {
        val sink = PcmSink(1.0)
        feed(sink, sineShorts(rate, ch, 3.0, 440.0, 0.5), ch, rate, 1_000_000, 2_000_000)
        val w = sink.finish(sink.firstPtsUs / 1e6)
        val p = pcmOf(w)
        check("$rate Hz / $ch ch → 16k mono: عدد العينات ≈ 16000", Math.abs(p.size - 16000) <= 3, "n=${p.size}")
        check("$rate/$ch: بداية المقطع عند 1.0 ث", Math.abs(w.startSec - 1.0) < 0.001, "start=${w.startSec}")
        val z = crossings(p)
        check("$rate/$ch: التردد 440Hz محفوظ", Math.abs(z - 880) <= 6, "crossings=$z")
        check("$rate/$ch: مش صامت", !w.silent)
        check("$rate/$ch: مدة ≈ 1ث", Math.abs(w.durSec - 1.0) < 0.001, "dur=${w.durSec}")
    }
    // 5.1 : الصوت في السنتر بس
    run {
        val sink = PcmSink(1.0)
        feed(sink, sineShorts(48000, 6, 2.0, 300.0, 0.6, chMask = 2), 6, 48000, 0, 1_000_000)
        val p = pcmOf(sink.finish(0.0)); val mx = p.maxOf { Math.abs(it.toInt()) }
        check("5.1: صوت السنتر بيوصل (دايلوج)", mx > 3000, "max=$mx")
    }
    // صمت
    run {
        val sink = PcmSink(1.0)
        feed(sink, ShortArray(48000 * 2 * 4), 2, 48000, 0, 4_000_000)
        check("صمت تام → silent=true", sink.finish(0.0).silent)
        val s2 = PcmSink(1.0); feed(s2, sineShorts(16000, 1, 4.0, 200.0, 0.3), 1, 16000, 0, 4_000_000)
        check("كلام → silent=false", !s2.finish(0.0).silent)
    }
    // WAV header
    run {
        val sink = PcmSink(1.0); feed(sink, sineShorts(16000, 1, 1.0, 200.0, 0.3), 1, 16000, 0, 1_000_000)
        val w = sink.finish(0.0); val b = ByteBuffer.wrap(w.bytes).order(ByteOrder.LITTLE_ENDIAN)
        check("WAV: RIFF/WAVE/fmt/data", String(w.bytes, 0, 4) == "RIFF" && String(w.bytes, 8, 4) == "WAVE" && String(w.bytes, 12, 4) == "fmt " && String(w.bytes, 36, 4) == "data")
        check("WAV: أطوال وmetadata صح", b.getInt(4) == w.bytes.size - 8 && b.getInt(40) == w.bytes.size - 44 && b.getShort(22).toInt() == 1 && b.getInt(24) == 16000 && b.getShort(34).toInt() == 16)
    }
    // مجال فارغ
    run { val s = PcmSink(1.0); feed(s, sineShorts(16000, 1, 1.0, 200.0, 0.3), 1, 16000, 5_000_000, 6_000_000); check("مجال بعد نهاية الصوت → فاضي", s.isEmpty()) }

    // ===== HLS =====
    val master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"ar\",DEFAULT=YES,URI=\"audio/ar.m3u8\"\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"aud\"\nhi/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO=\"aud\"\nlo/index.m3u8\n"
    val m = Hls.parseMaster(master, "https://cdn.example.com/v/master.m3u8")
    check("HLS master: اكتشاف", Hls.isMaster(master) && m.variants.size == 2 && m.audio.size == 1)
    check("HLS: بيختار rendition الصوت لوحده", Hls.pick(m) == "https://cdn.example.com/v/audio/ar.m3u8", Hls.pick(m) ?: "")
    val m2 = Hls.parseMaster("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900\nhi.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=300\nlo.m3u8\n", "https://x.com/a/m.m3u8")
    check("HLS: من غير صوت لوحده → أقل bandwidth", Hls.pick(m2) == "https://x.com/a/lo.m3u8", Hls.pick(m2) ?: "")
    val media = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x000102030405060708090A0B0C0D0E0F\n" +
        "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:4.5,\n/abs/seg2.ts\n#EXT-X-ENDLIST\n"
    val md = Hls.parseMedia(media, "https://h.com/p/index.m3u8")
    check("HLS media: segments + أوقات", md.segs.size == 2 && md.segs[1].start == 6.0 && Math.abs(md.total - 10.5) < 1e-9 && !md.live && md.unsupported == null)
    check("HLS media: روابط نسبية ومطلقة", md.segs[0].url == "https://h.com/p/seg1.ts" && md.segs[1].url == "https://h.com/abs/seg2.ts" && md.mapUrl == "https://h.com/p/init.mp4", md.segs[1].url)
    check("HLS: مفتاح AES-128 والـ IV", md.segs[0].key?.method == "AES-128" && md.segs[0].key?.uri == "https://h.com/p/key.bin" && Hls.ivBytes(md.segs[0].key?.iv, 7)[15].toInt() == 15)
    check("HLS: IV الافتراضي = رقم الـ segment", Hls.ivBytes(null, 258).let { it[15].toInt() == 2 && it[14].toInt() == 1 })
    check("HLS: DRM (SAMPLE-AES) بيتكشف", Hls.parseMedia("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"x\"\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n", "https://a.com/i.m3u8").unsupported != null)
    check("HLS: Widevine keyformat بيتكشف", Hls.parseMedia("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"x\",KEYFORMAT=\"com.widevine\"\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n", "https://a.com/i.m3u8").unsupported != null)
    check("HLS: live (من غير ENDLIST)", Hls.parseMedia("#EXTM3U\n#EXTINF:5,\na.ts\n", "https://a.com/i.m3u8").live)
    check("HLS: BYTERANGE غير مدعوم", Hls.parseMedia("#EXTM3U\n#EXT-X-BYTERANGE:100@0\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n", "https://a.com/i.m3u8").unsupported != null)
    // AES فعلي
    run {
        val key = ByteArray(16) { it.toByte() }; val iv = Hls.ivBytes("0x000102030405060708090A0B0C0D0E0F", 0)
        val plain = ByteArray(1000) { (it * 7).toByte() }
        val enc = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv)) }.doFinal(plain)
        val dec = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(Hls.ivBytes("0x000102030405060708090A0B0C0D0E0F", 0))) }.doFinal(enc)
        check("AES-128 فك التشفير بنفس طريقة الكود", dec.contentEquals(plain))
    }
    println(if (fails == 0) "\nكل الاختبارات نجحت" else "\nفشل $fails")
    System.exit(if (fails == 0) 0 else 1)
}
