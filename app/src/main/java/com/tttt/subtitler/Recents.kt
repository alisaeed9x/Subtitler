package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject

/** آخر الفيديوهات (للشاشة الرئيسية) — فهرس صغير recent.json */
class Recent(val id: String, val title: String, val url: String, val uri: String, val posSec: Double, val durSec: Double,
             val subs: Int, val coverSec: Double, val ts: Long) {
    val percent: Int get() = PlayerLogic.percent(coverSec, durSec)
}

object Recents {
    fun parse(s: String): List<Recent> = try {
        val a = JSONArray(s.ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Recent(it.optString("id"), it.optString("title"), it.optString("url"), it.optString("uri"), it.optDouble("pos", 0.0),
                it.optDouble("dur", 0.0), it.optInt("subs", 0), it.optDouble("cover", 0.0), it.optLong("ts", 0))
        }.filter { it.id.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    fun toJson(l: List<Recent>): String {
        val a = JSONArray()
        for (r in l) a.put(JSONObject().put("id", r.id).put("title", r.title).put("url", r.url).put("uri", r.uri).put("pos", r.posSec)
            .put("dur", r.durSec).put("subs", r.subs).put("cover", r.coverSec).put("ts", r.ts))
        return a.toString()
    }
    fun remove(l: List<Recent>, id: String): List<Recent> = l.filter { it.id != id }
    /** نفس الفيديو بيتحدّث ويطلع فوق؛ والقايمة محدودة */
    fun upsert(l: List<Recent>, r: Recent, max: Int = 15): List<Recent> = (listOf(r) + l.filter { it.id != r.id }).take(max)

    fun titleOf(videoId: String): String {
        val raw = videoId.removePrefix("f:").removePrefix("u:")
        val name = if (videoId.startsWith("f:")) raw.substringBeforeLast(':') else raw.substringBefore('?').substringAfterLast('/')
        return name.ifBlank { raw }
    }
}
