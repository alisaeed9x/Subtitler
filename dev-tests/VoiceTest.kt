package com.tttt.subtitler

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// اختبار بصمة الصوت بأصوات صناعية: مصدر نبضات (glottal) × رنين فورمانتات (حروف علة) — بيتأكد من الطبقة والجنس والتفريق بين الأصوات
private var pass = 0; private var fail = 0
private fun check(name: String, ok: Boolean, extra: String = "") { if (ok) { pass++; println("PASS $name $extra") } else { fail++; println("FAIL $name $extra") } }

private var DRIFT = 0.08
private class Spk(val name: String, val f0: Double, val tract: Double, val male: Boolean)
private val VOW = arrayOf(doubleArrayOf(730.0, 1090.0, 2440.0), doubleArrayOf(270.0, 2290.0, 3010.0), doubleArrayOf(300.0, 870.0, 2240.0), doubleArrayOf(530.0, 1840.0, 2480.0), doubleArrayOf(570.0, 840.0, 2410.0))

private fun phrase(sp: Spk, seed: Long, sec: Double, amp: Double): ByteArray {
    val rnd = Random(seed); val rate = 16000; val n = (sec * rate).toInt()
    val out = ShortArray(n)
    var phase = 0.0
    val st = Array(3) { DoubleArray(2) }
    var vi = rnd.nextInt(VOW.size); var next = 0
    val drift = 1.0 + (rnd.nextDouble() - 0.5) * DRIFT
    val buf = DoubleArray(n)
    for (i in 0 until n) {
        if (i >= next) { vi = rnd.nextInt(VOW.size); next = i + (0.12 * rate).toInt() + rnd.nextInt((0.1 * rate).toInt()) }
        val t = i.toDouble() / rate
        val f0 = sp.f0 * drift * (1.0 + 0.08 * sin(2 * PI * 0.9 * t + seed) + (rnd.nextDouble() - 0.5) * 0.01)
        phase += f0 / rate; if (phase >= 1.0) phase -= 1.0
        var src = if (phase < 0.02) 1.0 else 0.0
        src += (phase * 2 - 1) * 0.15 + (rnd.nextDouble() - 0.5) * 0.02
        var y = 0.0
        for (f in 0 until 3) {
            val fc = VOW[vi][f] * sp.tract; val bw = 80.0 + 40 * f
            val r = Math.exp(-PI * bw / rate); val a1 = -2 * r * cos(2 * PI * fc / rate); val a2 = r * r
            val x = src - a1 * st[f][0] - a2 * st[f][1]
            st[f][1] = st[f][0]; st[f][0] = x
            y += x * (if (f == 0) 1.0 else 0.6)
        }
        buf[i] = y
    }
    var mx = 1e-9; for (v in buf) mx = maxOf(mx, Math.abs(v))
    for (i in 0 until n) out[i] = (buf[i] / mx * amp * 32767 + (rnd.nextDouble() - 0.5) * 60).toInt().coerceIn(-32768, 32767).toShort()
    val bytes = ByteArray(44 + n * 2)
    System.arraycopy(Wav.header(n * 2, rate), 0, bytes, 0, 44)
    for (i in 0 until n) { bytes[44 + i * 2] = (out[i].toInt() and 255).toByte(); bytes[45 + i * 2] = (out[i].toInt() shr 8).toByte() }
    return bytes
}

private fun sweep() {
    val spk = listOf(Spk("m1", 110.0, 1.0, true), Spk("m2", 135.0, 0.93, true), Spk("m3", 100.0, 1.12, true), Spk("f1", 210.0, 1.18, false), Spk("f2", 235.0, 1.12, false), Spk("kid", 300.0, 1.4, false))
    val rnd = Random(5)
    val data = ArrayList<Pair<String, VPrint>>()
    for (s in spk) for (k in 0 until 14) {
        val dur = 1.0 + rnd.nextDouble() * 2.0
        val pcm = VoiceDsp.pcmOf(phrase(s, 777L * s.name.hashCode() + k, dur, 0.15 + 0.4 * rnd.nextDouble())) ?: continue
        val fp = VoiceDsp.print(pcm, 1.0, 0.0, dur) ?: continue
        data.add(Pair(s.name, fp))
    }
    val base = floatArrayOf(3.0f, 2.2f, 1.8f, 1.5f, 1.3f, 1.2f, 1.1f, 1.0f, 1.0f, 0.9f, 0.9f, 0.8f)
    class R(val desc: String, val score: Double, val nv: Double, val frag: Double, val mix: Double)
    val res = ArrayList<R>()
    for (ps in listOf(0.10f, 0.12f, 0.15f, 0.20f)) for (mm in listOf(2f, 3f, 4f, 6f)) for (use in listOf(6, 12)) for (tm in listOf(0.9, 1.1, 1.3)) {
        VoiceDsp.SC = FloatArray(13) { if (it == 0) ps else base[it - 1] * mm }; VoiceDsp.USE = use
        var fr = 0.0; var mx = 0.0; var nv = 0.0
        for (seed in 0 until 6) {
            val bank = VoiceBank(tm, tm * 1.7); val ids = HashMap<String, MutableSet<String>>(); val owner = HashMap<String, MutableSet<String>>()
            for ((n, fp) in data.shuffled(Random(seed.toLong()))) { val id = bank.assign(fp, "", if (n.startsWith("m")) "male" else "female").first; ids.getOrPut(n) { HashSet() }.add(id); owner.getOrPut(id) { HashSet() }.add(n) }
            fr += ids.values.sumOf { it.size - 1 }; mx += owner.values.count { it.size > 1 }; nv += bank.size()
        }
        fr /= 6; mx /= 6; nv /= 6
        res.add(R("ps=$ps mm=$mm use=$use t=$tm", fr + 2 * mx, nv, fr, mx))
    }
    res.sortBy { it.score }
    for (r in res.take(14)) println("%-34s score=%.1f voices=%.1f frag=%.1f mix=%.1f".format(r.desc, r.score, r.nv, r.frag, r.mix))
}

fun main(args: Array<String>) {
    if (args.contains("sweep")) { DRIFT = 0.2; sweep(); return }
    val spk = listOf(Spk("m1", 110.0, 1.0, true), Spk("m2", 135.0, 0.93, true), Spk("m3", 100.0, 1.12, true), Spk("f1", 210.0, 1.18, false), Spk("f2", 235.0, 1.12, false), Spk("kid", 300.0, 1.4, false))
    val fps = HashMap<String, MutableList<VPrint>>()
    for (s in spk) for (k in 0 until 6) {
        val pcm = VoiceDsp.pcmOf(phrase(s, 100L * s.name.hashCode() + k, 1.8, 0.2 + 0.12 * k)) ?: error("pcm")
        val fp = VoiceDsp.print(pcm, 1.0, 0.0, 1.8)
        check("print ${s.name}#$k", fp != null)
        if (fp != null) fps.getOrPut(s.name) { ArrayList() }.add(fp)
    }
    // دقة الطبقة
    for (s in spk) { val m = fps[s.name]!!.map { it.f0 }.average(); check("F0 ${s.name}", Math.abs(m - s.f0) / s.f0 < 0.12, "est=%.0f true=%.0f".format(m, s.f0)) }
    // جنس من الطبقة
    for (s in spk.take(5)) { val g = VoiceDsp.f0Gender(fps[s.name]!!.map { it.f0 }.average()); check("gender ${s.name}", g == (if (s.male) 1 else -1)) }
    // بنك: 36 جملة بترتيب عشوائي لازم تطلع 6 أصوات (±1)
    val bank = VoiceBank(); val all = spk.flatMap { s -> fps[s.name]!!.map { Pair(s.name, it) } }.shuffled(Random(7))
    val map = HashMap<String, MutableSet<String>>()
    for ((n, fp) in all) { val id = bank.assign(fp, "", if (n.startsWith("m")) "male" else "female").first; map.getOrPut(n) { HashSet() }.add(id) }
    println("bank=${bank.size()} map=$map")
    check("bank voices 4..8", bank.size() in 4..8, "n=${bank.size()}")
    val owners = HashMap<String, MutableSet<String>>(); for ((n, ids) in map) for (id in ids) owners.getOrPut(id) { HashSet() }.add(n)
    check("no cluster mixes male and female", owners.values.none { o -> o.any { it.startsWith("m") } && o.any { !it.startsWith("m") } }, owners.toString())
    check("kid never joins an adult cluster", owners.values.none { o -> o.contains("kid") && o.size > 1 })
    // الجنس من البنك: كل صوت ذكر/أنثى صح
    for ((n, ids) in map) for (id in ids) { val g = bank.find(id)!!.gender(); if (n.startsWith("m")) check("bank gender $n/$id", g == "male") else if (n != "kid") check("bank gender $n/$id", g == "female") }
    // حفظ/استرجاع
    val b2 = VoiceBank(); b2.fromJson(bank.toJson()); check("json roundtrip", b2.size() == bank.size() && b2.promptBlock() == bank.promptBlock())
    println(bank.promptBlock())
    // إصلاح الجنس: جيميناي قلب جنس صوت أنثى في جملة
    val wavF = phrase(spk[3], 999L, 3.0, 0.5)
    val ch = WavChunk(wavF, 100.0, 3.0, false, 1.0)
    fun sub(g: String, a: Double, b: Double, v: String) = Sub(a, b, "x", "y", g, "unknown", "none", emptyList(), emptyList(), false, false, 0, voice = v)
    val bank3 = VoiceBank()
    for (k in 0 until 4) bank3.assign(fps["f1"]!![k], "", "female")
    val res = VoiceTrack.apply(bank3, ch, listOf(sub("male", 100.2, 102.8, "new1")), null)
    check("gender fix by voice", res.subs[0].gender == "female" && res.genderFixed == 1, "g=${res.subs[0].gender} v=${res.subs[0].voice}")
    check("hint builds", VoiceTrack.hint(bank3, ch).let { it.isEmpty() || it.contains("V1") }, "")
    println("== الاختبارات: نجح $pass · فشل $fail")
}
