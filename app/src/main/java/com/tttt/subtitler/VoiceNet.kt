package com.tttt.subtitler

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * (v172) بصمة الصوت العصبية (اختيارية). لو ملف الموديل `voice_embed.onnx` موجود بيتحمّل ويشتغل؛ لو مش موجود التطبيق بيكمّل بالبصمة الخفيفة زي v171 بالظبط.
 * مكان الملف (بالترتيب): getExternalFilesDir(null) ← filesDir ← assets/ جوه المشروع.
 * الموديل المتوقع: WeSpeaker/ECAPA-style — مدخل [1, T, 80] log-mel fbank (Kaldi) بعد CMN، مخرج متجه [1, D].
 */
object VoiceNet {
    /** مقياس المسافة: (1 - cosine) / SCALE بيتحوّل لنفس وحدات عتبات VoiceBank (T_MATCH=1.1). اتحدد بالتقدير ومحتاج ظبط على أصوات حقيقية */
    @JvmField var SCALE = 0.38
    private const val NMEL = 80
    private const val NFFT = 512
    private const val FRAME = 400
    private const val SHIFT = 160
    private const val MAX_SEC = 6.0
    private const val MIN_SEC = 0.8

    private var ctx: Context? = null
    private var env: OrtEnvironment? = null
    private var sess: OrtSession? = null
    private var inName = ""
    private var tried = false
    private val lock = Any()

    fun init(c: Context) { ctx = c.applicationContext }

    fun available(): Boolean = synchronized(lock) { load() }

    /** وضع البصمة من الإعدادات: light (خفيفة فقط) · neural (عصبي فقط) · combo (الاتنين معًا — الافتراضي) */
    fun mode(): String = try { Cfg.str("voice_mode", "combo").let { if (it == "light" || it == "neural") it else "combo" } } catch (_: Throwable) { "combo" }
    /** نحسب البصمة العصبية بس لو الوضع مش «خفيف» والموديل متاح */
    fun useNet(): Boolean = mode() != "light" && available()

    private fun load(): Boolean {
        if (sess != null) return true
        if (tried) return false
        tried = true
        val c = ctx ?: return false
        try {
            val ext = c.getExternalFilesDir(null)?.let { java.io.File(it, "voice_embed.onnx") }
            val loc = java.io.File(c.filesDir, "voice_embed.onnx")
            val bytes = when {
                ext != null && ext.exists() -> ext.readBytes()
                loc.exists() -> loc.readBytes()
                else -> c.assets.open("voice_embed.onnx").use { it.readBytes() }
            }
            val e = OrtEnvironment.getEnvironment()
            val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val s = e.createSession(bytes, o)
            env = e; sess = s; inName = s.inputNames.first()
            LogStore.add("🧠 موديل بصمة الصوت العصبي اتحمّل")
            return true
        } catch (t: Throwable) { return false }   // مفيش موديل/مكتبة: بصمة خفيفة بس
    }

    fun norm(v: FloatArray): FloatArray {
        var s = 0.0; for (x in v) s += x * x
        val n = sqrt(s).let { if (it < 1e-9) 1.0 else it }
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    fun dist(a: FloatArray, b: FloatArray): Double {
        var d = 0.0
        for (i in a.indices) d += a[i] * b[i]
        return (1.0 - d) / SCALE
    }

    /** بصمة عصبية لمجال من الـ pcm (ثواني نسبية). null لو قصير أو الموديل مش متاح أو حصل خطأ */
    fun embed(pcm: ShortArray, fromSec: Double, toSec: Double): FloatArray? {
        if (!available()) return null
        var a = max(0, (fromSec * VoiceDsp.RATE).toInt()); var b = min(pcm.size, (toSec * VoiceDsp.RATE).toInt())
        if (b - a < (MIN_SEC * VoiceDsp.RATE).toInt()) return null
        val maxN = (MAX_SEC * VoiceDsp.RATE).toInt()
        if (b - a > maxN) { val mid = (a + b) / 2; a = mid - maxN / 2; b = a + maxN }
        val feats = fbank(pcm, a, b) ?: return null
        synchronized(lock) {
            val e = env ?: return null; val s = sess ?: return null
            try {
                OnnxTensor.createTensor(e, arrayOf(feats)).use { t ->
                    s.run(mapOf(inName to t)).use { res ->
                        val o = res[0].value
                        val v = if (o is Array<*>) o[0] as FloatArray else o as FloatArray
                        return norm(v)
                    }
                }
            } catch (t: Throwable) { return null }
        }
    }

    private val povey = DoubleArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).pow(0.85) }
    private val melBank: Array<DoubleArray> = run {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val lo = mel(20.0); val hi = mel(VoiceDsp.RATE / 2.0)
        val step = (hi - lo) / (NMEL + 1)
        Array(NMEL) { m ->
            val l = lo + m * step; val c = l + step; val r = c + step
            DoubleArray(NFFT / 2) { k ->
                val ml = mel(k * VoiceDsp.RATE.toDouble() / NFFT)
                when {
                    ml <= l || ml >= r -> 0.0
                    ml <= c -> (ml - l) / (c - l)
                    else -> (r - ml) / (r - c)
                }
            }
        }
    }

    /** Kaldi-style fbank: DC removal + pre-emphasis 0.97 + povey window + log-mel 80، وبعدها CMN على الزمن */
    private fun fbank(pcm: ShortArray, a: Int, b: Int): Array<FloatArray>? {
        val len = b - a
        if (len < FRAME) return null
        val nf = 1 + (len - FRAME) / SHIFT
        val out = Array(nf) { FloatArray(NMEL) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT); val fr = DoubleArray(FRAME)
        for (f in 0 until nf) {
            val st = a + f * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME) { fr[i] = pcm[st + i].toDouble(); mean += fr[i] }
            mean /= FRAME
            for (i in 0 until FRAME) fr[i] -= mean
            for (i in FRAME - 1 downTo 1) fr[i] -= 0.97 * fr[i - 1]
            fr[0] -= 0.97 * fr[0]
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            for (i in 0 until FRAME) re[i] = fr[i] * povey[i]
            VoiceDsp.fft(re, im)
            for (m in 0 until NMEL) {
                var e = 0.0
                val w = melBank[m]
                for (k in 0 until NFFT / 2) if (w[k] != 0.0) e += w[k] * (re[k] * re[k] + im[k] * im[k])
                out[f][m] = ln(max(e, 1.1920929e-7)).toFloat()
            }
        }
        for (m in 0 until NMEL) {
            var s = 0.0; for (f in 0 until nf) s += out[f][m]
            val mu = (s / nf).toFloat()
            for (f in 0 until nf) out[f][m] -= mu
        }
        return out
    }
}
