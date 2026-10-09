package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * (v170) بصمة الصوت.
 * المفاتيح المجانية بتاخد مقطع 30–60 ثانية بس، فكل مقطع بيتبعت لمفتاح مش عارف مين اللي اتكلم في اللي قبله.
 * الحل: بصمة بتتحسب على الجهاز من الصوت نفسه (طبقة الصوت + ألوان الصوت MFCC)، وبنك أصوات بيفضل طول الفيديو،
 * والبنك ده بيتحوّل لنص بيتحط في prompt كل مقطع جديد. كده كل «API» بيستلم من اللي قبله: الصوت V2 ده ذكر بالغ غليظ اسمه كذا.
 * ملاحظة: دي بصمة خفيفة (مش شبكة عصبية)، دقتها كويسة في الجنس/الطبقة والفروق الواضحة بين الأصوات، وجيميناي بيكمّلها بوصف الصوت.
 */
class VPrint(val v: FloatArray, val f0: Double, val frames: Int)

object VoiceDsp {
    const val RATE = 16000
    private const val N = 512
    private const val HOP = 320
    private const val NB = 20
    const val NC = 12
    const val DIM = 1 + NC

    private val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
    private val fb: Array<DoubleArray> = run {
        fun mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun inv(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
        val lo = mel(100.0); val hi = mel(7000.0)
        val pts = DoubleArray(NB + 2) { inv(lo + (hi - lo) * it / (NB + 1)) }
        Array(NB) { b ->
            DoubleArray(N / 2 + 1) { k ->
                val f = k.toDouble() * RATE / N
                when {
                    f <= pts[b] || f >= pts[b + 2] -> 0.0
                    f <= pts[b + 1] -> (f - pts[b]) / (pts[b + 1] - pts[b])
                    else -> (pts[b + 2] - f) / (pts[b + 2] - pts[b + 1])
                }
            }
        }
    }
    private val dct: Array<DoubleArray> = Array(NC) { j -> DoubleArray(NB) { b -> cos(PI * (j + 1) * (b + 0.5) / NB) } }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** PCM16 mono 16kHz من WAV (نفس صيغة WavChunk). null لو مش WAV صالح. */
    fun pcmOf(wav: ByteArray): ShortArray? {
        if (wav.size < 46 || wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte() || wav[2] != 'F'.code.toByte() || wav[3] != 'F'.code.toByte()) return null
        val rate = (wav[24].toInt() and 255) or ((wav[25].toInt() and 255) shl 8) or ((wav[26].toInt() and 255) shl 16) or ((wav[27].toInt() and 255) shl 24)
        if (rate != RATE) return null
        val n = (wav.size - 44) / 2
        val out = ShortArray(n)
        for (i in 0 until n) { val p = 44 + i * 2; out[i] = ((wav[p + 1].toInt() shl 8) or (wav[p].toInt() and 0xFF)).toShort() }
        return out
    }

    /** بصمة مجال من الصوت (ثواني نسبية لبداية الـ pcm). null لو مفيش كلام مُنغَّم كفاية (موسيقى/همس/صمت). */
    fun print(pcm: ShortArray, gain: Double, fromSec: Double, toSec: Double): VPrint? {
        val a = max(0, (fromSec * RATE).toInt()); val b = min(pcm.size, (toSec * RATE).toInt())
        if (b - a < N + HOP * 4) return null
        val thr = 0.012 * max(1.0, gain)
        val x = DoubleArray(N); val d = DoubleArray(N / 2); val sq = DoubleArray(N / 2 + 1)
        val re = DoubleArray(N); val im = DoubleArray(N)
        val acc = DoubleArray(NC); var cnt = 0
        val f0s = ArrayList<Double>()
        var s = a
        while (s + N <= b) {
            var e = 0.0
            for (i in 0 until N) { val v = pcm[s + i] / 32768.0; x[i] = v; e += v * v }
            s += HOP
            if (sqrt(e / N) < thr) continue
            // طبقة الصوت: ارتباط ذاتي على إشارة متخفّضة لـ 8kHz (لأن نطاق 70–400Hz كفاية ومش محتاجين تكلفة أعلى)
            var mean = 0.0
            for (i in 0 until N / 2) { d[i] = (x[2 * i] + x[2 * i + 1]) * 0.5; mean += d[i] }
            mean /= (N / 2)
            sq[0] = 0.0
            for (i in 0 until N / 2) { d[i] -= mean; sq[i + 1] = sq[i] + d[i] * d[i] }
            var best = 0.0; var bestLag = 0
            val nccf = DoubleArray(116)
            for (lag in 20..114) {
                val m = N / 2 - lag
                var r = 0.0
                for (i in 0 until m) r += d[i] * d[i + lag]
                val e1 = sq[m]; val e2 = sq[m + lag] - sq[lag]
                val den = sqrt(e1 * e2)
                val c = if (den > 1e-9) r / den else 0.0
                nccf[lag] = c
                if (c > best) { best = c; bestLag = lag }
            }
            if (best < 0.5 || bestLag == 0) continue
            // أصغر lag قريب من الأعلى (يمنع قفزة الأوكتاف لنص التردد)
            var lag = bestLag
            for (l in 20 until bestLag) if (nccf[l] >= 0.9 * best && nccf[l] >= nccf[l - 1] && nccf[l] >= nccf[l + 1]) { lag = l; break }
            val f0 = 8000.0 / lag
            if (f0 < 70.0 || f0 > 400.0) continue
            f0s.add(f0)
            // ألوان الصوت: MFCC
            for (i in 0 until N) { re[i] = x[i] * win[i]; im[i] = 0.0 }
            fft(re, im)
            val le = DoubleArray(NB)
            for (bnd in 0 until NB) {
                var sum = 0.0
                val w = fb[bnd]
                for (k in 0..N / 2) { if (w[k] != 0.0) sum += w[k] * (re[k] * re[k] + im[k] * im[k]) }
                le[bnd] = ln(sum + 1e-10)
            }
            for (j in 0 until NC) { var c = 0.0; for (bnd in 0 until NB) c += le[bnd] * dct[j][bnd]; acc[j] += c }
            cnt++
        }
        if (cnt < 6) return null
        f0s.sort()
        val med = f0s[f0s.size / 2]
        val v = FloatArray(DIM)
        v[0] = ln(med).toFloat()
        for (j in 0 until NC) v[1 + j] = (acc[j] / cnt).toFloat()
        return VPrint(v, med, cnt)
    }

    // مقياس الفرق في كل بُعد (يتظبط من اختبار الأصوات الصناعية): طبقة 8% = وحدة، وكل MFCC حسب انحرافه المعتاد
    // اتظبطت بمسح على أصوات صناعية (طبقة ±10% بين الجمل): الطبقة هي الأساس وألوان الصوت داعم، وأول 6 معاملات بس (الباقي ضوضاء)
    @JvmField var SC = floatArrayOf(0.12f, 9.0f, 6.6f, 5.4f, 4.5f, 3.9f, 3.6f, 3.3f, 3.0f, 3.0f, 2.7f, 2.7f, 2.4f)
    /** كام معامل MFCC بيدخلوا في المقارنة (الأعلى أضوض) */
    @JvmField var USE = 6
    fun dist(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in 0..USE) { val dd = (a[i] - b[i]) / SC[i]; s += dd * dd }
        return sqrt(s / (USE + 1))
    }
    /** فرق الطبقة بالـ ln: أكتر من PITCH_GATE = مستحيل يبقى نفس الصوت */
    fun pitchGap(a: FloatArray, b: FloatArray) = abs(a[0] - b[0]).toDouble()

    /** جنس من الطبقة: +1 ذكر واضح، -1 أنثى واضحة، 0 مش حاسم (منطقة وسط أو صوت طفل) */
    fun f0Gender(f0: Double): Int = when { f0 in 70.0..145.0 -> 1; f0 in 185.0..255.0 -> -1; else -> 0 }
    fun pitchWord(f0: Double): String = when {
        f0 < 100 -> "غليظ جدًا"; f0 < 130 -> "غليظ"; f0 < 165 -> "متوسط واطي"; f0 < 200 -> "متوسط"; f0 < 260 -> "رفيع"; else -> "رفيع جدًا (غالبًا طفل)"
    }
}

class VoiceProf(val id: String, val cent: FloatArray) {
    var n = 0
    var frames = 0
    var f0 = 0.0
    var gm = 0.0
    var gf = 0.0
    var name = ""
    var age = ""
    var style = ""
    fun gender(): String = if (gf > gm) "female" else "male"
    /** جنس موثوق: 3 جمل على الأقل وفرق أصوات واضح */
    fun genderSure(): Boolean = n >= 3 && abs(gm - gf) >= 3.0
}

/** نتيجة تحليل مقطع: الجمل بعد تعيين الأصوات + إحصائيات للّوج */
class VoiceResult(val subs: List<Sub>, val genderFixed: Int, val newVoices: Int, val matched: Int)

class VoiceBank(var tMatch: Double = T_MATCH, var tLoose: Double = T_LOOSE) {
    companion object {
        const val MAX = 24
        const val T_MATCH = 1.1     // تطابق مباشر بالبصمة
        const val T_LOOSE = 1.9     // تطابق لو جيميناي بيقول نفس الكود
        const val PITCH_GATE = 0.18 // 20% فرق طبقة = صوت تاني
    }
    private val lock = Any()
    private val voices = ArrayList<VoiceProf>()
    private var nextNo = 1

    fun size(): Int = synchronized(lock) { voices.size }
    fun clear() = synchronized(lock) { voices.clear(); nextNo = 1 }
    fun find(id: String): VoiceProf? = synchronized(lock) { voices.firstOrNull { it.id == id } }

    private fun nearest(fp: VPrint): Pair<VoiceProf, Double>? {
        var best: VoiceProf? = null; var bd = Double.MAX_VALUE
        for (p in voices) {
            if (VoiceDsp.pitchGap(p.cent, fp.v) > PITCH_GATE) continue
            val d = VoiceDsp.dist(p.cent, fp.v)
            if (d < bd) { bd = d; best = p }
        }
        return if (best == null) null else Pair(best, bd)
    }

    private fun update(p: VoiceProf, fp: VPrint, gemGender: String) {
        val lr = max(0.08, 1.0 / (p.n + 1))
        for (i in 0 until VoiceDsp.DIM) p.cent[i] = (p.cent[i] * (1 - lr) + fp.v[i] * lr).toFloat()
        p.n++; p.frames += fp.frames; p.f0 = exp(p.cent[0].toDouble())
        val g = VoiceDsp.f0Gender(fp.f0)
        if (g > 0) p.gm += 2.0 else if (g < 0) p.gf += 2.0
        if (gemGender == "female") p.gf += 1.0 else if (gemGender == "male") p.gm += 1.0
    }

    /** يعيّن الصوت لبصمة: يرجع كود البنك. gemId = الكود اللي جيميناي كتبه لو كان من البنك. */
    fun assign(fp: VPrint, gemId: String, gemGender: String): Pair<String, Boolean> = synchronized(lock) { assignLocked(fp, gemId, gemGender) }

    private fun assignLocked(fp: VPrint, gemId: String, gemGender: String): Pair<String, Boolean> {
        val nb = nearest(fp)
        if (nb != null && nb.second <= tMatch) { update(nb.first, fp, gemGender); return Pair(nb.first.id, false) }
        val gp = if (gemId.isNotEmpty()) voices.firstOrNull { it.id == gemId } else null
        if (gp != null && VoiceDsp.pitchGap(gp.cent, fp.v) <= PITCH_GATE && VoiceDsp.dist(gp.cent, fp.v) <= tLoose) { update(gp, fp, gemGender); return Pair(gp.id, false) }
        if (nb != null && voices.size >= MAX) { update(nb.first, fp, gemGender); return Pair(nb.first.id, false) }
        val p = VoiceProf("V" + nextNo++, fp.v.copyOf())
        voices.add(p); update(p, fp, gemGender)
        return Pair(p.id, true)
    }

    /** من غير تعديل: أقرب صوت معروف لبصمة (للتقسيم الآلي قبل الإرسال). null = مفيش تطابق كفاية */
    fun peek(fp: VPrint): VoiceProf? = synchronized(lock) { nearest(fp)?.takeIf { it.second <= tMatch }?.first }

    fun setInfo(id: String, gender: String, age: String, style: String, name: String) { synchronized(lock) { setInfoLocked(id, gender, age, style, name) } }

    private fun setInfoLocked(id: String, gender: String, age: String, style: String, name: String) {
        val p = voices.firstOrNull { it.id == id } ?: return
        if (age.isNotBlank()) p.age = age.trim().take(24)
        if (style.isNotBlank()) p.style = style.trim().take(80)
        if (name.isNotBlank() && name.length in 2..30) p.name = name.trim()
        // جيميناي بيشارك بصوت واحد في تصويت الجنس (الطبقة المحلية أقوى)
        if (gender == "female") p.gf += 0.5 else if (gender == "male") p.gm += 0.5
    }

    fun genderOf(id: String): String? = synchronized(lock) { voices.firstOrNull { it.id == id && it.genderSure() }?.gender() }
    fun nameOf(id: String): String = synchronized(lock) { voices.firstOrNull { it.id == id }?.name ?: "" }
    fun namedVoices(): List<Triple<String, String, Int>> = synchronized(lock) { voices.filter { it.name.isNotEmpty() && it.n >= 3 }.map { Triple(it.name, it.gender(), it.n) } }

    private fun ageWord(p: VoiceProf): String = when {
        p.age.isNotEmpty() -> p.age
        p.f0 > 260 -> "طفل غالبًا"
        else -> ""
    }

    /** جدول «بصمات الأصوات» اللي بيتحط في prompt كل مقطع جديد */
    fun promptBlock(): String = synchronized(lock) { promptLocked() }

    private fun promptLocked(): String {
        val list = voices.filter { it.n >= 2 || it.name.isNotEmpty() }.sortedByDescending { it.n }.take(14)
        if (list.isEmpty()) return ""
        val sb = StringBuilder("\n═══ بصمات الأصوات المعروفة (اتحسبت من مقاطع الفيديو اللي فاتت) ═══\n")
        for (p in list) {
            sb.append("- ").append(p.id).append(": ").append(if (p.gender() == "female") "أنثى" else "ذكر")
            val a = ageWord(p); if (a.isNotEmpty()) sb.append(" · ").append(a)
            sb.append(" · طبقة ").append(VoiceDsp.pitchWord(p.f0)).append(" (~").append(Math.round(p.f0)).append("Hz)")
            if (p.style.isNotEmpty()) sb.append(" · ").append(p.style)
            if (p.name.isNotEmpty()) sb.append(" · الاسم: ").append(p.name)
            sb.append(" · اتكلم ").append(p.n).append(" مرة\n")
        }
        return sb.toString()
    }

    fun toJson(): String = synchronized(lock) {
        val arr = JSONArray()
        for (p in voices) arr.put(JSONObject().put("id", p.id).put("n", p.n).put("fr", p.frames).put("gm", p.gm).put("gf", p.gf)
            .put("nm", p.name).put("ag", p.age).put("st", p.style).put("c", JSONArray(p.cent.map { it.toDouble() })))
        JSONObject().put("next", nextNo).put("v", arr).toString()
    }

    fun fromJson(s: String) { synchronized(lock) { fromJsonLocked(s) } }

    private fun fromJsonLocked(s: String) {
        if (s.isBlank()) return
        try {
            val j = JSONObject(s)
            voices.clear()
            val arr = j.optJSONArray("v") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ca = o.optJSONArray("c") ?: continue
                if (ca.length() != VoiceDsp.DIM) continue
                val p = VoiceProf(o.optString("id"), FloatArray(VoiceDsp.DIM) { ca.optDouble(it).toFloat() })
                p.n = o.optInt("n"); p.frames = o.optInt("fr"); p.gm = o.optDouble("gm"); p.gf = o.optDouble("gf")
                p.name = o.optString("nm"); p.age = o.optString("ag"); p.style = o.optString("st"); p.f0 = exp(p.cent[0].toDouble())
                if (p.id.isNotEmpty()) voices.add(p)
            }
            nextNo = max(j.optInt("next", 1), (voices.mapNotNull { it.id.removePrefix("V").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        } catch (_: Exception) { voices.clear() }
    }
}

/** ربط الجمل بالأصوات + تقسيم آلي مبدئي للمقطع الجاي */
object VoiceTrack {
    private fun gword(g: Int) = if (g > 0) "ذكر" else if (g < 0) "أنثى" else "مش حاسم"

    /**
     * بعد ما جيميناي يرد: لكل جملة بنحسب بصمة صوتها من الـ WAV ونعيّنها لصوت في البنك،
     * وبنصحّح حقل gender من جنس الصوت لو البنك واثق (ده اللي بيمنع قلب ذكر/أنثى بين المقاطع).
     */
    fun apply(bank: VoiceBank, w: WavChunk, fresh: List<Sub>, voicesJson: JSONArray?): VoiceResult {
        val pcm = VoiceDsp.pcmOf(w.bytes) ?: return VoiceResult(fresh, 0, 0, 0)
        val out = ArrayList<Sub>(fresh.size)
        val labelToId = HashMap<String, String>()
        var fixed = 0; var created = 0; var matched = 0
        // الجمل الأطول الأول: بصمتها أوضح فتبني الخريطة label→id قبل الجمل القصيرة
        val order = fresh.indices.sortedByDescending { fresh[it].end - fresh[it].start }
        val ids = arrayOfNulls<String>(fresh.size)
        for (ix in order) {
            val s = fresh[ix]
            if (s.isSound) continue
            val lbl = s.voice.trim()
            val fp = VoiceDsp.print(pcm, w.gain, s.start - w.startSec - 0.05, s.end - w.startSec + 0.05)
            if (fp != null) {
                val gemId = if (lbl.startsWith("V")) lbl else (labelToId[lbl] ?: "")
                val r = bank.assign(fp, gemId, s.gender)
                ids[ix] = r.first
                if (r.second) created++ else matched++
                if (lbl.isNotEmpty() && !labelToId.containsKey(lbl)) labelToId[lbl] = r.first
            }
        }
        for (ix in fresh.indices) {
            var s = fresh[ix]
            if (s.isSound) { out.add(s.copy(voice = "")); continue }
            val lbl = s.voice.trim()
            // جملة قصيرة/من غير نغمة كفاية: ناخد صوتها من باقي جمل نفس الكود في المقطع، وإلا من كود جيميناي لو معروف
            val id = ids[ix] ?: labelToId[lbl] ?: (if (lbl.startsWith("V") && bank.find(lbl) != null) lbl else "")
            s = s.copy(voice = id)
            if (id.isNotEmpty()) {
                val g = bank.genderOf(id)
                if (g != null && g != s.gender) { s = s.copy(gender = g); fixed++ }
            }
            out.add(s)
        }
        // معلومات جيميناي عن الأصوات (جنس/عمر/أسلوب/اسم)
        if (voicesJson != null) for (k in 0 until voicesJson.length()) {
            val o = voicesJson.optJSONObject(k) ?: continue
            val raw = o.optString("id").trim()
            val id = if (raw.startsWith("V")) raw else (labelToId[raw] ?: continue)
            bank.setInfo(id, o.optString("gender").lowercase(), o.optString("age"), o.optString("style"), o.optString("name"))
        }
        return VoiceResult(out, fixed, created, matched)
    }

    /**
     * تقسيم آلي مبدئي للمقطع قبل الإرسال: نوافذ 1.2ث بخطوة 0.6ث، كل نافذة بتتطابق مع صوت معروف
     * (أو بتتوصف بجنسها من الطبقة)، والنوافذ المتتالية المتشابهة بتتدمج. بيتحط في الـ prompt كتلميح مش أمر.
     */
    fun hint(bank: VoiceBank, w: WavChunk): String {
        val pcm = VoiceDsp.pcmOf(w.bytes) ?: return ""
        val total = pcm.size.toDouble() / VoiceDsp.RATE
        class Seg(var s: Double, var e: Double, val key: String, val label: String)
        val segs = ArrayList<Seg>()
        var t = 0.0
        while (t + 0.8 <= total) {
            val fp = VoiceDsp.print(pcm, w.gain, t, min(total, t + 1.2))
            if (fp != null) {
                val p = bank.peek(fp)
                val g = VoiceDsp.f0Gender(fp.f0)
                val key: String; val label: String
                if (p != null) { key = p.id; label = p.id + " (" + (if (p.gender() == "female") "أنثى" else "ذكر") + ")" }
                else { key = "u" + (if (g > 0) "m" else if (g < 0) "f" else "x") + (Math.round(fp.f0 / 25.0)); label = "صوت مش معروف (" + gword(g) + "، طبقة ~" + Math.round(fp.f0) + "Hz)" }
                val last = segs.lastOrNull()
                if (last != null && last.key == key && t - last.e < 0.9) last.e = min(total, t + 1.2) else segs.add(Seg(t, min(total, t + 1.2), key, label))
            }
            t += 0.6
        }
        val good = segs.filter { it.e - it.s >= 0.8 }.take(24)
        if (good.size < 2) return ""
        val sb = StringBuilder("\n═══ تقسيم آلي تقريبي للمقطع الحالي (اتحسب بالجهاز من الصوت — تلميح مش أمر) ═══\n")
        sb.append("- لو سمعك بيخالف التقسيم ده بوضوح اتبع سمعك. ده بس عشان تربط الأصوات بالأكواد وتثبّت جنس كل متكلم.\n")
        for (g in good) sb.append("- من ").append(String.format(java.util.Locale.US, "%.1f", g.s)).append(" إلى ").append(String.format(java.util.Locale.US, "%.1f", g.e)).append(" ثانية: ").append(g.label).append("\n")
        return sb.toString()
    }
}
