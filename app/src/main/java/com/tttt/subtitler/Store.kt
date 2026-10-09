package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** كل اللي بنحفظه لفيديو واحد عشان نكمّل بعد قفل التطبيق. */
class Saved(
    val chunkSec: Int, val srcLang: String, val detDone: Boolean,
    val subs: List<Sub>, val done: List<DoubleArray>, val failed: List<DoubleArray>,
    val chars: List<Chr>, val gloss: List<Gloss>, val tpl: Map<String, String>,
    val pos: Double, val charsTried: Int,
    /** حدود المقاطع بعد تعديلها لأقرب صمت: رقم المقطع -> بدايته بالثواني */
    val bounds: Map<Int, Double> = emptyMap(),
    /** الفجوات اللي اتحاولت قبل كده (عشان متتعادش كل جلسة) */
    val gapTried: List<DoubleArray> = emptyList(),
    /** (v170) بنك بصمات الأصوات (JSON) */
    val voices: String = ""
)

class Store(private val dir: File, key: String) {
    private val f = File(dir, "$key.json")
    private val saveLock = Any()

    companion object {
        fun keyFor(id: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(id.toByteArray(Charsets.UTF_8))
            return d.joinToString("") { String.format("%02x", it) }.take(20)
        }
        fun clearAll(dir: File): Int {
            var n = 0
            dir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
            return n
        }
    }

    private fun subJ(s: Sub) = JSONObject().put("s", s.start).put("e", s.end).put("o", s.original).put("t", s.translated)
        .put("g", s.gender).put("a", s.addressee).put("tg", s.topicGender)
        .put("p", JSONArray(s.people)).put("pl", JSONArray(s.places))
        .put("song", s.isSong).put("low", s.lowConf).put("c", s.chunk)
        .put("emo", s.emotion).put("ov", s.overlap).put("spk", s.speakerTag).put("cont", s.isContinuation).put("piv", s.pivot).put("fa", s.faint).put("cv", s.conv).put("snd", s.isSound).put("vo", s.voice)

    private fun strs(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

    private fun subOf(o: JSONObject) = Sub(
        o.optDouble("s"), o.optDouble("e"), o.optString("o"), o.optString("t"),
        o.optString("g", "male"), o.optString("a", "unknown"), o.optString("tg", "none"),
        strs(o.optJSONArray("p")), strs(o.optJSONArray("pl")), o.optBoolean("song"), o.optBoolean("low"), o.optInt("c", -1),
        o.optString("emo"), o.optBoolean("ov"), o.optString("spk"), o.optBoolean("cont"), o.optString("piv"), o.optBoolean("fa"), o.optBoolean("cv"), o.optBoolean("snd"), o.optString("vo")
    )

    private fun ranges(l: List<DoubleArray>): JSONArray {
        val a = JSONArray(); for (r in l) a.put(JSONArray().put(r[0]).put(r[1])); return a
    }
    private fun rangesOf(a: JSONArray?): List<DoubleArray> =
        (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONArray(it) }.filter { it.length() >= 2 }.map { doubleArrayOf(it.optDouble(0), it.optDouble(1)) }

    fun save(s: Saved) {
        val j = JSONObject().put("v", 2).put("chunkSec", s.chunkSec).put("srcLang", s.srcLang).put("detDone", s.detDone)
            .put("pos", s.pos).put("charsTried", s.charsTried).put("voices", s.voices)
        val subs = JSONArray(); for (x in s.subs) subs.put(subJ(x)); j.put("subs", subs)
        j.put("done", ranges(s.done)).put("failed", ranges(s.failed))
        val ch = JSONArray(); for (c in s.chars) ch.put(JSONObject().put("n", c.name).put("g", c.gender).put("r", c.role)); j.put("chars", ch)
        val gl = JSONArray(); for (g in s.gloss) gl.put(JSONObject().put("t", g.term).put("n", g.note)); j.put("gloss", gl)
        val tp = JSONObject(); for ((k, v) in s.tpl) tp.put(k, v); j.put("tpl", tp)
        val bj = JSONObject(); for ((k, v) in s.bounds) bj.put(k.toString(), v); j.put("bounds", bj)
        j.put("gapTried", ranges(s.gapTried))
        synchronized(saveLock) {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(j.toString(), Charsets.UTF_8)
            try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (e: Exception) { tmp.copyTo(f, true); tmp.delete() }
        }
    }

    fun load(): Saved? {
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText(Charsets.UTF_8))
            val sa = j.optJSONArray("subs")
            val subs = (0 until (sa?.length() ?: 0)).mapNotNull { sa!!.optJSONObject(it) }.map { subOf(it) }
            val ca = j.optJSONArray("chars")
            val chars = (0 until (ca?.length() ?: 0)).mapNotNull { ca!!.optJSONObject(it) }.map { Chr(it.optString("n"), it.optString("g", "male"), it.optString("r")) }
            val ga = j.optJSONArray("gloss")
            val gloss = (0 until (ga?.length() ?: 0)).mapNotNull { ga!!.optJSONObject(it) }.map { Gloss(it.optString("t"), it.optString("n")) }
            val tp = HashMap<String, String>()
            j.optJSONObject("tpl")?.let { o -> for (k in o.keys()) tp[k] = o.optString(k) }
            val bnd = HashMap<Int, Double>()
            j.optJSONObject("bounds")?.let { o -> for (k in o.keys()) k.toIntOrNull()?.let { bnd[it] = o.optDouble(k) } }
            Saved(j.optInt("chunkSec", 60), j.optString("srcLang"), j.optBoolean("detDone"), subs,
                rangesOf(j.optJSONArray("done")), rangesOf(j.optJSONArray("failed")), chars, gloss, tp,
                j.optDouble("pos", 0.0), j.optInt("charsTried", 0), bnd, rangesOf(j.optJSONArray("gapTried")), j.optString("voices"))
        } catch (e: Exception) {
            try { f.renameTo(File(dir, f.name + ".bad")) } catch (_: Exception) {}
            null
        }
    }
}
