package com.tttt.subtitler

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** إحصائية الاستهلاك: دقائق اترجمت + طلبات لكل مفتاح/موديل، لكل يوم (بتوقيت الجهاز). بتتحفظ آخر 35 يوم بس، والمفاتيح بآخر 4 حروف. */
object Stats {
    @Volatile var load: (() -> String)? = null
    @Volatile var save: ((String) -> Unit)? = null
    private var cache: JSONObject? = null

    class Day(val sec: Double, val req: Int, val keys: Map<String, Int>, val models: Map<String, Int>)

    private fun dayKey(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    @Synchronized private fun root(): JSONObject {
        cache?.let { return it }
        val j = try { JSONObject(load?.invoke().orEmpty().ifEmpty { "{}" }) } catch (_: Exception) { JSONObject() }
        if (!j.has("d")) j.put("d", JSONObject())
        cache = j; return j
    }
    private fun today(ms: Long): JSONObject {
        val d = root().getJSONObject("d"); val k = dayKey(ms)
        if (!d.has(k)) d.put(k, JSONObject().put("s", 0.0).put("r", 0).put("k", JSONObject()).put("m", JSONObject()))
        return d.getJSONObject(k)
    }
    private fun flush() {
        val d = root().getJSONObject("d")
        val keys = ArrayList<String>(); val it = d.keys(); while (it.hasNext()) keys.add(it.next())
        keys.sorted().dropLast(35).forEach { d.remove(it) }
        save?.invoke(root().toString())
    }
    @Synchronized fun addSec(sec: Double, ms: Long = System.currentTimeMillis()) {
        if (save == null || sec <= 0) return
        val t = today(ms); t.put("s", t.optDouble("s", 0.0) + sec); flush()
    }
    @Synchronized fun req(model: String, key: String, ms: Long = System.currentTimeMillis()) {
        if (save == null) return
        val t = today(ms); t.put("r", t.optInt("r", 0) + 1)
        val tail = key.takeLast(4)
        val k = t.getJSONObject("k"); k.put(tail, k.optInt(tail, 0) + 1)
        val m = t.getJSONObject("m"); m.put(model, m.optInt(model, 0) + 1)
        flush()
    }
    @Synchronized fun reset() { cache = JSONObject().put("d", JSONObject()); save?.invoke(cache.toString()) }

    /** مجموع آخر n يوم (n=1 → النهارده بس) */
    @Synchronized fun range(n: Int, now: Long = System.currentTimeMillis()): Day {
        var sec = 0.0; var req = 0; val ks = HashMap<String, Int>(); val ms = HashMap<String, Int>()
        val d = root().getJSONObject("d")
        for (i in 0 until n) {
            val o = d.optJSONObject(dayKey(now - i * 86_400_000L)) ?: continue
            sec += o.optDouble("s", 0.0); req += o.optInt("r", 0)
            o.optJSONObject("k")?.let { k -> k.keys().forEach { ks[it] = (ks[it] ?: 0) + k.optInt(it, 0) } }
            o.optJSONObject("m")?.let { m -> m.keys().forEach { ms[it] = (ms[it] ?: 0) + m.optInt(it, 0) } }
        }
        return Day(sec, req, ks, ms)
    }
    /** دقائق كل يوم من آخر n يوم: (اسم اليوم, دقائق) من الأقدم للأحدث */
    @Synchronized fun daily(n: Int, now: Long = System.currentTimeMillis()): List<Pair<String, Double>> {
        val d = root().getJSONObject("d")
        return (n - 1 downTo 0).map { i -> val k = dayKey(now - i * 86_400_000L); k.substring(5).replace('-', '/') to (d.optJSONObject(k)?.optDouble("s", 0.0) ?: 0.0) / 60.0 }
    }
}
