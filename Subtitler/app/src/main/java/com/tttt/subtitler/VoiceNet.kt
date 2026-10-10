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
 * (v172) بصمة الصوت العصبية (اختيارية). (v181) الموديل بقى بيتنزّل بره الـ APK (NeuralEngine) في filesDir/neural؛ لو لسه ما اتحمّلش التطبيق بيكمّل بالبصمة الخفيفة.
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
    private var retryAt = 0L
    private var embErrLogged = 0
    @Volatile var lastErr = ""
    private val lock = Any()

    fun init(c: Context) { ctx = c.applicationContext }

    fun available(): Boolean = synchronized(lock) { load() }
    fun isReady(): Boolean = sess != null

    /** (v181) بيقفل الجلسة ويصفّر المحاولات: بعد تحميل موديل جديد أو حذفه */
    fun reset() = synchronized(lock) {
        try { sess?.close() } catch (_: Throwable) {}
        sess = null; env = null; retryAt = 0L; lastErr = ""
    }

    /** وضع البصمة من الإعدادات: light (خفيفة فقط) · neural (عصبي فقط) · combo (الاتنين معًا — الافتراضي) */
    fun mode(): String = try { Cfg.str("voice_mode", "combo").let { if (it == "light" || it == "neural") it else "combo" } } catch (_: Throwable) { "combo" }
    /** نحسب البصمة العصبية بس لو الوضع مش «خفيف» والموديل متاح */
    fun useNet(): Boolean = mode() != "light" && available()

    private fun reason(t: Throwable): String {
        val m = (t.message ?: "").replace(Regex("\\s+"), " ").take(140)
        return when (t) {
            is UnsatisfiedLinkError -> "مكتبة onnxruntime مش متوافقة مع معالج الجهاز ($m)"
            is OutOfMemoryError -> "الذاكرة مش كفاية لتحميل الموديل"
            is java.io.IOException -> "مشكلة في قراءة ملف الموديل: $m"
            else -> "${t.javaClass.simpleName}: $m"
        }
    }

    /** (v181) اختبار ذاتي بعد التحميل: مدخل ثابت [1,200,80] لازم يطلع متجه أرقام سليمة ومش ثابتة */
    private fun selfTest(e: OrtEnvironment, s: OrtSession, name: String): Int {
        val rnd = java.util.Random(7)
        val x = Array(200) { FloatArray(NMEL) { (rnd.nextGaussian() * 1.5).toFloat() } }
        OnnxTensor.createTensor(e, arrayOf(x)).use { t ->
            s.run(mapOf(name to t)).use { res ->
                val o = res[0].value
                val v = if (o is Array<*>) o[0] as FloatArray else o as FloatArray
                if (v.size < 32) throw IllegalStateException("مخرج الموديل قصير (${v.size})")
                if (v.any { it.isNaN() || it.isInfinite() }) throw IllegalStateException("مخرج الموديل فيه NaN")
                if (v.all { it == v[0] }) throw IllegalStateException("مخرج الموديل ثابت")
                return v.size
            }
        }
    }

    /** (v181) مفيش «يأس دايم»: لو فشل بيحاول تاني بعد 30 ثانية، وسبب الفشل بيتسجّل في اللوج ويظهر في الإعدادات */
    private fun load(): Boolean {
        if (sess != null) return true
        val now = System.currentTimeMillis()
        if (now < retryAt) return false
        val c = ctx ?: return false
        if (!NeuralEngine.installed(c)) { lastErr = ""; retryAt = now + 30_000L; return false }
        var s: OrtSession? = null
        try {
            val f = NeuralEngine.file(c)
            val e = OrtEnvironment.getEnvironment()
            val o = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            s = e.createSession(f.absolutePath, o)
            val nm = s.inputNames.first()
            val d = selfTest(e, s, nm)
            env = e; sess = s; inName = nm; lastErr = ""
            LogStore.add("🧠 موديل بصمة الصوت العصبي اتحمّل واتجرّب (متجه $d)")
            return true
        } catch (t: Throwable) {
            try { s?.close() } catch (_: Throwable) {}
            lastErr = reason(t); retryAt = now + 30_000L
            LogStore.add("⚠ موديل بصمة الصوت فشل: $lastErr (هيحاول تاني بعد 30 ثانية)")
            return false
        }
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
            } catch (t: Throwable) {
                lastErr = reason(t)
                if (embErrLogged++ < 3) LogStore.add("⚠ حساب البصمة العصبية فشل: $lastErr")
                return null
            }
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
