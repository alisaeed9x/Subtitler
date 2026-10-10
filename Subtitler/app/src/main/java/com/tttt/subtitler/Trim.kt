package com.tttt.subtitler

/** كشف لحظة الصمت قرب نهاية المقطع (منقول من _findNearestSilenceNearEnd في الأصل) — منطق نقي، متختبر */
object Silence {
    const val WINDOW_SEC = 8.0
    const val MIN_TRIM_SEC = 1.5
    private const val STEP_SEC = 0.1
    private const val SILENCE_RMS = 0.010
    private const val MIN_RUN_SEC = 0.25

    private fun rateOf(wav: ByteArray): Int =
        (wav[24].toInt() and 255) or ((wav[25].toInt() and 255) shl 8) or ((wav[26].toInt() and 255) shl 16) or ((wav[27].toInt() and 255) shl 24)

    private fun isWav(b: ByteArray) = b.size > 44 + 2 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte()

    /** رقم الثانية (نسبي لبداية الـ WAV) اللي نقطع عندها، أو null لو مفيش صمت واضح قرب النهاية. WAV 16-bit mono. */
    fun findCut(wav: ByteArray, searchWindowSec: Double = WINDOW_SEC, minTrimSec: Double = MIN_TRIM_SEC): Double? {
        try {
            if (!isWav(wav)) return null
            val rate = rateOf(wav); if (rate <= 0) return null
            val n = (wav.size - 44) / 2
            val totalSec = n.toDouble() / rate
            if (totalSec <= minTrimSec + 0.5) return null
            val winStartSec = maxOf(0.0, totalSec - searchWindowSec)
            val startFrame = Math.floor(winStartSec * rate).toInt()
            val len = n - startFrame
            val stepSize = maxOf(1, Math.floor(STEP_SEC * rate).toInt())
            val minRunSteps = maxOf(1, Math.round(MIN_RUN_SEC / STEP_SEC).toInt())
            val totalSteps = Math.ceil(len.toDouble() / stepSize).toInt()
            var run = 0; var best = -1
            for (s in totalSteps - 1 downTo 0) {
                val a = s * stepSize; val b = minOf(a + stepSize, len)
                var sq = 0.0
                for (i in a until b) {
                    val o = 44 + (startFrame + i) * 2
                    val v = ((wav[o].toInt() and 255) or (wav[o + 1].toInt() shl 8)).toShort() / 32768.0
                    sq += v * v
                }
                val rms = Math.sqrt(sq / maxOf(1, b - a))
                if (rms < SILENCE_RMS) { run++; if (run >= minRunSteps) { best = s; break } } else run = 0
            }
            if (best == -1) return null
            val mid = best + minRunSteps / 2
            val cut = winStartSec + (mid * stepSize).toDouble() / rate
            if (totalSec - cut < 0.3) return null
            return cut
        } catch (_: Exception) { return null }
    }

    /** يقصّ الـ WAV عند ثانية معينة ويظبط الهيدر */
    fun truncate(wav: ByteArray, sec: Double): ByteArray {
        val rate = rateOf(wav)
        val samples = minOf((wav.size - 44) / 2, Math.round(sec * rate).toInt())
        val pcm = samples * 2
        val out = ByteArray(44 + pcm)
        System.arraycopy(Wav.header(pcm, rate), 0, out, 0, 44)
        System.arraycopy(wav, 44, out, 44, pcm)
        return out
    }
}

/** كشف الفجوات: مناطق اتترجمت (done) بس مفيهاش جمل لمدة طويلة */
object Gaps {
    const val MIN_SEC = 5.0
    const val LOOKAHEAD = 8.0
    const val COOLDOWN_MS = 3000L
    const val MAX_PARALLEL = 3
    const val MAX_TOTAL = 120

    /** كل فجوة [start,end] أطول من minSec جوه المناطق اللي اتترجمت */
    fun find(subs: List<Sub>, done: List<DoubleArray>, minSec: Double = MIN_SEC): List<DoubleArray> {
        val sorted = subs.sortedBy { it.start }
        val out = ArrayList<DoubleArray>()
        for (r in done) {
            var cursor = r[0]
            for (s in sorted) {
                if (s.end <= r[0] || s.start >= r[1]) continue
                if (s.start - cursor >= minSec) out.add(doubleArrayOf(cursor, s.start))
                if (s.end > cursor) cursor = s.end
            }
            if (r[1] - cursor >= minSec) out.add(doubleArrayOf(cursor, r[1]))
        }
        return out
    }

    /** يقسّم مجال لأجزاء مش أطول من maxSec */
    fun parts(a: Double, b: Double, maxSec: Double): List<DoubleArray> {
        val l = ArrayList<DoubleArray>(); var t = a
        while (t < b - 0.5) { l.add(doubleArrayOf(t, minOf(t + maxSec, b))); t += maxSec }
        return l
    }
}
